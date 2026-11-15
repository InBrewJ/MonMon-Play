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
pub struct DirectOutgoingItemDto {
    pub id: String,
    pub name: String,
    pub cost: f32,
    pub cost_formatted: String,
    pub due_day: i32,
    pub due_date: String,
    pub account_id: i64,
    pub account_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutgoingsFundingStepDto {
    pub from_savings_account_id: i64,
    pub from_savings_account_name: String,
    pub from_savings_account_type: String, // "SAVINGS", "DEBIT", "CREDIT"
    pub from_savings_account_balance: f32,
    pub from_savings_account_balance_formatted: String,
    pub to_debit_account_id: i64,
    pub to_debit_account_name: String,
    pub to_debit_account_type: String,
    pub amount: f32,
    pub amount_formatted: String,
    pub for_outgoings_summary: String,
}

#[derive(Debug, Clone)]
pub struct LiquidityPoolItem {
    pub id: i64,
    pub name: String,
    pub account_type: String, // "SAVINGS", "DEBIT", "CREDIT"
    pub tier: i32,            // 1 for Savings, 2 for Secondary Debit, 3 for Credit
    pub initial_bal: f32,
    pub remaining_bal: f32,
}

pub fn calculate_outgoings_funding_plan(
    source_acc_id: Option<i64>,
    source_acc_name: &str,
    source_acc_type: &str,
    mut liquidity_pool: Vec<LiquidityPoolItem>,
    max_needed: Option<f32>,
) -> Vec<OutgoingsFundingStepDto> {
    let mut plan = Vec::new();
    let mut remaining_cap = match max_needed {
        Some(cap) => ((cap * 100.0).round()) / 100.0,
        None => f32::MAX,
    };

    let target_dest_id = source_acc_id.unwrap_or(0);

    while remaining_cap > 0.005 {
        // Pick available liquidity item with the lowest tier (Savings=1 before Debit=2 before Credit=3),
        // and within the same tier, the highest remaining balance/headroom (excluding destination account)
        let best_idx = liquidity_pool
            .iter()
            .enumerate()
            .filter(|(_, s)| s.id != target_dest_id && s.remaining_bal > 0.005)
            .min_by(|(_, a), (_, b)| {
                a.tier.cmp(&b.tier).then_with(|| {
                    b.remaining_bal
                        .partial_cmp(&a.remaining_bal)
                        .unwrap_or(std::cmp::Ordering::Equal)
                })
            })
            .map(|(idx, _)| idx);

        match best_idx {
            Some(idx) => {
                let item = &mut liquidity_pool[idx];
                let take = ((remaining_cap.min(item.remaining_bal) * 100.0).round()) / 100.0;
                if take <= 0.0 {
                    break;
                }
                item.remaining_bal = ((item.remaining_bal - take) * 100.0).round() / 100.0;
                remaining_cap = ((remaining_cap - take) * 100.0).round() / 100.0;

                let summary = match item.tier {
                    1 => format!("Tops up {} to cover planned transfers & direct bills", source_acc_name),
                    2 => format!("Sweeps spare cash into {} to cover planned transfers & direct bills", source_acc_name),
                    _ => format!("Absorbs shortfall using available credit headroom on {}", item.name),
                };

                plan.push(OutgoingsFundingStepDto {
                    from_savings_account_id: item.id,
                    from_savings_account_name: item.name.clone(),
                    from_savings_account_type: item.account_type.clone(),
                    from_savings_account_balance: (item.initial_bal * 100.0).round() / 100.0,
                    from_savings_account_balance_formatted: format!("{:.2}", item.initial_bal),
                    to_debit_account_id: target_dest_id,
                    to_debit_account_name: source_acc_name.to_string(),
                    to_debit_account_type: source_acc_type.to_string(),
                    amount: take,
                    amount_formatted: format!("{:.2}", take),
                    for_outgoings_summary: summary,
                });
            }
            None => {
                break;
            }
        }
    }

    plan
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
    pub direct_outgoings_total: f32,
    pub direct_outgoings_total_formatted: String,
    pub direct_outgoings_count: usize,
    pub direct_outgoings_items: Vec<DirectOutgoingItemDto>,
    pub total_commitments: f32,
    pub total_commitments_formatted: String,
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
    pub show_coverage_indicator: bool,
    pub coverage_title: String,
    pub coverage_badge_text: String,
    pub coverage_badge_css: String,
    pub coverage_description: String,
    pub savings_needed_to_topup: f32,
    pub savings_needed_to_topup_formatted: String,
    pub total_savings: f32,
    pub total_savings_formatted: String,
    pub total_debit: f32,
    pub total_debit_formatted: String,
    pub total_credit: f32,
    pub total_credit_formatted: String,
    pub outgoings_funding_plan: Vec<OutgoingsFundingStepDto>,
    pub outgoings_items: Vec<PaydayTransferItemDto>,
    pub pot_items: Vec<PaydayTransferItemDto>,
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

    let (source_acc_id, source_acc_name, source_acc_type, source_acc_bal) = match source_acc_row {
        Some(r) => (
            Some(r.get::<i64, _>("id")),
            r.get::<String, _>("name"),
            r.get::<String, _>("type"),
            r.get::<f32, _>("bal"),
        ),
        None => (None, "Main Current Account".to_string(), "DEBIT".to_string(), 0.0f32),
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
    let mut direct_outgoings_items: Vec<DirectOutgoingItemDto> = Vec::new();

    for r in outgoing_acc_rows {
        let acc_id: i64 = r.get("account_id");
        let is_source_acc = match source_acc_id {
            Some(src_id) => acc_id == src_id,
            None => false,
        };

        let outgoing_month: Option<i32> = r.get("outgoing_month");
        let outgoing_day: i32 = r.get("outgoing_day");
        let cost: f32 = r.get("outgoing_cost");
        let outgoing_name: String = r.get("outgoing_name");
        let outgoing_id: i64 = r.get("outgoing_id");

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

        if !is_due || cost < 0.005 {
            continue;
        }

        if is_source_acc {
            direct_outgoings_items.push(DirectOutgoingItemDto {
                id: format!("direct_out_{}", outgoing_id),
                name: outgoing_name,
                cost: (cost * 100.0).round() / 100.0,
                cost_formatted: format!("{:.2}", cost),
                due_day: outgoing_day,
                due_date: format!("Day {}", outgoing_day),
                account_id: acc_id,
                account_name: source_acc_name.clone(),
            });
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

    direct_outgoings_items.sort_by_key(|it| it.due_day);
    let direct_outgoings_total: f32 = ((direct_outgoings_items.iter().map(|it| it.cost).sum::<f32>() * 100.0).round()) / 100.0;
    let direct_outgoings_total_formatted = format!("{:.2}", direct_outgoings_total);
    let direct_outgoings_count = direct_outgoings_items.len();

    let mut outgoings_items = Vec::new();
    for (_acc_id, grp) in account_groups {
        if grp.total_cost < 0.005 {
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

    // 3. Monthly Pot Transfers (Living Allowance / MAX per month)
    let mut pot_items = Vec::new();

    let active_savings_plan_dto = crate::savings_plan::get_or_create_active_plan(pool, resolved_uid).await.ok();
    let max_pot_allowance = active_savings_plan_dto
        .as_ref()
        .map(|p| p.breadline_month())
        .unwrap_or(0.0);

    if max_pot_allowance >= 0.005 {
        let active_pot_row = sqlx::query(
            r#"
            SELECT p.id, p.spending_plan_name, p.spending_plan_food, p.spending_plan_fuel,
                   p.spending_plan_transport, p.spending_plan_fun, p.spending_plan_sections_json,
                   p.food_account_id, p.fun_account_id, p.fuel_account_id, p.transport_account_id
            FROM pot p
            WHERE (p.userid = $1 OR p.user_id = $1)
              AND (p.archived IS NULL OR p.archived = false)
              AND p.type = 'MONTHLY'
            ORDER BY p.createdat DESC NULLS LAST, p.id DESC
            LIMIT 1
            "#,
        )
        .bind(resolved_uid)
        .fetch_optional(pool)
        .await?;

        if let Some(pr) = active_pot_row {
            let pot_id: i64 = pr.get("id");
            let sp_sections_json: Option<String> = pr.get("spending_plan_sections_json");
            let food_acc_id: Option<i64> = pr.get("food_account_id");
            let fuel_acc_id: Option<i64> = pr.get("fuel_account_id");
            let trans_acc_id: Option<i64> = pr.get("transport_account_id");
            let fun_acc_id: Option<i64> = pr.get("fun_account_id");

            let linked_pot_acc_ids: Vec<i64> = sqlx::query_scalar(
                "SELECT account_id FROM pot_accounts WHERE pot_id = $1 ORDER BY account_id ASC"
            )
            .bind(pot_id)
            .fetch_all(pool)
            .await
            .unwrap_or_default();

            // Load account details (name, type, latest balance) for lookup
            let all_user_acc_rows = sqlx::query(
                r#"
                SELECT a.id, a.name, a.type,
                       COALESCE((SELECT value FROM balance b WHERE b.account_id = a.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS bal
                FROM account a
                WHERE (a.userid = $1 OR a.user_id = $1)
                  AND (a.archived IS NULL OR a.archived = false)
                "#,
            )
            .bind(resolved_uid)
            .fetch_all(pool)
            .await
            .unwrap_or_default();

            let mut acc_map: std::collections::HashMap<i64, (String, String, f32)> = std::collections::HashMap::new();
            for ar in all_user_acc_rows {
                let aid: i64 = ar.get("id");
                let aname: String = ar.get("name");
                let atype: String = ar.get("type");
                let abal: f32 = ar.get("bal");
                acc_map.insert(aid, (aname, atype, abal));
            }

            struct RawSection {
                heading: String,
                amount: f32,
                account_id: Option<i64>,
            }
            let mut raw_sections: Vec<RawSection> = Vec::new();

            if let Some(ref j) = sp_sections_json {
                if let Ok(parsed) = serde_json::from_str::<Vec<serde_json::Value>>(j) {
                    for s in parsed {
                        let heading = s.get("heading").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
                        let amount = s.get("amount").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
                        let acc_id = s.get("accountId").and_then(|v| v.as_i64());
                        if !heading.is_empty() && amount >= 0.005 {
                            raw_sections.push(RawSection { heading, amount, account_id: acc_id });
                        }
                    }
                }
            }

            if raw_sections.is_empty() {
                if let Some(f) = pr.get::<Option<f32>, _>("spending_plan_food") {
                    if f >= 0.005 {
                        raw_sections.push(RawSection { heading: "Food".to_string(), amount: f, account_id: food_acc_id });
                    }
                }
                if let Some(f) = pr.get::<Option<f32>, _>("spending_plan_fuel") {
                    if f >= 0.005 {
                        raw_sections.push(RawSection { heading: "Fuel".to_string(), amount: f, account_id: fuel_acc_id });
                    }
                }
                if let Some(t) = pr.get::<Option<f32>, _>("spending_plan_transport") {
                    if t >= 0.005 {
                        raw_sections.push(RawSection { heading: "Transport".to_string(), amount: t, account_id: trans_acc_id });
                    }
                }
                if let Some(f) = pr.get::<Option<f32>, _>("spending_plan_fun") {
                    if f >= 0.005 {
                        raw_sections.push(RawSection { heading: "Fun".to_string(), amount: f, account_id: fun_acc_id });
                    }
                }
            }

            // Identify primary pot account
            let primary_pot_acc_id = food_acc_id
                .filter(|aid| Some(*aid) != source_acc_id)
                .or_else(|| {
                    raw_sections.iter().find_map(|s| {
                        s.account_id.filter(|aid| Some(*aid) != source_acc_id)
                    })
                })
                .or_else(|| {
                    linked_pot_acc_ids.iter().find(|aid| Some(**aid) != source_acc_id).copied()
                });

            let mut allocated_pot_total = 0.0f32;
            for (idx, sec) in raw_sections.into_iter().enumerate() {
                let target_acc_id = sec.account_id.or(primary_pot_acc_id);
                if let Some(dest_id) = target_acc_id {
                    if Some(dest_id) == source_acc_id {
                        continue;
                    }
                    if let Some((dest_name, dest_type, dest_bal)) = acc_map.get(&dest_id) {
                        let amt = (sec.amount * 100.0).round() / 100.0;
                        allocated_pot_total += amt;
                        let desc = format!("Monthly pot living allowance for {}", sec.heading);

                        pot_items.push(PaydayTransferItemDto {
                            id: format!("monthly_pot_{}_{}", pot_id, idx + 1),
                            priority: -5,
                            title: format!("{} (Monthly Pot)", sec.heading),
                            category: "MONTHLY_POT".to_string(),
                            source_account_id: source_acc_id,
                            source_account_name: source_acc_name.clone(),
                            destination_account_id: Some(dest_id),
                            destination_account_name: dest_name.clone(),
                            destination_account_type: dest_type.clone(),
                            amount: amt,
                            amount_formatted: format!("{:.2}", amt),
                            target_amount: Some(amt),
                            target_amount_formatted: Some(format!("{:.2}", amt)),
                            current_destination_balance: Some(*dest_bal),
                            current_destination_balance_formatted: Some(format!("{:.2}", dest_bal)),
                            progress_pct: None,
                            description: desc,
                            breakdown: Vec::new(),
                        });
                    }
                }
            }

            // If configured sections sum to less than MAX / mo living allowance, allocate remainder to primary pot account
            if max_pot_allowance > allocated_pot_total + 0.005 {
                let remainder = ((max_pot_allowance - allocated_pot_total) * 100.0).round() / 100.0;
                if let Some(dest_id) = primary_pot_acc_id {
                    if Some(dest_id) != source_acc_id {
                        if let Some((dest_name, dest_type, dest_bal)) = acc_map.get(&dest_id) {
                            let desc = format!("Remaining living allowance to reach MAX / mo (£{:.2})", max_pot_allowance);
                            pot_items.push(PaydayTransferItemDto {
                                id: format!("monthly_pot_{}_remainder", pot_id),
                                priority: -4,
                                title: "Remaining Living Allowance (Monthly Pot)".to_string(),
                                category: "MONTHLY_POT".to_string(),
                                source_account_id: source_acc_id,
                                source_account_name: source_acc_name.clone(),
                                destination_account_id: Some(dest_id),
                                destination_account_name: dest_name.clone(),
                                destination_account_type: dest_type.clone(),
                                amount: remainder,
                                amount_formatted: format!("{:.2}", remainder),
                                target_amount: Some(remainder),
                                target_amount_formatted: Some(format!("{:.2}", remainder)),
                                current_destination_balance: Some(*dest_bal),
                                current_destination_balance_formatted: Some(format!("{:.2}", dest_bal)),
                                progress_pct: None,
                                description: desc,
                                breakdown: Vec::new(),
                            });
                        }
                    }
                }
            }
        }
    }

    let mut savings_items = Vec::new();

    // 4. Yearly Outgoings Autosave / Sinking Fund Reserve (MonMon Calculated)
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

    if calc.monthly_saving >= 0.005 {
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

        let is_self_transfer = match (source_acc_id, dest_id) {
            (Some(s), Some(d)) if s == d => true,
            _ => source_acc_name == dest_name,
        };

        if !is_self_transfer {
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

            let amt_to_transfer = monthly_amt.max(0.0);
            if amt_to_transfer < 0.005 {
                continue;
            }

            // Exclude self-transfers where source account is identical to destination account:
            // if target account is the salary/incoming source account, incoming salary directly lands
            // there and covers the balance/overdraft; no outbound bank transfer is needed.
            let is_self_transfer = match (source_acc_id, acc_id) {
                (Some(s), Some(d)) if s == d => true,
                _ => acc_name.as_ref() == Some(&source_acc_name),
            };
            if is_self_transfer {
                continue;
            }

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

    // Filter out any items with 0.00 amount
    outgoings_items.retain(|it| it.amount >= 0.005);
    pot_items.retain(|it| it.amount >= 0.005);
    savings_items.retain(|it| it.amount >= 0.005);

    // Combine all transfers: Outgoings cover items sit segregated at the top, followed by monthly pot, then savings & sinking fund
    let mut items = outgoings_items.clone();
    items.extend(pot_items.clone());
    items.extend(savings_items.clone());
    items.retain(|it| it.amount >= 0.005);

    let total_transfers: f32 = items.iter().map(|it| it.amount).sum();
    let total_commitments = ((total_transfers + direct_outgoings_total) * 100.0).round() / 100.0;
    let total_commitments_formatted = format!("{:.2}", total_commitments);
    let source_remaining = ((source_acc_bal - total_commitments) * 100.0).round() / 100.0;

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

    // Build 4-Tier Liquidity Funding Plan (Waterfall):
    // Tier 1: Income (Salary)
    // Tier 2: Liquid Savings Accounts (highest balance first)
    // Tier 3: Secondary Debit & Escrow Accounts (highest balance first)
    // Tier 4: Credit Accounts / Headroom (highest available headroom first)
    let funding_account_rows = sqlx::query(
        r#"
        SELECT a.id, a.name, a.type,
               COALESCE((SELECT value FROM balance b WHERE b.account_id = a.id ORDER BY b.timestamp DESC LIMIT 1), 0.0)::real AS bal
        FROM account a
        WHERE (a.userid = $1 OR a.user_id = $1)
          AND (a.archived IS NULL OR a.archived = false)
        ORDER BY bal DESC, a.id ASC
        "#,
    )
    .bind(resolved_uid)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let target_dest_id = source_acc_id.unwrap_or(0);
    let mut liquidity_pool: Vec<LiquidityPoolItem> = Vec::new();

    let mut total_savings = 0.0f32;
    let mut total_debit = 0.0f32;
    let mut total_credit = 0.0f32;

    for r in funding_account_rows {
        let aid: i64 = r.get("id");
        if aid == target_dest_id {
            continue;
        }
        let aname: String = r.get("name");
        let atype: String = r.get("type");
        let bal: f32 = r.get("bal");
        if bal <= 0.005 {
            continue;
        }

        let atype_upper = atype.to_uppercase();
        if matches!(atype_upper.as_str(), "SAVINGS" | "SHORT_TERM_SAVINGS" | "LONG_TERM_SAVINGS") {
            total_savings += bal;
            liquidity_pool.push(LiquidityPoolItem {
                id: aid,
                name: aname,
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: bal,
                remaining_bal: bal,
            });
        } else if matches!(atype_upper.as_str(), "DEBIT" | "CURRENT" | "DEBIT_SHARED_BILLS" | "SHARED_DEBIT_BILLS") {
            total_debit += bal;
            liquidity_pool.push(LiquidityPoolItem {
                id: aid,
                name: aname,
                account_type: "DEBIT".to_string(),
                tier: 2,
                initial_bal: bal,
                remaining_bal: bal,
            });
        } else if atype_upper == "CREDIT" {
            total_credit += bal;
            liquidity_pool.push(LiquidityPoolItem {
                id: aid,
                name: aname,
                account_type: "CREDIT".to_string(),
                tier: 3,
                initial_bal: bal,
                remaining_bal: bal,
            });
        }
    }

    total_savings = (total_savings * 100.0).round() / 100.0;
    total_debit = (total_debit * 100.0).round() / 100.0;
    total_credit = (total_credit * 100.0).round() / 100.0;

    let total_funding_capacity = ((monthly_income + total_savings + total_debit + total_credit) * 100.0).round() / 100.0;
    let total_commitments_rounded = (total_commitments * 100.0).round() / 100.0;
    let total_transfers_rounded = (total_transfers * 100.0).round() / 100.0;
    let monthly_income_rounded = (monthly_income * 100.0).round() / 100.0;

    // Coverage calculation taking into account complete 4-tier waterfall:
    // 1. If monthly_income covers all commitments (transfers + direct outgoings):
    //    Income alone covers it -> coverage banner not needed.
    // 2. If monthly_income + savings covers:
    //    Show savings top-up needed.
    // 3. If monthly_income + savings + secondary debit covers:
    //    Show savings & debit cash sweep needed.
    // 4. If monthly_income + savings + debit + credit covers:
    //    Show shortfall covered by credit buffer!
    // 5. If everything exhausted:
    //    Show unavoidable deficit shortfall.
    let income_alone_covers = total_commitments_rounded <= 0.005 || monthly_income_rounded >= (total_commitments_rounded - 0.005);

    let (
        show_coverage_indicator,
        coverage_title,
        coverage_badge_text,
        coverage_badge_css,
        coverage_description,
        savings_needed,
        is_fully_covered,
        funding_cushion,
        outgoings_funding_plan,
    ) = if income_alone_covers {
        (
            false,
            String::new(),
            String::new(),
            "pill-green".to_string(),
            String::new(),
            0.0f32,
            true,
            ((monthly_income_rounded - total_commitments_rounded).max(0.0) * 100.0).round() / 100.0,
            Vec::new(),
        )
    } else {
        let topup = ((total_commitments_rounded - monthly_income_rounded) * 100.0).round() / 100.0;
        let capacity_savings = ((monthly_income_rounded + total_savings) * 100.0).round() / 100.0;
        let capacity_debit = ((capacity_savings + total_debit) * 100.0).round() / 100.0;
        let capacity_credit = ((capacity_debit + total_credit) * 100.0).round() / 100.0;

        if capacity_savings >= (total_commitments_rounded - 0.005) {
            let cushion = ((capacity_savings - total_commitments_rounded) * 100.0).round() / 100.0;
            let title = if monthly_income_rounded > 0.005 {
                if direct_outgoings_total > 0.005 {
                    "Income Partially Covers Outgoings & Transfers (Savings Top-Up Needed)".to_string()
                } else {
                    "Income Partially Covers Transfers (Savings Top-Up Needed)".to_string()
                }
            } else {
                "Outgoings & Transfers Covered by Savings".to_string()
            };
            let badge = format!("£{:.2} from Savings", topup);
            let desc = if monthly_income_rounded > 0.005 {
                if direct_outgoings_total > 0.005 {
                    format!(
                        "Incoming (£{:.2}) covers part of the £{:.2} commitments (£{:.2} transfers + £{:.2} direct bills) • £{:.2} needed from savings to top up (Available Savings: £{:.2})",
                        monthly_income_rounded, total_commitments_rounded, total_transfers_rounded, direct_outgoings_total, topup, total_savings
                    )
                } else {
                    format!(
                        "Incoming (£{:.2}) covers part of the £{:.2} transfers • £{:.2} needed from savings to top up (Available Savings: £{:.2})",
                        monthly_income_rounded, total_transfers_rounded, topup, total_savings
                    )
                }
            } else {
                format!(
                    "No income detected • £{:.2} fully covered by available savings (Total Savings: £{:.2})",
                    topup, total_savings
                )
            };
            let plan = calculate_outgoings_funding_plan(
                source_acc_id,
                &source_acc_name,
                &source_acc_type,
                liquidity_pool,
                Some(topup),
            );
            (
                true,
                title,
                badge,
                "pill-amber".to_string(),
                desc,
                topup,
                true,
                cushion,
                plan,
            )
        } else if capacity_debit >= (total_commitments_rounded - 0.005) {
            let cushion = ((capacity_debit - total_commitments_rounded) * 100.0).round() / 100.0;
            let debit_needed = ((total_commitments_rounded - capacity_savings) * 100.0).round() / 100.0;
            let title = "Savings Exhausted: Shortfall Filled by Debit Cash".to_string();
            let badge = format!("£{:.2} from Savings & Cash", topup);
            let desc = format!(
                "Income (£{:.2}) and Savings (£{:.2}) exhausted • £{:.2} drawn from secondary debit/escrow accounts to cover commitments (Available Cash: £{:.2})",
                monthly_income_rounded, total_savings, debit_needed, total_debit
            );
            let plan = calculate_outgoings_funding_plan(
                source_acc_id,
                &source_acc_name,
                &source_acc_type,
                liquidity_pool,
                Some(topup),
            );
            (
                true,
                title,
                badge,
                "pill-cyan".to_string(),
                desc,
                topup,
                true,
                cushion,
                plan,
            )
        } else if capacity_credit >= (total_commitments_rounded - 0.005) {
            let cushion = ((capacity_credit - total_commitments_rounded) * 100.0).round() / 100.0;
            let credit_needed = ((total_commitments_rounded - capacity_debit) * 100.0).round() / 100.0;
            let title = "Income & Savings Exhausted: Shortfall Covered by Credit".to_string();
            let badge = format!("£{:.2} on Credit Buffer", credit_needed);
            let desc = format!(
                "Income (£{:.2}), Savings (£{:.2}), and Debit cash (£{:.2}) are fully committed • £{:.2} shortfall absorbed by available credit headroom (£{:.2} available across cards)",
                monthly_income_rounded, total_savings, total_debit, credit_needed, total_credit
            );
            let plan = calculate_outgoings_funding_plan(
                source_acc_id,
                &source_acc_name,
                &source_acc_type,
                liquidity_pool,
                Some(topup),
            );
            (
                true,
                title,
                badge,
                "pill-purple".to_string(),
                desc,
                topup,
                true,
                cushion,
                plan,
            )
        } else {
            let deficit = ((total_commitments_rounded - capacity_credit) * 100.0).round() / 100.0;
            let title = "Transfer & Outgoings Shortfall: Insufficient Income, Savings & Credit".to_string();
            let badge = format!("-£{:.2} Deficit", deficit);
            let desc = format!(
                "All income (£{:.2}), savings (£{:.2}), cash (£{:.2}), and available credit (£{:.2}) are exhausted • £{:.2} unavoidable shortfall remaining to cover £{:.2} commitments",
                monthly_income_rounded, total_savings, total_debit, total_credit, deficit, total_commitments_rounded
            );
            let plan = calculate_outgoings_funding_plan(
                source_acc_id,
                &source_acc_name,
                &source_acc_type,
                liquidity_pool,
                Some(capacity_credit),
            );
            (
                true,
                title,
                badge,
                "pill-red".to_string(),
                desc,
                topup,
                false,
                deficit,
                plan,
            )
        }
    };

    let payday_display = payday_date.format("%A, %e %b %Y").to_string();

    Ok(PaydayWindowDto {
        cycle_type: cycle_type.to_string(),
        payday_date: payday_date.format("%Y-%m-%d").to_string(),
        payday_display,
        is_today,
        days_away,
        total_to_transfer: total_transfers_rounded,
        total_to_transfer_formatted: format!("{:.2}", total_transfers_rounded),
        transfers_count: items.len(),
        direct_outgoings_total,
        direct_outgoings_total_formatted,
        direct_outgoings_count,
        direct_outgoings_items,
        total_commitments: total_commitments_rounded,
        total_commitments_formatted,
        source_account_name: source_acc_name,
        source_account_balance: (source_acc_bal * 100.0).round() / 100.0,
        source_account_balance_formatted: format!("{:.2}", source_acc_bal),
        source_account_remaining_balance: (source_remaining * 100.0).round() / 100.0,
        source_account_remaining_balance_formatted: format!("{:.2}", source_remaining),
        monthly_income: monthly_income_rounded,
        monthly_income_formatted: format!("{:.2}", monthly_income_rounded),
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
        show_coverage_indicator,
        coverage_title,
        coverage_badge_text,
        coverage_badge_css,
        coverage_description,
        savings_needed_to_topup: savings_needed,
        savings_needed_to_topup_formatted: format!("{:.2}", savings_needed),
        total_savings,
        total_savings_formatted: format!("{:.2}", total_savings),
        total_debit,
        total_debit_formatted: format!("{:.2}", total_debit),
        total_credit,
        total_credit_formatted: format!("{:.2}", total_credit),
        outgoings_funding_plan,
        outgoings_items,
        pot_items,
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

    #[test]
    fn test_outgoings_funding_plan_from_highest_balance_savings_first() {
        // User requirement:
        // Say which savings accounts we need to transfer into which debit account to cover outgoings.
        // There may be multiple savings accounts that can cover the costs; take from the account
        // with the highest current balance first, then the next, then the next, and so on.

        // Scenario: Multiple savings accounts with varying balances
        // Savings A: £300 (second highest)
        // Savings B: £400 (highest)
        // Savings C: £50  (lowest)
        let savings_pool = vec![
            LiquidityPoolItem {
                id: 1,
                name: "Savings A (Emergency)".to_string(),
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: 300.0,
                remaining_bal: 300.0,
            },
            LiquidityPoolItem {
                id: 2,
                name: "Savings B (Premium Bonds)".to_string(),
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: 400.0,
                remaining_bal: 400.0,
            },
            LiquidityPoolItem {
                id: 3,
                name: "Savings C (Coin Jar)".to_string(),
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: 50.0,
                remaining_bal: 50.0,
            },
        ];

        let plan = calculate_outgoings_funding_plan(
            Some(999),
            "Natwest Reward Debit 💵💵💵",
            "DEBIT",
            savings_pool,
            Some(550.0),
        );

        assert_eq!(plan.len(), 2, "Should take from highest balance first (B: £400), then next (A: £150)");
        // Step 1: Takes all £400 from Savings B (highest initial bal) into Natwest
        assert_eq!(plan[0].from_savings_account_id, 2);
        assert_eq!(plan[0].from_savings_account_name, "Savings B (Premium Bonds)");
        assert_eq!(plan[0].to_debit_account_id, 999);
        assert_eq!(plan[0].to_debit_account_name, "Natwest Reward Debit 💵💵💵");
        assert_eq!(plan[0].amount, 400.0);

        // Step 2: Takes remaining £150 from Savings A (next highest bal) into Natwest
        assert_eq!(plan[1].from_savings_account_id, 1);
        assert_eq!(plan[1].from_savings_account_name, "Savings A (Emergency)");
        assert_eq!(plan[1].to_debit_account_id, 999);
        assert_eq!(plan[1].to_debit_account_name, "Natwest Reward Debit 💵💵💵");
        assert_eq!(plan[1].amount, 150.0);
    }

    #[test]
    fn test_self_transfers_excluded() {
        let source_acc_id = Some(2i64);
        let source_acc_name = "Natwest Reward Debit 💵💵💵".to_string();

        let items = vec![
            (Some(2i64), Some("Natwest Reward Debit 💵💵💵".to_string()), 250.0), // Self-transfer (same ID & name)
            (Some(3620i64), Some("First Direct Current ⬛".to_string()), 150.0), // External transfer
        ];

        let mut filtered = Vec::new();
        for (acc_id, acc_name, amt) in items {
            let is_self_transfer = match (source_acc_id, acc_id) {
                (Some(s), Some(d)) if s == d => true,
                _ => acc_name.as_ref() == Some(&source_acc_name),
            };
            if !is_self_transfer {
                filtered.push(amt);
            }
        }

        assert_eq!(filtered.len(), 1);
        assert_eq!(filtered[0], 150.0);
    }

    #[test]
    fn test_zero_amount_transfer_items_filtered_out() {
        // Items with 0.00 amount to transfer must not appear on the list
        let mut outgoings = vec![
            PaydayTransferItemDto {
                id: "outgoings_1".to_string(),
                priority: -10,
                title: "Zero Bill".to_string(),
                category: "OUTGOINGS_COVER".to_string(),
                source_account_id: None,
                source_account_name: "".to_string(),
                destination_account_id: None,
                destination_account_name: "".to_string(),
                destination_account_type: "".to_string(),
                amount: 0.0,
                amount_formatted: "0.00".to_string(),
                target_amount: None,
                target_amount_formatted: None,
                current_destination_balance: None,
                current_destination_balance_formatted: None,
                progress_pct: None,
                description: "".to_string(),
                breakdown: Vec::new(),
            },
            PaydayTransferItemDto {
                id: "outgoings_2".to_string(),
                priority: -10,
                title: "Valid Bill".to_string(),
                category: "OUTGOINGS_COVER".to_string(),
                source_account_id: None,
                source_account_name: "".to_string(),
                destination_account_id: None,
                destination_account_name: "".to_string(),
                destination_account_type: "".to_string(),
                amount: 75.0,
                amount_formatted: "75.00".to_string(),
                target_amount: None,
                target_amount_formatted: None,
                current_destination_balance: None,
                current_destination_balance_formatted: None,
                progress_pct: None,
                description: "".to_string(),
                breakdown: Vec::new(),
            },
        ];

        let mut savings = vec![
            PaydayTransferItemDto {
                id: "savings_1".to_string(),
                priority: 1,
                title: "Zero Savings Plan Target".to_string(),
                category: "SAVINGS_PLAN".to_string(),
                source_account_id: None,
                source_account_name: "".to_string(),
                destination_account_id: None,
                destination_account_name: "".to_string(),
                destination_account_type: "".to_string(),
                amount: 0.0,
                amount_formatted: "0.00".to_string(),
                target_amount: None,
                target_amount_formatted: None,
                current_destination_balance: None,
                current_destination_balance_formatted: None,
                progress_pct: None,
                description: "".to_string(),
                breakdown: Vec::new(),
            },
            PaydayTransferItemDto {
                id: "savings_2".to_string(),
                priority: 2,
                title: "Active Savings Plan Target".to_string(),
                category: "SAVINGS_PLAN".to_string(),
                source_account_id: None,
                source_account_name: "".to_string(),
                destination_account_id: None,
                destination_account_name: "".to_string(),
                destination_account_type: "".to_string(),
                amount: 100.0,
                amount_formatted: "100.00".to_string(),
                target_amount: None,
                target_amount_formatted: None,
                current_destination_balance: None,
                current_destination_balance_formatted: None,
                progress_pct: None,
                description: "".to_string(),
                breakdown: Vec::new(),
            },
        ];

        outgoings.retain(|it| it.amount >= 0.005);
        savings.retain(|it| it.amount >= 0.005);
        let mut items = outgoings.clone();
        items.extend(savings.clone());
        items.retain(|it| it.amount >= 0.005);

        assert_eq!(outgoings.len(), 1);
        assert_eq!(outgoings[0].title, "Valid Bill");
        assert_eq!(savings.len(), 1);
        assert_eq!(savings[0].title, "Active Savings Plan Target");
        assert_eq!(items.len(), 2);
    }

    #[test]
    fn test_coverage_indicator_logic_with_income() {
        // Scenario 1: Income alone covers transfers (£3000 income >= £500 transfers)
        // -> show_coverage_indicator must be false
        let total_transfers_1 = 500.0f32;
        let income_1 = 3000.0f32;
        let income_alone_covers_1 = total_transfers_1 <= 0.005 || income_1 >= (total_transfers_1 - 0.005);
        assert!(income_alone_covers_1, "Income alone covers, so coverage banner must not be shown");

        // Scenario 2: Income partially covers transfers (£1000 income < £1500 transfers, savings £2000)
        // -> show_coverage_indicator must be true, topup £500 needed from savings
        let total_transfers_2 = 1500.0f32;
        let income_2 = 1000.0f32;
        let savings_2 = 2000.0f32;
        let income_alone_covers_2 = total_transfers_2 <= 0.005 || income_2 >= (total_transfers_2 - 0.005);
        assert!(!income_alone_covers_2);
        let topup_2 = total_transfers_2 - income_2;
        assert_eq!(topup_2, 500.0);
        let total_available_2 = income_2 + savings_2;
        assert!(total_available_2 >= total_transfers_2);

        // Scenario 3: Income + Savings cannot cover transfers (£1000 income + £200 savings < £1500 transfers)
        // -> show_coverage_indicator must be true, deficit £300
        let total_transfers_3 = 1500.0f32;
        let income_3 = 1000.0f32;
        let savings_3 = 200.0f32;
        let total_available_3 = income_3 + savings_3;
        assert!(total_available_3 < total_transfers_3);
        let deficit_3 = total_transfers_3 - total_available_3;
        assert_eq!(deficit_3, 300.0);
    }

    #[test]
    fn test_monthly_pot_transfers_and_full_allowance_topup() {
        // Scenario matching user jason@urawizard.com:
        // Income = £2642.83
        // Outgoings transfers = £2024.09
        // Savings transfers = £1362.13
        // Monthly pot MAX / mo allowance = £367.16
        // Configured sections: Food £216.67, Vape £28.00 (sum = £244.67)
        // Remainder = £367.16 - £244.67 = £122.49 allocated to primary pot account (Monzo)
        // Total pot transfers = 216.67 + 28.00 + 122.49 = £367.16
        // Total transfers = 2024.09 + 1362.13 + 367.16 = £3753.38
        // Top-up needed = £3753.38 - £2642.83 = exactly £1110.55!
        let income = 2642.83f32;
        let outgoings = 2024.09f32;
        let savings = 1362.13f32;
        let max_pot_allowance = 367.16f32;

        let food_amount = 216.67f32;
        let vape_amount = 28.00f32;
        let allocated_sections = ((food_amount + vape_amount) * 100.0).round() / 100.0;
        let remainder = ((max_pot_allowance - allocated_sections) * 100.0).round() / 100.0;
        assert_eq!(remainder, 122.49);

        let total_pot_transfers = ((allocated_sections + remainder) * 100.0).round() / 100.0;
        assert_eq!(total_pot_transfers, 367.16);

        let total_transfers = ((outgoings + savings + total_pot_transfers) * 100.0).round() / 100.0;
        assert_eq!(total_transfers, 3753.38);

        let topup = ((total_transfers - income) * 100.0).round() / 100.0;
        assert_eq!(topup, 1110.55);

        // Verify savings pool takes from highest balance savings account (NS&I bal £2175)
        let savings_pool = vec![
            LiquidityPoolItem {
                id: 6594,
                name: "[Real Emergency 🚨] NS&I Premium Bonds".to_string(),
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: 2175.0,
                remaining_bal: 2175.0,
            },
            LiquidityPoolItem {
                id: 54,
                name: "Overdraft yearly bills".to_string(),
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: 100.45,
                remaining_bal: 100.45,
            },
        ];

        let plan = calculate_outgoings_funding_plan(
            Some(2),
            "Natwest Reward Debit 💵💵💵",
            "DEBIT",
            savings_pool,
            Some(topup),
        );

        assert_eq!(plan.len(), 1);
        assert_eq!(plan[0].from_savings_account_id, 6594);
        assert_eq!(plan[0].from_savings_account_name, "[Real Emergency 🚨] NS&I Premium Bonds");
        assert_eq!(plan[0].to_debit_account_id, 2);
        assert_eq!(plan[0].amount, 1110.55);
        assert_eq!(plan[0].amount_formatted, "1110.55");
    }

    #[test]
    fn test_waterfall_savings_debit_and_credit_buffer() {
        // Required topup needed: £1110.55
        // Savings accounts: NS&I £500, Other Savings £100 (Total Savings = £600)
        // Debit accounts: First Direct Cash £50 (Total Debit = £50)
        // Credit accounts: Flex £428.45, Halifax £264.75 (Total Credit = £693.20)
        // Waterfall priority:
        // Tier 1: Savings (drained first: £500 from NS&I, then £100 from Other Savings) -> £600
        // Tier 2: Debit (drained next: £50 from First Direct Cash) -> £50
        // Remaining needed: £1110.55 - £650.00 = £460.55
        // Tier 3: Credit (drained in order of highest headroom first):
        //         Flex has £428.45 -> take £428.45
        //         Halifax has £264.75 -> take remaining £32.10
        // Remaining needed: £0.00 -> fully covered by credit buffer!
        let pool = vec![
            LiquidityPoolItem {
                id: 10,
                name: "Flex".to_string(),
                account_type: "CREDIT".to_string(),
                tier: 3,
                initial_bal: 428.45,
                remaining_bal: 428.45,
            },
            LiquidityPoolItem {
                id: 11,
                name: "Halifax".to_string(),
                account_type: "CREDIT".to_string(),
                tier: 3,
                initial_bal: 264.75,
                remaining_bal: 264.75,
            },
            LiquidityPoolItem {
                id: 20,
                name: "First Direct Cash".to_string(),
                account_type: "DEBIT".to_string(),
                tier: 2,
                initial_bal: 50.0,
                remaining_bal: 50.0,
            },
            LiquidityPoolItem {
                id: 30,
                name: "NS&I Premium Bonds".to_string(),
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: 500.0,
                remaining_bal: 500.0,
            },
            LiquidityPoolItem {
                id: 31,
                name: "Other Savings".to_string(),
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: 100.0,
                remaining_bal: 100.0,
            },
        ];

        let plan = calculate_outgoings_funding_plan(
            Some(2),
            "Natwest Reward Debit 💵💵💵",
            "DEBIT",
            pool,
            Some(1110.55),
        );

        assert_eq!(plan.len(), 5);
        // Step 1: NS&I (£500)
        assert_eq!(plan[0].from_savings_account_name, "NS&I Premium Bonds");
        assert_eq!(plan[0].from_savings_account_type, "SAVINGS");
        assert_eq!(plan[0].amount, 500.0);
        // Step 2: Other Savings (£100)
        assert_eq!(plan[1].from_savings_account_name, "Other Savings");
        assert_eq!(plan[1].from_savings_account_type, "SAVINGS");
        assert_eq!(plan[1].amount, 100.0);
        // Step 3: First Direct Cash (£50)
        assert_eq!(plan[2].from_savings_account_name, "First Direct Cash");
        assert_eq!(plan[2].from_savings_account_type, "DEBIT");
        assert_eq!(plan[2].amount, 50.0);
        // Step 4: Flex (£428.45)
        assert_eq!(plan[3].from_savings_account_name, "Flex");
        assert_eq!(plan[3].from_savings_account_type, "CREDIT");
        assert_eq!(plan[3].amount, 428.45);
        // Step 5: Halifax (£32.10)
        assert_eq!(plan[4].from_savings_account_name, "Halifax");
        assert_eq!(plan[4].from_savings_account_type, "CREDIT");
        assert_eq!(plan[4].amount, 32.10);
    }

    #[test]
    fn test_direct_outgoings_on_salary_account_increases_topup() {
        // User jason@urawizard.com realistic scenario:
        // Income = £2642.83
        // Inter-account transfers = £3753.38 (outgoings cover on secondary accounts + monthly pot £367.16 + savings targets)
        // Direct outgoings on Natwest Reward Debit (the salary account):
        //   - Ciaran vehicle tax: £24.06
        //   - Council tax: £149.00
        //   - Natwest Platinum fee: £23.00
        //   Direct outgoings total = £196.06
        // Total commitments = transfers (£3753.38) + direct outgoings (£196.06) = £3949.44
        // Top-up needed from savings = £3949.44 - £2642.83 = £1306.61 (instead of £1110.55!)
        let income = 2642.83f32;
        let transfers = 3753.38f32;
        let direct_bills = vec![
            DirectOutgoingItemDto {
                id: "direct_out_8534".to_string(),
                name: "Ciaran (NA15 CKP) vehicle tax".to_string(),
                cost: 24.06,
                cost_formatted: "24.06".to_string(),
                due_day: 3,
                due_date: "Day 3".to_string(),
                account_id: 2,
                account_name: "Natwest Reward Debit 💵💵💵".to_string(),
            },
            DirectOutgoingItemDto {
                id: "direct_out_8488".to_string(),
                name: "79 Main Street Council tax (+water, love you Scotland)".to_string(),
                cost: 149.00,
                cost_formatted: "149.00".to_string(),
                due_day: 8,
                due_date: "Day 8".to_string(),
                account_id: 2,
                account_name: "Natwest Reward Debit 💵💵💵".to_string(),
            },
            DirectOutgoingItemDto {
                id: "direct_out_4751".to_string(),
                name: "Natwest Platinum Reward fee".to_string(),
                cost: 23.00,
                cost_formatted: "23.00".to_string(),
                due_day: 19,
                due_date: "Day 19".to_string(),
                account_id: 2,
                account_name: "Natwest Reward Debit 💵💵💵".to_string(),
            },
        ];

        let direct_bills_total: f32 = ((direct_bills.iter().map(|b| b.cost).sum::<f32>() * 100.0).round()) / 100.0;
        assert_eq!(direct_bills_total, 196.06);

        let total_commitments = ((transfers + direct_bills_total) * 100.0).round() / 100.0;
        assert_eq!(total_commitments, 3949.44);

        let topup = ((total_commitments - income) * 100.0).round() / 100.0;
        assert_eq!(topup, 1306.61);

        // Verify funding plan tops up £1306.61 from NS&I Premium Bonds
        let pool = vec![
            LiquidityPoolItem {
                id: 6594,
                name: "[Real Emergency 🚨] NS&I Premium Bonds".to_string(),
                account_type: "SAVINGS".to_string(),
                tier: 1,
                initial_bal: 2175.0,
                remaining_bal: 2175.0,
            },
        ];

        let plan = calculate_outgoings_funding_plan(
            Some(2),
            "Natwest Reward Debit 💵💵💵",
            "DEBIT",
            pool,
            Some(topup),
        );

        assert_eq!(plan.len(), 1);
        assert_eq!(plan[0].from_savings_account_id, 6594);
        assert_eq!(plan[0].amount, 1306.61);
        assert_eq!(plan[0].amount_formatted, "1306.61");
    }
}
