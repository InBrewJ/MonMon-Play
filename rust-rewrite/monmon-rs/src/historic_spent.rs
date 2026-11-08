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
use askama::Template;
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
        2 => if (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0) { 29 } else { 28 },
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

/// Computes total spent on an account between start_sec and end_sec
/// based on balance deltas (curr.value - prev.value < 0), matching Java HistoricSpentCalculator.
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
        let ts_sec = if raw_ts > 100_000_000_000 { raw_ts / 1000 } else { raw_ts };
        map.entry(acc_id).or_default().push((ts_sec, val));
    }
    Ok(map)
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
    pub is_over_pot_allowance: bool,
    pub absolute_pot_overspend: f32,
    pub budget_utilization_percent: f32,
    pub pot_account_ids: Vec<i64>,
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
        SELECT id, type
        FROM account
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    let mut ledger_account_ids = Vec::new();
    for r in accounts_rows {
        let acc_id: i64 = r.get("id");
        let acc_type: String = r.get("type");
        let t_upper = acc_type.to_uppercase();
        if t_upper != "LOAN" && t_upper != "MORTGAGE" && t_upper != "PENSION" {
            ledger_account_ids.push(acc_id);
        }
    }

    let pot_row = sqlx::query(
        r#"
        SELECT id FROM pot
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
          AND type = 'MONTHLY'
        ORDER BY createdat DESC NULLS LAST, id DESC
        LIMIT 1
        "#,
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .unwrap_or(None);

    let pot_account_ids: Vec<i64> = if let Some(pr) = pot_row {
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
        ledger_account_ids.clone()
    };

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

        let end_sec = if is_current {
            Utc::now().timestamp()
        } else {
            curr_cycle_end
                .and_hms_opt(0, 0, 0)
                .unwrap()
                .and_local_timezone(chrono::Utc)
                .unwrap()
                .timestamp()
        };

        let days_in_cycle = (curr_cycle_end - curr_cycle_start).num_days().max(1) as i32;
        let days_elapsed = if is_current {
            ((today - curr_cycle_start).num_days() as i32 + 1).min(days_in_cycle).max(1)
        } else {
            days_in_cycle
        };

        let mut pot_spent = 0.0f32;
        for pid in &pot_account_ids {
            if let Some(bals) = balance_map.get(pid) {
                pot_spent += calculate_account_spent(bals, start_sec, end_sec);
            }
        }
        pot_spent = ((pot_spent * 100.0).round()) / 100.0;

        let mut total_spent = 0.0f32;
        for lid in &ledger_account_ids {
            if let Some(bals) = balance_map.get(lid) {
                total_spent += calculate_account_spent(bals, start_sec, end_sec);
            }
        }
        total_spent = ((total_spent * 100.0).round()) / 100.0;

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

        cycles.push(HistoricCycleDto {
            cycle_start_date: curr_cycle_start,
            cycle_end_date: curr_cycle_end,
            cycle_date_range,
            is_current_cycle: is_current,
            days_elapsed,
            days_in_cycle,
            total_spent,
            monthly_income,
            pot_allowance,
            pot_spent,
            is_over_pot_allowance: is_over,
            absolute_pot_overspend,
            budget_utilization_percent: utilization,
            pot_account_ids: pot_account_ids.clone(),
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
