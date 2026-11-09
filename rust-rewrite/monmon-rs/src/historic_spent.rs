use askama::Template;
use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::Html,
};
use chrono::{Datelike, Local, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{PgPool, Row};
use std::collections::HashMap;
use std::sync::Arc;
use crate::AppState;

pub fn is_weekend(d: NaiveDate) -> bool {
    let weekday = d.weekday();
    weekday == chrono::Weekday::Sat || weekday == chrono::Weekday::Sun
}

pub fn get_last_working_day_on_or_before(mut d: NaiveDate) -> NaiveDate {
    while is_weekend(d) {
        d = d.pred_opt().unwrap_or(d);
    }
    d
}

pub fn get_actual_payday_date(nominal_payday: i32, year: i32, month: u32) -> NaiveDate {
    let max_days = match month {
        4 | 6 | 9 | 11 => 30,
        2 => {
            if (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0) {
                29
            } else {
                28
            }
        }
        _ => 31,
    };
    let day = (nominal_payday as u32).clamp(1, max_days);
    let pay_date = NaiveDate::from_ymd_opt(year, month, day)
        .unwrap_or_else(|| NaiveDate::from_ymd_opt(year, month, 28).unwrap());
    get_last_working_day_on_or_before(pay_date)
}

pub fn find_last_payday_date(as_of: NaiveDate, nominal_payday: i32) -> NaiveDate {
    let current_month_payday = get_actual_payday_date(nominal_payday, as_of.year(), as_of.month());
    if as_of >= current_month_payday {
        current_month_payday
    } else {
        let (prev_year, prev_month) = if as_of.month() == 1 {
            (as_of.year() - 1, 12)
        } else {
            (as_of.year(), as_of.month() - 1)
        };
        get_actual_payday_date(nominal_payday, prev_year, prev_month)
    }
}

pub fn find_next_payday_date(as_of: NaiveDate, nominal_payday: i32) -> NaiveDate {
    let current_month_payday = get_actual_payday_date(nominal_payday, as_of.year(), as_of.month());
    if as_of < current_month_payday {
        current_month_payday
    } else {
        let (next_year, next_month) = if as_of.month() == 12 {
            (as_of.year() + 1, 1)
        } else {
            (as_of.year(), as_of.month() + 1)
        };
        get_actual_payday_date(nominal_payday, next_year, next_month)
    }
}

/// Computes start balance of an account for a given cycle window matching Java HistoricSpentCalculator
pub fn get_account_start_balance(balances: &[(i64, f32)], cycle_start_sec: i64, cycle_end_sec: i64) -> f32 {
    if balances.is_empty() {
        return 0.0;
    }
    // If earliest recorded balance is after cycle_end_sec, account did not exist yet
    if balances[0].0 > cycle_end_sec {
        return 0.0;
    }
    // 1. Check if an explicit balance was recorded on or shortly after payday (within 24h)
    for &(t, v) in balances {
        if t >= cycle_start_sec && t <= cycle_start_sec + 86400 {
            return ((v * 100.0).round()) / 100.0;
        }
    }
    // 2. Earliest balance recorded within the cycle
    for &(t, v) in balances {
        if t >= cycle_start_sec && t < cycle_end_sec {
            return ((v * 100.0).round()) / 100.0;
        }
    }
    // 3. Fallback: latest balance recorded before cycle_start_sec
    let mut latest_before = None;
    for &(t, v) in balances {
        if t < cycle_start_sec {
            latest_before = Some(v);
        } else {
            break;
        }
    }
    if let Some(v) = latest_before {
        return ((v * 100.0).round()) / 100.0;
    }
    ((balances[0].1 * 100.0).round()) / 100.0
}

/// Computes end balance of an account for a given cycle window matching Java HistoricSpentCalculator
pub fn get_account_end_balance(
    balances: &[(i64, f32)],
    cycle_start_sec: i64,
    cycle_end_sec: i64,
    is_current_cycle: bool,
    now_sec: i64,
) -> f32 {
    if balances.is_empty() {
        return 0.0;
    }
    let max_boundary = if is_current_cycle { now_sec } else { cycle_end_sec };
    if balances[0].0 > max_boundary {
        return 0.0;
    }

    if is_current_cycle {
        let mut latest = None;
        for &(t, v) in balances {
            if t <= now_sec {
                latest = Some(v);
            }
        }
        return ((latest.unwrap_or(balances[balances.len() - 1].1) * 100.0).round()) / 100.0;
    }

    // Completed past cycle: latest balance within cycle (up to cycle_end_sec)
    let mut latest_in_cycle = None;
    for &(t, v) in balances {
        if t >= cycle_start_sec && t <= cycle_end_sec {
            latest_in_cycle = Some(v);
        }
    }
    if let Some(v) = latest_in_cycle {
        return ((v * 100.0).round()) / 100.0;
    }

    // Otherwise latest before cycle_end_sec
    let mut latest_before_end = None;
    for &(t, v) in balances {
        if t < cycle_end_sec {
            latest_before_end = Some(v);
        }
    }
    if let Some(v) = latest_before_end {
        return ((v * 100.0).round()) / 100.0;
    }
    ((balances[0].1 * 100.0).round()) / 100.0
}

/// Computes net spent on an account matching Java HistoricSpentCalculator
pub fn calculate_account_net_spent(
    balances: &[(i64, f32)],
    cycle_start_sec: i64,
    effective_end_sec: i64,
    start_bal: f32,
    end_bal: f32,
) -> f32 {
    if balances.len() < 2 {
        return (((start_bal - end_bal) * 100.0).round()) / 100.0;
    }

    let mut start_idx = None;
    let mut best_dist = i64::MAX;
    for (i, &(t, _)) in balances.iter().enumerate() {
        let dist = (t - cycle_start_sec).abs();
        if dist <= 86400 && dist < best_dist {
            best_dist = dist;
            start_idx = Some(i);
        }
    }
    if start_idx.is_none() {
        for (i, &(t, _)) in balances.iter().enumerate() {
            if t >= cycle_start_sec && t < effective_end_sec {
                start_idx = Some(i);
                break;
            }
        }
    }
    if start_idx.is_none() {
        for (i, &(t, _)) in balances.iter().enumerate() {
            if t < cycle_start_sec {
                start_idx = Some(i);
            } else {
                break;
            }
        }
    }

    let mut total_net_spent = 0.0f32;
    let mut has_transition = false;

    if let Some(s_idx) = start_idx {
        for i in (s_idx + 1)..balances.len() {
            let (t_curr, val_curr) = balances[i];
            if t_curr <= effective_end_sec {
                let (_, val_prev) = balances[i - 1];
                let delta = val_curr - val_prev;
                total_net_spent += -delta;
                has_transition = true;
            } else {
                break;
            }
        }
    }

    if !has_transition {
        return (((start_bal - end_bal) * 100.0).round()) / 100.0;
    }

    ((total_net_spent * 100.0).round()) / 100.0
}

/// Computes total spent on an account between start_sec and end_sec based on negative balance deltas
pub fn calculate_account_spent(
    balances: &[(i64, f32)], // (timestamp_sec, value) sorted ascending
    start_sec: i64,
    end_sec: i64,
) -> f32 {
    if balances.len() < 2 {
        return 0.0;
    }
    let mut total_spent = 0.0f32;
    for i in 1..balances.len() {
        let (t_curr, val_curr) = balances[i];
        if t_curr >= start_sec && t_curr <= end_sec {
            let (_, val_prev) = balances[i - 1];
            let delta = val_curr - val_prev;
            if delta < 0.0 {
                total_spent += -delta;
            }
        }
    }
    ((total_spent * 100.0).round()) / 100.0
}

/// Loads all valid balances for given account IDs from PostgreSQL
pub async fn load_account_balances(
    pool: &PgPool,
    account_ids: &[i64],
) -> Result<HashMap<i64, Vec<(i64, f32)>>, sqlx::Error> {
    if account_ids.is_empty() {
        return Ok(HashMap::new());
    }

    let rows = sqlx::query(
        r#"
        SELECT account_id, 
               COALESCE(timestamp, 0)::bigint AS timestamp, 
               COALESCE(value, 0.0)::real AS value
        FROM balance
        WHERE account_id = ANY($1)
          AND value IS NOT NULL
        ORDER BY account_id, timestamp ASC, id ASC
        "#,
    )
    .bind(account_ids)
    .fetch_all(pool)
    .await?;

    let mut map: HashMap<i64, Vec<(i64, f32)>> = HashMap::new();
    for r in rows {
        let acc_id: i64 = r.get("account_id");
        let raw_ts: i64 = r.get("timestamp");
        let val: f32 = r.get("value");
        let ts_sec = if raw_ts > 100_000_000_000 {
            raw_ts / 1000
        } else {
            raw_ts
        };
        map.entry(acc_id).or_default().push((ts_sec, val));
    }
    Ok(map)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountSpentDto {
    pub account_name: String,
    pub account_type: String,
    pub start_balance: f32,
    pub end_balance: f32,
    pub amount_spent: f32,
    pub is_pot_account: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountBalanceSnapshotDto {
    pub account_name: String,
    pub account_type: String,
    pub balance_on_date: f32,
    pub amount_spent: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoricPotSnapshotDto {
    pub plan_summary: String,
    pub created_at_human_readable: String,
    pub is_first_pot: bool,
    pub is_last_pot: bool,
    pub is_intermediary_pot: bool,
    pub is_archived_in_period: bool,
    pub archived_date_human_readable: String,
    pub pot_spent: f32,
    pub account_snapshots: Vec<AccountBalanceSnapshotDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoricCycleDto {
    pub cycle_start_date: NaiveDate,
    pub cycle_end_date: NaiveDate,
    pub cycle_date_range: String,
    pub is_current_cycle: bool,
    pub days_elapsed: i32,
    pub days_in_cycle: i32,
    pub total_spent: f32,
    pub monthly_income: f32,
    pub pot_allowance: f32,
    pub pot_spent: f32,
    pub starting_total_balance: f32,
    pub ending_total_balance: f32,
    pub actual_daily_spend: f32,
    pub is_over_pot_allowance: bool,
    pub absolute_pot_overspend: f32,
    pub budget_utilization_percent: f32,
    pub pot_account_ids: Vec<i64>,
    pub account_breakdowns: Vec<AccountSpentDto>,
    pub pots_in_period: Vec<HistoricPotSnapshotDto>,
    pub has_multiple_pots: bool,
}

#[derive(Template)]
#[template(path = "historic_spent.html")]
pub struct HistoricSpentTemplate {
    pub has_history: bool,
    pub average_monthly_spend: f32,
    pub highest_spent: Option<f32>,
    pub highest_spent_range: Option<String>,
    pub lowest_spent: Option<f32>,
    pub lowest_spent_range: Option<String>,
    pub cycles: Vec<HistoricCycleDto>,
}

#[derive(Debug, Clone)]
struct PotRecord {
    id: i64,
    name: String,
    created_at: Option<i64>,
    last_updated: Option<i64>,
    archived: bool,
    food: Option<f32>,
    fuel: Option<f32>,
    transport: Option<f32>,
    fun: Option<f32>,
    accounts: Vec<i64>,
}

pub async fn calculate_historic_cycles(
    pool: &PgPool,
    user_id: &str,
    num_cycles: usize,
) -> Result<Vec<HistoricCycleDto>, sqlx::Error> {
    let spog = crate::spog::calculate_spog(pool, user_id).await?;
    let nominal_payday = spog.next_pay_day.max(1);
    let today = Local::now().date_naive();

    let accounts_rows = sqlx::query(
        r#"
        SELECT id, name, type
        FROM account
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let mut ledger_accounts = Vec::new();
    let mut ledger_account_ids = Vec::new();
    let mut account_names_map: HashMap<i64, (String, String)> = HashMap::new();

    for r in accounts_rows {
        let acc_id: i64 = r.get("id");
        let name: String = r.get("name");
        let acc_type: String = r.get("type");
        let t_upper = acc_type.to_uppercase();
        account_names_map.insert(acc_id, (name.clone(), acc_type.clone()));
        if t_upper != "LOAN" && t_upper != "MORTGAGE" && t_upper != "PENSION" {
            ledger_account_ids.push(acc_id);
            ledger_accounts.push((acc_id, name, acc_type));
        }
    }

    // Load monthly pots for user
    let pot_rows = sqlx::query(
        r#"
        SELECT id, name, createdat, lastupdated, COALESCE(archived, false) AS archived,
               spendingplanfood, spendingplanfuel, spendingplantransport, spendingplanfun
        FROM pot
        WHERE (userid = $1 OR user_id = $1)
          AND type = 'MONTHLY'
        ORDER BY createdat ASC NULLS LAST, id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let pot_ids: Vec<i64> = pot_rows.iter().map(|r| r.get("id")).collect();
    let pot_accounts_rows = if !pot_ids.is_empty() {
        sqlx::query(
            r#"
            SELECT pot_id, account_id
            FROM pot_accounts
            WHERE pot_id = ANY($1)
            "#,
        )
        .bind(&pot_ids)
        .fetch_all(pool)
        .await
        .unwrap_or_default()
    } else {
        Vec::new()
    };

    let mut pot_accounts_map: HashMap<i64, Vec<i64>> = HashMap::new();
    for r in pot_accounts_rows {
        let pid: i64 = r.get("pot_id");
        let aid: i64 = r.get("account_id");
        pot_accounts_map.entry(pid).or_default().push(aid);
    }

    let mut pots: Vec<PotRecord> = Vec::new();
    for r in pot_rows {
        let pid: i64 = r.get("id");
        let accounts = pot_accounts_map.get(&pid).cloned().unwrap_or_default();
        pots.push(PotRecord {
            id: pid,
            name: r.get("name"),
            created_at: r.get("createdat"),
            last_updated: r.get("lastupdated"),
            archived: r.get("archived"),
            food: r.get("spendingplanfood"),
            fuel: r.get("spendingplanfuel"),
            transport: r.get("spendingplantransport"),
            fun: r.get("spendingplanfun"),
            accounts,
        });
    }

    let balance_map = load_account_balances(pool, &ledger_account_ids)
        .await
        .unwrap_or_default();

    let mut cycles = Vec::new();
    let current_cycle_start = find_last_payday_date(today, nominal_payday);
    let mut curr_cycle_start = current_cycle_start;

    for _ in 0..num_cycles.max(1) {
        let curr_cycle_end = find_next_payday_date(curr_cycle_start, nominal_payday);
        let is_current = today >= curr_cycle_start && today < curr_cycle_end;

        let start_sec = curr_cycle_start
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_local_timezone(chrono::Utc)
            .unwrap()
            .timestamp();

        let end_sec = curr_cycle_end
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_local_timezone(chrono::Utc)
            .unwrap()
            .timestamp();

        let effective_end_sec = if is_current {
            Utc::now().timestamp()
        } else {
            end_sec
        };

        let days_in_cycle = (curr_cycle_end - curr_cycle_start).num_days().max(1) as i32;
        let days_elapsed = if is_current {
            ((today - curr_cycle_start).num_days() as i32 + 1).min(days_in_cycle).max(1)
        } else {
            days_in_cycle
        };

        // Determine active pot for this cycle (latest monthly pot created on or before cycle end)
        let active_pot = pots
            .iter()
            .filter(|p| {
                let ct = p.created_at.map(|ts| if ts > 100_000_000_000 { ts / 1000 } else { ts }).unwrap_or(0);
                ct <= end_sec
            })
            .last()
            .or_else(|| pots.last());

        let pot_account_ids: Vec<i64> = if let Some(p) = active_pot {
            if !p.accounts.is_empty() {
                p.accounts.clone()
            } else {
                ledger_account_ids.clone()
            }
        } else {
            ledger_account_ids.clone()
        };

        let mut starting_total_balance = 0.0f32;
        let mut ending_total_balance = 0.0f32;
        let mut cycle_spent = 0.0f32;
        let mut raw_pot_spent = 0.0f32;
        let mut total_savings_inflow = 0.0f32;
        let mut account_breakdowns = Vec::new();

        for &(acc_id, ref acc_name, ref acc_type) in &ledger_accounts {
            let empty_vec = Vec::new();
            let bals = balance_map.get(&acc_id).unwrap_or(&empty_vec);
            if bals.is_empty() {
                continue;
            }
            if bals[0].0 > effective_end_sec {
                continue; // Account didn't exist yet
            }

            let start_bal = get_account_start_balance(bals, start_sec, end_sec);
            let end_bal = get_account_end_balance(bals, start_sec, end_sec, is_current, effective_end_sec);
            let acc_spent = calculate_account_net_spent(bals, start_sec, effective_end_sec, start_bal, end_bal);
            let is_pot_account = pot_account_ids.contains(&acc_id);

            let t_upper = acc_type.to_uppercase();
            let is_savings = t_upper.contains("SAVING");
            if is_savings && acc_spent < 0.0 {
                total_savings_inflow += -acc_spent;
            }

            if is_pot_account {
                raw_pot_spent += acc_spent;
            }

            starting_total_balance += start_bal;
            ending_total_balance += end_bal;
            cycle_spent += acc_spent;

            account_breakdowns.push(AccountSpentDto {
                account_name: acc_name.clone(),
                account_type: acc_type.clone(),
                start_balance: start_bal,
                end_balance: end_bal,
                amount_spent: acc_spent,
                is_pot_account,
            });
        }

        cycle_spent = ((cycle_spent.max(0.0) * 100.0).round()) / 100.0;
        let pot_spent = (((raw_pot_spent - total_savings_inflow).max(0.0) * 100.0).round()) / 100.0;
        starting_total_balance = ((starting_total_balance * 100.0).round()) / 100.0;
        ending_total_balance = ((ending_total_balance * 100.0).round()) / 100.0;
        let actual_daily_spend = if days_elapsed > 0 {
            (((cycle_spent / days_elapsed as f32) * 100.0).round()) / 100.0
        } else {
            0.0
        };

        let monthly_income = spog.incoming_total;
        let pot_allowance = spog.surplus;

        let diff = pot_spent - pot_allowance;
        let is_over = diff > 0.0;
        let absolute_pot_overspend = ((diff.abs()) * 100.0).round() / 100.0;
        let utilization = if pot_allowance > 0.0 {
            ((pot_spent / pot_allowance) * 100.0).clamp(0.0, 150.0)
        } else {
            0.0
        };

        let cycle_date_range = format!(
            "{} – {}",
            curr_cycle_start.format("%d %b %Y"),
            curr_cycle_end.format("%d %b %Y")
        );

        // Build pots configured in period snapshots
        let mut pots_in_period = Vec::new();
        let cycle_pots: Vec<&PotRecord> = pots
            .iter()
            .filter(|p| {
                let ct = p.created_at.map(|ts| if ts > 100_000_000_000 { ts / 1000 } else { ts }).unwrap_or(0);
                ct <= end_sec
            })
            .collect();

        let num_cycle_pots = cycle_pots.len();
        for (p_idx, p) in cycle_pots.iter().enumerate() {
            let is_first = p_idx == 0;
            let is_last = p_idx + 1 == num_cycle_pots;
            let is_intermediary = !is_first && !is_last;

            let arch_sec = p.last_updated.map(|ts| if ts > 100_000_000_000 { ts / 1000 } else { ts });
            let is_archived_in_period = p.archived && arch_sec.map(|t| t <= end_sec).unwrap_or(false);
            let archived_date_human_readable = if let Some(ts) = arch_sec {
                if let Some(dt) = chrono::DateTime::from_timestamp(ts, 0) {
                    dt.format("%d %b %Y").to_string()
                } else {
                    "Archived".to_string()
                }
            } else {
                "Active".to_string()
            };

            let created_at_human_readable = if let Some(ts) = p.created_at {
                let s = if ts > 100_000_000_000 { ts / 1000 } else { ts };
                chrono::DateTime::from_timestamp(s, 0)
                    .map(|dt| dt.format("%d %b %Y").to_string())
                    .unwrap_or_else(|| "—".to_string())
            } else {
                "—".to_string()
            };

            let mut plan_summary = p.name.clone();
            let mut sec_parts = Vec::new();
            if let Some(f) = p.food {
                if f > 0.0 { sec_parts.push(format!("Food: £{:.2}", f)); }
            }
            if let Some(f) = p.fuel {
                if f > 0.0 { sec_parts.push(format!("Fuel: £{:.2}", f)); }
            }
            if let Some(f) = p.transport {
                if f > 0.0 { sec_parts.push(format!("Transport: £{:.2}", f)); }
            }
            if let Some(f) = p.fun {
                if f > 0.0 { sec_parts.push(format!("Fun: £{:.2}", f)); }
            }
            if !sec_parts.is_empty() {
                plan_summary = format!("{} ({})", plan_summary, sec_parts.join(", "));
            }

            let p_accounts: Vec<i64> = if !p.accounts.is_empty() {
                p.accounts.clone()
            } else {
                ledger_account_ids.clone()
            };

            let mut account_snapshots = Vec::new();
            let mut pot_window_spent = 0.0f32;

            for &pa_id in &p_accounts {
                let empty_vec = Vec::new();
                let bals = balance_map.get(&pa_id).unwrap_or(&empty_vec);
                let (acc_name, acc_type) = account_names_map
                    .get(&pa_id)
                    .cloned()
                    .unwrap_or_else(|| ("Account".to_string(), "DEBIT".to_string()));

                let bal_on_date = get_account_end_balance(bals, start_sec, end_sec, is_current, effective_end_sec);
                let pa_start_bal = get_account_start_balance(bals, start_sec, end_sec);
                let pa_spent = ((pa_start_bal - bal_on_date).max(0.0) * 100.0).round() / 100.0;
                pot_window_spent += pa_spent;

                account_snapshots.push(AccountBalanceSnapshotDto {
                    account_name: acc_name,
                    account_type: acc_type,
                    balance_on_date: bal_on_date,
                    amount_spent: pa_spent,
                });
            }

            pots_in_period.push(HistoricPotSnapshotDto {
                plan_summary,
                created_at_human_readable,
                is_first_pot: is_first,
                is_last_pot: is_last,
                is_intermediary_pot: is_intermediary,
                is_archived_in_period,
                archived_date_human_readable,
                pot_spent: ((pot_window_spent * 100.0).round()) / 100.0,
                account_snapshots,
            });
        }

        let has_multiple_pots = pots_in_period.len() > 1;

        cycles.push(HistoricCycleDto {
            cycle_start_date: curr_cycle_start,
            cycle_end_date: curr_cycle_end,
            cycle_date_range,
            is_current_cycle: is_current,
            days_elapsed,
            days_in_cycle,
            total_spent: cycle_spent,
            monthly_income,
            pot_allowance,
            pot_spent,
            starting_total_balance,
            ending_total_balance,
            actual_daily_spend,
            is_over_pot_allowance: is_over,
            absolute_pot_overspend,
            budget_utilization_percent: utilization,
            pot_account_ids,
            account_breakdowns,
            pots_in_period,
            has_multiple_pots,
        });

        let prev_day = curr_cycle_start.pred_opt().unwrap_or(curr_cycle_start);
        curr_cycle_start = find_last_payday_date(prev_day, nominal_payday);
    }

    Ok(cycles)
}

pub async fn get_historic_spent_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
) -> Result<Html<String>, (StatusCode, String)> {
    if !crate::management::is_authenticated(&headers) {
        return Ok(Html(format!(
            "<script>window.location.href = '/login?return_to=/historicSpent';</script>"
        )));
    }

    let raw_uid = crate::pot::get_user_id_from_headers_or_default(
        &headers,
        query.get("userId").or_else(|| query.get("user_id")).map(|s| s.as_str()),
    );
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;

    let cycles = calculate_historic_cycles(&state.pool, &user_id, 12)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let has_history = !cycles.is_empty();
    let completed_cycles: Vec<&HistoricCycleDto> = cycles.iter().filter(|c| !c.is_current_cycle).collect();
    let avg_spend = if !completed_cycles.is_empty() {
        let sum: f32 = completed_cycles.iter().map(|c| c.pot_spent).sum();
        ((sum / completed_cycles.len() as f32) * 100.0).round() / 100.0
    } else {
        cycles.first().map(|c| c.pot_spent).unwrap_or(0.0)
    };

    let highest = completed_cycles.iter().max_by(|a, b| a.pot_spent.partial_cmp(&b.pot_spent).unwrap());
    let lowest = completed_cycles.iter().min_by(|a, b| a.pot_spent.partial_cmp(&b.pot_spent).unwrap());

    let tmpl = HistoricSpentTemplate {
        has_history,
        average_monthly_spend: avg_spend,
        highest_spent: highest.map(|h| h.pot_spent),
        highest_spent_range: highest.map(|h| h.cycle_date_range.clone()),
        lowest_spent: lowest.map(|l| l.pot_spent),
        lowest_spent_range: lowest.map(|l| l.cycle_date_range.clone()),
        cycles,
    };

    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_calculate_account_spent_deltas() {
        let balances = vec![
            (1000, 200.0),
            (2000, 180.0), // -20 spent
            (3000, 250.0), // +70 deposit (ignored)
            (4000, 220.0), // -30 spent
            (5000, 220.0), // no change
            (6000, 210.0), // -10 spent
        ];
        let spent = calculate_account_spent(&balances, 1500, 5500);
        assert_eq!(spent, 50.0);
    }

    #[test]
    fn test_calculate_account_net_spent() {
        let balances = vec![
            (1000, 500.0),
            (2000, 450.0), // -50
            (3000, 400.0), // -50
        ];
        let net = calculate_account_net_spent(&balances, 1000, 3000, 500.0, 400.0);
        assert_eq!(net, 100.0);
    }

    #[test]
    fn test_get_account_start_and_end_balance() {
        let balances = vec![
            (1000, 150.0),
            (2000, 200.0),
            (3000, 180.0),
        ];
        let start = get_account_start_balance(&balances, 1000, 3000);
        assert_eq!(start, 150.0);

        let end = get_account_end_balance(&balances, 1000, 3000, false, 3000);
        assert_eq!(end, 180.0);
    }

    #[test]
    fn test_payday_working_day_shift() {
        let actual = get_actual_payday_date(31, 2026, 10);
        assert_eq!(actual, NaiveDate::from_ymd_opt(2026, 10, 30).unwrap());

        let actual_nov = get_actual_payday_date(31, 2026, 11);
        assert_eq!(actual_nov, NaiveDate::from_ymd_opt(2026, 11, 30).unwrap());
    }

    #[test]
    fn test_find_last_and_next_payday() {
        let as_of = NaiveDate::from_ymd_opt(2026, 11, 8).unwrap();
        let last = find_last_payday_date(as_of, 31);
        let next = find_next_payday_date(as_of, 31);
        assert_eq!(last, NaiveDate::from_ymd_opt(2026, 10, 30).unwrap());
        assert_eq!(next, NaiveDate::from_ymd_opt(2026, 11, 30).unwrap());
    }
}
