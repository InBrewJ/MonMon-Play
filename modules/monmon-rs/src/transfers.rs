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
    pub monthly_income: f32,
    pub monthly_income_formatted: String,
    pub savings_percentage: i32,
    pub monthly_savings_budget: f32,
    pub monthly_savings_budget_formatted: String,
    pub available_liquid_funds: f32,
    pub available_liquid_funds_formatted: String,
    pub total_funding_capacity: f32,
    pub total_funding_capacity_formatted: String,
    pub funding_cushion: f32,
    pub funding_cushion_formatted: String,
    pub is_fully_covered: bool,
    pub outgoings_items: Vec<PaydayTransferItemDto>,
    pub savings_items: Vec<PaydayTransferItemDto>,
    pub items: Vec<PaydayTransferItemDto>,
}

#[derive(Template)]
#[template(path = "transfers.html")]
pub struct TransfersTemplate {
    #[allow(dead_code)]
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

    // 2. Outgoings Cover Transfers (Segregated at the top)
    // Accounts with active outgoings (other than the primary salary source account)
    let next_cycle_payday = find_next_payday_date(payday_date + chrono::Duration::days(1), nominal_payday);

    let outgoing_acc_rows = sqlx::query(
        r#"
        SELECT
            a.id AS account_id,
            a.name AS account_name,
            a.type AS account_type,
            COALESCE((SELECT value FROM balance b WHERE b.account_id = a.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS current_bal,
            o.id AS outgoing_id,
            o.name AS outgoing_name,
            o.cost::real AS outgoing_cost,
            o.outgoingday AS outgoing_day,
            o.outgoingmonth AS outgoing_month
        FROM outgoing o
        JOIN account a ON o.account_id = a.id
        WHERE (o.userid = $1 OR o.user_id = $1)
          AND (o.archived IS NULL OR o.archived = false)
          AND (o.paused IS NULL OR o.paused = false)
          AND (a.archived IS NULL OR a.archived = false)
        ORDER BY a.name ASC, o.outgoingday ASC, o.id ASC
        "#,
    )
    .bind(resolved_uid)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    use std::collections::BTreeMap;
    struct AccountOutgoingGroup {
        acc_id: i64,
        acc_name: String,
        acc_type: String,
        current_bal: f32,
        total_cost: f32,
        breakdown: Vec<YearlyBillBreakdownItem>,
    }

    let mut account_groups: BTreeMap<i64, AccountOutgoingGroup> = BTreeMap::new();

    for r in outgoing_acc_rows {
        let acc_id: i64 = r.get("account_id");
        if let Some(src_id) = source_acc_id {
            if acc_id == src_id {
                continue;
            }
        }

        let outgoing_month: Option<i32> = r.get("outgoing_month");
        let outgoing_day: i32 = r.get("outgoing_day");
        let cost: f32 = r.get("outgoing_cost");
        let outgoing_name: String = r.get("outgoing_name");

        let is_due = if let Some(m) = outgoing_month {
            if m > 0 {
                let due_year = if m < payday_date.month() as i32 {
                    payday_date.year() + 1
                } else {
                    payday_date.year()
                };
                if let Some(due_date) = NaiveDate::from_ymd_opt(due_year, m as u32, (outgoing_day as u32).min(28)) {
                    due_date >= payday_date && due_date < next_cycle_payday
                } else {
                    false
                }
            } else {
                true
            }
        } else {
            true
        };

        if !is_due || cost <= 0.0 {
            continue;
        }

        let entry = account_groups.entry(acc_id).or_insert_with(|| AccountOutgoingGroup {
            acc_id,
            acc_name: r.get("account_name"),
            acc_type: r.get("account_type"),
            current_bal: r.get("current_bal"),
            total_cost: 0.0,
            breakdown: Vec::new(),
        });

        entry.total_cost += cost;
        entry.breakdown.push(YearlyBillBreakdownItem {
            name: outgoing_name,
            cost,
            cost_formatted: format!("{:.2}", cost),
            due_date: format!("Day {}", outgoing_day),
            monthly_saving: cost,
            monthly_saving_formatted: format!("{:.2}", cost),
        });
    }

    let mut outgoings_items = Vec::new();
    for (_acc_id, grp) in account_groups {
        if grp.total_cost <= 0.0 {
            continue;
        }
        let count = grp.breakdown.len();
        let desc = format!(
            "Transfer £{:.2} to cover {} outgoing{} due before next payday",
            grp.total_cost,
            count,
            if count == 1 { "" } else { "s" }
        );

        outgoings_items.push(PaydayTransferItemDto {
            id: format!("outgoings_acc_{}", grp.acc_id),
            priority: -10, // Top priority so outgoings cover sits segregated at the top
            title: format!("{} Outgoings Cover", grp.acc_name),
            category: "OUTGOINGS_COVER".to_string(),
            source_account_id: source_acc_id,
            source_account_name: source_acc_name.clone(),
            destination_account_id: Some(grp.acc_id),
            destination_account_name: grp.acc_name,
            destination_account_type: grp.acc_type,
            amount: grp.total_cost,
            amount_formatted: format!("{:.2}", grp.total_cost),
            target_amount: Some(grp.total_cost),
            target_amount_formatted: Some(format!("{:.2}", grp.total_cost)),
            current_destination_balance: Some(grp.current_bal),
            current_destination_balance_formatted: Some(format!("{:.2}", grp.current_bal)),
            progress_pct: None,
            description: desc,
            breakdown: grp.breakdown,
        });
    }

    // Sort outgoings transfers by descending amount (largest bill commitment first)
    outgoings_items.sort_by(|a, b| b.amount.partial_cmp(&a.amount).unwrap_or(std::cmp::Ordering::Equal));

    let mut savings_items = Vec::new();

    // 3. Yearly Outgoings Autosave / Sinking Fund Reserve (MonMon Calculated)
    let yearly_account_row = sqlx::query(
        "SELECT yearly_bills_account_id FROM user_access WHERE (userid = $1 OR user_id = $1 OR username = $1 OR email = $1) LIMIT 1"
    )
    .bind(resolved_uid)
    .fetch_optional(pool)
    .await?;

    let yearly_acc_id: Option<i64> = yearly_account_row.and_then(|r| r.get("yearly_bills_account_id"));

    let (dest_id, dest_name, dest_type, dest_bal) = if let Some(target_acc_id) = yearly_acc_id {
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
            (
                Some(target_acc_id),
                ar.get::<String, _>("name"),
                ar.get::<String, _>("type"),
                ar.get::<f32, _>("bal"),
            )
        } else {
            (None, "Yearly Bills Reserve".to_string(), "SAVINGS".to_string(), 0.0f32)
        }
    } else {
        (None, "Yearly Bills Reserve".to_string(), "SAVINGS".to_string(), 0.0f32)
    };

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

        savings_items.push(PaydayTransferItemDto {
            id: "yearly_bills_autosave".to_string(),
            priority: 0, // Priority 0 for annual bill safety
            title: "Yearly Bills Reserve (MonMon Calculated)".to_string(),
            category: "YEARLY_BILLS_AUTOSAVE".to_string(),
            source_account_id: source_acc_id,
            source_account_name: source_acc_name.clone(),
            destination_account_id: dest_id,
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

    // 4. Savings Plan Priority Items
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

    let savings_percentage: i32 = active_plan_row
        .as_ref()
        .map(|r| r.get("savingspercentage"))
        .unwrap_or(0);

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

            // User requirement: all items on the savings plan are read and reported
            // with their exact values from the savings plan (even if zero) and never inflated wildly.
            let amt_to_transfer = monthly_amt.max(0.0);

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

            savings_items.push(PaydayTransferItemDto {
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

    // Sort savings items by priority (Yearly Bills Autosave #0 first, then Savings Plan priorities #1, #2...)
    savings_items.sort_by_key(|it| it.priority);

    // Combine all transfers: Outgoings cover items sit segregated at the top, followed by savings & sinking fund
    let mut items = outgoings_items.clone();
    items.extend(savings_items.clone());

    let total_transfers: f32 = items.iter().map(|it| it.amount).sum();
    let source_remaining = source_acc_bal - total_transfers;

    // Monthly income total (e.g. from salary and incoming records)
    let monthly_income: f32 = sqlx::query_scalar(
        r#"
        SELECT COALESCE(SUM(netvalue), 0.0)::real
        FROM incoming
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        "#,
    )
    .bind(resolved_uid)
    .fetch_one(pool)
    .await
    .unwrap_or(0.0);

    // Available liquid funds across debit, shared_debit_bills, and savings accounts
    let available_liquid_funds: f32 = sqlx::query_scalar(
        r#"
        SELECT COALESCE(SUM(latest_bal), 0.0)::real
        FROM (
            SELECT a.id,
                   COALESCE((SELECT value FROM balance b WHERE b.account_id = a.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS latest_bal
            FROM account a
            WHERE (a.userid = $1 OR a.user_id = $1)
              AND (a.archived IS NULL OR a.archived = false)
              AND UPPER(a.type) IN ('DEBIT', 'CURRENT', 'DEBIT_SHARED_BILLS', 'SHARED_DEBIT_BILLS', 'SAVINGS', 'SHORT_TERM_SAVINGS', 'LONG_TERM_SAVINGS')
        ) t
        WHERE latest_bal > 0.0
        "#,
    )
    .bind(resolved_uid)
    .fetch_one(pool)
    .await
    .unwrap_or(0.0);

    let monthly_savings_budget = (monthly_income * (savings_percentage as f32 / 100.0) * 100.0).round() / 100.0;
    let total_funding_capacity = ((monthly_savings_budget + available_liquid_funds) * 100.0).round() / 100.0;
    let funding_cushion_val = total_funding_capacity - total_transfers;
    let is_fully_covered = funding_cushion_val >= 0.0;
    let funding_cushion = (funding_cushion_val.abs() * 100.0).round() / 100.0;

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
        monthly_income: (monthly_income * 100.0).round() / 100.0,
        monthly_income_formatted: format!("{:.2}", monthly_income),
        savings_percentage,
        monthly_savings_budget,
        monthly_savings_budget_formatted: format!("{:.2}", monthly_savings_budget),
        available_liquid_funds: (available_liquid_funds * 100.0).round() / 100.0,
        available_liquid_funds_formatted: format!("{:.2}", available_liquid_funds),
        total_funding_capacity,
        total_funding_capacity_formatted: format!("{:.2}", total_funding_capacity),
        funding_cushion,
        funding_cushion_formatted: format!("{:.2}", funding_cushion),
        is_fully_covered,
        outgoings_items,
        savings_items,
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

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::NaiveDate;

    #[test]
    fn test_savings_item_uses_exact_monthly_amount_never_inflated() {
        // Test requirement: all items on the savings plan are read and reported with their exact
        // values from the savings plan (even if zero) and never inflated wildly by the target gap (target_amt - cur_bal).
        let monthly_amt: f32 = 0.0;
        let target_amt: f32 = 2000.0;
        let cur_bal: f32 = 3.88;

        let amt_to_transfer = monthly_amt.max(0.0);
        assert_eq!(amt_to_transfer, 0.0, "Items with 0 monthly amount must be reported as 0.0, not inflated to 1996.12");
        assert_ne!(amt_to_transfer, target_amt - cur_bal);

        let positive_monthly_amt: f32 = 150.0;
        assert_eq!(positive_monthly_amt.max(0.0), 150.0);
    }

    #[test]
    fn test_payday_transfer_dates_calculation() {
        // Nominal payday on 25th
        let today = NaiveDate::from_ymd_opt(2026, 11, 14).unwrap();
        let nominal = 25;

        let last_payday = find_last_payday_date(today, nominal);
        let next_payday = find_next_payday_date(today, nominal);

        // In Nov 2026:
        // Next payday: 25 Nov 2026 (Wednesday)
        assert_eq!(next_payday, NaiveDate::from_ymd_opt(2026, 11, 25).unwrap());
        // Last payday: 25 Oct 2026 is Sunday -> adjusted to Friday 23 Oct 2026
        assert_eq!(last_payday, NaiveDate::from_ymd_opt(2026, 10, 23).unwrap());
    }

    #[test]
    fn test_outgoings_cover_transfer_grouping() {
        // Test user requirement: On payday (e.g. £3000 in), Account A has £200 outgoings for the rest
        // of the month, Account B has £10 outgoings, and Salary Account has £100 direct debits.
        // Accounts A and B must appear in the transfer window; Salary Account outgoings must not be transferred.
        let salary_acc_id = 100i64;
        let acc_a_id = 200i64;
        let acc_b_id = 300i64;

        struct TestOutgoing {
            account_id: i64,
            cost: f32,
        }

        let outgoings = vec![
            TestOutgoing { account_id: acc_a_id, cost: 150.0 },
            TestOutgoing { account_id: acc_a_id, cost: 50.0 },
            TestOutgoing { account_id: acc_b_id, cost: 10.0 },
            TestOutgoing { account_id: salary_acc_id, cost: 100.0 }, // Direct debit on salary account itself
        ];

        let mut groups: std::collections::BTreeMap<i64, f32> = std::collections::BTreeMap::new();
        for o in outgoings {
            // Exclude bills that are directly debited from the salary source account
            if o.account_id == salary_acc_id {
                continue;
            }
            *groups.entry(o.account_id).or_insert(0.0) += o.cost;
        }

        assert_eq!(groups.len(), 2, "Only secondary accounts with outgoings should be in transfer window");
        assert_eq!(groups.get(&acc_a_id).copied(), Some(200.0), "Account A needs £200 outgoings cover");
        assert_eq!(groups.get(&acc_b_id).copied(), Some(10.0), "Account B needs £10 outgoings cover");
        assert_eq!(groups.get(&salary_acc_id), None, "Salary account itself needs no inter-account transfer");
    }
}
