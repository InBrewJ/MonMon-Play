use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, Json},
};
use chrono::{Datelike, Local, NaiveDate};
use serde::{Deserialize, Serialize};
use sqlx::Row;
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct DigQuery {
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
}

impl DigQuery {
    pub fn get_user_id(&self) -> String {
        self.user_id.clone().unwrap_or_else(|| "demo".to_string())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DigTransactionDto {
    pub id: i64,
    pub lat: f64,
    pub lng: f64,
    pub amount: f32,
    pub merchant: String,
    pub category: String,
    pub emoji: String,
    pub address: String,
    pub date: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DigHistogramDto {
    pub category: String,
    pub raw_category: String,
    pub count: i64,
    pub total: f64,
    pub average: f64,
    pub percentage: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CycleRangeDto {
    pub start_date: String,
    pub end_date: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CyclesDto {
    pub nominal_payday: u32,
    pub this_month: CycleRangeDto,
    pub last_2_cycles: CycleRangeDto,
    pub last_6_cycles: CycleRangeDto,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DigDataResponse {
    pub transactions: Vec<DigTransactionDto>,
    pub histogram: Vec<DigHistogramDto>,
    pub cycles: CyclesDto,
    pub total_transactions: usize,
    pub limit_reached: bool,
    pub limit: usize,
}

#[derive(Template)]
#[template(path = "dig.html")]
pub struct DigTemplate {
    pub is_monzo_connected: bool,
    pub is_limit_reached: bool,
    pub data_limit: usize,
    pub total_geotagged: usize,
    pub total_spent: f64,
    pub top_category: String,
    pub json_payload: String,
}

fn format_category_name(raw: &str) -> String {
    if raw.trim().is_empty() {
        return "General".to_string();
    }
    raw.replace('_', " ")
        .split_whitespace()
        .map(|w| {
            let mut c = w.chars();
            match c.next() {
                None => String::new(),
                Some(f) => f.to_uppercase().collect::<String>() + &c.as_str().to_lowercase(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn format_range(start: NaiveDate, end: NaiveDate) -> String {
    if start.year() == end.year() {
        format!(
            "{} – {}",
            start.format("%-d %b"),
            end.format("%-d %b %Y")
        )
    } else {
        format!(
            "{} – {}",
            start.format("%-d %b %Y"),
            end.format("%-d %b %Y")
        )
    }
}

fn compute_cycles(today: NaiveDate, payday: u32) -> CyclesDto {
    let payday_clamped = payday.clamp(1, 28); // Safe day of month

    let current_month_payday = NaiveDate::from_ymd_opt(today.year(), today.month(), payday_clamped)
        .unwrap_or(today);

    let this_month_start = if today >= current_month_payday {
        current_month_payday
    } else {
        let (prev_year, prev_month) = if today.month() == 1 {
            (today.year() - 1, 12)
        } else {
            (today.year(), today.month() - 1)
        };
        NaiveDate::from_ymd_opt(prev_year, prev_month, payday_clamped).unwrap_or(today)
    };

    let last_2_cycles_start = {
        let (y, m) = if this_month_start.month() == 1 {
            (this_month_start.year() - 1, 12)
        } else {
            (this_month_start.year(), this_month_start.month() - 1)
        };
        NaiveDate::from_ymd_opt(y, m, payday_clamped).unwrap_or(this_month_start)
    };

    let mut last_6_cycles_start = this_month_start;
    for _ in 0..5 {
        let (y, m) = if last_6_cycles_start.month() == 1 {
            (last_6_cycles_start.year() - 1, 12)
        } else {
            (last_6_cycles_start.year(), last_6_cycles_start.month() - 1)
        };
        last_6_cycles_start = NaiveDate::from_ymd_opt(y, m, payday_clamped).unwrap_or(last_6_cycles_start);
    }

    CyclesDto {
        nominal_payday: payday_clamped,
        this_month: CycleRangeDto {
            start_date: this_month_start.format("%Y-%m-%d").to_string(),
            end_date: today.format("%Y-%m-%d").to_string(),
            label: format_range(this_month_start, today),
        },
        last_2_cycles: CycleRangeDto {
            start_date: last_2_cycles_start.format("%Y-%m-%d").to_string(),
            end_date: today.format("%Y-%m-%d").to_string(),
            label: format_range(last_2_cycles_start, today),
        },
        last_6_cycles: CycleRangeDto {
            start_date: last_6_cycles_start.format("%Y-%m-%d").to_string(),
            end_date: today.format("%Y-%m-%d").to_string(),
            label: format_range(last_6_cycles_start, today),
        },
    }
}

pub async fn build_dig_data(
    pool: &sqlx::PgPool,
    user_id: &str,
    limit: usize,
) -> Result<DigDataResponse, sqlx::Error> {
    // 1. Fetch geotagged transactions
    let rows = sqlx::query(
        r#"
        SELECT
            id,
            latitude,
            longitude,
            amount,
            COALESCE(merchant_name, merchantname, description) AS merchant,
            COALESCE(category, 'general') AS category,
            COALESCE(merchant_emoji, merchantemoji, '') AS emoji,
            COALESCE(merchant_address, merchantaddress, '') AS address,
            COALESCE(date, '') AS tx_date,
            COALESCE(timestamp, 0) AS ts
        FROM bank_transaction
        WHERE (userid = $1 OR user_id = $1)
          AND latitude IS NOT NULL
          AND longitude IS NOT NULL
          AND amount > 0
        ORDER BY timestamp DESC NULLS LAST, id DESC
        LIMIT $2
        "#,
    )
    .bind(user_id)
    .bind(limit as i64)
    .fetch_all(pool)
    .await?;

    let mut transactions = Vec::new();
    for r in rows {
        transactions.push(DigTransactionDto {
            id: r.get("id"),
            lat: r.get("latitude"),
            lng: r.get("longitude"),
            amount: r.get("amount"),
            merchant: r.get("merchant"),
            category: r.get("category"),
            emoji: r.get("emoji"),
            address: r.get("address"),
            date: r.get("tx_date"),
            timestamp: r.get("ts"),
        });
    }

    // 2. Fetch category histogram directly aggregated in PostgreSQL
    let hist_rows = sqlx::query(
        r#"
        SELECT
            COALESCE(NULLIF(TRIM(category), ''), 'general') AS cat,
            COUNT(*)::bigint AS cnt,
            COALESCE(SUM(amount), 0.0)::float8 AS total_spent,
            COALESCE(AVG(amount), 0.0)::float8 AS avg_spent,
            ROUND(COALESCE(SUM(amount) / NULLIF(SUM(SUM(amount)) OVER (), 0) * 100, 0)::numeric, 1)::float8 AS pct
        FROM bank_transaction
        WHERE (userid = $1 OR user_id = $1)
          AND amount > 0
        GROUP BY COALESCE(NULLIF(TRIM(category), ''), 'general')
        ORDER BY total_spent DESC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    let mut histogram = Vec::new();
    for hr in hist_rows {
        let raw_cat: String = hr.get("cat");
        let formatted = format_category_name(&raw_cat);
        histogram.push(DigHistogramDto {
            category: formatted,
            raw_category: raw_cat,
            count: hr.get("cnt"),
            total: (hr.get::<f64, _>("total_spent") * 100.0).round() / 100.0,
            average: (hr.get::<f64, _>("avg_spent") * 100.0).round() / 100.0,
            percentage: hr.get("pct"),
        });
    }

    // 3. Resolve nominal payday
    let payday_row = sqlx::query(
        "SELECT incomingmonthday FROM incoming WHERE (userid = $1 OR user_id = $1) AND archived = false AND payday = true ORDER BY id ASC LIMIT 1"
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .unwrap_or(None);

    let nominal_payday: u32 = payday_row
        .and_then(|r| r.try_get::<i32, _>("incomingmonthday").ok())
        .filter(|&d| d >= 1 && d <= 31)
        .map(|d| d as u32)
        .unwrap_or(1);

    let today = Local::now().date_naive();
    let cycles = compute_cycles(today, nominal_payday);

    let limit_reached = transactions.len() >= limit;
    let total_transactions = transactions.len();

    Ok(DigDataResponse {
        transactions,
        histogram,
        cycles,
        total_transactions,
        limit_reached,
        limit,
    })
}

pub async fn get_dig_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<DigQuery>,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    let data_limit = 2000;
    let dig_data = build_dig_data(&state.pool, &resolved, data_limit)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    // Check Monzo connection
    let conn_row = sqlx::query("SELECT id FROM monzo_connection WHERE userid = $1 LIMIT 1")
        .bind(&resolved)
        .fetch_optional(&state.pool)
        .await
        .unwrap_or(None);
    let is_monzo_connected = conn_row.is_some();

    let total_spent = dig_data.histogram.iter().map(|h| h.total).sum::<f64>();
    let total_spent = (total_spent * 100.0).round() / 100.0;
    let top_category = dig_data
        .histogram
        .first()
        .map(|h| h.category.clone())
        .unwrap_or_else(|| "None".to_string());

    let json_payload = serde_json::to_string(&dig_data)
        .unwrap_or_else(|_| "{}".to_string());

    let tmpl = DigTemplate {
        is_monzo_connected,
        is_limit_reached: dig_data.limit_reached,
        data_limit,
        total_geotagged: dig_data.total_transactions,
        total_spent,
        top_category,
        json_payload,
    };

    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_dig_data_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<DigQuery>,
) -> Result<Json<DigDataResponse>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    let dig_data = build_dig_data(&state.pool, &resolved, 2000)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(dig_data))
}
