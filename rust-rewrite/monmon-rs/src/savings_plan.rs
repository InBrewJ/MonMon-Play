use crate::spog::resolve_user_id;
use askama::Template;
use axum::{
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, Json, Redirect},
    Form,
};
use serde::{Deserialize, Serialize};
use sqlx::{PgPool, Row};
use std::sync::Arc;
use crate::AppState;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsPlanItemDto {
    pub id: i64,
    pub savings_plan_id: i64,
    pub name: String,
    #[serde(rename = "type")]
    pub item_type: String,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
    pub account_type: Option<String>,
    pub account_limit: Option<f32>,
    pub current_balance: Option<f32>,
    pub debt_owed: f32,
    pub remaining_to_target: f32,
    pub rough_months: Option<i32>,
    pub target_amount: f32,
    pub monthly_amount: f32,
    pub priority: i32,
    pub locked: bool,
    pub archived: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsPlanSummaryDto {
    pub id: i64,
    pub name: String,
    pub savings_percentage: i32,
    pub created_at: i64,
    pub items_count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsPlanDto {
    pub id: i64,
    pub user_id: String,
    pub name: String,
    pub savings_percentage: i32,
    pub incoming_total: f32,
    pub target_amount: f32,
    pub total_allocated: f32,
    pub unallocated_amount: f32,
    pub max_surplus: f32,
    pub items: Vec<SavingsPlanItemDto>,
    pub previous_plans: Vec<SavingsPlanSummaryDto>,
}

impl SavingsPlanDto {
    pub fn is_overallocated(&self) -> bool {
        self.unallocated_amount < 0.0
    }
    pub fn breadline_month(&self) -> f32 {
        (((self.max_surplus - self.target_amount).max(0.0)) * 100.0).round() / 100.0
    }
    pub fn breadline_week(&self) -> f32 {
        (((self.max_surplus - self.target_amount).max(0.0) / 4.33) * 100.0).round() / 100.0
    }
    pub fn breadline_day(&self) -> f32 {
        (((self.max_surplus - self.target_amount).max(0.0) / 30.0) * 100.0).round() / 100.0
    }
    pub fn abs_unallocated(&self) -> f32 {
        self.unallocated_amount.abs()
    }
    pub fn max_percent(&self) -> i32 {
        if self.incoming_total > 0.0 {
            100.min(0.max(((self.max_surplus / self.incoming_total) * 100.0).floor() as i32))
        } else {
            100
        }
    }
}

impl SavingsPlanItemDto {
    pub fn is_debt_paid_off(&self) -> bool {
        self.debt_owed <= 0.0
    }
    pub fn is_target_reached(&self) -> bool {
        self.target_amount > 0.0 && self.remaining_to_target <= 0.0
    }
    pub fn has_target(&self) -> bool {
        self.target_amount > 0.0
    }
    pub fn has_limit(&self) -> bool {
        self.account_limit.map(|l| l > 0.0).unwrap_or(false)
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsPlanQuery {
    #[serde(alias = "user_id")]
    pub user_id: Option<String>,
}

impl SavingsPlanQuery {
    pub fn get_user_id(&self) -> String {
        self.user_id
            .clone()
            .unwrap_or_else(|| "demo".to_string())
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateItemPayload {
    pub name: String,
    #[serde(rename = "type")]
    pub item_type: Option<String>,
    #[serde(alias = "target_amount")]
    pub target_amount: Option<f32>,
    #[serde(alias = "monthly_amount")]
    pub monthly_amount: Option<f32>,
    pub priority: Option<i32>,
    #[serde(alias = "account_id")]
    pub account_id: Option<i64>,
    #[serde(alias = "user_id")]
    pub user_id: Option<String>,
    #[serde(alias = "plan_id")]
    pub plan_id: Option<i64>,
}

#[derive(Template)]
#[template(path = "savings_plan.html")]
pub struct SavingsPlanTemplate {
    pub plan: SavingsPlanDto,
    pub accounts: Vec<AccountSimple>,
    pub editing: bool,
    pub editing_item: Option<SavingsPlanItemDto>,
}

impl SavingsPlanTemplate {
    pub fn is_selected_account(&self, acc_id: &i64) -> bool {
        if let Some(ref item) = self.editing_item {
            item.account_id == Some(*acc_id)
        } else {
            false
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountSimple {
    pub id: i64,
    pub name: String,
    pub account_type: String,
    pub balance: f32,
    pub limit: f32,
}

// -------------------------------------------------------------
// Pure Domain Calculations (Mirrored from SavingsPlanTest.java)
// -------------------------------------------------------------

pub fn calculate_debt_owed(
    account_type: Option<&str>,
    limit: Option<f32>,
    current_balance: Option<f32>,
    target_amount: f32,
) -> f32 {
    let lim = limit.unwrap_or(0.0);
    let bal = current_balance.unwrap_or(0.0);

    match account_type.map(|s| s.to_uppercase()).as_deref() {
        Some("CREDIT") => (lim - bal).max(0.0),
        Some("DEBIT") | Some("DEBIT_SHARED_BILLS") => {
            if bal < 0.0 {
                (-bal).max(0.0)
            } else {
                0.0
            }
        }
        Some("LOAN") => bal.max(0.0),
        _ => {
            if target_amount > 0.0 {
                target_amount
            } else {
                (lim - bal).max(0.0)
            }
        }
    }
}

pub fn calculate_remaining_to_target(
    current_balance: Option<f32>,
    target_amount: f32,
) -> f32 {
    let bal = current_balance.unwrap_or(0.0);
    (target_amount - bal).max(0.0)
}

pub fn calculate_rough_months_to_payoff(debt_owed: f32, monthly_amount: f32) -> Option<i32> {
    if debt_owed <= 0.0 {
        Some(0)
    } else if monthly_amount <= 0.0 {
        None
    } else {
        Some((debt_owed / monthly_amount).ceil() as i32)
    }
}

pub fn calculate_rough_months_to_target(
    remaining_to_target: f32,
    monthly_amount: f32,
    target_amount: f32,
) -> Option<i32> {
    if target_amount <= 0.0 {
        None
    } else if remaining_to_target <= 0.0 {
        Some(0)
    } else if monthly_amount <= 0.0 {
        None
    } else {
        Some((remaining_to_target / monthly_amount).ceil() as i32)
    }
}

pub fn scale_items_proportionally(items: &mut [SavingsPlanItemDto], target_pot: f32) {
    if items.is_empty() {
        return;
    }

    let initial_total: f32 = items.iter().filter(|i| !i.locked).map(|i| i.monthly_amount).sum();
    let locked_total: f32 = items.iter().filter(|i| i.locked).map(|i| i.monthly_amount).sum();
    let available_pot = (target_pot - locked_total).max(0.0);

    let unlocked_count = items.iter().filter(|i| !i.locked).count();
    if unlocked_count == 0 {
        return;
    }

    if initial_total <= 0.0 {
        let equal_share = ((available_pot / unlocked_count as f32) * 100.0).round() / 100.0;
        for it in items.iter_mut() {
            if !it.locked {
                it.monthly_amount = equal_share;
            }
        }
    } else {
        let ratio = available_pot / initial_total;
        for it in items.iter_mut() {
            if !it.locked {
                it.monthly_amount = ((it.monthly_amount * ratio) * 100.0).round() / 100.0;
            }
        }
    }
}

pub fn balance_items_evenly(items: &mut [SavingsPlanItemDto], target_pot: f32) {
    let locked_total: f32 = items.iter().filter(|i| i.locked).map(|i| i.monthly_amount).sum();
    let available_pot = (target_pot - locked_total).max(0.0);
    let unlocked_count = items.iter().filter(|i| !i.locked).count();
    if unlocked_count == 0 {
        return;
    }
    let share = ((available_pot / unlocked_count as f32) * 100.0).round() / 100.0;
    for it in items.iter_mut() {
        if !it.locked {
            it.monthly_amount = share;
        }
    }
}

pub fn trim_items_deficit(items: &mut [SavingsPlanItemDto], target_pot: f32) {
    let total_allocated: f32 = items.iter().map(|i| i.monthly_amount).sum();
    if total_allocated <= target_pot {
        return;
    }
    let mut deficit = total_allocated - target_pot;

    // Traverse unlocked items in reverse priority (lowest priority first)
    for it in items.iter_mut().rev() {
        if !it.locked && it.monthly_amount > 0.0 {
            if it.monthly_amount >= deficit {
                it.monthly_amount = ((it.monthly_amount - deficit) * 100.0).round() / 100.0;
                break;
            } else {
                deficit -= it.monthly_amount;
                it.monthly_amount = 0.0;
            }
        }
    }
}

/// A debt-payoff slice proposed by one of the "Add all ..." bulk actions.
#[derive(Debug, Clone, PartialEq)]
pub struct DebtCandidate {
    pub account_id: i64,
    pub name: String,
    pub debt: f32,
}

fn is_debit_type(t: &str) -> bool {
    matches!(t.to_uppercase().as_str(), "DEBIT" | "DEBIT_SHARED_BILLS")
}

/// Mirrors Java `SavingsPlanController.addAllOverdrafts`: overdrawn debit accounts not yet in the
/// plan; if none are overdrawn, falls back to debit accounts with a configured overdraft limit.
pub fn select_overdraft_candidates(
    accounts: &[AccountSimple],
    already_linked: &[i64],
) -> Vec<DebtCandidate> {
    let free = |a: &&AccountSimple| is_debit_type(&a.account_type) && !already_linked.contains(&a.id);
    let mut picked: Vec<&AccountSimple> =
        accounts.iter().filter(free).filter(|a| a.balance < 0.0).collect();
    if picked.is_empty() {
        picked = accounts.iter().filter(free).filter(|a| a.limit > 0.0).collect();
    }
    picked
        .into_iter()
        .map(|a| DebtCandidate {
            account_id: a.id,
            name: format!("{} Overdraft", a.name),
            debt: if a.balance < 0.0 { a.balance.abs() } else { a.limit },
        })
        .collect()
}

/// Mirrors Java `SavingsPlanController.addAllCreditCards`: credit cards with an outstanding
/// balance that are not already in the plan.
pub fn select_credit_card_candidates(
    accounts: &[AccountSimple],
    already_linked: &[i64],
) -> Vec<DebtCandidate> {
    accounts
        .iter()
        .filter(|a| a.account_type.eq_ignore_ascii_case("CREDIT") && !already_linked.contains(&a.id))
        .filter_map(|a| {
            let mut debt = (a.limit - a.balance).max(0.0);
            if debt <= 0.0 && a.limit == 0.0 && a.balance > 0.0 {
                debt = a.balance;
            }
            if debt > 0.0 {
                Some(DebtCandidate { account_id: a.id, name: a.name.clone(), debt })
            } else {
                None
            }
        })
        .collect()
}

/// Extracts `amount_<itemId>=<value>` pairs from the "Save Slices" form (Java `saveAllocations`).
/// Malformed keys/values are ignored and negative amounts are clamped to zero.
pub fn parse_allocation_amounts(
    form: &std::collections::HashMap<String, String>,
) -> Vec<(i64, f32)> {
    let mut out: Vec<(i64, f32)> = form
        .iter()
        .filter_map(|(k, v)| {
            let id = k.strip_prefix("amount_")?.parse::<i64>().ok()?;
            let amt = v.trim().parse::<f32>().ok()?;
            Some((id, amt.max(0.0)))
        })
        .collect();
    out.sort_by_key(|(id, _)| *id);
    out
}

/// Mirrors the Java "Allocate max" button: the whole positive unallocated amount.
pub fn allocate_max_amount(unallocated: f32) -> f32 {
    unallocated.max(0.0)
}

/// Mirrors the Java "Take off deficit" button: reduce the slice by the over-allocation, never below zero.
pub fn deduct_deficit_from_slice(current_slice: f32, unallocated: f32) -> f32 {
    if unallocated >= 0.0 {
        return current_slice;
    }
    ((current_slice + unallocated).max(0.0) * 100.0).round() / 100.0
}

// -------------------------------------------------------------
// Database Helpers & Loaders
// -------------------------------------------------------------

pub async fn get_user_incoming_total(pool: &PgPool, user_id: &str) -> f32 {
    let row = sqlx::query(
        r#"
        SELECT COALESCE(SUM(netvalue), 0.0)::real AS total
        FROM incoming
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        "#,
    )
    .bind(user_id)
    .fetch_one(pool)
    .await;

    match row {
        Ok(r) => r.get::<f32, _>("total"),
        Err(_) => 0.0,
    }
}

pub async fn get_user_outgoing_total(pool: &PgPool, user_id: &str) -> f32 {
    let outgoings = crate::db::list_outgoings(pool, user_id).await.unwrap_or_default();
    let today = chrono::Local::now().date_naive();
    crate::models::total_effective_outgoings(&outgoings, today)
}

pub async fn get_or_create_active_plan(pool: &PgPool, user_id: &str) -> Result<SavingsPlanDto, sqlx::Error> {
    let resolved = resolve_user_id(pool, user_id).await;

    let plan_row = sqlx::query(
        r#"
        SELECT id, name, savingspercentage
        FROM savings_plan
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY createdat DESC NULLS LAST
        LIMIT 1
        "#,
    )
    .bind(&resolved)
    .fetch_optional(pool)
    .await?;

    let (plan_id, name, savings_percentage) = match plan_row {
        Some(row) => (
            row.get::<i64, _>("id"),
            row.get::<String, _>("name"),
            row.get::<i32, _>("savingspercentage"),
        ),
        None => {
            let now = chrono::Utc::now().timestamp_millis();
            let new_row = sqlx::query(
                r#"
                INSERT INTO savings_plan (id, userid, user_id, "userId", name, savingspercentage, archived, createdat, lastupdated)
                VALUES (nextval('hibernate_sequence'), $1, $1, $1, 'Monthly Savings Plan', 0, false, $2, $2)
                RETURNING id
                "#,
            )
            .bind(&resolved)
            .bind(now)
            .fetch_one(pool)
            .await?;

            (new_row.get::<i64, _>("id"), "Monthly Savings Plan".to_string(), 0)
        }
    };

    let items_rows = sqlx::query(
        r#"
        SELECT 
            i.id, i.savings_plan_id, i.name, COALESCE(i.type, 'SAVINGS_TARGET') AS item_type,
            i.account_id, COALESCE(i.targetamount, 0.0)::real AS targetamount,
            COALESCE(i.monthlyamount, 0.0)::real AS monthlyamount,
            COALESCE(i.priority, 1) AS priority,
            COALESCE(i.locked, false) AS locked,
            COALESCE(i.archived, false) AS archived,
            a.name AS account_name,
            a.type AS account_type,
            COALESCE(a.account_limit, 0.0)::real AS account_limit,
            COALESCE((SELECT b.value FROM balance b WHERE b.account_id = a.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS current_balance
        FROM savings_plan_item i
        LEFT JOIN account a ON a.id = i.account_id
        WHERE i.savings_plan_id = $1
          AND (i.archived IS NULL OR i.archived = false)
        ORDER BY i.priority ASC NULLS LAST, i.id ASC
        "#,
    )
    .bind(plan_id)
    .fetch_all(pool)
    .await?;

    let items: Vec<SavingsPlanItemDto> = items_rows
        .into_iter()
        .enumerate()
        .map(|(idx, r)| {
            let account_type: Option<String> = r.get("account_type");
            let account_limit: Option<f32> = Some(r.get("account_limit"));
            let current_balance: Option<f32> = Some(r.get("current_balance"));
            let target_amount: f32 = r.get("targetamount");
            let monthly_amount: f32 = r.get("monthlyamount");
            let item_type: String = r.get("item_type");

            let debt_owed = if item_type == "DEBT_PAYOFF" {
                calculate_debt_owed(account_type.as_deref(), account_limit, current_balance, target_amount)
            } else {
                0.0
            };

            let remaining_to_target = if item_type == "SAVINGS_TARGET" {
                calculate_remaining_to_target(current_balance, target_amount)
            } else {
                0.0
            };

            let rough_months = if item_type == "DEBT_PAYOFF" {
                calculate_rough_months_to_payoff(debt_owed, monthly_amount)
            } else {
                calculate_rough_months_to_target(remaining_to_target, monthly_amount, target_amount)
            };

            SavingsPlanItemDto {
                id: r.get("id"),
                savings_plan_id: r.get("savings_plan_id"),
                name: r.get("name"),
                item_type,
                account_id: r.get("account_id"),
                account_name: r.get("account_name"),
                account_type,
                account_limit,
                current_balance,
                debt_owed,
                remaining_to_target,
                rough_months,
                target_amount,
                monthly_amount,
                priority: (idx + 1) as i32,
                locked: r.get("locked"),
                archived: r.get("archived"),
            }
        })
        .collect();

    // Rebalance priorities in DB
    for item in &items {
        let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2")
            .bind(item.priority)
            .bind(item.id)
            .execute(pool)
            .await;
    }

    let incoming_total = get_user_incoming_total(pool, &resolved).await;
    let outgoing_total = get_user_outgoing_total(pool, &resolved).await;
    let max_surplus = (incoming_total - outgoing_total).max(0.0);
    let target_amount = (savings_percentage as f32 / 100.0) * incoming_total;
    let total_allocated: f32 = items.iter().map(|it| it.monthly_amount).sum();
    let unallocated_amount = target_amount - total_allocated;

    // Previous plans (archived)
    let previous_rows = sqlx::query(
        r#"
        SELECT sp.id, sp.name, sp.savingspercentage, COALESCE(sp.createdat, 0) AS createdat,
               (SELECT COUNT(*) FROM savings_plan_item spi WHERE spi.savings_plan_id = sp.id AND spi.archived = false) AS items_count
        FROM savings_plan sp
        WHERE (sp.userid = $1 OR sp.user_id = $1)
          AND sp.archived = true
        ORDER BY sp.createdat DESC NULLS LAST
        LIMIT 10
        "#,
    )
    .bind(&resolved)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let previous_plans = previous_rows
        .into_iter()
        .map(|r| SavingsPlanSummaryDto {
            id: r.get("id"),
            name: r.get("name"),
            savings_percentage: r.get("savingspercentage"),
            created_at: r.get("createdat"),
            items_count: r.get("items_count"),
        })
        .collect();

    Ok(SavingsPlanDto {
        id: plan_id,
        user_id: resolved,
        name,
        savings_percentage,
        incoming_total: (incoming_total * 100.0).round() / 100.0,
        target_amount: (target_amount * 100.0).round() / 100.0,
        total_allocated: (total_allocated * 100.0).round() / 100.0,
        unallocated_amount: (unallocated_amount * 100.0).round() / 100.0,
        max_surplus: (max_surplus * 100.0).round() / 100.0,
        items,
        previous_plans,
    })
}

// -------------------------------------------------------------
// JSON API Handlers
// -------------------------------------------------------------

pub async fn get_savings_plan_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<SavingsPlanDto>, (StatusCode, String)> {
    let user_id = q.get_user_id();
    get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn create_savings_plan_item_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(payload): Json<CreateItemPayload>,
) -> Result<Json<SavingsPlanItemDto>, (StatusCode, String)> {
    let user_id = crate::pot::get_user_id_from_headers_or_default(&headers, payload.user_id.as_deref());
    let plan = get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let plan_id = payload.plan_id.unwrap_or(plan.id);
    let target_amount = payload.target_amount.unwrap_or(0.0);
    let monthly_amount = payload.monthly_amount.unwrap_or(0.0);
    let item_type = match payload.item_type.as_deref() {
        Some("SAVINGS") | Some("SAVINGS_TARGET") => "SAVINGS_TARGET".to_string(),
        Some("DEBT_PAYOFF") => "DEBT_PAYOFF".to_string(),
        _ => "SAVINGS_TARGET".to_string(),
    };
    let account_id = match payload.account_id {
        Some(id) if id > 0 => Some(id),
        _ => None,
    };
    let new_priority = payload.priority.unwrap_or_else(|| (plan.items.len() + 1) as i32);

    let row = sqlx::query(
        r#"
        INSERT INTO savings_plan_item (
            id, savings_plan_id, name, type, account_id, targetamount, target_amount,
            monthlyamount, monthly_amount, priority, locked, archived
        )
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $5, $5, $6, $6, $7, false, false)
        RETURNING id, savings_plan_id, name, type, account_id, targetamount, monthlyamount, priority, locked, archived
        "#,
    )
    .bind(plan_id)
    .bind(&payload.name)
    .bind(&item_type)
    .bind(account_id)
    .bind(target_amount)
    .bind(monthly_amount)
    .bind(new_priority)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(SavingsPlanItemDto {
        id: row.get("id"),
        savings_plan_id: row.get("savings_plan_id"),
        name: row.get("name"),
        item_type: row.get("type"),
        account_id: row.get("account_id"),
        account_name: None,
        account_type: None,
        account_limit: None,
        current_balance: None,
        debt_owed: 0.0,
        remaining_to_target: 0.0,
        rough_months: None,
        target_amount: row.get("targetamount"),
        monthly_amount: row.get("monthlyamount"),
        priority: row.get("priority"),
        locked: row.get("locked"),
        archived: row.get("archived"),
    }))
}

pub async fn move_item_up_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    headers: HeaderMap,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let plan = get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let idx = plan.items.iter().position(|it| it.id == id).ok_or_else(|| {
        (StatusCode::FORBIDDEN, format!("Access denied: Item #{} not found in user plan", id))
    })?;

    if idx > 0 {
        let prev = &plan.items[idx - 1];
        let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2 AND savings_plan_id = $3")
            .bind(prev.priority)
            .bind(id)
            .bind(plan.id)
            .execute(&state.pool)
            .await;
        let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2 AND savings_plan_id = $3")
            .bind(plan.items[idx].priority)
            .bind(prev.id)
            .bind(plan.id)
            .execute(&state.pool)
            .await;
    }

    Ok(Json(serde_json::json!({ "success": true, "moved": id, "direction": "up" })))
}

pub async fn move_item_down_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    headers: HeaderMap,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let plan = get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let idx = plan.items.iter().position(|it| it.id == id).ok_or_else(|| {
        (StatusCode::FORBIDDEN, format!("Access denied: Item #{} not found in user plan", id))
    })?;

    if idx + 1 < plan.items.len() {
        let next = &plan.items[idx + 1];
        let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2 AND savings_plan_id = $3")
            .bind(next.priority)
            .bind(id)
            .bind(plan.id)
            .execute(&state.pool)
            .await;
        let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2 AND savings_plan_id = $3")
            .bind(plan.items[idx].priority)
            .bind(next.id)
            .bind(plan.id)
            .execute(&state.pool)
            .await;
    }

    Ok(Json(serde_json::json!({ "success": true, "moved": id, "direction": "down" })))
}

pub async fn toggle_lock_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<SavingsPlanItemDto>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;

    let row = sqlx::query(
        r#"
        UPDATE savings_plan_item
        SET locked = NOT COALESCE(locked, false)
        WHERE id = $1
          AND savings_plan_id IN (SELECT id FROM savings_plan WHERE userid = $2 OR user_id = $2)
        RETURNING id, savings_plan_id, name, type, account_id, targetamount, monthlyamount, priority, locked, archived
        "#,
    )
    .bind(id)
    .bind(&user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let row = crate::authz::Authz::verify_ownership(row, "Savings Plan Item", id)?;

    Ok(Json(SavingsPlanItemDto {
        id: row.get("id"),
        savings_plan_id: row.get("savings_plan_id"),
        name: row.get("name"),
        item_type: row.get("type"),
        account_id: row.get("account_id"),
        account_name: None,
        account_type: None,
        account_limit: None,
        current_balance: None,
        debt_owed: 0.0,
        remaining_to_target: 0.0,
        rough_months: None,
        target_amount: row.get("targetamount"),
        monthly_amount: row.get("monthlyamount"),
        priority: row.get("priority"),
        locked: row.get("locked"),
        archived: row.get("archived"),
    }))
}

pub async fn delete_item_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    headers: HeaderMap,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let plan = get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let deleted_item = plan.items.iter().find(|it| it.id == id).ok_or_else(|| {
        (StatusCode::FORBIDDEN, format!("Access denied: Item #{} not found in user plan", id))
    })?;
    let amount_to_roll_over = deleted_item.monthly_amount;

    let res = sqlx::query("UPDATE savings_plan_item SET archived = true, monthlyamount = 0.0, monthly_amount = 0.0 WHERE id = $1 AND savings_plan_id = $2")
        .bind(id)
        .bind(plan.id)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Savings Plan Item", id)?;

    let remaining_items: Vec<_> = plan.items.into_iter().filter(|it| it.id != id).collect();
    if !remaining_items.is_empty() {
        let top_id = remaining_items[0].id;
        let new_top_amount = remaining_items[0].monthly_amount + amount_to_roll_over;
        let _ = sqlx::query("UPDATE savings_plan_item SET monthlyamount = $1, monthly_amount = $1 WHERE id = $2 AND savings_plan_id = $3")
            .bind(new_top_amount)
            .bind(top_id)
            .bind(plan.id)
            .execute(&state.pool)
            .await;

        for (new_pri, item) in remaining_items.iter().enumerate() {
            let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2 AND savings_plan_id = $3")
                .bind((new_pri + 1) as i32)
                .bind(item.id)
                .bind(plan.id)
                .execute(&state.pool)
                .await;
        }
    }

    Ok(Json(serde_json::json!({ "success": true, "deleted": id })))
}

// -------------------------------------------------------------
// HTML Form / Page Handlers (Form submissions matching Java routes)
// -------------------------------------------------------------

/// HTML forms submit empty inputs as `""`; treat those (and whitespace) as "not provided"
/// instead of failing to parse a number.
fn empty_as_none<'de, D, T>(d: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: std::str::FromStr,
    T::Err: std::fmt::Display,
{
    let raw: Option<String> = Option::deserialize(d)?;
    match raw {
        None => Ok(None),
        Some(v) if v.trim().is_empty() => Ok(None),
        Some(v) => v.trim().parse::<T>().map(Some).map_err(serde::de::Error::custom),
    }
}

#[derive(Debug, Deserialize)]
pub struct FormAddItem {
    pub name: String,
    pub r#type: Option<String>,
    #[serde(rename = "targetAmount", default, deserialize_with = "empty_as_none")]
    pub target_amount: Option<f32>,
    #[serde(rename = "monthlyAmount", default, deserialize_with = "empty_as_none")]
    pub monthly_amount: Option<f32>,
    #[serde(default, deserialize_with = "empty_as_none")]
    pub priority: Option<i32>,
    #[serde(rename = "accountId", default, deserialize_with = "empty_as_none")]
    pub account_id: Option<i64>,
}

impl FormAddItem {
    /// The "-- Select Account --" option posts `0`; that is "no account", not a foreign key.
    pub fn linked_account_id(&self) -> Option<i64> {
        self.account_id.filter(|id| *id > 0)
    }

    /// `SAVINGS` is accepted as an alias; anything unknown falls back to a savings target.
    pub fn normalized_type(&self) -> &'static str {
        match self.r#type.as_deref() {
            Some("DEBT_PAYOFF") => "DEBT_PAYOFF",
            _ => "SAVINGS_TARGET",
        }
    }
}

/// Moves `moving_id` to 1-based `new_priority` within `ordered_ids` (current queue order) and
/// returns the full renumbered queue as `(id, priority)`, shifting the others cleanly.
pub fn reorder_priorities(ordered_ids: &[i64], moving_id: i64, new_priority: i32) -> Vec<(i64, i32)> {
    let mut ids: Vec<i64> = ordered_ids.iter().copied().filter(|i| *i != moving_id).collect();
    if ordered_ids.contains(&moving_id) {
        let pos = (new_priority.max(1) as usize - 1).min(ids.len());
        ids.insert(pos, moving_id);
    }
    ids.into_iter().enumerate().map(|(i, id)| (id, (i + 1) as i32)).collect()
}

#[derive(Debug, Deserialize)]
pub struct FormUpdatePercentage {
    #[serde(rename = "planId")]
    pub plan_id: Option<i64>,
    #[serde(rename = "savingsPercentage")]
    pub savings_percentage: i32,
}

pub async fn get_savings_plan_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<SavingsPlanQuery>,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.user_id.as_deref());
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let accounts_rows = sqlx::query(
        r#"
        SELECT 
            id, 
            name, 
            type, 
            COALESCE((SELECT b.value FROM balance b WHERE b.account_id = account.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS balance, 
            COALESCE(account_limit, 0.0)::real AS limit
        FROM account
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY name ASC
        "#,
    )
    .bind(&plan.user_id)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let accounts = accounts_rows
        .into_iter()
        .map(|r| AccountSimple {
            id: r.get("id"),
            name: r.get("name"),
            account_type: r.get("type"),
            balance: r.get("balance"),
            limit: r.get("limit"),
        })
        .collect();

    let tmpl = SavingsPlanTemplate {
        plan,
        accounts,
        editing: false,
        editing_item: None,
    };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_edit_savings_plan_item_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(item_id): axum::extract::Path<i64>,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let editing_item = plan.items.iter().find(|i| i.id == item_id).cloned();
    let editing = editing_item.is_some();

    let accounts_rows = sqlx::query(
        r#"
        SELECT 
            id, 
            name, 
            type, 
            COALESCE((SELECT b.value FROM balance b WHERE b.account_id = account.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS balance, 
            COALESCE(account_limit, 0.0)::real AS limit
        FROM account
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY name ASC
        "#,
    )
    .bind(&plan.user_id)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let accounts = accounts_rows
        .into_iter()
        .map(|r| AccountSimple {
            id: r.get("id"),
            name: r.get("name"),
            account_type: r.get("type"),
            balance: r.get("balance"),
            limit: r.get("limit"),
        })
        .collect();

    let tmpl = SavingsPlanTemplate {
        plan,
        accounts,
        editing,
        editing_item,
    };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn update_item_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(item_id): axum::extract::Path<i64>,
    Form(form): Form<FormAddItem>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let existing = plan.items.iter().find(|i| i.id == item_id).ok_or_else(|| {
        (StatusCode::FORBIDDEN, format!("Access denied: Item #{} not found in user plan", item_id))
    })?;

    let target_amount = form.target_amount.unwrap_or(0.0).max(0.0);
    let monthly_amount = form.monthly_amount.unwrap_or(existing.monthly_amount).max(0.0);
    let account_id = form.linked_account_id();
    let item_type = form.normalized_type();

    // Both the legacy (targetamount/monthlyamount) and snake_case columns are kept in sync;
    // the plan loader reads the legacy ones, so writing only snake_case silently loses edits.
    let res = sqlx::query(
        r#"
        UPDATE savings_plan_item
        SET name = $1, type = $2, account_id = $3,
            targetamount = $4, target_amount = $4,
            monthlyamount = $5, monthly_amount = $5
        WHERE id = $6 AND savings_plan_id = $7
        "#,
    )
    .bind(&form.name)
    .bind(item_type)
    .bind(account_id)
    .bind(target_amount)
    .bind(monthly_amount)
    .bind(item_id)
    .bind(plan.id)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Savings Plan Item", item_id)?;

    if let Some(new_priority) = form.priority {
        let order: Vec<i64> = plan.items.iter().map(|i| i.id).collect();
        for (id, pri) in reorder_priorities(&order, item_id, new_priority) {
            let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2 AND savings_plan_id = $3")
                .bind(pri)
                .bind(id)
                .bind(plan.id)
                .execute(&state.pool)
                .await;
        }
    }

    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}

pub async fn add_item_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<FormAddItem>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let payload = CreateItemPayload {
        name: form.name,
        item_type: form.r#type,
        target_amount: form.target_amount,
        monthly_amount: form.monthly_amount,
        priority: form.priority,
        account_id: form.account_id,
        user_id: Some(raw_uid),
        plan_id: None,
    };

    let _ = create_savings_plan_item_api(State(state), headers, Json(payload)).await?;
    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}

pub async fn move_up_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let q = SavingsPlanQuery { user_id: Some(raw_uid) };
    let _ = move_item_up_api(State(state), Path(id), headers, Query(q)).await;
    Ok(Redirect::to(&format!("/savingsPlan#savings-item-{}", id)))
}

pub async fn move_down_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let q = SavingsPlanQuery { user_id: Some(raw_uid) };
    let _ = move_item_down_api(State(state), Path(id), headers, Query(q)).await;
    Ok(Redirect::to(&format!("/savingsPlan#savings-item-{}", id)))
}

pub async fn move_to_top_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    if let Some(target_item) = plan.items.iter().find(|it| it.id == id) {
        let old_priority = target_item.priority;
        let _ = sqlx::query("UPDATE savings_plan_item SET priority = 1 WHERE id = $1")
            .bind(id)
            .execute(&state.pool)
            .await;

        for it in plan.items.iter().filter(|it| it.id != id && it.priority < old_priority) {
            let _ = sqlx::query("UPDATE savings_plan_item SET priority = priority + 1 WHERE id = $1")
                .bind(it.id)
                .execute(&state.pool)
                .await;
        }
    }

    Ok(Redirect::to(&format!("/savingsPlan#savings-item-{}", id)))
}

pub async fn move_to_bottom_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let max_priority = plan.items.len() as i32;
    if let Some(target_item) = plan.items.iter().find(|it| it.id == id) {
        let old_priority = target_item.priority;
        let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2")
            .bind(max_priority)
            .bind(id)
            .execute(&state.pool)
            .await;

        for it in plan.items.iter().filter(|it| it.id != id && it.priority > old_priority) {
            let _ = sqlx::query("UPDATE savings_plan_item SET priority = priority - 1 WHERE id = $1")
                .bind(it.id)
                .execute(&state.pool)
                .await;
        }
    }

    Ok(Redirect::to(&format!("/savingsPlan#savings-item-{}", id)))
}

pub async fn toggle_lock_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let q = SavingsPlanQuery { user_id: Some(raw_uid) };
    let _ = toggle_lock_api(State(state), headers, Path(id), Query(q)).await;
    Ok(Redirect::to(&format!("/savingsPlan#savings-item-{}", id)))
}

