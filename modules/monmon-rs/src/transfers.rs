use askama::Template;
use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Json, Response},
};
use chrono::{Datelike, Local, NaiveDate};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sqlx::{PgPool, Row};
use std::sync::Arc;

use crate::anchoring::{find_last_payday_date, find_next_payday_date};
use crate::models::{calculate_yearly_bills_monthly_saving, Outgoing};
use crate::spog::resolve_user_id;
use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct TransfersQuery {
    pub tab: Option<String>,
    pub user_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct YearlyBillBreakdownItem {
    pub name: String,
    pub cost: f32,
    pub cost_formatted: String,
    pub due_date: String,
    pub monthly_saving: f32,
    pub monthly_saving_formatted: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PaydayTransferItemDto {
    pub id: String,
    pub priority: i32,
    pub title: String,
    pub category: String, // "SAVINGS_PLAN" or "YEARLY_BILLS_AUTOSAVE"
    pub source_account_id: Option<i64>,
    pub source_account_name: String,
    pub destination_account_id: Option<i64>,
    pub destination_account_name: String,
    pub destination_account_type: String,
    pub amount: f32,
    pub amount_formatted: String,
    pub target_amount: Option<f32>,
    pub target_amount_formatted: Option<String>,
    pub current_destination_balance: Option<f32>,
    pub current_destination_balance_formatted: Option<String>,
    pub progress_pct: Option<i32>,
    pub description: String,
    pub breakdown: Vec<YearlyBillBreakdownItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PaydayWindowDto {
    pub cycle_type: String, // "next" or "last"
    pub payday_date: String, // "2026-11-25"
    pub payday_display: String, // "Wednesday, 25 Nov 2026"
    pub is_today: bool,
    pub days_away: i32, // e.g. 11 (in 11 days), 0 (today), -20 (20 days ago)
    pub total_to_transfer: f32,
    pub total_to_transfer_formatted: String,
    pub transfers_count: usize,
    pub source_account_name: String,
    pub source_account_balance: f32,
    pub source_account_balance_formatted: String,
    pub source_account_remaining_balance: f32,
    pub source_account_remaining_balance_formatted: String,
    pub items: Vec<PaydayTransferItemDto>,
}

#[derive(Template)]
#[template(path = "transfers.html")]
pub struct TransfersTemplate {
    pub username: String,
    pub is_payday_today: bool,
    pub nominal_payday: i32,
    pub next_window: PaydayWindowDto,
    pub last_window: PaydayWindowDto,
    pub selected_tab: String,
}

pub async fn calculate_payday_window(
    pool: &PgPool,
    resolved_uid: &str,
    payday_date: NaiveDate,
    cycle_type: &str,
    nominal_payday: i32,
) -> Result<PaydayWindowDto, sqlx::Error> {
    let today = Local::now().date_naive();
    let is_today = today == payday_date;
    let days_away = (payday_date - today).num_days() as i32;

    // 1. Identify primary source salary account
    let source_acc_row = sqlx::query(
        r#"
        SELECT a.id, a.name, a.type,
               COALESCE((SELECT value FROM balance b WHERE b.account_id = a.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS bal
        FROM account a
        WHERE (a.userid = $1 OR a.user_id = $1)
          AND (a.archived IS NULL OR a.archived = false)
          AND (
            a.id IN (
                SELECT account_id FROM incoming
                WHERE (userid = $1 OR user_id = $1)
                  AND (archived IS NULL OR archived = false)
                  AND (payday = true OR type = 'SALARY')
                  AND account_id IS NOT NULL
            )
            OR a.type IN ('CURRENT', 'DEBIT')
          )
        ORDER BY
            CASE WHEN a.type = 'DEBIT' THEN 0 WHEN a.type = 'CURRENT' THEN 1 ELSE 2 END,
            a.id ASC
        LIMIT 1
        "#,
    )
    .bind(resolved_uid)
    .fetch_optional(pool)
    .await?;

    let (source_acc_id, source_acc_name, source_acc_bal) = match source_acc_row {
        Some(r) => (
            Some(r.get::<i64, _>("id")),
            r.get::<String, _>("name"),
            r.get::<f32, _>("bal"),
        ),
        None => (None, "Main Current Account".to_string(), 0.0f32),
    };

    let mut items = Vec::new();

    // 2. Savings Plan Priority Items
    let active_plan_row = sqlx::query(
        r#"
        SELECT id, savingspercentage
        FROM savings_plan
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY id DESC
        LIMIT 1
        "#,
    )
    .bind(resolved_uid)
    .fetch_optional(pool)
    .await?;

    if let Some(plan_row) = active_plan_row {
        let plan_id: i64 = plan_row.get("id");

        let items_rows = sqlx::query(
            r#"
            SELECT
                i.id, i.name, COALESCE(i.monthlyamount, 0.0)::real AS monthlyamount,
                COALESCE(i.targetamount, 0.0)::real AS targetamount,
                COALESCE(i.priority, 1) AS priority,
                COALESCE(i.type, 'SAVINGS_TARGET') AS item_type,
                a.id AS account_id,
                a.name AS account_name,
                a.type AS account_type,
                COALESCE((SELECT value FROM balance b WHERE b.account_id = a.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS current_bal
            FROM savings_plan_item i
            LEFT JOIN account a ON a.id = i.account_id
            WHERE i.savings_plan_id = $1
              AND (i.archived IS NULL OR i.archived = false)
            ORDER BY i.priority ASC NULLS LAST, i.id ASC
            "#,
        )
        .bind(plan_id)
        .fetch_all(pool)
        .await
        .unwrap_or_default();

        for r in items_rows {
            let monthly_amt: f32 = r.get("monthlyamount");
            let target_amt: f32 = r.get("targetamount");
            let acc_id: Option<i64> = r.get("account_id");
            let acc_name: Option<String> = r.get("account_name");
            let acc_type: Option<String> = r.get("account_type");
            let cur_bal: f32 = r.get("current_bal");

            let amt_to_transfer = if monthly_amt > 0.0 {
                monthly_amt
            } else if target_amt > 0.0 && cur_bal < target_amt {
                // If no fixed monthly set, suggest remaining gap if smaller than target
                target_amt - cur_bal
            } else {
                0.0
            };

            // Include if there's an amount to transfer or a linked account with target
            if amt_to_transfer > 0.0 || (target_amt > 0.0 && acc_id.is_some()) {
                let prog_pct = if target_amt > 0.0 {
                    Some(((cur_bal / target_amt) * 100.0).clamp(0.0, 100.0).round() as i32)
                } else {
                    None
                };

                let dest_name = acc_name.unwrap_or_else(|| "Unlinked Account".to_string());
                let dest_type = acc_type.unwrap_or_else(|| "SAVINGS".to_string());
                let item_type: String = r.get("item_type");

                let category_desc = if item_type.to_uppercase().contains("DEBT") {
                    "Debt Payoff Target"
                } else {
                    "Savings Target"
                };

                let p = r.get::<i32, _>("priority");
                let desc = format!("Savings Plan Priority #{} ({})", p, category_desc);

                items.push(PaydayTransferItemDto {
                    id: format!("savings_{}", r.get::<i64, _>("id")),
                    priority: p,
                    title: r.get("name"),
                    category: "SAVINGS_PLAN".to_string(),
                    source_account_id: source_acc_id,
                    source_account_name: source_acc_name.clone(),
                    destination_account_id: acc_id,
                    destination_account_name: dest_name,
                    destination_account_type: dest_type,
                    amount: amt_to_transfer,
                    amount_formatted: format!("{:.2}", amt_to_transfer),
                    target_amount: if target_amt > 0.0 { Some(target_amt) } else { None },
                    target_amount_formatted: if target_amt > 0.0 { Some(format!("{:.2}", target_amt)) } else { None },
                    current_destination_balance: Some(cur_bal),
                    current_destination_balance_formatted: Some(format!("{:.2}", cur_bal)),
                    progress_pct: prog_pct,
                    description: desc,
                    breakdown: Vec::new(),
                });
            }
        }
    }

    // 3. Yearly Outgoings Autosave / Sinking Fund Reserve
    let yearly_account_row = sqlx::query(
        "SELECT yearly_bills_account_id FROM user_access WHERE (user_id = $1 OR userid = $1) LIMIT 1"
    )
    .bind(resolved_uid)
    .fetch_optional(pool)
    .await?;

    let yearly_acc_id: Option<i64> = yearly_account_row.and_then(|r| r.get("yearly_bills_account_id"));

    if let Some(target_acc_id) = yearly_acc_id {
        let target_acc_row = sqlx::query(
            r#"
            SELECT a.name, a.type,
                   COALESCE((SELECT value FROM balance b WHERE b.account_id = a.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS bal
            FROM account a
            WHERE a.id = $1
            "#,
        )
        .bind(target_acc_id)
        .fetch_optional(pool)
        .await?;

        if let Some(ar) = target_acc_row {
            let dest_name: String = ar.get("name");
            let dest_type: String = ar.get("type");
            let dest_bal: f32 = ar.get("bal");

            let outgoings = sqlx::query_as::<_, Outgoing>(
                r#"
                SELECT id, name, cost, outgoingday, bill, rent, hiddenfromtotal, paused,
                       outgoingmonth, startingyear, yearinterval, account_id, user_id,
                       pause_reason, paused_at, archived
                FROM outgoing
                WHERE (userid = $1 OR user_id = $1)
                  AND (archived IS NULL OR archived = false)
                  AND (paused IS NULL OR paused = false)
                  AND (outgoingmonth IS NOT NULL AND outgoingmonth > 0)
                ORDER BY outgoingmonth ASC, outgoingday ASC
                "#,
            )
            .bind(resolved_uid)
            .fetch_all(pool)
            .await
            .unwrap_or_default();

            let calc = calculate_yearly_bills_monthly_saving(
                payday_date,
                nominal_payday,
                &outgoings,
                dest_bal,
            );

            if calc.monthly_saving > 0.0 {
                let breakdown_items: Vec<YearlyBillBreakdownItem> = calc
                    .breakdown
                    .into_iter()
                    .map(|b| YearlyBillBreakdownItem {
                        name: b.name,
                        cost: b.total_cost,
                        cost_formatted: format!("{:.2}", b.total_cost),
                        due_date: b.due_date,
                        monthly_saving: b.monthly_saving,
                        monthly_saving_formatted: format!("{:.2}", b.monthly_saving),
                    })
                    .collect();

                let desc = format!(
                    "Monthly Sinking Fund Reserve for {} upcoming annual bills (£{:.2} total cost)",
                    calc.upcoming_count, calc.total_upcoming_cost
                );

                items.push(PaydayTransferItemDto {
                    id: "yearly_bills_autosave".to_string(),
                    priority: 0, // Top priority for bill safety
                    title: "Yearly Bills Reserve Autosave".to_string(),
                    category: "YEARLY_BILLS_AUTOSAVE".to_string(),
                    source_account_id: source_acc_id,
                    source_account_name: source_acc_name.clone(),
                    destination_account_id: Some(target_acc_id),
                    destination_account_name: dest_name,
                    destination_account_type: dest_type,
                    amount: calc.monthly_saving,
                    amount_formatted: format!("{:.2}", calc.monthly_saving),
                    target_amount: Some(calc.total_upcoming_cost),
                    target_amount_formatted: Some(format!("{:.2}", calc.total_upcoming_cost)),
                    current_destination_balance: Some(dest_bal),
                    current_destination_balance_formatted: Some(format!("{:.2}", dest_bal)),
                    progress_pct: if calc.total_upcoming_cost > 0.0 {
                        Some(((dest_bal / calc.total_upcoming_cost) * 100.0).clamp(0.0, 100.0).round() as i32)
                    } else {
                        None
                    },
                    description: desc,
                    breakdown: breakdown_items,
                });
            }
        }
    }

    // Sort items: Priority 0 (Yearly Bills Autosave) first, then Savings Plan items by ascending priority
    items.sort_by_key(|it| it.priority);

    let total_transfers: f32 = items.iter().map(|it| it.amount).sum();
    let source_remaining = source_acc_bal - total_transfers;

    let payday_display = payday_date.format("%A, %e %b %Y").to_string();

    Ok(PaydayWindowDto {
        cycle_type: cycle_type.to_string(),
        payday_date: payday_date.format("%Y-%m-%d").to_string(),
        payday_display,
        is_today,
        days_away,
        total_to_transfer: (total_transfers * 100.0).round() / 100.0,
        total_to_transfer_formatted: format!("{:.2}", total_transfers),
        transfers_count: items.len(),
        source_account_name: source_acc_name,
        source_account_balance: (source_acc_bal * 100.0).round() / 100.0,
        source_account_balance_formatted: format!("{:.2}", source_acc_bal),
        source_account_remaining_balance: (source_remaining * 100.0).round() / 100.0,
        source_account_remaining_balance_formatted: format!("{:.2}", source_remaining),
        items,
    })
}

pub async fn get_transfers_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<TransfersQuery>,
) -> Result<Response, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let today = Local::now().date_naive();

    // Nominal payday from incoming or user_access
    let incoming_payday: i32 = sqlx::query_scalar(
        r#"
        SELECT COALESCE(
            (SELECT incomingmonthday FROM incoming 
             WHERE (userid = $1 OR user_id = $1) 
               AND (archived IS NULL OR archived = false) 
               AND (payday = true OR type = 'SALARY') 
             ORDER BY id ASC LIMIT 1),
            25
        )
        "#,
    )
    .bind(&resolved)
    .fetch_one(&state.pool)
    .await
    .unwrap_or(25);

    let nominal_payday = incoming_payday.max(1);

    let last_payday_date = find_last_payday_date(today, nominal_payday);
    let next_payday_date = find_next_payday_date(today, nominal_payday);

    let is_payday_today = today == last_payday_date || today == next_payday_date;

    let next_window = calculate_payday_window(
        &state.pool,
        &resolved,
        next_payday_date,
        "next",
        nominal_payday,
    )
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let last_window = calculate_payday_window(
        &state.pool,
        &resolved,
        last_payday_date,
        "last",
        nominal_payday,
    )
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let selected_tab = query.tab.unwrap_or_else(|| {
        "next".to_string()
    });

    let tmpl = TransfersTemplate {
        username: raw_uid.clone(),
        is_payday_today,
        nominal_payday,
        next_window,
        last_window,
        selected_tab,
    };

    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_transfers_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<TransfersQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let today = Local::now().date_naive();

    let incoming_payday: i32 = sqlx::query_scalar(
        r#"
        SELECT COALESCE(
            (SELECT incomingmonthday FROM incoming 
             WHERE (userid = $1 OR user_id = $1) 
               AND (archived IS NULL OR archived = false) 
               AND (payday = true OR type = 'SALARY') 
             ORDER BY id ASC LIMIT 1),
            25
        )
        "#,
    )
    .bind(&resolved)
    .fetch_one(&state.pool)
    .await
    .unwrap_or(25);

    let nominal_payday = incoming_payday.max(1);

    let last_payday_date = find_last_payday_date(today, nominal_payday);
    let next_payday_date = find_next_payday_date(today, nominal_payday);

    let is_payday_today = today == last_payday_date || today == next_payday_date;

    let next_window = calculate_payday_window(
        &state.pool,
        &resolved,
        next_payday_date,
        "next",
        nominal_payday,
    )
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let last_window = calculate_payday_window(
        &state.pool,
        &resolved,
        last_payday_date,
        "last",
        nominal_payday,
    )
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(json!({
        "status": "ok",
        "isPaydayToday": is_payday_today,
        "nominalPayday": nominal_payday,
        "nextPayday": next_window,
        "lastPayday": last_window
    })))
}
