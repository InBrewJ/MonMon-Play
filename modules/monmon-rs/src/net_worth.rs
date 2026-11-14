use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, Json, Redirect},
};
use chrono::{Datelike, TimeZone, Utc};
use serde::{Deserialize, Serialize};
use sqlx::Row;
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct NetWorthQuery {
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
}

impl NetWorthQuery {
    #[allow(dead_code)]
    pub fn get_user_id(&self) -> String {
        self.user_id.clone().unwrap_or_else(|| "demo".to_string())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetWorthAssetDto {
    pub id: i64,
    pub name: String,
    pub category: String,
    pub category_human_readable: String,
    pub category_icon: String,
    pub value: f64,
    pub notes: Option<String>,
    pub acquired_at: Option<i64>,
    pub acquired_date_formatted: String,
    pub acquired_date_html_value: String,
    pub archived: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountItemDto {
    pub id: i64,
    pub name: String,
    pub nickname: Option<String>,
    pub account_type: String,
    pub balance: f64,
    pub available_limit: f64,
    pub amount_owed: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetWorthHistoryPoint {
    pub timestamp: i64,
    pub date_str: String,
    pub pensions: f64,
    pub cash_savings: f64,
    pub fixed_assets: f64,
    pub total: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetWorthDto {
    pub net_worth: f64,
    pub total_assets: f64,
    pub total_liabilities: f64,
    pub cash_total: f64,
    pub pension_total: f64,
    pub custom_assets_total: f64,
    pub credit_debt_total: f64,
    pub loan_debt_total: f64,
    pub investable_portfolio: f64,
    pub annual_safe_withdrawal: f64,
    pub monthly_safe_withdrawal: f64,
    pub monthly_food_cost: f64,
    pub food_covered_percent: i64,
    pub asset_to_debt_ratio: Option<f64>,
    pub debt_to_asset_ratio: Option<f64>,
    pub debt_to_asset_ratio_formatted: String,
    pub is_healthy_ratio: bool,
    pub is_debt_free: bool,
    pub custom_assets: Vec<NetWorthAssetDto>,
    pub pension_accounts: Vec<AccountItemDto>,
    pub cash_accounts: Vec<AccountItemDto>,
    pub credit_accounts: Vec<AccountItemDto>,
    pub loan_accounts: Vec<AccountItemDto>,
}

#[derive(Template)]
#[template(path = "net_worth.html")]
pub struct NetWorthTemplate {
    pub vm: NetWorthDto,
    pub chart_history_json: String,
}

fn round2(val: f64) -> f64 {
    (val * 100.0).round() / 100.0
}

fn category_info(cat: &str) -> (&'static str, &'static str) {
    match cat {
        "PROPERTY" => ("Property", "fa-home"),
        "VEHICLE" => ("Vehicle", "fa-car"),
        "VALUABLE" => ("Valuable", "fa-gem"),
        "INVESTMENT" => ("Investment", "fa-chart-line"),
        _ => ("Other Asset", "fa-box"),
    }
}

pub async fn calculate_net_worth(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<NetWorthDto, sqlx::Error> {
    // 1. Fetch physical/custom assets
    let asset_rows = sqlx::query(
        r#"
        SELECT
            id, name, category, asset_value, notes, archived,
            COALESCE(acquired_at, acquiredat, created_at, createdat) as acq_at
        FROM net_worth_asset
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY asset_value DESC, id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    let mut custom_assets = Vec::new();
    let mut custom_assets_total = 0.0;

    for r in asset_rows {
        let id: i64 = r.get("id");
        let name: String = r.get("name");
        let category: String = r.get("category");
        let val: f64 = r.get("asset_value");
        let notes: Option<String> = r.get("notes");
        let archived: bool = r.get("archived");
        let acq_at: Option<i64> = r.get("acq_at");

        let (cat_label, cat_icon) = category_info(&category);

        let (date_str, date_html) = match acq_at {
            Some(ms) => {
                let dt = Utc.timestamp_millis_opt(ms).single().unwrap_or_else(Utc::now);
                (dt.format("%-d %b %Y").to_string(), dt.format("%Y-%m-%d").to_string())
            }
            None => {
                let now = Utc::now();
                (now.format("%-d %b %Y").to_string(), now.format("%Y-%m-%d").to_string())
            }
        };

        custom_assets_total += val.max(0.0);

        custom_assets.push(NetWorthAssetDto {
            id,
            name,
            category,
            category_human_readable: cat_label.to_string(),
            category_icon: cat_icon.to_string(),
            value: round2(val),
            notes,
            acquired_at: acq_at,
            acquired_date_formatted: date_str,
            acquired_date_html_value: date_html,
            archived,
        });
    }

    // 2. Fetch accounts with their latest balance directly in PostgreSQL
    let acc_rows = sqlx::query(
        r#"
        WITH latest_balances AS (
            SELECT DISTINCT ON (account_id)
                account_id AS acc_id,
                value AS val
            FROM balance
            ORDER BY account_id, timestamp DESC NULLS LAST, id DESC
        )
        SELECT
            a.id,
            a.name,
            a.nickname,
            a.type,
            COALESCE(a.account_limit, 0)::float8 as available_limit,
            COALESCE(lb.val, 0)::float8 as balance
        FROM account a
        LEFT JOIN latest_balances lb ON lb.acc_id = a.id
        WHERE (a.userid = $1 OR a.user_id = $1)
          AND (a.archived IS NULL OR a.archived = false)
        ORDER BY a.name ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    let mut pension_accounts = Vec::new();
    let mut cash_accounts = Vec::new();
    let mut credit_accounts = Vec::new();
    let mut loan_accounts = Vec::new();

    let mut pension_total = 0.0;
    let mut cash_total = 0.0;
    let mut credit_debt_total = 0.0;
    let mut loan_debt_total = 0.0;

    for r in acc_rows {
        let id: i64 = r.get("id");
        let name: String = r.get("name");
        let nickname: Option<String> = r.get("nickname");
        let acc_type: String = r.get("type");
        let available_limit: f64 = r.get("available_limit");
        let balance: f64 = r.get("balance");

        let acc_dto = AccountItemDto {
            id,
            name,
            nickname,
            account_type: acc_type.clone(),
            balance: round2(balance),
            available_limit: round2(available_limit),
            amount_owed: 0.0,
        };

        match acc_type.as_str() {
            "PENSION" => {
                pension_total += balance.max(0.0);
                pension_accounts.push(acc_dto);
            }
            "LOAN" => {
                loan_debt_total += balance.max(0.0);
                loan_accounts.push(AccountItemDto {
                    amount_owed: round2(balance.max(0.0)),
                    ..acc_dto
                });
            }
            "CREDIT" => {
                let owed = (available_limit - balance).max(0.0);
                credit_debt_total += owed;
                credit_accounts.push(AccountItemDto {
                    amount_owed: round2(owed),
                    ..acc_dto
                });
            }
            _ => {
                cash_total += balance;
                cash_accounts.push(acc_dto);
            }
        }
    }

    cash_total = round2(cash_total);
    pension_total = round2(pension_total);
    custom_assets_total = round2(custom_assets_total);
    credit_debt_total = round2(credit_debt_total);
    loan_debt_total = round2(loan_debt_total);

    let total_assets = round2(cash_total + pension_total + custom_assets_total);
    let total_liabilities = round2(credit_debt_total + loan_debt_total);
    let net_worth = round2(total_assets - total_liabilities);

    let is_debt_free = total_liabilities <= 0.0;
    let asset_to_debt_ratio = if !is_debt_free {
        Some(round2(total_assets / total_liabilities))
    } else {
        None
    };

    let (debt_to_asset_ratio, debt_to_asset_ratio_formatted, is_healthy_ratio) = if is_debt_free {
        (Some(0.0), "0.00 (0.0% — Debt Free)".to_string(), true)
    } else if total_assets <= 0.0 {
        (Some(1.0), ">1.0 (>100%)".to_string(), false)
    } else {
        let r = round2(total_liabilities / total_assets);
        let pct = round2(r * 100.0);
        (Some(r), format!("{:.2} ({:.1}%)", r, pct), r <= 1.0)
    };

    let investable_portfolio = round2(cash_total + pension_total);
    let annual_safe_withdrawal = round2(investable_portfolio * 0.04);
    let monthly_safe_withdrawal = round2(annual_safe_withdrawal / 12.0);

    // Look up spending_plan_food in active monthly pot if present
    let pot_row = sqlx::query(
        r#"
        SELECT spending_plan_food
        FROM pot
        WHERE (userid = $1 OR user_id = $1)
          AND type = 'MONTHLY'
          AND (archived IS NULL OR archived = false)
        ORDER BY id DESC LIMIT 1
        "#,
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .unwrap_or(None);

    let monthly_food_cost = pot_row
        .and_then(|r| r.get::<Option<f32>, _>("spending_plan_food"))
        .map(|f| f as f64)
        .filter(|&f| f > 0.0)
        .unwrap_or(216.67);

    let food_covered_percent = if monthly_food_cost > 0.0 {
        ((monthly_safe_withdrawal / monthly_food_cost) * 100.0).round() as i64
    } else {
        100
    };

    Ok(NetWorthDto {
        net_worth,
        total_assets,
        total_liabilities,
        cash_total,
        pension_total,
        custom_assets_total,
        credit_debt_total,
        loan_debt_total,
        investable_portfolio,
        annual_safe_withdrawal,
        monthly_safe_withdrawal,
        monthly_food_cost: round2(monthly_food_cost),
        food_covered_percent,
        asset_to_debt_ratio,
        debt_to_asset_ratio,
        debt_to_asset_ratio_formatted,
        is_healthy_ratio,
        is_debt_free,
        custom_assets,
        pension_accounts,
        cash_accounts,
        credit_accounts,
        loan_accounts,
    })
}

pub async fn calculate_history_points(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<Vec<NetWorthHistoryPoint>, sqlx::Error> {
    let now = Utc::now();
    let today = now.date_naive();
    let now_sec = today.and_hms_opt(23, 59, 59).unwrap().and_utc().timestamp();

    let ten_years_ago = chrono::NaiveDate::from_ymd_opt(today.year() - 10, today.month(), 1)
        .unwrap_or_else(|| today - chrono::Duration::days(3650));
    let ten_years_ago_sec = ten_years_ago.and_hms_opt(0, 0, 0).unwrap().and_utc().timestamp();

    // 1. Fetch user accounts
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
    .await?;

    let mut pension_acc_ids = Vec::new();
    let mut cash_savings_acc_ids = Vec::new();

    for r in accounts_rows {
        let acc_id: i64 = r.get("id");
        let acc_type: String = r.get("type");
        match acc_type.as_str() {
            "PENSION" => pension_acc_ids.push(acc_id),
            "DEBIT" | "SHORT_TERM_SAVINGS" | "LONG_TERM_SAVINGS" | "DEBIT_SHARED_BILLS" | "SAVINGS" => {
                cash_savings_acc_ids.push(acc_id);
            }
            _ => {}
        }
    }

    // 2. Fetch all historical balances for these accounts
    let balance_rows = sqlx::query(
        r#"
        SELECT account_id, value::float8 as val, timestamp
        FROM balance
        WHERE (userid = $1 OR user_id = $1)
        ORDER BY timestamp ASC, id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    let mut account_balances_map: std::collections::HashMap<i64, Vec<(i64, f64)>> = std::collections::HashMap::new();
    for r in balance_rows {
        let acc_id: Option<i64> = r.get("account_id");
        let val: Option<f64> = r.get("val");
        let ts: Option<i64> = r.get("timestamp");
        if let (Some(aid), Some(v), Some(t)) = (acc_id, val, ts) {
            account_balances_map.entry(aid).or_default().push((t, v));
        }
    }

    // 3. Fetch custom assets
    struct AssetItem {
        value: f64,
        start_sec: Option<i64>,
        archived: bool,
        delete_sec: Option<i64>,
    }

    let asset_rows = sqlx::query(
        r#"
        SELECT 
            asset_value, archived,
            COALESCE(acquired_at, acquiredat, created_at, createdat) as acq_ms,
            updated_at, deleted_at
        FROM net_worth_asset
        WHERE (userid = $1 OR user_id = $1)
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    let mut assets = Vec::new();
    for r in asset_rows {
        let val: f64 = r.get("asset_value");
        let archived: bool = r.get("archived");
        let acq_ms: Option<i64> = r.get("acq_ms");
        let start_sec = acq_ms.map(|ms| ms / 1000);
        let del_ms: Option<i64> = r.get("deleted_at");
        let upd_ms: Option<i64> = r.get("updated_at");
        let delete_sec = del_ms.or(upd_ms).map(|ms| ms / 1000);

        assets.push(AssetItem {
            value: val,
            start_sec,
            archived,
            delete_sec,
        });
    }

    // 4. Sample timestamps across last 10 years
    let mut timestamp_set = std::collections::BTreeSet::new();

    // Monthly anchors
    let mut cur_month = ten_years_ago;
    while cur_month <= today {
        if let Some(dt) = cur_month.and_hms_opt(0, 0, 0) {
            timestamp_set.insert(dt.and_utc().timestamp());
        }
        let (y, m) = if cur_month.month() == 12 {
            (cur_month.year() + 1, 1)
        } else {
            (cur_month.year(), cur_month.month() + 1)
        };
        cur_month = chrono::NaiveDate::from_ymd_opt(y, m, 1).unwrap_or(today + chrono::Duration::days(1));
    }

    // Balance timestamps
    for list in account_balances_map.values() {
        for &(t, _) in list {
            if t >= ten_years_ago_sec && t <= now_sec {
                timestamp_set.insert(t);
            }
        }
    }

    // Asset timestamps
    for a in &assets {
        if let Some(s) = a.start_sec {
            if s >= ten_years_ago_sec && s <= now_sec {
                if s - 86400 >= ten_years_ago_sec {
                    timestamp_set.insert(s - 86400);
                }
                timestamp_set.insert(s);
            }
        }
        if a.archived {
            if let Some(del) = a.delete_sec {
                if del >= ten_years_ago_sec && del <= now_sec {
                    if del - 86400 >= ten_years_ago_sec {
                        timestamp_set.insert(del - 86400);
                    }
                    timestamp_set.insert(del);
                }
            }
        }
    }

    timestamp_set.insert(now_sec);

    // 5. Coalesce by day
    let mut date_to_latest_ts = std::collections::BTreeMap::new();
    for &ts in &timestamp_set {
        if let Some(dt) = Utc.timestamp_opt(ts, 0).single() {
            let d = dt.date_naive();
            date_to_latest_ts.insert(d, ts);
        }
    }

    // 6. Compute points
    let mut points = Vec::new();
    for (d, ts) in date_to_latest_ts {
        let mut pensions_sum = 0.0;
        for &pid in &pension_acc_ids {
            if let Some(b_list) = account_balances_map.get(&pid) {
                let latest_val = b_list.iter().filter(|(t, _)| *t <= ts).map(|(_, v)| *v).last();
                if let Some(v) = latest_val {
                    pensions_sum += v.max(0.0);
                }
            }
        }

        let mut cash_sum = 0.0;
        for &cid in &cash_savings_acc_ids {
            if let Some(b_list) = account_balances_map.get(&cid) {
                let latest_val = b_list.iter().filter(|(t, _)| *t <= ts).map(|(_, v)| *v).last();
                if let Some(v) = latest_val {
                    cash_sum += v;
                }
            }
        }

        let mut fixed_sum = 0.0;
        for a in &assets {
            if let Some(s) = a.start_sec {
                if ts < s {
                    continue;
                }
            }
            if a.archived {
                if let Some(del) = a.delete_sec {
                    if ts >= del {
                        continue;
                    }
                }
            }
            fixed_sum += a.value.max(0.0);
        }

        let pensions = round2(pensions_sum);
        let cash_savings = round2(cash_sum);
        let fixed_assets = round2(fixed_sum);
        let total = round2(pensions + cash_savings + fixed_assets);
        let date_str = d.format("%-d %b %Y").to_string();

        points.push(NetWorthHistoryPoint {
            timestamp: ts,
            date_str,
            pensions,
            cash_savings,
            fixed_assets,
            total,
        });
    }

    Ok(points)
}

pub async fn get_net_worth_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<NetWorthQuery>,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    let vm = calculate_net_worth(&state.pool, &resolved)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let history_points = calculate_history_points(&state.pool, &resolved)
        .await
        .unwrap_or_default();
    let chart_history_json = serde_json::to_string(&history_points).unwrap_or_else(|_| "[]".to_string());

    let tmpl = NetWorthTemplate {
        vm,
        chart_history_json,
    };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_net_worth_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<NetWorthQuery>,
) -> Result<Json<NetWorthDto>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    calculate_net_worth(&state.pool, &resolved)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

#[derive(Debug, Deserialize)]
pub struct AddAssetForm {
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
    pub name: String,
    pub category: Option<String>,
    pub value: f64,
    pub notes: Option<String>,
    #[serde(alias = "acquiredDate")]
    pub acquired_date: Option<String>,
}

pub async fn add_asset_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(form): Json<AddAssetForm>,
) -> Result<Json<NetWorthAssetDto>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, form.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let now_ms = Utc::now().timestamp_millis();
    let category = form.category.unwrap_or_else(|| "OTHER".to_string());

    let acq_at = if let Some(ref d) = form.acquired_date {
        chrono::NaiveDate::parse_from_str(d, "%Y-%m-%d")
            .ok()
            .and_then(|nd| nd.and_hms_opt(0, 0, 0))
            .map(|dt| Utc.from_utc_datetime(&dt).timestamp_millis())
            .unwrap_or(now_ms)
    } else {
        now_ms
    };

    let row = sqlx::query(
        r#"
        INSERT INTO net_worth_asset (
            id, userid, user_id, "userId", name, category, asset_value, notes,
            archived, acquired_at, created_at, updated_at
        ) VALUES (
            nextval('hibernate_sequence'), $1, $1, $1, $2, $3, $4, $5,
            false, $6, $7, $7
        )
        RETURNING id, name, category, asset_value, notes, archived, acquired_at
        "#,
    )
    .bind(&resolved)
    .bind(&form.name)
    .bind(&category)
    .bind(form.value)
    .bind(&form.notes)
    .bind(acq_at)
    .bind(now_ms)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let id: i64 = row.get("id");
    let name: String = row.get("name");
    let cat: String = row.get("category");
    let val: f64 = row.get("asset_value");
    let notes: Option<String> = row.get("notes");
    let (cat_label, cat_icon) = category_info(&cat);

    let dt = Utc.timestamp_millis_opt(acq_at).single().unwrap_or_else(Utc::now);

    Ok(Json(NetWorthAssetDto {
        id,
        name,
        category: cat,
        category_human_readable: cat_label.to_string(),
        category_icon: cat_icon.to_string(),
        value: round2(val),
        notes,
        acquired_at: Some(acq_at),
        acquired_date_formatted: dt.format("%-d %b %Y").to_string(),
        acquired_date_html_value: dt.format("%Y-%m-%d").to_string(),
        archived: false,
    }))
}

pub async fn add_asset_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Form(form): axum::extract::Form<AddAssetForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let _ = add_asset_api(State(state), headers, Json(form)).await?;
    Ok(Redirect::to("/netWorth"))
}

pub async fn update_asset_endpoint(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    axum::extract::Form(form): axum::extract::Form<AddAssetForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, form.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let now_ms = Utc::now().timestamp_millis();
    let category = form.category.unwrap_or_else(|| "OTHER".to_string());
    let acq_at = if let Some(ref d) = form.acquired_date {
        chrono::NaiveDate::parse_from_str(d, "%Y-%m-%d")
            .ok()
            .and_then(|nd| nd.and_hms_opt(0, 0, 0))
            .map(|dt| Utc.from_utc_datetime(&dt).timestamp_millis())
    } else {
        None
    };

    let res = sqlx::query(
        r#"
        UPDATE net_worth_asset
        SET name = $1, category = $2, asset_value = $3, notes = $4,
            acquired_at = COALESCE($5, acquired_at), updated_at = $6
        WHERE id = $7 AND (userid = $8 OR user_id = $8)
        "#,
    )
    .bind(&form.name)
    .bind(&category)
    .bind(form.value)
    .bind(&form.notes)
    .bind(acq_at)
    .bind(now_ms)
    .bind(id)
    .bind(&resolved)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Net Worth Asset", id)?;

    Ok(Redirect::to("/netWorth"))
}

pub async fn bin_asset_endpoint(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    let now = Utc::now().timestamp_millis();
    let res = sqlx::query(
        "UPDATE net_worth_asset SET archived = true, updated_at = $1, deleted_at = $1 WHERE id = $2 AND (userid = $3 OR user_id = $3)",
    )
    .bind(now)
    .bind(id)
    .bind(&resolved)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Net Worth Asset", id)?;

    Ok(Redirect::to("/netWorth"))
}