pub async fn update_percentage_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<FormUpdatePercentage>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let plan_id = form.plan_id.unwrap_or(plan.id);
    let new_percent = form.savings_percentage.clamp(0, 100);

    let _ = sqlx::query("UPDATE savings_plan SET savingspercentage = $1 WHERE id = $2")
        .bind(new_percent)
        .bind(plan_id)
        .execute(&state.pool)
        .await;

    let incoming = plan.incoming_total;
    let new_target_pot = (new_percent as f32 / 100.0) * incoming;

    let mut items = plan.items;
    scale_items_proportionally(&mut items, new_target_pot);

    for it in &items {
        let _ = sqlx::query("UPDATE savings_plan_item SET monthlyamount = $1, monthly_amount = $1 WHERE id = $2")
            .bind(it.monthly_amount)
            .bind(it.id)
            .execute(&state.pool)
            .await;
    }

    Ok(Redirect::to("/savingsPlan"))
}

pub async fn balance_evenly_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(_plan_id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let mut items = plan.items;
    balance_items_evenly(&mut items, plan.target_amount);

    for it in &items {
        let _ = sqlx::query("UPDATE savings_plan_item SET monthlyamount = $1, monthly_amount = $1 WHERE id = $2")
            .bind(it.monthly_amount)
            .bind(it.id)
            .execute(&state.pool)
            .await;
    }

    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}

