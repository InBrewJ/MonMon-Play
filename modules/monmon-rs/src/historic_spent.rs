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
use crate::money::Pence;
use crate::AppState;

#[allow(dead_code)]
pub fn is_weekend(d: NaiveDate) -> bool {
    crate::anchoring::is_weekend(d)
}

#[allow(dead_code)]
pub fn get_last_working_day_on_or_before(d: NaiveDate) -> NaiveDate {
    crate::anchoring::get_last_working_day_on_or_before(d)
}

#[allow(dead_code)]
pub fn get_actual_payday_date(nominal_payday: i32, year: i32, month: u32) -> NaiveDate {
    crate::anchoring::get_actual_payday_date(nominal_payday, year, month)
}

pub fn find_last_payday_date(as_of: NaiveDate, nominal_payday: i32) -> NaiveDate {
    crate::anchoring::find_last_payday_date(as_of, nominal_payday)
}

pub fn find_next_payday_date(as_of: NaiveDate, nominal_payday: i32) -> NaiveDate {
    crate::anchoring::find_next_payday_date(as_of, nominal_payday)
}

/// Computes start balance of an account for a given cycle window matching Java HistoricSpentCalculator
pub fn get_account_start_balance(balances: &[(i64, f32)], cycle_start_sec: i64, cycle_end_sec: i64) -> f32 {
    get_account_start_balance_with_inflow(balances, cycle_start_sec, cycle_end_sec, 0.0)
}

