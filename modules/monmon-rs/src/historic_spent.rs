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

    // 1. Check if an explicit balance was recorded on or shortly after payday (within 24h)
    for &(t, v) in balances {
        if t >= cycle_start_sec && t <= cycle_start_sec + 86400 {
            return ((v * 100.0).round()) / 100.0;
        }
    }

    // 2. If no explicit balance on payday, but we have a payday inflow:
    if payday_inflow > 0.0 {
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

    // 3. Fallback balance object:
    // a. Balance near cycle start (within 24 hours around cycle_start_sec)
    let mut best_near_start = None;
    let mut best_dist = i64::MAX;
    for &(t, v) in balances {
        let dist = (t - cycle_start_sec).abs();
        if dist <= 86400 && dist < best_dist {
            best_dist = dist;
            best_near_start = Some(v);
        }
    }
    if let Some(v) = best_near_start {
        return ((v * 100.0).round()) / 100.0;
    }

    // b. Earliest balance recorded within the cycle
    for &(t, v) in balances {
        if t >= cycle_start_sec && t < cycle_end_sec {
            return ((v * 100.0).round()) / 100.0;
        }
    }

    // c. Latest before cycle_start_sec
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
        let acc_id: i64 = r.get("account_id");
        let sum: f32 = r.get("sum");
        map.insert(acc_id, sum);
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
        if t_upper != "LOAN" && t_upper != "MORTGAGE" && t_upper != "PENSION" {
            ledger_account_ids.push(acc_id);
            ledger_accounts.push((acc_id, name, acc_type, ledger_mode));
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

    let balance_map = load_account_balances(pool, &ledger_account_ids)
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

        // Resolve accountSourcePot (if pot has no accounts, inherit from subsequent pot or default to debit accounts)
        let pot_account_ids: Vec<i64> = if let Some(ref p) = active_pot {
            if !p.accounts.is_empty() {
                p.accounts.clone()
            } else {
                let mut found_accounts = Vec::new();
                if let Some(idx) = pots.iter().position(|x| x.id == p.id) {
                    for later in &pots[(idx + 1)..] {
                        if !later.accounts.is_empty() {
                            found_accounts = later.accounts.clone();
                            break;
                        }
                    }
                }
                if !found_accounts.is_empty() {
                    found_accounts
                } else {
                    ledger_account_ids.clone()
                }
            }
        } else {
            ledger_account_ids.clone()
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

        // TBL transactions map for this cycle
        let tbl_spent_by_account = get_accounts_spent_sum_map(pool, user_id, start_sec, effective_end_sec).await;

        let mut starting_total_balance = 0.0f32;
        let mut ending_total_balance = 0.0f32;
        let mut cycle_spent = 0.0f32;
        let mut raw_pot_spent = 0.0f32;
        let mut total_savings_inflow = 0.0f32;
        let mut starting_pot_budget = 0.0f32;
        let mut ending_pot_budget = 0.0f32;
        let mut account_breakdowns = Vec::new();

        for &(acc_id, ref acc_name, ref acc_type, ref ledger_mode) in &ledger_accounts {
            let empty_vec = Vec::new();
            let bals = balance_map.get(&acc_id).unwrap_or(&empty_vec);
            if bals.is_empty() {
                continue;
            }
            if bals[0].0 > effective_end_sec {
                continue;
            }

            let acc_inflow = get_account_payday_inflow(acc_id, &cycle_incomings, curr_cycle_start, start_sec, end_sec);
            let start_bal = get_account_start_balance_with_inflow(bals, start_sec, end_sec, acc_inflow);
            let end_bal = get_account_end_balance(bals, start_sec, end_sec, is_current, effective_end_sec);

            let is_tbl_active = ledger_mode == "TBL"
                && acc_type.to_uppercase() != "PENSION"
                && acc_type.to_uppercase() != "LOAN";

            let acc_spent = if is_current && is_tbl_active && tbl_spent_by_account.contains_key(&acc_id) {
                tbl_spent_by_account[&acc_id]
            } else {
                calculate_account_net_spent(bals, start_sec, effective_end_sec, start_bal, end_bal)
            };

            let is_pot_account = pot_account_ids.contains(&acc_id);
            if is_pot_account {
                starting_pot_budget += start_bal;
                ending_pot_budget += end_bal;
                raw_pot_spent += acc_spent;
            }

            let t_upper = acc_type.to_uppercase();
            let is_savings = t_upper.contains("SAVING");
            if is_savings && acc_spent < 0.0 {
                total_savings_inflow += -acc_spent;
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
        starting_pot_budget = ((starting_pot_budget * 100.0).round()) / 100.0;
        let _ = ending_pot_budget;

        let actual_daily_spend = if days_elapsed > 0 {
            (((cycle_spent / days_elapsed as f32) * 100.0).round()) / 100.0
        } else {
            0.0
        };

        // Pot Allowance / Budget
        let allowance_incomings = if !cycle_incomings.is_empty() {
            cycle_incomings.clone()
        } else {
            incomings.iter().filter(|i| !i.archived).collect()
        };
        let cycle_allowance = calculate_monthly_allowance(&allowance_incomings, &outgoings, &plans, curr_cycle_start);

        let pot_allowance = if is_current {
            spog.surplus.to_pounds_f32()
        } else if cycle_allowance > 0.0 {
            cycle_allowance
        } else if let Some(ref p) = active_pot {
            let plan_sum = p.food.unwrap_or(0.0) + p.fuel.unwrap_or(0.0) + p.transport.unwrap_or(0.0) + p.fun.unwrap_or(0.0);
            if plan_sum > 0.0 {
                plan_sum
            } else if starting_pot_budget > 0.0 {
                starting_pot_budget
            } else {
                monthly_income
            }
        } else if starting_pot_budget > 0.0 {
            starting_pot_budget
        } else {
            monthly_income
        };
        let pot_allowance = ((pot_allowance * 100.0).round()) / 100.0;

        let diff = pot_spent - pot_allowance;
        let is_over = diff > 0.0;
        let absolute_pot_overspend = ((diff.abs()) * 100.0).round() / 100.0;
        let utilization = if pot_allowance > 0.0 {
            ((pot_spent / pot_allowance) * 100.0).clamp(0.0, 150.0)
        } else if monthly_income > 0.0 {
            ((cycle_spent / monthly_income) * 100.0).clamp(0.0, 150.0)
        } else {
            0.0
        };

        let cycle_date_range = format!(
            "{} – {}",
            curr_cycle_start.format("%d %b %Y"),
            curr_cycle_end.format("%d %b %Y")
        );

        // Build pots configured in period snapshots (matching Java HistoricPotSnapshot)
        let mut pots_in_period = Vec::new();
        let display_intervals = active_intervals_in_cycle;
        let num_display = display_intervals.len();

        for (idx, interval) in display_intervals.iter().enumerate() {
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

            let pot_start_boundary = interval.start.max(start_sec);
            let pot_end_boundary = interval.end.min(effective_end_sec);

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

                let start_bal_on_date = if pot_start_boundary <= start_sec + 86400 {
                    get_account_start_balance(bals, start_sec, end_sec)
                } else {
                    let b = get_account_end_balance(bals, 0, pot_start_boundary, false, pot_start_boundary);
                    if b == 0.0 {
                        get_account_start_balance(bals, start_sec, end_sec)
                    } else {
                        b
                    }
                };

                let bal_on_date = get_account_end_balance(
                    bals,
                    start_sec,
                    pot_end_boundary,
                    is_current && pot_end_boundary == effective_end_sec,
                    pot_end_boundary,
                );
                let pa_spent = ((start_bal_on_date - bal_on_date).max(0.0) * 100.0).round() / 100.0;
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
        let sum: f32 = completed_cycles.iter().map(|c| c.total_spent).sum();
        ((sum / completed_cycles.len() as f32) * 100.0).round() / 100.0
    } else {
        cycles.first().map(|c| c.total_spent).unwrap_or(0.0)
    };

    let highest = completed_cycles.iter().max_by(|a, b| a.total_spent.partial_cmp(&b.total_spent).unwrap());
    let lowest = completed_cycles.iter().min_by(|a, b| a.total_spent.partial_cmp(&b.total_spent).unwrap());

    let tmpl = HistoricSpentTemplate {
        has_history,
        average_monthly_spend: avg_spend,
        highest_spent: highest.map(|h| h.total_spent),
        highest_spent_range: highest.map(|h| h.cycle_date_range.clone()),
        lowest_spent: lowest.map(|l| l.total_spent),
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
}