pub async fn trim_deficit_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(_plan_id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let mut items = plan.items;
    trim_items_deficit(&mut items, plan.target_amount);

    for it in &items {
        let _ = sqlx::query("UPDATE savings_plan_item SET monthlyamount = $1, monthly_amount = $1 WHERE id = $2")
            .bind(it.monthly_amount)
            .bind(it.id)
            .execute(&state.pool)
            .await;
    }

    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}

async fn load_account_simples(pool: &PgPool, user_id: &str) -> Vec<AccountSimple> {
    sqlx::query(
        r#"
        SELECT id, name, type,
            COALESCE((SELECT b.value FROM balance b WHERE b.account_id = account.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS balance,
            COALESCE(account_limit, 0.0)::real AS limit
        FROM account
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY name ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default()
    .into_iter()
    .map(|r| AccountSimple {
        id: r.get("id"),
        name: r.get("name"),
        account_type: r.get("type"),
        balance: r.get("balance"),
        limit: r.get("limit"),
    })
    .collect()
}

async fn add_debt_candidates(
    pool: &PgPool,
    plan: &SavingsPlanDto,
    candidates: Vec<DebtCandidate>,
) -> Result<(), (StatusCode, String)> {
    let mut priority = plan.items.len() as i32;
    for c in candidates {
        priority += 1;
        sqlx::query(
            r#"
            INSERT INTO savings_plan_item (
                id, savings_plan_id, name, type, account_id, targetamount, target_amount,
                monthlyamount, monthly_amount, priority, locked, archived
            )
            VALUES (nextval('hibernate_sequence'), $1, $2, 'DEBT_PAYOFF', $3, $4, $4, 0.0, 0.0, $5, false, false)
            "#,
        )
        .bind(plan.id)
        .bind(&c.name)
        .bind(c.account_id)
        .bind(c.debt)
        .bind(priority)
        .execute(pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    }
    Ok(())
}

pub async fn add_all_overdrafts_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let accounts = load_account_simples(&state.pool, &plan.user_id).await;
    let linked: Vec<i64> = plan.items.iter().filter_map(|i| i.account_id).collect();
    add_debt_candidates(&state.pool, &plan, select_overdraft_candidates(&accounts, &linked)).await?;
    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}

pub async fn add_all_credit_cards_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let accounts = load_account_simples(&state.pool, &plan.user_id).await;
    let linked: Vec<i64> = plan.items.iter().filter_map(|i| i.account_id).collect();
    add_debt_candidates(&state.pool, &plan, select_credit_card_candidates(&accounts, &linked)).await?;
    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}

pub async fn save_allocations_form_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(_plan_id): Path<i64>,
    Form(form): Form<std::collections::HashMap<String, String>>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let plan = get_or_create_active_plan(&state.pool, &raw_uid)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    for (item_id, amount) in parse_allocation_amounts(&form) {
        // Only items belonging to the caller's own active plan may be updated.
        if plan.items.iter().any(|it| it.id == item_id) {
            sqlx::query("UPDATE savings_plan_item SET monthlyamount = $1, monthly_amount = $1 WHERE id = $2 AND savings_plan_id = $3")
                .bind(amount)
                .bind(item_id)
                .bind(plan.id)
                .execute(&state.pool)
                .await
                .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
        }
    }
    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}

pub async fn bin_item_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let q = SavingsPlanQuery { user_id: Some(raw_uid) };
    let _ = delete_item_api(State(state), Path(id), headers, Query(q)).await;
    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}