pub fn get_account_start_balance_with_inflow(
    balances: &[(i64, f32)],
    cycle_start_sec: i64,
    cycle_end_sec: i64,
    payday_inflow: f32,
) -> f32 {
    if balances.is_empty() {
        return if payday_inflow > 0.0 { ((payday_inflow * 100.0).round()) / 100.0 } else { 0.0 };
    }
    if balances[0].0 > cycle_end_sec {
        return 0.0;
    }

    // 1. If this account receives payday salary inflow:
    if payday_inflow > 0.0 {
        // Check if an explicit balance was recorded on or shortly after payday (within 24 hours after cycle_start_sec)
        for &(t, v) in balances {
            if t >= cycle_start_sec && t <= cycle_start_sec + 86400 {
                return ((v * 100.0).round()) / 100.0;
            }
        }
        // If no explicit balance on payday, latest balance recorded before cycle_start_sec + payday_inflow
        let mut latest_before = None;
        for &(t, v) in balances {
            if t < cycle_start_sec {
                latest_before = Some(v);
            } else {
                break;
            }
        }
        if let Some(lb) = latest_before {
            return (((lb + payday_inflow) * 100.0).round()) / 100.0;
        }
        return ((payday_inflow * 100.0).round()) / 100.0;
    }

    // 2. For non-salary accounts (payday_inflow == 0.0):
    // The opening balance at cycle start is the latest balance recorded BEFORE cycle_start_sec.
    // Any balance recorded on or after cycle_start_sec is an update within the cycle (e.g. transfer in).
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

    // 3. Fallback: If no balance exists before cycle start (e.g. newly created account),
    // pick earliest balance recorded on or after cycle_start_sec
    for &(t, v) in balances {
        if t >= cycle_start_sec && t < cycle_end_sec {
            return ((v * 100.0).round()) / 100.0;
        }
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

    // First, find latest balance before cycle_start_sec
    let mut latest_before_idx = None;
    for (i, &(t, _)) in balances.iter().enumerate() {
        if t < cycle_start_sec {
            latest_before_idx = Some(i);
        } else {
            break;
        }
    }

    let mut start_idx = None;
    if let Some(lb_idx) = latest_before_idx {
        let lb_val = ((balances[lb_idx].1 * 100.0).round()) / 100.0;
        if (lb_val - start_bal).abs() < 0.01 {
            start_idx = Some(lb_idx);
        }
    }

    if start_idx.is_none() {
        let mut best_dist = i64::MAX;
        for (i, &(t, _)) in balances.iter().enumerate() {
            let dist = (t - cycle_start_sec).abs();
            if dist <= 86400 && dist < best_dist {
                best_dist = dist;
                start_idx = Some(i);
            }
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
        start_idx = latest_before_idx;
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

    // If start_bal was adjusted with payday inflow (i.e. start_bal differs from start balance object),
    // reconcile the starting difference into total_net_spent
    if let Some(s_idx) = start_idx {
        let obj_val = ((balances[s_idx].1 * 100.0).round()) / 100.0;
        if obj_val != start_bal {
            let start_offset = start_bal - obj_val;
            total_net_spent += start_offset;
        }
    }

    ((total_net_spent * 100.0).round()) / 100.0
}

/// Computes total spent on an account between start_sec and end_sec based on negative balance deltas.
/// When `inclusive_end` is false, snapshots at exact timestamp `end_sec` are excluded so consecutive
/// pot windows `[start, boundary)` and `[boundary, next]` never double-count a boundary snapshot.
pub fn calculate_account_spent_window(
    balances: &[(i64, f32)], // (timestamp_sec, value) sorted ascending
    start_sec: i64,
    end_sec: i64,
    inclusive_end: bool,
) -> f32 {
    if balances.len() < 2 {
        return 0.0;
    }
    if inclusive_end {
        if end_sec < start_sec {
            return 0.0;
        }
    } else if end_sec <= start_sec {
        return 0.0;
    }

    let mut total_spent = 0.0f32;
    for i in 1..balances.len() {
        let (t_curr, val_curr) = balances[i];
        let in_window = if inclusive_end {
            t_curr >= start_sec && t_curr <= end_sec
        } else {
            t_curr >= start_sec && t_curr < end_sec
        };
        if in_window {
            let (_, val_prev) = balances[i - 1];
            let delta = val_curr - val_prev;
            if delta < 0.0 {
                total_spent += -delta;
            }
        }
    }
    ((total_spent * 100.0).round()) / 100.0
}

/// Computes total spent on an account between start_sec and end_sec based on negative balance deltas
pub fn calculate_account_spent(
    balances: &[(i64, f32)], // (timestamp_sec, value) sorted ascending
    start_sec: i64,
    end_sec: i64,
) -> f32 {
    calculate_account_spent_window(balances, start_sec, end_sec, true)
}

/// Formats signed Pence as GBP without awkward `£-12.34` ordering (`-£12.34` / `£12.34`)
pub fn format_signed_gbp(p: Pence) -> String {
    if p.is_negative() {
        format!("-£{}", p.abs())
    } else {
        format!("£{}", p)
    }
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
    pub start_balance_pence: Pence,
    pub end_balance_pence: Pence,
    pub amount_spent_pence: Pence,
    pub is_liability: bool,
    pub is_net_inflow: bool,
    pub abs_amount_spent_pence: Pence,
    pub is_pot_account: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountBalanceSnapshotDto {
    pub account_name: String,
    pub account_type: String,
    pub balance_on_date: f32,
    pub amount_spent: f32,
    pub balance_on_date_pence: Pence,
    pub amount_spent_pence: Pence,
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
    pub pot_spent_pence: Pence,
    pub account_snapshots: Vec<AccountBalanceSnapshotDto>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PotPaceMetrics {
    pub pot_daily_pace: Pence,
    pub pot_max_per_day: Pence,
    pub is_pot_overspent_daily: bool,
    pub absolute_pot_daily_overspend: Pence,
    pub is_over_pot_allowance: bool,
    pub absolute_pot_overspend: Pence,
    pub budget_utilization_percent: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoricPotsSummaryDto {
    pub avg_pot_spend: Pence,
    pub avg_daily_pace: Pence,
    pub cycles_within_max: usize,
    pub total_completed_cycles: usize,
    pub within_max_percent: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoricAllAccountsSummaryDto {
    pub avg_net_spend: Pence,
    pub avg_is_net_inflow: bool,
    pub avg_abs_net_change: Pence,
    pub highest_spent: Option<Pence>,
    pub highest_spent_range: Option<String>,
    pub lowest_spent: Option<Pence>,
    pub lowest_is_net_inflow: bool,
    pub lowest_abs_net_change: Option<Pence>,
    pub lowest_spent_range: Option<String>,
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
    pub monthly_income_pence: Pence,
    pub pot_allowance: f32,
    pub pot_allowance_pence: Pence,
    pub pot_spent: f32,
    pub pot_spent_pence: Pence,
    pub pot_daily_pace: Pence,
    pub pot_max_per_day: Pence,
    pub is_pot_overspent_daily: bool,
    pub absolute_pot_daily_overspend: Pence,
    pub starting_total_balance: f32,
    pub starting_total_balance_pence: Pence,
    pub ending_total_balance: f32,
    pub ending_total_balance_pence: Pence,
    pub all_accounts_net_spent: Pence,
    pub all_accounts_is_net_inflow: bool,
    pub all_accounts_abs_net_change: Pence,
    pub actual_daily_spend: f32,
    pub is_over_pot_allowance: bool,
    pub absolute_pot_overspend: f32,
    pub absolute_pot_overspend_pence: Pence,
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
    pub pots_summary: HistoricPotsSummaryDto,
    pub all_accounts_summary: HistoricAllAccountsSummaryDto,
    pub average_monthly_spend: f32,
    pub highest_spent: Option<f32>,
    pub highest_spent_range: Option<String>,
    pub lowest_spent: Option<f32>,
    pub lowest_spent_range: Option<String>,
    pub cycles: Vec<HistoricCycleDto>,
}

/// Computes signed start balance, signed end balance, signed net spent (`start - end`),
/// and whether the account is treated as a liability (`LOAN`).
///
/// - `LOAN` accounts store positive amount owed in MonMon, so their balances are negated
///   to treat them as liabilities: paying £200 from Debit to a Loan nets to £0.00.
/// - Payday-morning guard: on the current cycle, if `payday_inflow > 0.0` and no balance
///   snapshot exists at or after `start_sec` yet, mirror the synthetic payday inflow into
///   `end_bal` so payday morning shows £0.00 spent until the bank syncs.
pub fn compute_account_cycle_net(
    acc_type: &str,
    balances: &[(i64, f32)],
    start_sec: i64,
    end_sec: i64,
    is_current: bool,
    effective_end_sec: i64,
    payday_inflow: f32,
) -> (Pence, Pence, Pence, bool) {
    let is_liability = acc_type.eq_ignore_ascii_case("LOAN");
    let raw_start = get_account_start_balance_with_inflow(balances, start_sec, end_sec, payday_inflow);
    let has_snapshot_in_cycle = balances.iter().any(|&(t, _)| t >= start_sec && t <= effective_end_sec);
    let raw_end = if is_current && payday_inflow > 0.0 && !has_snapshot_in_cycle {
        raw_start
    } else {
        get_account_end_balance(balances, start_sec, end_sec, is_current, effective_end_sec)
    };

    let signed_start = if is_liability {
        -Pence::from_pounds_f32(raw_start)
    } else {
        Pence::from_pounds_f32(raw_start)
    };
    let signed_end = if is_liability {
        -Pence::from_pounds_f32(raw_end)
    } else {
        Pence::from_pounds_f32(raw_end)
    };
    let net_spent = signed_start - signed_end;
    (signed_start, signed_end, net_spent, is_liability)
}

/// Computes per-pot window snapshots and total cycle monthly-pot outflow spend using SPOG's
/// negative-delta outflow helper (`calculate_account_spent_window`).
pub fn compute_cycle_pot_snapshots(
    intervals: &[ActivePotInterval],
    balance_map: &HashMap<i64, Vec<(i64, f32)>>,
    account_names_map: &HashMap<i64, (String, String)>,
    debit_account_ids: &[i64],
    start_sec: i64,
    end_sec: i64,
    effective_end_sec: i64,
    is_current: bool,
) -> (Pence, Vec<HistoricPotSnapshotDto>) {
    if intervals.is_empty() {
        if is_current {
            return (Pence::ZERO, Vec::new());
        }
        let mut fallback_spent = Pence::ZERO;
        for &acc_id in debit_account_ids {
            if let Some(bals) = balance_map.get(&acc_id) {
                let s = calculate_account_spent_window(bals, start_sec, effective_end_sec, true);
                fallback_spent += Pence::from_pounds_f32(s);
            }
        }
        return (fallback_spent, Vec::new());
    }

    let num_display = intervals.len();
    let mut pots_in_period = Vec::with_capacity(num_display);
    let mut total_pot_spent = Pence::ZERO;

    for (idx, interval) in intervals.iter().enumerate() {
        let p = &interval.pot;
        let is_first = idx == 0;
        let is_last = idx + 1 == num_display;
        let is_intermediary = !is_first && !is_last;
        let is_archived_in_period = interval.end <= end_sec;

        let archived_date_human_readable = if is_archived_in_period {
            chrono::DateTime::from_timestamp(interval.end, 0)
                .map(|dt| dt.format("%d %b %Y").to_string())
                .unwrap_or_else(|| "Replaced".to_string())
        } else if p.archived {
            p.last_updated
                .and_then(|ts| normalize_to_epoch_sec(Some(ts)))
                .and_then(|s| chrono::DateTime::from_timestamp(s, 0))
                .map(|dt| dt.format("%d %b %Y").to_string())
                .unwrap_or_else(|| "Archived".to_string())
        } else {
            "Active".to_string()
        };

        let created_at_human_readable = normalize_to_epoch_sec(p.created_at)
            .and_then(|s| chrono::DateTime::from_timestamp(s, 0))
            .map(|dt| dt.format("%d %b %Y").to_string())
            .unwrap_or_else(|| "—".to_string());

        let mut plan_summary = if p.name.trim().is_empty() {
            "Monthly Pot".to_string()
        } else {
            p.name.clone()
        };
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

        let win_start = if is_first {
            start_sec
        } else {
            interval.start.max(start_sec)
        };
        let (win_end, inclusive_end) = if !is_last {
            // Bridge any archive->create gap to the incoming pot's start so no drops are lost
            (intervals[idx + 1].start.min(effective_end_sec), false)
        } else if interval.end < effective_end_sec {
            // Unreplaced archived pot stops at its archive timestamp
            (interval.end, true)
        } else {
            (effective_end_sec, true)
        };

        let p_accounts: &[i64] = if !p.accounts.is_empty() {
            &p.accounts
        } else if is_current {
            &[]
        } else {
            debit_account_ids
        };

        let mut account_snapshots = Vec::new();
        let mut pot_window_spent = Pence::ZERO;

        for &pa_id in p_accounts {
            let empty_vec = Vec::new();
            let bals = balance_map.get(&pa_id).unwrap_or(&empty_vec);
            let (acc_name, acc_type) = account_names_map
                .get(&pa_id)
                .cloned()
                .unwrap_or_else(|| ("Account".to_string(), "DEBIT".to_string()));

            let pa_spent_f32 = calculate_account_spent_window(bals, win_start, win_end, inclusive_end);
            let pa_spent_pence = Pence::from_pounds_f32(pa_spent_f32);
            let bal_on_date_f32 = get_account_end_balance(
                bals,
                start_sec,
                win_end,
                is_current && win_end == effective_end_sec,
                win_end,
            );
            let bal_on_date_pence = Pence::from_pounds_f32(bal_on_date_f32);
            pot_window_spent += pa_spent_pence;

            account_snapshots.push(AccountBalanceSnapshotDto {
                account_name: acc_name,
                account_type: acc_type,
                balance_on_date: bal_on_date_f32,
                amount_spent: pa_spent_f32,
                balance_on_date_pence: bal_on_date_pence,
                amount_spent_pence: pa_spent_pence,
            });
        }

        total_pot_spent += pot_window_spent;

        pots_in_period.push(HistoricPotSnapshotDto {
            plan_summary,
            created_at_human_readable,
            is_first_pot: is_first,
            is_last_pot: is_last,
            is_intermediary_pot: is_intermediary,
            is_archived_in_period,
            archived_date_human_readable,
            pot_spent: pot_window_spent.to_pounds_f32(),
            pot_spent_pence: pot_window_spent,
            account_snapshots,
        });
    }

    // In the current cycle, if the latest pot is archived (no active monthly pot), SPOG shows £0.00
    if is_current && intervals.last().map(|i| i.pot.archived).unwrap_or(false) {
        total_pot_spent = Pence::ZERO;
    }

    (total_pot_spent, pots_in_period)
}

/// Computes SPOG-consistent daily spending pace, daily overspend vs MAX/day,
/// total over/under allowance, and progress bar utilization.
pub fn compute_pot_pace_metrics(
    pot_spent: Pence,
    pot_allowance: Pence,
    days_elapsed: i32,
    days_in_cycle: i32,
    max_per_day_override: Option<Pence>,
) -> PotPaceMetrics {
    let pot_daily_pace = pot_spent.checked_div_days(days_elapsed.max(1) as i64);
    let pot_max_per_day = max_per_day_override
        .unwrap_or_else(|| pot_allowance.checked_div_days(days_in_cycle.max(1) as i64));
    let (_daily_diff, is_pot_overspent_daily, absolute_pot_daily_overspend) =
        crate::spog::compute_daily_overspend(pot_daily_pace, pot_max_per_day);

    let total_diff = pot_spent - pot_allowance;
    let is_over_pot_allowance = total_diff.is_positive();
    let absolute_pot_overspend = total_diff.abs();

    let budget_utilization_percent = if pot_allowance.is_positive() {
        (((pot_spent.as_pence() as f64 / pot_allowance.as_pence() as f64) * 100.0)
            .clamp(0.0, 100.0)) as f32
    } else if pot_spent.is_positive() {
        100.0
    } else {
        0.0
    };

    PotPaceMetrics {
        pot_daily_pace,
        pot_max_per_day,
        is_pot_overspent_daily,
        absolute_pot_daily_overspend,
        is_over_pot_allowance,
        absolute_pot_overspend,
        budget_utilization_percent,
    }
}

/// Computes independent At-a-Glance KPI strips for Monthly Pots and All Accounts.
/// Uses completed cycles when present, falling back to all available cycles (e.g. current cycle only).
pub fn compute_historic_summaries(
    cycles: &[HistoricCycleDto],
) -> (HistoricPotsSummaryDto, HistoricAllAccountsSummaryDto) {
    let completed: Vec<&HistoricCycleDto> = cycles.iter().filter(|c| !c.is_current_cycle).collect();
    let sample: Vec<&HistoricCycleDto> = if !completed.is_empty() {
        completed
    } else {
        cycles.iter().collect()
    };

    let n = sample.len();
    let pots_summary = if n == 0 {
        HistoricPotsSummaryDto {
            avg_pot_spend: Pence::ZERO,
            avg_daily_pace: Pence::ZERO,
            cycles_within_max: 0,
            total_completed_cycles: 0,
            within_max_percent: 0,
        }
    } else {
        let sum_pot: Pence = sample.iter().map(|c| c.pot_spent_pence).sum();
        let sum_pace: Pence = sample.iter().map(|c| c.pot_daily_pace).sum();
        let within_max = sample.iter().filter(|c| !c.is_pot_overspent_daily).count();
        let pct = ((within_max as f64 / n as f64) * 100.0).round() as i32;
        HistoricPotsSummaryDto {
            avg_pot_spend: sum_pot.checked_div_days(n as i64),
            avg_daily_pace: sum_pace.checked_div_days(n as i64),
            cycles_within_max: within_max,
            total_completed_cycles: n,
            within_max_percent: pct,
        }
    };

    let all_accounts_summary = if n == 0 {
        HistoricAllAccountsSummaryDto {
            avg_net_spend: Pence::ZERO,
            avg_is_net_inflow: false,
            avg_abs_net_change: Pence::ZERO,
            highest_spent: None,
            highest_spent_range: None,
            lowest_spent: None,
            lowest_is_net_inflow: false,
            lowest_abs_net_change: None,
            lowest_spent_range: None,
        }
    } else {
        let sum_all: Pence = sample.iter().map(|c| c.all_accounts_net_spent).sum();
        let avg_net = sum_all.checked_div_days(n as i64);
        let highest = sample.iter().max_by_key(|c| c.all_accounts_net_spent);
        let lowest = sample.iter().min_by_key(|c| c.all_accounts_net_spent);
        let lowest_val = lowest.map(|c| c.all_accounts_net_spent);
        HistoricAllAccountsSummaryDto {
            avg_net_spend: avg_net,
            avg_is_net_inflow: avg_net.is_negative(),
            avg_abs_net_change: avg_net.abs(),
            highest_spent: highest.map(|c| c.all_accounts_net_spent),
            highest_spent_range: highest.map(|c| c.cycle_date_range.clone()),
            lowest_spent: lowest_val,
            lowest_is_net_inflow: lowest_val.map(|v| v.is_negative()).unwrap_or(false),
            lowest_abs_net_change: lowest_val.map(|v| v.abs()),
            lowest_spent_range: lowest.map(|c| c.cycle_date_range.clone()),
        }
    };

    (pots_summary, all_accounts_summary)
}

#[derive(Debug, Clone)]
pub struct PotRecord {
    #[allow(dead_code)]
    pub id: i64,
    pub name: String,
    pub created_at: Option<i64>,
    pub last_updated: Option<i64>,
    pub archived: bool,
    pub food: Option<f32>,
    pub fuel: Option<f32>,
    pub transport: Option<f32>,
    pub fun: Option<f32>,
    pub accounts: Vec<i64>,
}

#[derive(Debug, Clone)]
pub struct IncomingRecord {
    pub id: i64,
    #[allow(dead_code)]
    pub name: String,
    pub net_value: f32,
    #[allow(dead_code)]
    pub gross_value: Option<f32>,
    pub incoming_month_day: i32,
    pub payday: bool,
    pub account_id: Option<i64>,
    pub created_at: Option<i64>,
    pub archived_at: Option<i64>,
    pub archived: bool,
    pub history_json: Option<String>,
}

impl IncomingRecord {
    pub fn get_effective_net_value_for_cycle(&self, cycle_start_sec: i64, _cycle_end_sec: i64) -> f32 {
        if let Some(ref json_str) = self.history_json {
            if let Ok(entries) = serde_json::from_str::<Vec<serde_json::Value>>(json_str) {
                for entry in entries {
                    if let Some(ts_val) = entry.get("timestamp").and_then(|v| v.as_i64()) {
                        let ts_sec = if ts_val > 100_000_000_000 { ts_val / 1000 } else { ts_val };
                        if ts_sec >= cycle_start_sec {
                            if let Some(nv) = entry.get("netValue").and_then(|v| v.as_f64()) {
                                return nv as f32;
                            }
                        }
                    }
                }
            }
        }
        self.net_value
    }

    pub fn get_effective_incoming_month_day(&self, cycle_start: NaiveDate) -> i32 {
        if let Some(ref json_str) = self.history_json {
            let cycle_start_sec = cycle_start
                .and_hms_opt(0, 0, 0)
                .unwrap()
                .and_local_timezone(chrono::Utc)
                .unwrap()
                .timestamp();
            if let Ok(entries) = serde_json::from_str::<Vec<serde_json::Value>>(json_str) {
                for entry in entries {
                    if let Some(ts_val) = entry.get("timestamp").and_then(|v| v.as_i64()) {
                        let ts_sec = if ts_val > 100_000_000_000 { ts_val / 1000 } else { ts_val };
                        if ts_sec >= cycle_start_sec {
                            if let Some(imd) = entry.get("incomingMonthDay").and_then(|v| v.as_i64()) {
                                return imd as i32;
                            }
                        }
                    }
                }
            }
        }
        self.incoming_month_day
    }
}

pub fn normalize_to_epoch_sec(ts: Option<i64>) -> Option<i64> {
    ts.map(|t| if t > 100_000_000_000 { t / 1000 } else { t })
}

pub fn get_effective_start_sec(inc: &IncomingRecord, all_incomings: &[IncomingRecord]) -> i64 {
    if let Some(created_sec) = normalize_to_epoch_sec(inc.created_at) {
        if created_sec > 0 {
            return created_sec;
        }
    }

    let mut preceding: Vec<&IncomingRecord> = all_incomings
        .iter()
        .filter(|other| other.id != inc.id && other.archived)
        .filter(|other| {
            if inc.payday || other.payday {
                return inc.payday && other.payday;
            }
            if let (Some(a1), Some(a2)) = (inc.account_id, other.account_id) {
                return a1 == a2;
            }
            true
        })
        .filter(|other| {
            if inc.archived {
                let inc_arch = normalize_to_epoch_sec(inc.archived_at);
                let other_arch = normalize_to_epoch_sec(other.archived_at);
                if let (Some(ia), Some(oa)) = (inc_arch, other_arch) {
                    if ia != oa {
                        return oa < ia;
                    }
                }
                return other.id < inc.id;
            }
            true
        })
        .collect();

    preceding.sort_by(|a, b| {
        let a_arch = normalize_to_epoch_sec(a.archived_at);
        let b_arch = normalize_to_epoch_sec(b.archived_at);
        if let (Some(aa), Some(ba)) = (a_arch, b_arch) {
            if aa != ba {
                return ba.cmp(&aa);
            }
        }
        b.id.cmp(&a.id)
    });

    if let Some(latest) = preceding.first() {
        if let Some(arch_sec) = normalize_to_epoch_sec(latest.archived_at) {
            if arch_sec > 0 {
                return arch_sec;
            }
        }
    }

    0
}

pub fn get_account_payday_inflow(
    acc_id: i64,
    cycle_incomings: &[&IncomingRecord],
    cycle_start: NaiveDate,
    cycle_start_sec: i64,
    cycle_end_sec: i64,
) -> f32 {
    let mut total = 0.0f32;
    for inc in cycle_incomings {
        if inc.account_id == Some(acc_id) {
            let is_payday_item = inc.payday || inc.get_effective_incoming_month_day(cycle_start) == cycle_start.day() as i32;
            if is_payday_item {
                let val = inc.get_effective_net_value_for_cycle(cycle_start_sec, cycle_end_sec);
                if val > 0.0 {
                    total += val;
                }
            }
        }
    }
    ((total * 100.0).round()) / 100.0
}

#[derive(Debug, Clone)]
pub struct ActivePotInterval {
    pub pot: PotRecord,
    pub start: i64,
    pub end: i64,
}

impl ActivePotInterval {
    pub fn is_active_in(&self, cycle_start_sec: i64, cycle_end_sec: i64) -> bool {
        let overlap = self.end.min(cycle_end_sec) - self.start.max(cycle_start_sec);
        overlap > 0
    }
}

pub fn compute_pot_intervals(monthly_pots: &[PotRecord]) -> Vec<ActivePotInterval> {
    if monthly_pots.is_empty() {
        return Vec::new();
    }
    let mut sorted = monthly_pots.to_vec();
    sorted.sort_by(|p1, p2| {
        let t1 = normalize_to_epoch_sec(p1.created_at)
            .or_else(|| normalize_to_epoch_sec(p1.last_updated))
            .unwrap_or(0);
        let t2 = normalize_to_epoch_sec(p2.created_at)
            .or_else(|| normalize_to_epoch_sec(p2.last_updated))
            .unwrap_or(0);
        if t1 != t2 {
            return t1.cmp(&t2);
        }
        p1.id.cmp(&p2.id)
    });

    let mut intervals: Vec<ActivePotInterval> = Vec::new();
    let n = sorted.len();
    for i in 0..n {
        let p = &sorted[i];
        let p_created = normalize_to_epoch_sec(p.created_at).unwrap_or(0);
        let p_updated = normalize_to_epoch_sec(p.last_updated).unwrap_or(0);
        let start = if p_created > 0 {
            p_created
        } else if p_updated > 0 {
            p_updated
        } else if i == 0 {
            i64::MIN
        } else {
            intervals[i - 1].start
        };

        let mut next_start = i64::MAX;
        if i + 1 < n {
            let next = &sorted[i + 1];
            let n_created = normalize_to_epoch_sec(next.created_at).unwrap_or(0);
            let n_updated = normalize_to_epoch_sec(next.last_updated).unwrap_or(0);
            if n_created > 0 {
                next_start = n_created;
            } else if n_updated > 0 {
                next_start = n_updated;
            }
        }

        let mut end = next_start;
        if p.archived && p_updated > 0 {
            end = end.min(p_updated);
        }

        intervals.push(ActivePotInterval {
            pot: p.clone(),
            start,
            end,
        });
    }
    intervals
}

pub fn find_active_pots_in_cycle(
    intervals: &[ActivePotInterval],
    cycle_start_sec: i64,
    cycle_end_sec: i64,
) -> Vec<ActivePotInterval> {
    intervals
        .iter()
        .filter(|int| int.is_active_in(cycle_start_sec, cycle_end_sec))
        .cloned()
        .collect()
}

pub fn find_active_pot_for_cycle(
    monthly_pots: &[PotRecord],
    cycle_start_sec: i64,
    cycle_end_sec: i64,
) -> Option<PotRecord> {
    if monthly_pots.is_empty() {
        return None;
    }
    let intervals = compute_pot_intervals(monthly_pots);
    let active = find_active_pots_in_cycle(&intervals, cycle_start_sec, cycle_end_sec);
    if let Some(last) = active.last() {
        return Some(last.pot.clone());
    }
    intervals
        .iter()
        .rev()
        .find(|int| int.start <= cycle_end_sec)
        .map(|int| int.pot.clone())
}

#[derive(Debug, Clone)]
pub struct OutgoingRecord {
    #[allow(dead_code)]
    pub id: i64,
    #[allow(dead_code)]
    pub name: String,
    pub cost: f32,
    #[allow(dead_code)]
    pub outgoing_day: i32,
    pub outgoing_month: Option<i32>,
    pub year_interval: Option<i32>,
    pub starting_year: Option<i32>,
    pub bill: bool,
    pub rent: bool,
    pub hidden_from_total: bool,
    pub paused: bool,
    pub archived: bool,
}

#[derive(Debug, Clone)]
pub struct PlanRecord {
    #[allow(dead_code)]
    pub id: i64,
    pub plan_type: String,
    pub split: f32,
}

pub fn calculate_monthly_allowance(
    incomings: &[&IncomingRecord],
    outgoings: &[OutgoingRecord],
    plans: &[PlanRecord],
    as_of: NaiveDate,
) -> f32 {
    use chrono::Datelike;
    let first_monthly_savings_goal = plans
        .iter()
        .find(|p| p.plan_type == "MONTHLY_SAVINGS_GOAL" || p.plan_type == "SAVINGS_GOAL");
    let first_rent_share = plans.iter().find(|p| p.plan_type == "RENT_SHARE");
    let first_bill_share = plans.iter().find(|p| p.plan_type == "BILL_SHARE");

    let mut adjusted_outgoings_total = 0.0f32;
    for o in outgoings {
        if o.archived || o.paused || o.hidden_from_total {
            continue;
        }
        let is_yearly = o.outgoing_month.map_or(false, |m| m > 0);
        if is_yearly {
            let target_month = o.outgoing_month.unwrap_or(1);
            if target_month != as_of.month() as i32 {
                continue;
            }
            let interval = o.year_interval.unwrap_or(1).max(1);
            if interval > 1 {
                let start = o.starting_year.unwrap_or(as_of.year());
                if as_of.year() < start || (as_of.year() - start) % interval != 0 {
                    continue;
                }
            }
        }
        let mut cost = o.cost;
        if let Some(bs) = first_bill_share {
            if o.bill {
                cost *= bs.split;
            }
        }
        if let Some(rs) = first_rent_share {
            if o.rent {
                cost *= rs.split;
            }
        }
        adjusted_outgoings_total += cost;
    }

    let incoming_total: f32 = incomings.iter().map(|i| i.net_value).sum();
    let outgoing_total = ((adjusted_outgoings_total * 100.0).round()) / 100.0;
    let mut surplus = ((incoming_total - outgoing_total) * 100.0).round() / 100.0;

    let savings_goal_percent = first_monthly_savings_goal.map(|p| p.split).unwrap_or(0.0);
    let savings_cost = savings_goal_percent * incoming_total;
    surplus -= savings_cost;

    ((surplus * 100.0).round()) / 100.0
}

pub async fn get_accounts_spent_sum_map(
    pool: &PgPool,
    user_id: &str,
    start_sec: i64,
    end_sec: i64,
) -> HashMap<i64, f32> {
    let rows = sqlx::query(
        r#"
        SELECT account_id, COALESCE(SUM(amount), 0.0)::real AS sum
        FROM bank_transaction
        WHERE (user_id = $1 OR userid = $1)
          AND account_id IS NOT NULL
          AND timestamp >= $2 AND timestamp <= $3
          AND amount > 0
          AND (category IS NULL OR lower(category) NOT IN ('transfers', 'savings', 'pots'))
          AND (description IS NULL OR (
              lower(description) NOT LIKE '%saving%'
              AND lower(description) NOT LIKE '%pot%'
              AND lower(description) NOT LIKE '%isa%'
              AND lower(description) NOT LIKE '%marcus%'
              AND lower(description) NOT LIKE '%chip%'
              AND lower(description) NOT LIKE '%vanguard%'
          ))
        GROUP BY account_id
        "#,
    )
    .bind(user_id)
    .bind(start_sec)
    .bind(end_sec)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let mut map = HashMap::new();
    for r in rows {
        if let Some(acc_id) = r.try_get::<Option<i64>, _>("account_id").ok().flatten() {
            let sum: f32 = r.try_get("sum").unwrap_or(0.0);
            map.insert(acc_id, sum);
        }
    }
    map
}

pub async fn calculate_historic_cycles(
    pool: &PgPool,
    user_id: &str,
    _num_cycles: usize,
) -> Result<Vec<HistoricCycleDto>, sqlx::Error> {
    let spog = crate::spog::calculate_spog(pool, user_id).await?;
    let nominal_payday = spog.next_pay_day.max(1);
    let today = Local::now().date_naive();

    let accounts_rows = sqlx::query(
        r#"
        SELECT id, name, type, COALESCE(ledger_mode, 'ABL') AS ledger_mode
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
    let mut debit_account_ids = Vec::new();
    let mut account_names_map: HashMap<i64, (String, String)> = HashMap::new();
    let mut account_ledger_modes: HashMap<i64, String> = HashMap::new();

    for r in accounts_rows {
        let acc_id: i64 = r.get("id");
        let name: String = r.get("name");
        let acc_type: String = r.get("type");
        let ledger_mode: String = r.get("ledger_mode");
        let t_upper = acc_type.to_uppercase();
        account_names_map.insert(acc_id, (name.clone(), acc_type.clone()));
        account_ledger_modes.insert(acc_id, ledger_mode.clone());
        if t_upper != "MORTGAGE" && t_upper != "PENSION" {
            ledger_account_ids.push(acc_id);
            ledger_accounts.push((acc_id, name, acc_type.clone(), ledger_mode));
            if t_upper == "DEBIT" || t_upper == "CURRENT" {
                debit_account_ids.push(acc_id);
            }
        }
    }

    // Load monthly pots for user
    let pot_rows = sqlx::query(
        r#"
        SELECT id, spending_plan_name, createdat, lastupdated, COALESCE(archived, false) AS archived,
               spending_plan_food, spending_plan_fuel, spending_plan_transport, spending_plan_fun
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

    let pot_ids: Vec<i64> = pot_rows.iter().filter_map(|r| r.try_get("id").ok()).collect();
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
        if let (Ok(pid), Ok(aid)) = (r.try_get::<i64, _>("pot_id"), r.try_get::<i64, _>("account_id")) {
            pot_accounts_map.entry(pid).or_default().push(aid);
        }
    }

    let mut pots: Vec<PotRecord> = Vec::new();
    for r in pot_rows {
        let pid: i64 = r.try_get("id").unwrap_or(0);
        let accounts = pot_accounts_map.get(&pid).cloned().unwrap_or_default();
        pots.push(PotRecord {
            id: pid,
            name: r.try_get::<Option<String>, _>("spending_plan_name").ok().flatten().unwrap_or_default(),
            created_at: r.try_get("createdat").ok(),
            last_updated: r.try_get("lastupdated").ok(),
            archived: r.try_get("archived").unwrap_or(false),
            food: r.try_get("spending_plan_food").ok(),
            fuel: r.try_get("spending_plan_fuel").ok(),
            transport: r.try_get("spending_plan_transport").ok(),
            fun: r.try_get("spending_plan_fun").ok(),
            accounts,
        });
    }

    // Load all incomings (including archived & history)
    let incoming_rows = sqlx::query(
        r#"
        SELECT id, name, COALESCE(netvalue, 0.0)::real AS netvalue,
               grossvalue::real AS grossvalue,
               COALESCE(incomingmonthday, 1) AS incomingmonthday,
               COALESCE(payday, false) AS payday,
               account_id,
               COALESCE(created_at, 0)::bigint AS created_at,
               archived_at::bigint AS archived_at,
               COALESCE(archived, false) AS archived,
               history_json
        FROM incoming
        WHERE (userid = $1 OR user_id = $1)
        ORDER BY id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let mut incomings: Vec<IncomingRecord> = Vec::new();
    for r in incoming_rows {
        incomings.push(IncomingRecord {
            id: r.get("id"),
            name: r.get("name"),
            net_value: r.get("netvalue"),
            gross_value: r.get("grossvalue"),
            incoming_month_day: r.get("incomingmonthday"),
            payday: r.get("payday"),
            account_id: r.get("account_id"),
            created_at: r.get("created_at"),
            archived_at: r.get("archived_at"),
            archived: r.get("archived"),
            history_json: r.get("history_json"),
        });
    }

    // Load all outgoings
    let outgoing_rows = sqlx::query(
        r#"
        SELECT id, name, COALESCE(cost, 0.0)::real AS cost,
               COALESCE(outgoingday, 1) AS outgoingday,
               outgoingmonth, yearinterval, startingyear,
               COALESCE(bill, false) AS bill,
               COALESCE(rent, false) AS rent,
               COALESCE(hiddenfromtotal, false) AS hiddenfromtotal,
               COALESCE(paused, false) AS paused,
               COALESCE(archived, false) AS archived
        FROM outgoing
        WHERE (userid = $1 OR user_id = $1)
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let mut outgoings: Vec<OutgoingRecord> = Vec::new();
    for r in outgoing_rows {
        outgoings.push(OutgoingRecord {
            id: r.get("id"),
            name: r.get("name"),
            cost: r.get("cost"),
            outgoing_day: r.get("outgoingday"),
            outgoing_month: r.get("outgoingmonth"),
            year_interval: r.get("yearinterval"),
            starting_year: r.get("startingyear"),
            bill: r.get("bill"),
            rent: r.get("rent"),
            hidden_from_total: r.get("hiddenfromtotal"),
            paused: r.get("paused"),
            archived: r.get("archived"),
        });
    }

    // Load plans
    let plan_rows = sqlx::query(
        r#"
        SELECT id, type, COALESCE(split, 0.0)::real AS split
        FROM plan
        WHERE (userid = $1 OR user_id = $1)
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let mut plans: Vec<PlanRecord> = Vec::new();
    for r in plan_rows {
        plans.push(PlanRecord {
            id: r.get("id"),
            plan_type: r.get("type"),
            split: r.get("split"),
        });
    }

    // Also load names/types and balances for any archived account referenced in historical pot_accounts
    let mut all_balance_account_ids = ledger_account_ids.clone();
    let mut missing_pot_account_ids: Vec<i64> = Vec::new();
    for acc_list in pot_accounts_map.values() {
        for &aid in acc_list {
            if !all_balance_account_ids.contains(&aid) {
                all_balance_account_ids.push(aid);
            }
            if !account_names_map.contains_key(&aid) && !missing_pot_account_ids.contains(&aid) {
                missing_pot_account_ids.push(aid);
            }
        }
    }
    if !missing_pot_account_ids.is_empty() {
        let archived_acc_rows = sqlx::query(
            r#"
            SELECT id, name, type
            FROM account
            WHERE id = ANY($1)
            "#,
        )
        .bind(&missing_pot_account_ids)
        .fetch_all(pool)
        .await
        .unwrap_or_default();
        for r in archived_acc_rows {
            let aid: i64 = r.get("id");
            let name: String = r.get("name");
            let acc_type: String = r.get("type");
            account_names_map.insert(aid, (name, acc_type));
        }
    }

    let balance_map = load_account_balances(pool, &all_balance_account_ids)
        .await
        .unwrap_or_default();

    // Determine earliest date across all account balances
    let mut min_balance_sec = i64::MAX;
    for bals in balance_map.values() {
        for &(t, _) in bals {
            if t > 0 && t < min_balance_sec {
                min_balance_sec = t;
            }
        }
    }

    let earliest_date = if min_balance_sec != i64::MAX {
        chrono::DateTime::from_timestamp(min_balance_sec, 0)
            .map(|dt| dt.date_naive())
            .unwrap_or(today - chrono::Months::new(6))
    } else {
        today - chrono::Months::new(6)
    };

    let min_allowed = today - chrono::Months::new(6);
    let earliest_date = if earliest_date > min_allowed { min_allowed } else { earliest_date };
    let max_allowed = today - chrono::Months::new(24);
    let earliest_date = if earliest_date < max_allowed { max_allowed } else { earliest_date };

    let pot_intervals = compute_pot_intervals(&pots);

    let mut cycles = Vec::new();
    let current_cycle_start = find_last_payday_date(today, nominal_payday);
    let mut curr_cycle_start = current_cycle_start;
    let mut cycle_count = 0;

    while !curr_cycle_start.lt(&earliest_date) && cycle_count < 36 {
        cycle_count += 1;
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
            ((today - curr_cycle_start).num_days() as i32).min(days_in_cycle).max(1)
        } else {
            days_in_cycle
        };

        // Determine active pot for this cycle (using last monthly pot in active interval if multiple)
        let active_intervals_in_cycle = find_active_pots_in_cycle(&pot_intervals, start_sec, end_sec);
        let active_pot = if !active_intervals_in_cycle.is_empty() {
            Some(active_intervals_in_cycle.last().unwrap().pot.clone())
        } else {
            find_active_pot_for_cycle(&pots, start_sec, end_sec)
        };

        // Resolve pot_account_ids for /spendingPace and Pot Account badge
        let pot_account_ids: Vec<i64> = if let Some(ref p) = active_pot {
            if !p.accounts.is_empty() {
                if is_current && p.archived {
                    Vec::new()
                } else {
                    p.accounts.clone()
                }
            } else if is_current {
                Vec::new()
            } else {
                debit_account_ids.clone()
            }
        } else if is_current {
            Vec::new()
        } else {
            debit_account_ids.clone()
        };

        // Filter incomings active during this cycle
        let cycle_incomings: Vec<&IncomingRecord> = if is_current {
            incomings.iter().filter(|i| !i.archived).collect()
        } else {
            incomings
                .iter()
                .filter(|i| {
                    let start_sec_eff = get_effective_start_sec(i, &incomings);
                    let pay_sec = if i.payday {
                        start_sec
                    } else {
                        let day = i.get_effective_incoming_month_day(curr_cycle_start);
                        let mut pay_date = curr_cycle_start.with_day(day as u32).unwrap_or(curr_cycle_start);
                        if pay_date < curr_cycle_start {
                            let next_m = curr_cycle_start + chrono::Duration::days(32);
                            pay_date = next_m.with_day(day.min(28) as u32).unwrap_or(curr_cycle_start);
                        }
                        pay_date
                            .and_hms_opt(0, 0, 0)
                            .unwrap()
                            .and_local_timezone(chrono::Utc)
                            .unwrap()
                            .timestamp()
                    };
                    if start_sec_eff > pay_sec + 86400 {
                        return false;
                    }
                    if i.archived {
                        let arch_sec = normalize_to_epoch_sec(i.archived_at);
                        match arch_sec {
                            Some(as_sec) => as_sec > pay_sec + 86400,
                            None => false,
                        }
                    } else {
                        true
                    }
                })
                .collect()
        };

        let mut monthly_income = 0.0f32;
        for inc in &cycle_incomings {
            let eff = inc.get_effective_net_value_for_cycle(start_sec, end_sec);
            if eff > 0.0 {
                monthly_income += eff;
            }
        }
        if monthly_income <= 0.0 && !incomings.is_empty() {
            for inc in incomings.iter().filter(|i| !i.archived) {
                let eff = inc.get_effective_net_value_for_cycle(start_sec, end_sec);
                if eff > 0.0 {
                    monthly_income += eff;
                }
            }
        }
        monthly_income = ((monthly_income * 100.0).round()) / 100.0;

        // All-Accounts wider picture: strictly post-payday Start -> pre-next-payday End
        // across all active accounts excluding MORTGAGE and PENSION (including LOAN as liability).
        let mut starting_total_balance_pence = Pence::ZERO;
        let mut ending_total_balance_pence = Pence::ZERO;
        let mut starting_pot_budget = 0.0f32;
        let mut account_breakdowns = Vec::new();

        for &(acc_id, ref acc_name, ref acc_type, ref _ledger_mode) in &ledger_accounts {
            let empty_vec = Vec::new();
            let bals = balance_map.get(&acc_id).unwrap_or(&empty_vec);
            if bals.is_empty() {
                continue;
            }
            if bals[0].0 > effective_end_sec {
                continue;
            }

            let acc_inflow = get_account_payday_inflow(acc_id, &cycle_incomings, curr_cycle_start, start_sec, end_sec);
            let (signed_start, signed_end, acc_net_spent, is_liability) = compute_account_cycle_net(
                acc_type,
                bals,
                start_sec,
                end_sec,
                is_current,
                effective_end_sec,
                acc_inflow,
            );

            let is_pot_account = pot_account_ids.contains(&acc_id);
            if is_pot_account {
                starting_pot_budget += signed_start.to_pounds_f32();
            }

            starting_total_balance_pence += signed_start;
            ending_total_balance_pence += signed_end;

            account_breakdowns.push(AccountSpentDto {
                account_name: acc_name.clone(),
                account_type: acc_type.clone(),
                start_balance: signed_start.to_pounds_f32(),
                end_balance: signed_end.to_pounds_f32(),
                amount_spent: acc_net_spent.to_pounds_f32(),
                start_balance_pence: signed_start,
                end_balance_pence: signed_end,
                amount_spent_pence: acc_net_spent,
                is_liability,
                is_net_inflow: acc_net_spent.is_negative(),
                abs_amount_spent_pence: acc_net_spent.abs(),
                is_pot_account,
            });
        }

        let all_accounts_net_spent = starting_total_balance_pence - ending_total_balance_pence;
        let all_accounts_is_net_inflow = all_accounts_net_spent.is_negative();
        let all_accounts_abs_net_change = all_accounts_net_spent.abs();
        let starting_total_balance = starting_total_balance_pence.to_pounds_f32();
        let ending_total_balance = ending_total_balance_pence.to_pounds_f32();
        let cycle_spent = all_accounts_net_spent.to_pounds_f32();
        starting_pot_budget = ((starting_pot_budget * 100.0).round()) / 100.0;

        // Monthly Pots (SPOG-parity outflow calculation across all active pot windows in cycle)
        let (pot_spent_pence, pots_in_period) = compute_cycle_pot_snapshots(
            &active_intervals_in_cycle,
            &balance_map,
            &account_names_map,
            &debit_account_ids,
            start_sec,
            end_sec,
            effective_end_sec,
            is_current,
        );
        let pot_spent = pot_spent_pence.to_pounds_f32();

        // Pot Allowance / Budget
        let allowance_incomings = if !cycle_incomings.is_empty() {
            cycle_incomings.clone()
        } else {
            incomings.iter().filter(|i| !i.archived).collect()
        };
        let cycle_allowance = calculate_monthly_allowance(&allowance_incomings, &outgoings, &plans, curr_cycle_start);

        let pot_allowance_pence = if is_current {
            spog.surplus
        } else if cycle_allowance > 0.0 {
            Pence::from_pounds_f32(cycle_allowance)
        } else if let Some(ref p) = active_pot {
            let plan_sum = p.food.unwrap_or(0.0) + p.fuel.unwrap_or(0.0) + p.transport.unwrap_or(0.0) + p.fun.unwrap_or(0.0);
            if plan_sum > 0.0 {
                Pence::from_pounds_f32(plan_sum)
            } else if starting_pot_budget > 0.0 {
                Pence::from_pounds_f32(starting_pot_budget)
            } else {
                Pence::from_pounds_f32(monthly_income)
            }
        } else if starting_pot_budget > 0.0 {
            Pence::from_pounds_f32(starting_pot_budget)
        } else {
            Pence::from_pounds_f32(monthly_income)
        };
        let pot_allowance = pot_allowance_pence.to_pounds_f32();
        let monthly_income_pence = Pence::from_pounds_f32(monthly_income);

        let pot_pace = compute_pot_pace_metrics(
            pot_spent_pence,
            pot_allowance_pence,
            days_elapsed,
            days_in_cycle,
            if is_current { Some(spog.max_per_day) } else { None },
        );

        let cycle_date_range = format!(
            "{} – {}",
            curr_cycle_start.format("%d %b %Y"),
            curr_cycle_end.format("%d %b %Y")
        );

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
            monthly_income_pence,
            pot_allowance,
            pot_allowance_pence,
            pot_spent,
            pot_spent_pence,
            pot_daily_pace: pot_pace.pot_daily_pace,
            pot_max_per_day: pot_pace.pot_max_per_day,
            is_pot_overspent_daily: pot_pace.is_pot_overspent_daily,
            absolute_pot_daily_overspend: pot_pace.absolute_pot_daily_overspend,
            starting_total_balance,
            starting_total_balance_pence,
            ending_total_balance,
            ending_total_balance_pence,
            all_accounts_net_spent,
            all_accounts_is_net_inflow,
            all_accounts_abs_net_change,
            actual_daily_spend: pot_pace.pot_daily_pace.to_pounds_f32(),
            is_over_pot_allowance: pot_pace.is_over_pot_allowance,
            absolute_pot_overspend: pot_pace.absolute_pot_overspend.to_pounds_f32(),
            absolute_pot_overspend_pence: pot_pace.absolute_pot_overspend,
            budget_utilization_percent: pot_pace.budget_utilization_percent,
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
    let (pots_summary, all_accounts_summary) = compute_historic_summaries(&cycles);

    let tmpl = HistoricSpentTemplate {
        has_history,
        average_monthly_spend: all_accounts_summary.avg_net_spend.to_pounds_f32(),
        highest_spent: all_accounts_summary.highest_spent.map(|v| v.to_pounds_f32()),
        highest_spent_range: all_accounts_summary.highest_spent_range.clone(),
        lowest_spent: all_accounts_summary.lowest_spent.map(|v| v.to_pounds_f32()),
        lowest_spent_range: all_accounts_summary.lowest_spent_range.clone(),
        pots_summary,
        all_accounts_summary,
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
    fn test_start_balance_reconciles_payday_inflow_when_no_explicit_snapshot() {
        let balances = vec![
            (1000, 500.0), // Recorded before cycle start
        ];
        // Cycle starts at 2000, payday inflow is 1500.0
        let start = get_account_start_balance_with_inflow(&balances, 2000, 4000, 1500.0);
        assert_eq!(start, 2000.0); // 500 + 1500

        // In calculate_account_net_spent, reconciliation offset applies
        let balances2 = vec![
            (1000, 500.0),
            (2500, 1800.0),
        ];
        // 2000 start bal (with inflow), 1800 end bal -> 200 net spent
        let net = calculate_account_net_spent(&balances2, 2000, 4000, 2000.0, 1800.0);
        assert_eq!(net, 200.0);
    }

    #[test]
    fn test_salary_timeline_axiom_honors_historic_edit_and_preceding_job_transition() {
        let june2025_sec = NaiveDate::from_ymd_opt(2025, 6, 1)
            .unwrap()
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_local_timezone(chrono::Utc)
            .unwrap()
            .timestamp();

        let old_job = IncomingRecord {
            id: 1,
            name: "Old Job Salary".to_string(),
            net_value: 4168.00,
            gross_value: Some(4168.00),
            incoming_month_day: 30,
            payday: true,
            account_id: Some(1),
            created_at: None,
            archived_at: Some(june2025_sec),
            archived: true,
            history_json: None,
        };

        let oct29_26_sec = NaiveDate::from_ymd_opt(2026, 10, 29)
            .unwrap()
            .and_hms_opt(12, 0, 0)
            .unwrap()
            .and_local_timezone(chrono::Utc)
            .unwrap()
            .timestamp();

        let occasio_insights = IncomingRecord {
            id: 2,
            name: "Occasio Insights".to_string(),
            net_value: 2642.83,
            gross_value: Some(2642.83),
            incoming_month_day: 30,
            payday: true,
            account_id: Some(1),
            created_at: Some(june2025_sec),
            archived_at: None,
            archived: false,
            history_json: Some(format!(
                "[{{\"timestamp\":{},\"netValue\":3111.96,\"grossValue\":3111.96,\"name\":\"Occasio Insights\",\"incomingMonthDay\":30,\"payDay\":true}}]",
                oct29_26_sec
            )),
        };

        let all_incomings = vec![occasio_insights.clone(), old_job.clone()];

        // 1. Current cycle in October 2026 (30 Oct 2026 - 30 Nov 2026): £2,642.83
        let oct30_26_sec = NaiveDate::from_ymd_opt(2026, 10, 30).unwrap().and_hms_opt(0, 0, 0).unwrap().and_local_timezone(chrono::Utc).unwrap().timestamp();
        let eff_oct = occasio_insights.get_effective_net_value_for_cycle(oct30_26_sec, oct30_26_sec + 86400 * 30);
        assert_eq!(eff_oct, 2642.83);

        // 2. September 2026 cycle (30 Sep 2026 - 30 Oct 2026): £3,111.96 (from historyJson snapshot)
        let sep30_26_sec = NaiveDate::from_ymd_opt(2026, 9, 30).unwrap().and_hms_opt(0, 0, 0).unwrap().and_local_timezone(chrono::Utc).unwrap().timestamp();
        let eff_sep = occasio_insights.get_effective_net_value_for_cycle(sep30_26_sec, oct30_26_sec);
        assert_eq!(eff_sep, 3111.96);

        // 3. Before June 2025 (e.g. May 30 2025): oldJob was active, occasioInsights was not yet active
        let may30_25_sec = NaiveDate::from_ymd_opt(2025, 5, 30).unwrap().and_hms_opt(0, 0, 0).unwrap().and_local_timezone(chrono::Utc).unwrap().timestamp();
        let start_occasio = get_effective_start_sec(&occasio_insights, &all_incomings);
        assert_eq!(start_occasio, june2025_sec);
        assert!(start_occasio > may30_25_sec + 86400); // occasio not active in May 2025

        let eff_may = old_job.get_effective_net_value_for_cycle(may30_25_sec, may30_25_sec + 86400 * 30);
        assert_eq!(eff_may, 4168.00);
    }

    #[test]
    fn test_pot_intervals_match_java_replacement_chronology() {
        let sep1_sec = 1000000;
        let oct1_sec = 2000000;

        let old_pot = PotRecord {
            id: 1,
            name: "Old Pot".to_string(),
            created_at: Some(sep1_sec),
            last_updated: Some(oct1_sec),
            archived: true,
            food: Some(200.0),
            fuel: None,
            transport: None,
            fun: None,
            accounts: vec![1],
        };

        let new_pot = PotRecord {
            id: 2,
            name: "New Pot".to_string(),
            created_at: Some(oct1_sec),
            last_updated: None,
            archived: false,
            food: Some(300.0),
            fuel: None,
            transport: None,
            fun: None,
            accounts: vec![1, 2],
        };

        let intervals = compute_pot_intervals(&[old_pot, new_pot]);
        assert_eq!(intervals.len(), 2);
        assert_eq!(intervals[0].start, sep1_sec);
        assert_eq!(intervals[0].end, oct1_sec); // ended when new pot started
        assert_eq!(intervals[1].start, oct1_sec);
        assert_eq!(intervals[1].end, i64::MAX);

        // Check active in September cycle: only old pot is active
        let sep_active = find_active_pots_in_cycle(&intervals, sep1_sec, oct1_sec);
        assert_eq!(sep_active.len(), 1);
        assert_eq!(sep_active[0].pot.id, 1);

        // Check active in October cycle: new pot is active
        let oct_active = find_active_pots_in_cycle(&intervals, oct1_sec, oct1_sec + 86400 * 31);
        assert_eq!(oct_active.len(), 1);
        assert_eq!(oct_active[0].pot.id, 2);
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

    #[test]
    fn test_payday_transfer_between_accounts_is_neutral() {
        let oct30_midnight = NaiveDate::from_ymd_opt(2026, 10, 30)
            .unwrap()
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_local_timezone(chrono::Utc)
            .unwrap()
            .timestamp();
        let oct30_end = oct30_midnight + 86400 * 31;

        // t0: before payday, somewhere else has balance 500
        let t0 = oct30_midnight - 3600 * 12;
        // t1: payday morning, salary account has 2000
        let t1 = oct30_midnight + 3600 * 9;
        // t3: payday afternoon, salary account transferred 500 out, so now 1500
        let t3 = oct30_midnight + 3600 * 14;
        // t4: payday evening, somewhere else received 500, now 1000
        let t4 = oct30_midnight + 3600 * 16;

        let salary_bals = vec![(t1, 2000.0), (t3, 1500.0)];
        let savings_bals = vec![(t0, 500.0), (t4, 1000.0)];

        // Salary account: payday_inflow = 2000.0
        let start_salary = get_account_start_balance_with_inflow(&salary_bals, oct30_midnight, oct30_end, 2000.0);
        let end_salary = get_account_end_balance(&salary_bals, oct30_midnight, oct30_end, true, t4 + 3600);
        let salary_spent = calculate_account_net_spent(&salary_bals, oct30_midnight, t4 + 3600, start_salary, end_salary);

        assert_eq!(start_salary, 2000.0);
        assert_eq!(end_salary, 1500.0);
        assert_eq!(salary_spent, 500.0); // Dropped by 500

        // Savings account: payday_inflow = 0.0 (non-salary account)
        let start_savings = get_account_start_balance_with_inflow(&savings_bals, oct30_midnight, oct30_end, 0.0);
        let end_savings = get_account_end_balance(&savings_bals, oct30_midnight, oct30_end, true, t4 + 3600);
        let savings_spent = calculate_account_net_spent(&savings_bals, oct30_midnight, t4 + 3600, start_savings, end_savings);

        assert_eq!(start_savings, 500.0); // Kept 500 from t0 before payday
        assert_eq!(end_savings, 1000.0);
        assert_eq!(savings_spent, -500.0); // Gained 500 (negative spend)

        // Net effect across both accounts: 500 + (-500) = 0 (Transfer neutral!)
        assert_eq!(salary_spent + savings_spent, 0.0);
    }

    #[test]
    fn test_calculate_account_spent_window_half_open_prevents_boundary_double_count() {
        let balances = vec![
            (100, 500.0),
            (200, 450.0), // -50 at exact boundary 200
            (300, 400.0), // -50 at 300
        ];
        let w1 = calculate_account_spent_window(&balances, 100, 200, false);
        let w2 = calculate_account_spent_window(&balances, 200, 300, true);
        assert_eq!(w1, 0.0);
        assert_eq!(w2, 100.0);
        assert_eq!(w1 + w2, calculate_account_spent(&balances, 100, 300));
    }

    #[test]
    fn test_multi_pot_window_sum_matches_spog_across_boundary_and_gap() {
        let start_sec = 1_000_000;
        let t_arch = 1_500_000;
        let t_gap_drop = 1_600_000;
        let t_create = 1_700_000;
        let end_sec = 2_000_000;

        let pot1 = PotRecord {
            id: 1,
            name: "Pot 1".to_string(),
            created_at: Some(start_sec - 10_000),
            last_updated: Some(t_arch),
            archived: true,
            food: None,
            fuel: None,
            transport: None,
            fun: None,
            accounts: vec![10],
        };
        let pot2 = PotRecord {
            id: 2,
            name: "Pot 2".to_string(),
            created_at: Some(t_create),
            last_updated: None,
            archived: false,
            food: None,
            fuel: None,
            transport: None,
            fun: None,
            accounts: vec![10],
        };

        let intervals = compute_pot_intervals(&[pot1.clone(), pot2.clone()]);
        let active = find_active_pots_in_cycle(&intervals, start_sec, end_sec);

        let mut balance_map: HashMap<i64, Vec<(i64, f32)>> = HashMap::new();
        balance_map.insert(
            10,
            vec![
                (start_sec - 100, 500.0),
                (start_sec + 100, 460.0), // -40 in Pot 1
                (t_gap_drop, 430.0),      // -30 in archive->create gap (bridged into Pot 1)
                (t_create, 410.0),        // -20 on exact Pot 2 boundary (counted once in Pot 2)
                (end_sec - 100, 350.0),   // -60 in Pot 2
            ],
        );
        let mut names = HashMap::new();
        names.insert(10, ("Monzo".to_string(), "DEBIT".to_string()));

        let (total_spent, snaps) = compute_cycle_pot_snapshots(
            &active,
            &balance_map,
            &names,
            &[10],
            start_sec,
            end_sec,
            end_sec,
            true,
        );

        let spog_spent = Pence::from_pounds_f32(calculate_account_spent(&balance_map[&10], start_sec, end_sec));
        assert_eq!(total_spent, spog_spent);
        assert_eq!(total_spent, Pence::from_pounds_f64(150.0));
        assert_eq!(snaps.len(), 2);
        assert_eq!(snaps[0].pot_spent_pence, Pence::from_pounds_f64(70.0)); // 40 + 30 gap
        assert_eq!(snaps[1].pot_spent_pence, Pence::from_pounds_f64(80.0)); // 20 boundary + 60

        // Different account sets across Pot 1 (acc 10) and Pot 2 (acc 20)
        let mut pot2_diff = pot2;
        pot2_diff.accounts = vec![20];
        balance_map.insert(
            20,
            vec![
                (t_create - 100, 300.0),
                (t_create + 100, 255.0), // -45 in Pot 2's account
            ],
        );
        names.insert(20, ("Starling".to_string(), "DEBIT".to_string()));
        let intervals_diff = compute_pot_intervals(&[pot1, pot2_diff]);
        let active_diff = find_active_pots_in_cycle(&intervals_diff, start_sec, end_sec);
        let (total_diff, snaps_diff) = compute_cycle_pot_snapshots(
            &active_diff,
            &balance_map,
            &names,
            &[10, 20],
            start_sec,
            end_sec,
            end_sec,
            false,
        );
        assert_eq!(snaps_diff[0].pot_spent_pence, Pence::from_pounds_f64(70.0));
        assert_eq!(snaps_diff[1].pot_spent_pence, Pence::from_pounds_f64(45.0));
        assert_eq!(total_diff, Pence::from_pounds_f64(115.0));
    }

    #[test]
    fn test_current_cycle_without_active_pot_matches_spog_zero() {
        let start_sec = 1_000_000;
        let end_sec = 2_000_000;
        let mut balance_map: HashMap<i64, Vec<(i64, f32)>> = HashMap::new();
        balance_map.insert(10, vec![(start_sec - 10, 500.0), (start_sec + 100, 400.0)]);
        let mut names = HashMap::new();
        names.insert(10, ("Monzo".to_string(), "DEBIT".to_string()));

        // 1. No pots configured at all in current cycle -> £0.00 (matches SPOG)
        let (spent_none, _) = compute_cycle_pot_snapshots(
            &[],
            &balance_map,
            &names,
            &[10],
            start_sec,
            end_sec,
            end_sec,
            true,
        );
        assert_eq!(spent_none, Pence::ZERO);

        // 2. Unreplaced archived pot in current cycle -> £0.00 (matches SPOG)
        let archived_pot = PotRecord {
            id: 1,
            name: "Deleted Pot".to_string(),
            created_at: Some(start_sec),
            last_updated: Some(start_sec + 500),
            archived: true,
            food: None,
            fuel: None,
            transport: None,
            fun: None,
            accounts: vec![10],
        };
        let intervals = compute_pot_intervals(&[archived_pot]);
        let active = find_active_pots_in_cycle(&intervals, start_sec, end_sec);
        let (spent_arch, snaps_arch) = compute_cycle_pot_snapshots(
            &active,
            &balance_map,
            &names,
            &[10],
            start_sec,
            end_sec,
            end_sec,
            true,
        );
        assert_eq!(spent_arch, Pence::ZERO);
        assert_eq!(snaps_arch.len(), 1);

        // 3. Active pot with empty pot_accounts in current cycle -> £0.00 (matches SPOG)
        let empty_pot = PotRecord {
            id: 2,
            name: "Empty Pot".to_string(),
            created_at: Some(start_sec),
            last_updated: None,
            archived: false,
            food: None,
            fuel: None,
            transport: None,
            fun: None,
            accounts: vec![],
        };
        let intervals_empty = compute_pot_intervals(&[empty_pot]);
        let active_empty = find_active_pots_in_cycle(&intervals_empty, start_sec, end_sec);
        let (spent_empty, _) = compute_cycle_pot_snapshots(
            &active_empty,
            &balance_map,
            &names,
            &[10],
            start_sec,
            end_sec,
            end_sec,
            true,
        );
        assert_eq!(spent_empty, Pence::ZERO);
    }

    #[test]
    fn test_past_cycle_unreplaced_archived_pot_stops_at_archive_and_empty_falls_back_to_debit() {
        let start_sec = 1_000_000;
        let t_arch = 1_500_000;
        let end_sec = 2_000_000;
        let mut balance_map: HashMap<i64, Vec<(i64, f32)>> = HashMap::new();
        balance_map.insert(
            10,
            vec![
                (start_sec - 10, 500.0),
                (start_sec + 100, 440.0), // -60 before archive
                (t_arch + 100, 340.0),    // -100 after archive (should be ignored for unreplaced archived pot)
            ],
        );
        let mut names = HashMap::new();
        names.insert(10, ("Monzo".to_string(), "DEBIT".to_string()));

        let archived_pot = PotRecord {
            id: 1,
            name: "Old Pot".to_string(),
            created_at: Some(start_sec - 1000),
            last_updated: Some(t_arch),
            archived: true,
            food: None,
            fuel: None,
            transport: None,
            fun: None,
            accounts: vec![10],
        };
        let intervals = compute_pot_intervals(&[archived_pot]);
        let active = find_active_pots_in_cycle(&intervals, start_sec, end_sec);
        let (spent, _) = compute_cycle_pot_snapshots(
            &active,
            &balance_map,
            &names,
            &[10],
            start_sec,
            end_sec,
            end_sec,
            false,
        );
        assert_eq!(spent, Pence::from_pounds_f64(60.0));

        // Past cycle with empty pot_accounts falls back to debit_account_ids
        let empty_pot = PotRecord {
            id: 2,
            name: "Legacy Pot".to_string(),
            created_at: Some(start_sec - 1000),
            last_updated: None,
            archived: false,
            food: None,
            fuel: None,
            transport: None,
            fun: None,
            accounts: vec![],
        };
        let intervals_empty = compute_pot_intervals(&[empty_pot]);
        let active_empty = find_active_pots_in_cycle(&intervals_empty, start_sec, end_sec);
        let (spent_fallback, _) = compute_cycle_pot_snapshots(
            &active_empty,
            &balance_map,
            &names,
            &[10],
            start_sec,
            end_sec,
            end_sec,
            false,
        );
        assert_eq!(spent_fallback, Pence::from_pounds_f64(160.0));
    }

    #[test]
    fn test_pot_pace_metrics_matches_spog_screenshot_values() {
        let metrics = compute_pot_pace_metrics(
            Pence::from_pounds_f64(504.87),
            Pence::from_pounds_f64(366.72),
            22,
            31,
            Some(Pence::from_pounds_f64(11.83)),
        );
        assert_eq!(metrics.pot_daily_pace, Pence::from_pounds_f64(22.95));
        assert_eq!(metrics.pot_max_per_day, Pence::from_pounds_f64(11.83));
        assert!(metrics.is_pot_overspent_daily);
        assert_eq!(metrics.absolute_pot_daily_overspend, Pence::from_pounds_f64(11.12));
        assert!(metrics.is_over_pot_allowance);
        assert_eq!(metrics.absolute_pot_overspend, Pence::from_pounds_f64(138.15));
        assert_eq!(metrics.budget_utilization_percent, 100.0);
    }

    #[test]
    fn test_loan_liability_repayment_neutral_and_interest_counts_as_spend() {
        let start_sec = 1_000_000;
        let end_sec = 2_000_000;
        let debit_bals = vec![(start_sec - 100, 2000.0), (start_sec + 500, 1800.0)];
        let loan_bals_principal = vec![(start_sec - 100, 5000.0), (start_sec + 500, 4800.0)];

        let (d_start, d_end, d_net, d_liab) =
            compute_account_cycle_net("DEBIT", &debit_bals, start_sec, end_sec, false, end_sec, 0.0);
        let (l_start, l_end, l_net, l_liab) =
            compute_account_cycle_net("LOAN", &loan_bals_principal, start_sec, end_sec, false, end_sec, 0.0);

        assert!(!d_liab);
        assert!(l_liab);
        assert_eq!(d_start, Pence::from_pounds_f64(2000.0));
        assert_eq!(d_end, Pence::from_pounds_f64(1800.0));
        assert_eq!(d_net, Pence::from_pounds_f64(200.0));
        assert_eq!(l_start, Pence::from_pounds_f64(-5000.0));
        assert_eq!(l_end, Pence::from_pounds_f64(-4800.0));
        assert_eq!(l_net, Pence::from_pounds_f64(-200.0));
        assert_eq!(d_net + l_net, Pence::ZERO);

        // Repayment £200 from Debit where £50 was interest and £150 principal -> £50 net spend
        let loan_bals_interest = vec![(start_sec - 100, 5000.0), (start_sec + 500, 4850.0)];
        let (_, _, l_net_int, _) =
            compute_account_cycle_net("LOAN", &loan_bals_interest, start_sec, end_sec, false, end_sec, 0.0);
        assert_eq!(d_net + l_net_int, Pence::from_pounds_f64(50.0));
    }

    #[test]
    fn test_all_accounts_house_purchase_and_unclamped_net_inflow() {
        let start_sec = 1_000_000;
        let end_sec = 2_000_000;
        let savings_house = vec![(start_sec - 100, 50000.0), (start_sec + 500, 10000.0)];
        let debit_flat = vec![(start_sec - 100, 1000.0), (start_sec + 500, 1000.0)];

        let (s_start, s_end, s_net, _) =
            compute_account_cycle_net("SAVINGS", &savings_house, start_sec, end_sec, false, end_sec, 0.0);
        let (d_start, d_end, d_net, _) =
            compute_account_cycle_net("DEBIT", &debit_flat, start_sec, end_sec, false, end_sec, 0.0);

        assert_eq!(s_start + d_start, Pence::from_pounds_f64(51000.0));
        assert_eq!(s_end + d_end, Pence::from_pounds_f64(11000.0));
        assert_eq!(s_net + d_net, Pence::from_pounds_f64(40000.0));

        // Windfall / net inflow month is NOT clamped to £0.00
        let windfall_bals = vec![(start_sec - 100, 1500.0), (start_sec + 500, 2100.0)];
        let (w_start, w_end, w_net, _) =
            compute_account_cycle_net("DEBIT", &windfall_bals, start_sec, end_sec, false, end_sec, 0.0);
        assert_eq!(w_start, Pence::from_pounds_f64(1500.0));
        assert_eq!(w_end, Pence::from_pounds_f64(2100.0));
        assert_eq!(w_net, Pence::from_pounds_f64(-600.0));
    }

    #[test]
    fn test_payday_morning_guard_before_snapshot_shows_zero_spend() {
        let start_sec = 1_000_000;
        let end_sec = 2_000_000;
        let now_sec = start_sec + 300; // 5 minutes into payday, no post-payday snapshot yet

        let pre_payday_only = vec![(start_sec - 3600, 500.0)];
        let (s0, e0, net0, _) =
            compute_account_cycle_net("DEBIT", &pre_payday_only, start_sec, end_sec, true, now_sec, 1000.0);
        assert_eq!(s0, Pence::from_pounds_f64(1500.0));
        assert_eq!(e0, Pence::from_pounds_f64(1500.0));
        assert_eq!(net0, Pence::ZERO);

        // Once post-payday snapshot arrives and spending occurs, real balances take over
        let post_payday = vec![
            (start_sec - 3600, 500.0),
            (start_sec + 3600, 1400.0),
            (start_sec + 7200, 1100.0),
        ];
        let (s1, e1, net1, _) = compute_account_cycle_net(
            "DEBIT",
            &post_payday,
            start_sec,
            end_sec,
            true,
            start_sec + 10_000,
            1000.0,
        );
        assert_eq!(s1, Pence::from_pounds_f64(1400.0));
        assert_eq!(e1, Pence::from_pounds_f64(1100.0));
        assert_eq!(net1, Pence::from_pounds_f64(300.0));
    }

    #[test]
    fn test_compute_historic_summaries_separates_pots_and_all_accounts_kpis() {
        let make_cycle = |range: &str,
                          is_current: bool,
                          pot_spent: f64,
                          pot_pace: f64,
                          is_over_daily: bool,
                          all_net_spent: f64| {
            let p_spent = Pence::from_pounds_f64(pot_spent);
            let a_net = Pence::from_pounds_f64(all_net_spent);
            HistoricCycleDto {
                cycle_start_date: NaiveDate::from_ymd_opt(2026, 10, 1).unwrap(),
                cycle_end_date: NaiveDate::from_ymd_opt(2026, 11, 1).unwrap(),
                cycle_date_range: range.to_string(),
                is_current_cycle: is_current,
                days_elapsed: 30,
                days_in_cycle: 30,
                total_spent: all_net_spent as f32,
                monthly_income: 3000.0,
                monthly_income_pence: Pence::from_pounds_f64(3000.0),
                pot_allowance: 400.0,
                pot_allowance_pence: Pence::from_pounds_f64(400.0),
                pot_spent: pot_spent as f32,
                pot_spent_pence: p_spent,
                pot_daily_pace: Pence::from_pounds_f64(pot_pace),
                pot_max_per_day: Pence::from_pounds_f64(13.33),
                is_pot_overspent_daily: is_over_daily,
                absolute_pot_daily_overspend: Pence::from_pounds_f64(2.0),
                starting_total_balance: 10000.0,
                starting_total_balance_pence: Pence::from_pounds_f64(10000.0),
                ending_total_balance: (10000.0 - all_net_spent) as f32,
                ending_total_balance_pence: Pence::from_pounds_f64(10000.0 - all_net_spent),
                all_accounts_net_spent: a_net,
                all_accounts_is_net_inflow: a_net.is_negative(),
                all_accounts_abs_net_change: a_net.abs(),
                actual_daily_spend: pot_pace as f32,
                is_over_pot_allowance: pot_spent > 400.0,
                absolute_pot_overspend: (pot_spent - 400.0).abs() as f32,
                absolute_pot_overspend_pence: Pence::from_pounds_f64((pot_spent - 400.0).abs()),
                budget_utilization_percent: 100.0,
                pot_account_ids: vec![],
                account_breakdowns: vec![],
                pots_in_period: vec![],
                has_multiple_pots: false,
            }
        };

        let cycles = vec![
            make_cycle("Nov 2026", true, 999.0, 99.0, true, 99999.0),
            make_cycle("Oct 2026", false, 300.0, 10.0, false, -600.0),
            make_cycle("Sep 2026", false, 500.0, 16.0, true, 40000.0),
        ];

        let (pots_sum, all_sum) = compute_historic_summaries(&cycles);
        assert_eq!(pots_sum.avg_pot_spend, Pence::from_pounds_f64(400.0));
        assert_eq!(pots_sum.avg_daily_pace, Pence::from_pounds_f64(13.0));
        assert_eq!(pots_sum.cycles_within_max, 1);
        assert_eq!(pots_sum.total_completed_cycles, 2);
        assert_eq!(pots_sum.within_max_percent, 50);

        assert_eq!(all_sum.avg_net_spend, Pence::from_pounds_f64(19700.0));
        assert!(!all_sum.avg_is_net_inflow);
        assert_eq!(all_sum.highest_spent, Some(Pence::from_pounds_f64(40000.0)));
        assert_eq!(all_sum.highest_spent_range.as_deref(), Some("Sep 2026"));
        assert_eq!(all_sum.lowest_spent, Some(Pence::from_pounds_f64(-600.0)));
        assert!(all_sum.lowest_is_net_inflow);
        assert_eq!(all_sum.lowest_abs_net_change, Some(Pence::from_pounds_f64(600.0)));
        assert_eq!(all_sum.lowest_spent_range.as_deref(), Some("Oct 2026"));
    }
}
