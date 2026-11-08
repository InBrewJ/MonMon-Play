use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::{Html, Json, Redirect},
};
use chrono::{TimeZone, Utc};
use serde::{Deserialize, Serialize};
use sqlx::Row;
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct NetWorthQuery {
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
}

impl NetWorthQuery {
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
        is_debt_free,
        custom_assets,
        pension_accounts,
        cash_accounts,
        credit_accounts,
        loan_accounts,
    })
}

pub async fn get_net_worth_page(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, "demo").await;
    let vm = calculate_net_worth(&state.pool, &resolved)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let tmpl = NetWorthTemplate { vm };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_net_worth_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<NetWorthQuery>,
) -> Result<Json<NetWorthDto>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;
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
    Json(form): Json<AddAssetForm>,
) -> Result<Json<NetWorthAssetDto>, (StatusCode, String)> {
    let raw_uid = form.user_id.unwrap_or_else(|| "demo".to_string());
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
    axum::extract::Form(form): axum::extract::Form<AddAssetForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let _ = add_asset_api(State(state), Json(form)).await?;
    Ok(Redirect::to("/netWorth"))
}

pub async fn bin_asset_endpoint(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let now = Utc::now().timestamp_millis();
    sqlx::query(
        "UPDATE net_worth_asset SET archived = true, updated_at = $1, deleted_at = $1 WHERE id = $2",
    )
    .bind(now)
    .bind(id)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/netWorth"))
}