pub async fn bin_plan_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let res = sqlx::query("UPDATE savings_plan SET archived = true WHERE id = $1 AND (userid = $2 OR user_id = $2)")
        .bind(id)
        .bind(&user_id)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Savings Plan", id)?;

    Ok(Redirect::to("/savingsPlan"))
}

// -------------------------------------------------------------
// Unit Tests (Translating Java SavingsPlanTest.java)
// -------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_credit_payoff_calculates_rough_months() {
        // Limit: 2000, Available balance: 1400 -> Debt: 600
        let debt = calculate_debt_owed(Some("CREDIT"), Some(2000.0), Some(1400.0), 0.0);
        assert_eq!(debt, 600.0);
        // 600 / 200 = 3 months
        let months = calculate_rough_months_to_payoff(debt, 200.0);
        assert_eq!(months, Some(3));
    }

    #[test]
    fn test_credit_payoff_rounds_up_fractional_months() {
        // Limit: 1000, Available balance: 650 -> Debt: 350
        let debt = calculate_debt_owed(Some("CREDIT"), Some(1000.0), Some(650.0), 0.0);
        assert_eq!(debt, 350.0);
        // 350 / 150 = 2.333... -> 3 months
        let months = calculate_rough_months_to_payoff(debt, 150.0);
        assert_eq!(months, Some(3));
    }

    #[test]
    fn test_credit_payoff_when_already_paid_off_returns_zero_months() {
        // Limit: 1000, Available balance: 1000 -> Debt: 0
        let debt = calculate_debt_owed(Some("CREDIT"), Some(1000.0), Some(1000.0), 0.0);
        assert_eq!(debt, 0.0);
        let months = calculate_rough_months_to_payoff(debt, 100.0);
        assert_eq!(months, Some(0));
    }

    #[test]
    fn test_credit_payoff_without_monthly_allocation_returns_none() {
        let debt = calculate_debt_owed(Some("CREDIT"), Some(1000.0), Some(700.0), 0.0);
        assert_eq!(debt, 300.0);
        let months = calculate_rough_months_to_payoff(debt, 0.0);
        assert_eq!(months, None);
    }

    #[test]
    fn test_savings_target_calculates_rough_months() {
        // Current balance: 250, Target: 1000 -> Remaining: 750
        let remaining = calculate_remaining_to_target(Some(250.0), 1000.0);
        assert_eq!(remaining, 750.0);
        // 750 / 250 = 3 months
        let months = calculate_rough_months_to_target(remaining, 250.0, 1000.0);
        assert_eq!(months, Some(3));
    }

    #[test]
    fn test_savings_target_when_achieved_returns_zero_months() {
        let remaining = calculate_remaining_to_target(Some(1200.0), 1000.0);
        assert_eq!(remaining, 0.0);
        let months = calculate_rough_months_to_target(remaining, 200.0, 1000.0);
        assert_eq!(months, Some(0));
    }

    #[test]
    fn test_debit_overdraft_payoff_calculates_debt_and_rough_months() {
        // Debit account with £500 overdraft limit, currently at -£300 balance
        let debt = calculate_debt_owed(Some("DEBIT"), Some(500.0), Some(-300.0), 0.0);
        assert_eq!(debt, 300.0);
        let months = calculate_rough_months_to_payoff(debt, 100.0);
        assert_eq!(months, Some(3));
    }

    #[test]
    fn test_debit_positive_balance_has_zero_debt_owed() {
        let debt = calculate_debt_owed(Some("DEBIT"), Some(500.0), Some(250.0), 0.0);
        assert_eq!(debt, 0.0);
        let months = calculate_rough_months_to_payoff(debt, 50.0);
        assert_eq!(months, Some(0));
    }

    #[test]
    fn test_scale_items_proportionally_preserves_weights() {
        let mut items = vec![
            SavingsPlanItemDto {
                id: 1,
                savings_plan_id: 1,
                name: "Credit Card".to_string(),
                item_type: "DEBT_PAYOFF".to_string(),
                account_id: None,
                account_name: None,
                account_type: None,
                account_limit: None,
                current_balance: None,
                debt_owed: 500.0,
                remaining_to_target: 0.0,
                rough_months: Some(2),
                target_amount: 0.0,
                monthly_amount: 100.0,
                priority: 1,
                locked: false,
                archived: false,
            },
            SavingsPlanItemDto {
                id: 2,
                savings_plan_id: 1,
                name: "Holiday Pot".to_string(),
                item_type: "SAVINGS_TARGET".to_string(),
                account_id: None,
                account_name: None,
                account_type: None,
                account_limit: None,
                current_balance: None,
                debt_owed: 0.0,
                remaining_to_target: 1000.0,
                rough_months: Some(4),
                target_amount: 1000.0,
                monthly_amount: 300.0,
                priority: 2,
                locked: false,
                archived: false,
            },
        ];

        // Total allocated = 400. Item 1 has 25% share, Item 2 has 75% share.
        scale_items_proportionally(&mut items, 600.0);
        assert_eq!(items[0].monthly_amount, 150.0);
        assert_eq!(items[1].monthly_amount, 450.0);

        scale_items_proportionally(&mut items, 200.0);
        assert_eq!(items[0].monthly_amount, 50.0);
        assert_eq!(items[1].monthly_amount, 150.0);
    }

    #[test]
    fn test_balance_evenly_divides_target_pot() {
        let mut items = vec![
            SavingsPlanItemDto {
                id: 1,
                savings_plan_id: 1,
                name: "Item 1".to_string(),
                item_type: "SAVINGS_TARGET".to_string(),
                account_id: None,
                account_name: None,
                account_type: None,
                account_limit: None,
                current_balance: None,
                debt_owed: 0.0,
                remaining_to_target: 500.0,
                rough_months: None,
                target_amount: 500.0,
                monthly_amount: 50.0,
                priority: 1,
                locked: false,
                archived: false,
            },
            SavingsPlanItemDto {
                id: 2,
                savings_plan_id: 1,
                name: "Item 2".to_string(),
                item_type: "SAVINGS_TARGET".to_string(),
                account_id: None,
                account_name: None,
                account_type: None,
                account_limit: None,
                current_balance: None,
                debt_owed: 0.0,
                remaining_to_target: 500.0,
                rough_months: None,
                target_amount: 500.0,
                monthly_amount: 150.0,
                priority: 2,
                locked: false,
                archived: false,
            },
        ];

        balance_items_evenly(&mut items, 400.0);
        assert_eq!(items[0].monthly_amount, 200.0);
        assert_eq!(items[1].monthly_amount, 200.0);
    }

    #[test]
    fn test_trim_deficit_removes_from_lower_priority() {
        let mut items = vec![
            SavingsPlanItemDto {
                id: 1,
                savings_plan_id: 1,
                name: "High Priority P1".to_string(),
                item_type: "DEBT_PAYOFF".to_string(),
                account_id: None,
                account_name: None,
                account_type: None,
                account_limit: None,
                current_balance: None,
                debt_owed: 500.0,
                remaining_to_target: 0.0,
                rough_months: None,
                target_amount: 0.0,
                monthly_amount: 300.0,
                priority: 1,
                locked: false,
                archived: false,
            },
            SavingsPlanItemDto {
                id: 2,
                savings_plan_id: 1,
                name: "Low Priority P2".to_string(),
                item_type: "SAVINGS_TARGET".to_string(),
                account_id: None,
                account_name: None,
                account_type: None,
                account_limit: None,
                current_balance: None,
                debt_owed: 0.0,
                remaining_to_target: 500.0,
                rough_months: None,
                target_amount: 500.0,
                monthly_amount: 200.0,
                priority: 2,
                locked: false,
                archived: false,
            },
        ];

        // Total allocated is 500. Target pot is 350. Deficit is 150.
        // It should trim 150 from P2 (low priority), leaving P1 untouched!
        trim_items_deficit(&mut items, 350.0);
        assert_eq!(items[0].monthly_amount, 300.0); // P1 intact
        assert_eq!(items[1].monthly_amount, 50.0);  // P2 trimmed from 200 down to 50
    }

    fn acct(id: i64, name: &str, t: &str, balance: f32, limit: f32) -> AccountSimple {
        AccountSimple { id, name: name.to_string(), account_type: t.to_string(), balance, limit }
    }

    #[test]
    fn test_overdraft_candidates_picks_overdrawn_debit_accounts() {
        let accounts = vec![
            acct(1, "Current", "DEBIT", -120.5, 500.0),
            acct(2, "Bills", "DEBIT_SHARED_BILLS", 40.0, 200.0),
            acct(3, "Card", "CREDIT", -10.0, 1000.0),
        ];
        let c = select_overdraft_candidates(&accounts, &[]);
        assert_eq!(c, vec![DebtCandidate { account_id: 1, name: "Current Overdraft".into(), debt: 120.5 }]);
    }

    #[test]
    fn test_overdraft_candidates_falls_back_to_overdraft_limit() {
        let accounts = vec![acct(1, "Current", "DEBIT", 10.0, 500.0), acct(2, "NoOD", "DEBIT", 10.0, 0.0)];
        let c = select_overdraft_candidates(&accounts, &[]);
        assert_eq!(c.len(), 1);
        assert_eq!(c[0].debt, 500.0);
    }

    #[test]
    fn test_overdraft_candidates_skips_already_linked() {
        let accounts = vec![acct(1, "Current", "DEBIT", -50.0, 500.0)];
        assert!(select_overdraft_candidates(&accounts, &[1]).is_empty());
    }

    #[test]
    fn test_credit_card_candidates_debt_and_skips() {
        let accounts = vec![
            acct(1, "Barclaycard", "CREDIT", 1400.0, 2000.0),
            acct(2, "Paid off", "CREDIT", 1000.0, 1000.0),
            acct(3, "NoLimit", "CREDIT", 75.0, 0.0),
            acct(4, "Linked", "CREDIT", 0.0, 500.0),
            acct(5, "Debit", "DEBIT", -5.0, 0.0),
        ];
        let c = select_credit_card_candidates(&accounts, &[4]);
        assert_eq!(c.len(), 2);
        assert_eq!((c[0].account_id, c[0].debt), (1, 600.0));
        assert_eq!((c[1].account_id, c[1].debt), (3, 75.0));
    }

    #[test]
    fn test_allocate_max_amount_never_negative() {
        assert_eq!(allocate_max_amount(42.5), 42.5);
        assert_eq!(allocate_max_amount(-10.0), 0.0);
    }

    #[test]
    fn test_deduct_deficit_from_slice() {
        assert_eq!(deduct_deficit_from_slice(100.0, -30.0), 70.0);
        assert_eq!(deduct_deficit_from_slice(20.0, -30.0), 0.0);
        assert_eq!(deduct_deficit_from_slice(100.0, 15.0), 100.0);
    }

    #[test]
    fn test_parse_allocation_amounts() {
        let mut form = std::collections::HashMap::new();
        form.insert("amount_12".to_string(), "150.50".to_string());
        form.insert("amount_3".to_string(), "-5".to_string());
        form.insert("amount_x".to_string(), "10".to_string());
        form.insert("amount_7".to_string(), "abc".to_string());
        form.insert("csrfToken".to_string(), "t".to_string());
        assert_eq!(parse_allocation_amounts(&form), vec![(3, 0.0), (12, 150.5)]);
    }

    #[test]
    fn test_form_empty_numeric_fields_are_none() {
        let f: FormAddItem = serde_json::from_str(
            r#"{"name":"Holiday","type":"SAVINGS_TARGET","targetAmount":"","monthlyAmount":" 25.50 ","priority":"","accountId":"0"}"#,
        )
        .unwrap();
        assert_eq!(f.target_amount, None);
        assert_eq!(f.monthly_amount, Some(25.5));
        assert_eq!(f.priority, None);
        assert_eq!(f.linked_account_id(), None);
    }

    #[test]
    fn test_form_missing_fields_and_real_account() {
        let f: FormAddItem = serde_json::from_str(r#"{"name":"x","accountId":"42","targetAmount":"1000"}"#).unwrap();
        assert_eq!(f.linked_account_id(), Some(42));
        assert_eq!(f.target_amount, Some(1000.0));
        assert_eq!(f.monthly_amount, None);
        assert_eq!(f.normalized_type(), "SAVINGS_TARGET");
    }

    #[test]
    fn test_form_rejects_garbage_number() {
        assert!(serde_json::from_str::<FormAddItem>(r#"{"name":"x","targetAmount":"abc"}"#).is_err());
    }

    #[test]
    fn test_reorder_priorities_moves_and_shifts() {
        let order = [10, 20, 30, 40];
        assert_eq!(reorder_priorities(&order, 40, 1), vec![(40, 1), (10, 2), (20, 3), (30, 4)]);
        assert_eq!(reorder_priorities(&order, 10, 3), vec![(20, 1), (30, 2), (10, 3), (40, 4)]);
        assert_eq!(reorder_priorities(&order, 20, 99), vec![(10, 1), (30, 2), (40, 3), (20, 4)]);
        assert_eq!(reorder_priorities(&order, 20, 0), vec![(20, 1), (10, 2), (30, 3), (40, 4)]);
        assert_eq!(reorder_priorities(&order, 99, 2), vec![(10, 1), (20, 2), (30, 3), (40, 4)]);
    }
}
