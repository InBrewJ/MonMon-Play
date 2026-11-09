use askama::Template;
use chrono::{Datelike, Local};
use serde::{Deserialize, Serialize};
use sqlx::{PgPool, Row};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpogSpendingSectionDto {
    pub heading: String,
    pub planned_amount: f32,
    pub actual_spent: f32,
    pub remaining: f32,
    pub account_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpogSpendingPlanDto {
    pub name: String,
    pub max_monthly_allowance: f32,
    pub spent_this_month: f32,
    pub remaining_allowance: f32,
    pub sections: Vec<SpogSpendingSectionDto>,
    pub remaining_essentials: f32,
    pub adjusted_fun: f32,
    pub is_deficit: bool,
    pub absolute_deficit: f32,
    pub fun_left_per_day: f32,
    pub days_until_next_payday: i32,
    pub actual_monthly_pot_balance: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpogMetrics {
    pub incoming_total: f32,
    pub outgoing_total: f32,
    pub surplus: f32,
    pub next_pay_day: i32,
    pub next_pay_date: String,
    pub days_between_paydays: i32,
    pub max_per_day: f32,
    pub max_per_week: f32,
    pub max_per_month: f32,
    pub completed_outgoings: f32,
    pub pending_outgoings: f32,
    pub bills_cost: f32,
    pub rent_cost: f32,
    pub monthly_pot_left_per_day: f32,
    pub total_available_debit: f32,
    pub total_available_credit: f32,
    pub credit_balance: f32,
    pub credit_limit: f32,
    pub credit_usage: f32,
    pub liquid_savings_pot: f32,
    pub total_savings_pot: f32,
    pub account_groups: Vec<SpogAccountGroupDto>,
    pub spending_plan: Option<SpogSpendingPlanDto>,
    pub spent_this_month: f32,
    pub days_until_next_payday: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpogAccountStatusDto {
    pub id: i64,
    pub name: String,
    pub nickname: Option<String>,
    pub account_type: String,
    pub latest_balance: f32,
    pub latest_balance_timestamp: Option<i64>,
    pub time_ago: String,
    pub already_paid: f32,
    pub pending: f32,
    pub adjusted_available: f32,
    pub limit: f32,
    pub interest_rate: Option<f32>,
    pub freshness_status: String,
    pub freshness_message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpogAccountGroupDto {
    pub group_name: String,
    pub icon: String,
    pub accounts: Vec<SpogAccountStatusDto>,
}

pub fn format_time_ago(timestamp_sec: i64) -> String {
    let now = chrono::Utc::now().timestamp();
    let ts = if timestamp_sec > 100_000_000_000 {
        timestamp_sec / 1000
    } else {
        timestamp_sec
    };
    let diff = (now - ts).max(0);
    if diff < 60 {
        format!("{}s ago", diff)
    } else if diff < 3600 {
        format!("{}m ago", diff / 60)
    } else if diff < 86400 {
        format!("{}h ago", diff / 3600)
    } else if diff < 604800 {
        format!("{}d ago", diff / 86400)
    } else {
        format!("{}w ago", diff / 604800)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpendingPaceMetrics {
    pub spent_this_month: f32,
    pub actual_daily_spend: f32,
    pub baseline_diff_total: f32,
    pub is_behind_baseline: bool,
    pub absolute_baseline_diff_total: f32,
}

pub async fn resolve_user_id(pool: &PgPool, user_id_or_name: &str) -> String {
    let clean = user_id_or_name.trim();
    if clean.is_empty() {
        return "2ccc203f-a98c-488f-a5ba-8d20a384a9fc".to_string();
    }

    // Check user_access table first (canonical MonMon user mapping)
    let row = sqlx::query(
        r#"
        SELECT userid FROM user_access 
        WHERE userid = $1 
           OR (LOWER(username) = LOWER($1) AND userid != username) 
           OR (LOWER(email) = LOWER($1) AND userid != email)
        ORDER BY CASE WHEN userid = $1 THEN 1 WHEN length(userid) > 20 THEN 2 ELSE 3 END, id ASC 
        LIMIT 1
        "#,
    )
    .bind(clean)
    .fetch_optional(pool)
    .await
    .unwrap_or(None);

    if let Some(r) = row {
        return r.get("userid");
    }

    // Check app_user table as fallback if present
    if let Ok(Some(row)) = sqlx::query(
        "SELECT id FROM app_user WHERE LOWER(username) = LOWER($1) OR LOWER(email) = LOWER($1) OR id = $1 LIMIT 1",
    )
    .bind(clean)
    .fetch_optional(pool)
    .await
    {
        return row.get::<String, _>("id");
    }

    clean.to_string()
}

pub async fn calculate_spog(pool: &PgPool, raw_user_id: &str) -> Result<SpogMetrics, sqlx::Error> {
    let user_id = resolve_user_id(pool, raw_user_id).await;

    // 1. Incomings
    let incoming_row = sqlx::query(
        r#"
        SELECT 
            COALESCE(SUM(netvalue), 0.0)::real AS total,
            COALESCE(
                (SELECT incomingmonthday FROM incoming 
                 WHERE (userid = $1 OR user_id = $1) 
                   AND (archived IS NULL OR archived = false) 
                   AND (payday = true OR type = 'SALARY') 
                 ORDER BY id ASC LIMIT 1),
                25
            ) AS payday
        FROM incoming
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        "#,
    )
    .bind(&user_id)
    .fetch_one(pool)
    .await?;

    let incoming_total: f32 = incoming_row.get::<f32, _>("total");
    let next_pay_day: i32 = incoming_row.get::<i32, _>("payday");

    // 2. Calendar logic
    let today = Local::now().date_naive();
    let today_day = today.day() as i32;
    let current_month = today.month() as i32;
    let current_year = today.year();

    let nominal_payday = next_pay_day.max(1);
    let cycle_start = crate::historic_spent::find_last_payday_date(today, nominal_payday);
    let cycle_end = crate::historic_spent::find_next_payday_date(today, nominal_payday);
    let days_between_paydays = (cycle_end - cycle_start).num_days().max(1) as i32;
    let _days_since_last_payday = ((today - cycle_start).num_days() as i32 + 1).min(days_between_paydays).max(1);
    let days_until_next_payday = (cycle_end - today).num_days().max(1) as i32;
    let days_left = days_until_next_payday;

    // 3. Outgoings with Rent & Bill Breakdown (anchoring logic for actual outgoings due)
    let outgoings_rows = sqlx::query(
        r#"
        SELECT 
            cost, outgoingday, bill, rent
        FROM outgoing
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
          AND (hiddenfromtotal IS NULL OR hiddenfromtotal = false)
          AND (paused IS NULL OR paused = false)
          AND (outgoingmonth IS NULL OR outgoingmonth = 0 OR (outgoingmonth = $2 AND (COALESCE(yearinterval, 1) <= 1 OR ($3 >= COALESCE(startingyear, $3) AND ($3 - COALESCE(startingyear, $3)) % COALESCE(yearinterval, 1) = 0))))
        "#,
    )
    .bind(&user_id)
    .bind(current_month)
    .bind(current_year)
    .fetch_all(pool)
    .await?;

    let mut outgoing_total = 0.0f32;
    let mut bills_cost = 0.0f32;
    let mut rent_cost = 0.0f32;
    let mut completed_outgoings = 0.0f32;
    let mut pending_outgoings = 0.0f32;

    for row in outgoings_rows {
        let cost: f32 = row.get("cost");
        let out_day: i32 = row.get("outgoingday");
        let bill: bool = row.get::<Option<bool>, _>("bill").unwrap_or(false);
        let rent: bool = row.get::<Option<bool>, _>("rent").unwrap_or(false);

        outgoing_total += cost;
        if bill { bills_cost += cost; }
        if rent { rent_cost += cost; }

        let effective_day = crate::anchoring::get_effective_outgoing_day(out_day, today) as i32;
        if effective_day <= today_day {
            completed_outgoings += cost;
        } else {
            pending_outgoings += cost;
        }
    }

    // Surplus & Allowances (accounting for active Savings Plan percentage)
    let savings_plan_row = sqlx::query(
        r#"
        SELECT savingspercentage
        FROM savings_plan
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY createdat DESC NULLS LAST, id DESC
        LIMIT 1
        "#,
    )
    .bind(&user_id)
    .fetch_optional(pool)
    .await?;

    let savings_percentage: i32 = savings_plan_row
        .map(|r| r.get::<i32, _>("savingspercentage"))
        .unwrap_or(0);
    let savings_plan_cost = (savings_percentage as f32 / 100.0) * incoming_total;
    let surplus = (((incoming_total - outgoing_total - savings_plan_cost).max(0.0)) * 100.0).round() / 100.0;
    let max_per_day = if days_between_paydays > 0 {
        ((surplus / (days_between_paydays as f32)) * 100.0).round() / 100.0
    } else {
        0.0
    };
    let max_per_week = ((max_per_day * 7.0) * 100.0).round() / 100.0;
    let max_per_month = surplus;

    // 4. Accounts & Balances and Freshness Calculation
    let accounts_rows = sqlx::query(
        r#"
        SELECT 
            id, name, nickname, type,
            COALESCE(account_limit, 0.0)::real AS limit_val,
            interest_rate
        FROM account
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY name ASC
        "#,
    )
    .bind(&user_id)
    .fetch_all(pool)
    .await?;

    let mut total_available_debit = 0.0f32;
    let mut total_available_credit = 0.0f32;
    let mut credit_balance = 0.0f32;
    let mut credit_limit = 0.0f32;
    let mut liquid_savings_pot = 0.0f32;
    let mut total_savings_pot = 0.0f32;

    let mut all_account_statuses = Vec::new();

    for row in accounts_rows {
        let acc_id: i64 = row.get("id");
        let name: String = row.get("name");
        let nickname: Option<String> = row.get("nickname");
        let acc_type: String = row.get("type");
        let limit_val: f32 = row.get("limit_val");
        let interest_rate: Option<f32> = row.get("interest_rate");

        // Fetch latest balance for this account
        let bal_row = sqlx::query(
            "SELECT COALESCE(value, 0.0)::real AS value, COALESCE(timestamp, 0)::bigint AS timestamp FROM balance WHERE account_id = $1 ORDER BY timestamp DESC, id DESC LIMIT 1"
        )
        .bind(acc_id)
        .fetch_optional(pool)
        .await
        .unwrap_or(None);

        let (latest_balance, latest_balance_timestamp) = match bal_row {
            Some(r) => {
                let val: f32 = r.get("value");
                let ts: i64 = r.get("timestamp");
                let ts_sec = if ts > 100_000_000_000 { ts / 1000 } else { ts };
                (val, Some(ts_sec))
            }
            None => (0.0f32, None),
        };

        // Fetch outgoings for this account due in this cycle
        let outgoings = sqlx::query(
            r#"
            SELECT name, cost, outgoingday
            FROM outgoing
            WHERE account_id = $1
              AND (archived IS NULL OR archived = false)
              AND (paused IS NULL OR paused = false)
              AND (hiddenfromtotal IS NULL OR hiddenfromtotal = false)
              AND (outgoingmonth IS NULL OR outgoingmonth = 0 OR (outgoingmonth = $2 AND (COALESCE(yearinterval, 1) <= 1 OR ($3 >= COALESCE(startingyear, $3) AND ($3 - COALESCE(startingyear, $3)) % COALESCE(yearinterval, 1) = 0))))
            "#
        )
        .bind(acc_id)
        .bind(current_month)
        .bind(current_year)
        .fetch_all(pool)
        .await
        .unwrap_or_default();

        let mut already_paid = 0.0f32;
        let mut pending = 0.0f32;
        let mut latest_due_day: Option<i32> = None;
        let mut latest_due_date: Option<chrono::NaiveDate> = None;
        let mut latest_outgoing_name: Option<String> = None;

        for out in outgoings {
            let cost: f32 = out.get("cost");
            let out_day: i32 = out.get("outgoingday");
            let out_name: String = out.get("name");

            let eff_day = crate::anchoring::get_effective_outgoing_day(out_day, today) as i32;
            let act_date = crate::anchoring::get_actual_outgoing_date(out_day, current_year, current_month as u32);

            if eff_day <= today_day {
                already_paid += cost;
                if latest_due_day.map_or(true, |d| eff_day >= d) {
                    latest_due_day = Some(eff_day);
                    latest_due_date = Some(act_date);
                    latest_outgoing_name = Some(out_name);
                }
            } else {
                pending += cost;
            }
        }

        let adjusted_available = match acc_type.to_uppercase().as_str() {
            "SHORT_TERM_SAVINGS" | "LONG_TERM_SAVINGS" | "SAVINGS" | "PENSION" => latest_balance,
            "LOAN" | "MORTGAGE" => latest_balance,
            _ => latest_balance - pending,
        };

        // Freshness status calculation
        let (freshness_status, freshness_message) = match latest_balance_timestamp {
            None => (
                "NO_BALANCE".to_string(),
                "No balance recorded".to_string(),
            ),
            Some(bal_ts) => {
                if let (Some(due_day), Some(due_date)) = (latest_due_day, latest_due_date) {
                    let due_ts = due_date.and_hms_opt(0, 0, 0).unwrap().and_local_timezone(chrono::Utc).unwrap().timestamp();
                    let out_name_str = latest_outgoing_name.unwrap_or_else(|| "payment".to_string());
                    if bal_ts >= due_ts {
                        (
                            "FRESH".to_string(),
                            format!("Up to date: balance recorded after {} on day {}", out_name_str, due_day),
                        )
                    } else {
                        (
                            "STALE".to_string(),
                            format!("Balance Stale: {} was due on day {} after last balance snapshot", out_name_str, due_day),
                        )
                    }
                } else {
                    (
                        "NO_PAYMENT_DUE".to_string(),
                        "No payments due yet this cycle".to_string(),
                    )
                }
            }
        };

        let time_ago = latest_balance_timestamp
            .map(format_time_ago)
            .unwrap_or_else(|| "never".to_string());

        // Update overall summary metrics
        match acc_type.to_uppercase().as_str() {
            "DEBIT" | "CURRENT" => {
                total_available_debit += adjusted_available;
            }
            "CREDIT" => {
                credit_limit += limit_val;
                let used = (limit_val - latest_balance).max(0.0);
                credit_balance += used;
                total_available_credit += adjusted_available;
            }
            "SAVINGS" | "SHORT_TERM_SAVINGS" => {
                liquid_savings_pot += latest_balance;
                total_savings_pot += latest_balance;
            }
            "LONG_TERM_SAVINGS" | "PENSION" => {
                total_savings_pot += latest_balance;
            }
            _ => {
                // LOAN, MORTGAGE, DEBIT_SHARED_BILLS, etc. are NOT added to debit
            }
        }

        all_account_statuses.push(SpogAccountStatusDto {
            id: acc_id,
            name,
            nickname,
            account_type: acc_type,
            latest_balance,
            latest_balance_timestamp,
            time_ago,
            already_paid,
            pending,
            adjusted_available,
            limit: limit_val,
            interest_rate,
            freshness_status,
            freshness_message,
        });
    }

    // Group accounts
    let mut account_groups = Vec::new();
    let group_defs: [(&str, &str, &[&str]); 5] = [
        ("Current & Debit Accounts", "fas fa-university", &["CURRENT", "DEBIT", "DEBIT_SHARED_BILLS"]),
        ("Credit Cards", "fas fa-credit-card", &["CREDIT"]),
        ("Savings Accounts", "fas fa-piggy-bank", &["SHORT_TERM_SAVINGS", "LONG_TERM_SAVINGS", "SAVINGS"]),
        ("Loans & Mortgages", "fas fa-hand-holding-usd", &["LOAN", "MORTGAGE"]),
        ("Pensions & Investments", "fas fa-landmark", &["PENSION"]),
    ];

    for (g_name, g_icon, g_types) in group_defs {
        let matching: Vec<SpogAccountStatusDto> = all_account_statuses
            .iter()
            .filter(|a| g_types.contains(&a.account_type.to_uppercase().as_str()))
            .cloned()
            .collect();
        if !matching.is_empty() {
            account_groups.push(SpogAccountGroupDto {
                group_name: g_name.to_string(),
                icon: g_icon.to_string(),
                accounts: matching,
            });
        }
    }

    let other_accounts: Vec<SpogAccountStatusDto> = all_account_statuses
        .iter()
        .filter(|a| {
            !group_defs.iter().any(|(_, _, g_types)| g_types.contains(&a.account_type.to_uppercase().as_str()))
        })
        .cloned()
        .collect();
    if !other_accounts.is_empty() {
        account_groups.push(SpogAccountGroupDto {
            group_name: "Other Accounts".to_string(),
            icon: "fas fa-layer-group".to_string(),
            accounts: other_accounts,
        });
    }

    let total_available_credit = ((total_available_credit.max(0.0)) * 100.0).round() / 100.0;
    let credit_balance = ((credit_balance.max(0.0)) * 100.0).round() / 100.0;
    let credit_limit = ((credit_limit.max(0.0)) * 100.0).round() / 100.0;
    let credit_usage = if credit_limit > 0.0 {
        (((credit_balance / credit_limit) * 100.0 * 100.0).round() / 100.0).clamp(0.0, 100.0)
    } else {
        0.0
    };

    // 5. Monthly Pot Left Per Day
    let active_pot_id_row = sqlx::query(
        r#"
        SELECT id
        FROM pot
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
          AND type = 'MONTHLY'
        ORDER BY createdat DESC NULLS LAST, id DESC
        LIMIT 1
        "#,
    )
    .bind(&user_id)
    .fetch_optional(pool)
    .await?;

    let pot_account_ids: Vec<i64> = if let Some(pr) = active_pot_id_row {
        let pot_id: i64 = pr.get("id");
        sqlx::query("SELECT account_id FROM pot_accounts WHERE pot_id = $1")
            .bind(pot_id)
            .fetch_all(pool)
            .await
            .unwrap_or_default()
            .iter()
            .map(|r| r.get::<i64, _>("account_id"))
            .collect()
    } else {
        Vec::new()
    };

    let pot_balance: f32 = all_account_statuses
        .iter()
        .filter(|a| pot_account_ids.contains(&a.id))
        .map(|a| a.adjusted_available)
        .sum();

    let monthly_pot_left_per_day = (((pot_balance / (days_left.max(1) as f32)) * 100.0).round()) / 100.0;

    // 6. Active Pot Spending Plan & Mini Spending Plan
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
    .bind(&user_id)
    .fetch_optional(pool)
    .await?;

    let cycle_start_sec = cycle_start
        .and_hms_opt(0, 0, 0)
        .unwrap()
        .and_local_timezone(chrono::Utc)
        .unwrap()
        .timestamp();
    let now_sec = chrono::Utc::now().timestamp();

    let mut balance_acc_ids = pot_account_ids.clone();
    if let Some(ref pr) = active_pot_row {
        if let Some(ref j) = pr.get::<Option<String>, _>("spending_plan_sections_json") {
            if let Ok(parsed) = serde_json::from_str::<Vec<serde_json::Value>>(j) {
                for s in parsed {
                    if let Some(aid) = s.get("accountId").and_then(|v| v.as_i64()) {
                        if !balance_acc_ids.contains(&aid) {
                            balance_acc_ids.push(aid);
                        }
                    }
                }
            }
        }
        for col in &["food_account_id", "fuel_account_id", "transport_account_id", "fun_account_id"] {
            if let Some(aid) = pr.get::<Option<i64>, _>(*col) {
                if !balance_acc_ids.contains(&aid) {
                    balance_acc_ids.push(aid);
                }
            }
        }
    }

    let balance_map = crate::historic_spent::load_account_balances(pool, &balance_acc_ids)
        .await
        .unwrap_or_default();

    let mut pot_spent = 0.0f32;
    for pid in &pot_account_ids {
        if let Some(bals) = balance_map.get(pid) {
            pot_spent += crate::historic_spent::calculate_account_spent(bals, cycle_start_sec, now_sec);
        }
    }
    let spent_this_month = ((pot_spent * 100.0).round()) / 100.0;

    let spending_plan = if let Some(ref pr) = active_pot_row {
        let sp_name: Option<String> = pr.get("spending_plan_name");
        if let Some(name) = sp_name {
            if !name.trim().is_empty() {
                let sp_food: Option<f32> = pr.get("spending_plan_food");
                let sp_fuel: Option<f32> = pr.get("spending_plan_fuel");
                let sp_trans: Option<f32> = pr.get("spending_plan_transport");
                let sp_sections_json: Option<String> = pr.get("spending_plan_sections_json");

                let mut sections = Vec::new();
                if let Some(ref j) = sp_sections_json {
                    if let Ok(parsed) = serde_json::from_str::<Vec<serde_json::Value>>(j) {
                        for s in parsed {
                            let heading = s.get("heading").and_then(|v| v.as_str()).unwrap_or("").to_string();
                            let amount = s.get("amount").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
                            let account_id = s.get("accountId").and_then(|v| v.as_i64());

                            let (actual_spent, remaining, account_name) = if let Some(aid) = account_id {
                                let acc_name = all_account_statuses
                                    .iter()
                                    .find(|a| a.id == aid)
                                    .map(|a| a.nickname.clone().unwrap_or_else(|| a.name.clone()));
                                let spent = balance_map
                                    .get(&aid)
                                    .map(|b| crate::historic_spent::calculate_account_spent(b, cycle_start_sec, now_sec))
                                    .unwrap_or(0.0);
                                let rem = ((amount - spent).max(0.0) * 100.0).round() / 100.0;
                                (spent, rem, acc_name)
                            } else {
                                let fraction = (days_left as f32 / days_between_paydays as f32).clamp(0.0, 1.0);
                                let rem = ((amount * fraction) * 100.0).round() / 100.0;
                                (0.0, rem, None)
                            };

                            sections.push(SpogSpendingSectionDto {
                                heading,
                                planned_amount: amount,
                                actual_spent,
                                remaining,
                                account_name,
                            });
                        }
                    }
                }
                if sections.is_empty() {
                    let mut legacy_defs = Vec::new();
                    if let Some(f) = sp_food {
                        if f > 0.0 {
                            let aid: Option<i64> = pr.get("food_account_id");
                            legacy_defs.push(("Food", f, aid));
                        }
                    }
                    if let Some(f) = sp_fuel {
                        if f > 0.0 {
                            let aid: Option<i64> = pr.get("fuel_account_id");
                            legacy_defs.push(("Fuel", f, aid));
                        }
                    }
                    if let Some(t) = sp_trans {
                        if t > 0.0 {
                            let aid: Option<i64> = pr.get("transport_account_id");
                            legacy_defs.push(("Transport", t, aid));
                        }
                    }
                    for (heading, amount, aid_opt) in legacy_defs {
                        let (actual_spent, remaining, account_name) = if let Some(aid) = aid_opt {
                            let acc_name = all_account_statuses
                                .iter()
                                .find(|a| a.id == aid)
                                .map(|a| a.nickname.clone().unwrap_or_else(|| a.name.clone()));
                            let spent = balance_map
                                .get(&aid)
                                .map(|b| crate::historic_spent::calculate_account_spent(b, cycle_start_sec, now_sec))
                                .unwrap_or(0.0);
                            let rem = ((amount - spent).max(0.0) * 100.0).round() / 100.0;
                            (spent, rem, acc_name)
                        } else {
                            let fraction = (days_left as f32 / days_between_paydays as f32).clamp(0.0, 1.0);
                            let rem = ((amount * fraction) * 100.0).round() / 100.0;
                            (0.0, rem, None)
                        };

                        sections.push(SpogSpendingSectionDto {
                            heading: heading.to_string(),
                            planned_amount: amount,
                            actual_spent,
                            remaining,
                            account_name,
                        });
                    }
                }

                let remaining_essentials = ((sections.iter().map(|s| s.remaining).sum::<f32>()) * 100.0).round() / 100.0;
                let remaining_allowance = (((surplus - spent_this_month).max(0.0)) * 100.0).round() / 100.0;
                let adjusted_fun = (((remaining_allowance - remaining_essentials)) * 100.0).round() / 100.0;
                let is_deficit = adjusted_fun < 0.0;
                let absolute_deficit = adjusted_fun.abs();
                let fun_left_per_day = if is_deficit || days_left <= 0 {
                    0.0
                } else {
                    ((adjusted_fun / days_left as f32) * 100.0).round() / 100.0
                };

                Some(SpogSpendingPlanDto {
                    name,
                    max_monthly_allowance: surplus,
                    spent_this_month,
                    remaining_allowance,
                    sections,
                    remaining_essentials,
                    adjusted_fun,
                    is_deficit,
                    absolute_deficit,
                    fun_left_per_day,
                    days_until_next_payday: days_left,
                    actual_monthly_pot_balance: pot_balance,
                })
            } else {
                None
            }
        } else {
            None
        }
    } else {
        None
    };

    Ok(SpogMetrics {
        incoming_total,
        outgoing_total,
        surplus,
        next_pay_day,
        next_pay_date: cycle_end.format("%Y-%m-%d").to_string(),
        days_between_paydays,
        max_per_day,
        max_per_week,
        max_per_month,
        completed_outgoings,
        pending_outgoings,
        bills_cost,
        rent_cost,
        monthly_pot_left_per_day,
        total_available_debit,
        total_available_credit,
        credit_balance,
        credit_limit,
        credit_usage,
        liquid_savings_pot,
        total_savings_pot,
        account_groups,
        spending_plan,
        spent_this_month,
        days_until_next_payday,
    })
}

pub async fn calculate_spending_pace(
    pool: &PgPool,
    raw_user_id: &str,
) -> Result<SpendingPaceMetrics, sqlx::Error> {
    let user_id = resolve_user_id(pool, raw_user_id).await;
    let spog = calculate_spog(pool, &user_id).await?;
    let today = Local::now().date_naive();
    let nominal_payday = spog.next_pay_day.max(1);
    let cycle_start = crate::historic_spent::find_last_payday_date(today, nominal_payday);
    let cycle_end = crate::historic_spent::find_next_payday_date(today, nominal_payday);
    let days_in_cycle = (cycle_end - cycle_start).num_days().max(1) as i32;
    let days_elapsed = ((today - cycle_start).num_days() as i32 + 1).min(days_in_cycle).max(1);

    let spent_this_month = spog.spent_this_month;
    let actual_daily_spend = if days_elapsed > 0 {
        ((spent_this_month / days_elapsed as f32) * 100.0).round() / 100.0
    } else {
        0.0
    };

    let daily_overspend = ((actual_daily_spend - spog.max_per_day) * 100.0).round() / 100.0;
    let is_behind_baseline = daily_overspend > 0.0;
    let absolute_baseline_diff_total = ((daily_overspend.abs()) * 100.0).round() / 100.0;

    Ok(SpendingPaceMetrics {
        spent_this_month,
        actual_daily_spend,
        baseline_diff_total: daily_overspend,
        is_behind_baseline,
        absolute_baseline_diff_total,
    })
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct SpendingPaceChunkDto {
    pub chunk_index: i32,
    pub label: String,
    pub date_range: String,
    pub date_range_str: String,
    pub start_date: String,
    pub end_date: String,
    pub start_day: i32,
    pub end_day: i32,
    pub days_in_chunk: i32,
    pub days_elapsed: i32,
    pub total_spent: f32,
    pub spending_pace: f32,
    pub pace: f32,
    pub cumulative_spent: f32,
    pub cumulative_days_elapsed: i32,
    pub cumulative_pace: f32,
    pub target_max_pace: f32,
    pub difference_from_max: f32,
    pub is_current: bool,
    pub is_future: bool,
    pub is_over_target: bool,
    pub is_over_budget: bool,
}

#[derive(askama::Template)]
#[template(path = "spending_pace.html")]
pub struct SpendingPaceTemplate {
    pub days_elapsed: i32,
    pub days_in_cycle: i32,
    pub max_per_day: f32,
    pub monthly_budget: f32,
    pub actual_daily_spend: f32,
    pub spent_this_month: f32,
    pub absolute_baseline_diff_total: f32,
    pub is_behind_baseline: bool,
    pub chunks: Vec<SpendingPaceChunkDto>,
    pub chart_json: String,
    pub cycle_offset: i32,
    pub available_cycles_count: usize,
    pub is_current_cycle: bool,
    pub cycle_date_range: String,
    pub has_prev_cycle: bool,
    pub has_next_cycle: bool,
    pub prev_cycle_offset: i32,
    pub next_cycle_offset: i32,
}

pub async fn get_spending_pace_page(
    state: &std::sync::Arc<crate::AppState>,
    raw_user_id: &str,
    cycle_offset: i32,
) -> Result<axum::response::Html<String>, (axum::http::StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, raw_user_id).await;
    let available_cycles = crate::historic_spent::calculate_historic_cycles(&state.pool, &user_id, 12)
        .await
        .map_err(|e| (axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let today = Local::now().date_naive();
    let available_cycles_count = available_cycles.len();
    let safe_offset = (cycle_offset.max(0) as usize).min(available_cycles_count.saturating_sub(1));

    let selected = if !available_cycles.is_empty() {
        &available_cycles[safe_offset]
    } else {
        return Err((axum::http::StatusCode::INTERNAL_SERVER_ERROR, "No cycles available".to_string()));
    };

    let cycle_start_date = selected.cycle_start_date;
    let _cycle_end_date = selected.cycle_end_date;
    let is_current_cycle = selected.is_current_cycle;
    let days_in_cycle = selected.days_in_cycle.max(1);
    let days_elapsed = selected.days_elapsed;
    let monthly_budget = selected.pot_allowance;
    let max_per_day = ((monthly_budget / days_in_cycle as f32) * 100.0).round() / 100.0;
    let cycle_date_range = selected.cycle_date_range.clone();
    let pot_account_ids = selected.pot_account_ids.clone();

    // Load balances for pot accounts
    let balance_map = crate::historic_spent::load_account_balances(&state.pool, &pot_account_ids)
        .await
        .unwrap_or_default();

    // Build 5-day chunks
    let mut chunks = Vec::new();
    let mut cumulative_spent = 0.0f32;
    let mut cumulative_days_elapsed = 0i32;

    let mut start_day = 1;
    let mut chunk_index = 1;

    while start_day <= days_in_cycle {
        let end_day = (start_day + 4).min(days_in_cycle);
        let days_in_chunk = end_day - start_day + 1;
        let chunk_start_date = cycle_start_date + chrono::Duration::days((start_day - 1) as i64);
        let chunk_end_date = cycle_start_date + chrono::Duration::days((end_day - 1) as i64);

        let chunk_start_sec = chunk_start_date
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_local_timezone(chrono::Utc)
            .unwrap()
            .timestamp();
        let chunk_end_sec = chunk_end_date
            .and_hms_opt(23, 59, 59)
            .unwrap()
            .and_local_timezone(chrono::Utc)
            .unwrap()
            .timestamp();

        let (is_current, is_future, chunk_days_elapsed) = if is_current_cycle {
            if today < chunk_start_date {
                (false, true, 0)
            } else if today > chunk_end_date {
                (false, false, days_in_chunk)
            } else {
                let elapsed = ((today - chunk_start_date).num_days() as i32 + 1).min(days_in_chunk);
                (true, false, elapsed)
            }
        } else {
            (false, false, days_in_chunk)
        };

        let chunk_spent = if !is_future {
            let effective_end_sec = if is_current {
                chrono::Utc::now().timestamp()
            } else {
                chunk_end_sec
            };
            let mut s = 0.0f32;
            for pid in &pot_account_ids {
                if let Some(bals) = balance_map.get(pid) {
                    s += crate::historic_spent::calculate_account_spent(bals, chunk_start_sec, effective_end_sec);
                }
            }
            ((s * 100.0).round()) / 100.0
        } else {
            0.0f32
        };

        let spending_pace = if chunk_days_elapsed > 0 {
            ((chunk_spent / chunk_days_elapsed as f32) * 100.0).round() / 100.0
        } else {
            0.0f32
        };

        cumulative_spent = (((cumulative_spent + chunk_spent) * 100.0).round()) / 100.0;
        cumulative_days_elapsed += chunk_days_elapsed;
        let cumulative_pace = if cumulative_days_elapsed > 0 {
            ((cumulative_spent / cumulative_days_elapsed as f32) * 100.0).round() / 100.0
        } else {
            0.0f32
        };

        let is_over_target = spending_pace > max_per_day;
        let difference_from_max = ((spending_pace - max_per_day) * 100.0).round() / 100.0;

        let chunk_label = if start_day == end_day {
            format!("Day {}", start_day)
        } else {
            format!("Days {}–{}", start_day, end_day)
        };

        let date_range_formatted = format!(
            "{} – {}",
            chunk_start_date.format("%d %b"),
            chunk_end_date.format("%d %b")
        );

        chunks.push(SpendingPaceChunkDto {
            chunk_index,
            label: chunk_label,
            date_range: date_range_formatted.clone(),
            date_range_str: date_range_formatted,
            start_date: chunk_start_date.to_string(),
            end_date: chunk_end_date.to_string(),
            start_day,
            end_day,
            days_in_chunk,
            days_elapsed: chunk_days_elapsed,
            total_spent: chunk_spent,
            spending_pace,
            pace: spending_pace,
            cumulative_spent,
            cumulative_days_elapsed,
            cumulative_pace,
            target_max_pace: max_per_day,
            difference_from_max,
            is_current,
            is_future,
            is_over_target,
            is_over_budget: is_over_target,
        });

        start_day += 5;
        chunk_index += 1;
    }

    let total_spent = cumulative_spent;
    let actual_daily_spend = if days_elapsed > 0 {
        ((total_spent / days_elapsed as f32) * 100.0).round() / 100.0
    } else {
        0.0f32
    };

    let daily_overspend = ((actual_daily_spend - max_per_day) * 100.0).round() / 100.0;
    let is_behind_baseline = daily_overspend > 0.0;
    let absolute_baseline_diff_total = ((daily_overspend.abs()) * 100.0).round() / 100.0;

    let chart_data = serde_json::json!({
        "isCurrentCycle": is_current_cycle,
        "daysInCycle": days_in_cycle,
        "daysElapsed": days_elapsed,
        "monthlyBudget": monthly_budget,
        "maxPerDay": max_per_day,
        "totalSpent": total_spent,
        "actualDailySpend": actual_daily_spend,
        "cycleDateRange": cycle_date_range,
        "chunks": chunks.iter().map(|c| {
            serde_json::json!({
                "chunkIndex": c.chunk_index,
                "label": c.label,
                "dateRangeStr": c.date_range_str,
                "startDate": c.start_date,
                "endDate": c.end_date,
                "startDay": c.start_day,
                "endDay": c.end_day,
                "daysInChunk": c.days_in_chunk,
                "daysElapsed": c.days_elapsed,
                "totalSpent": c.total_spent,
                "spendingPace": c.spending_pace,
                "cumulativeSpent": c.cumulative_spent,
                "cumulativeDaysElapsed": c.cumulative_days_elapsed,
                "cumulativePace": c.cumulative_pace,
                "targetMaxPace": c.target_max_pace,
                "differenceFromMax": c.difference_from_max,
                "isOverBudget": c.is_over_budget,
                "isCurrent": c.is_current,
                "isFuture": c.is_future
            })
        }).collect::<Vec<_>>()
    });

    let chart_json = serde_json::to_string(&chart_data).unwrap_or_else(|_| "{}".to_string());

    let has_prev_cycle = safe_offset < available_cycles_count.saturating_sub(1);
    let has_next_cycle = safe_offset > 0;
    let prev_cycle_offset = (safe_offset + 1) as i32;
    let next_cycle_offset = (safe_offset.saturating_sub(1)) as i32;

    let tmpl = SpendingPaceTemplate {
        days_elapsed,
        days_in_cycle,
        max_per_day,
        monthly_budget,
        actual_daily_spend,
        spent_this_month: total_spent,
        absolute_baseline_diff_total,
        is_behind_baseline,
        chunks,
        chart_json,
        cycle_offset: safe_offset as i32,
        available_cycles_count,
        is_current_cycle,
        cycle_date_range,
        has_prev_cycle,
        has_next_cycle,
        prev_cycle_offset,
        next_cycle_offset,
    };

    tmpl.render()
        .map(axum::response::Html)
        .map_err(|e| (axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}
