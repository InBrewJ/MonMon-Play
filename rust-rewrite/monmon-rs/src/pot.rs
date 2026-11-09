use crate::spog::{calculate_spog, resolve_user_id};
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Path, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{Html, IntoResponse, Redirect, Response},
};
use serde::{Deserialize, Serialize};
use sqlx::Row;
use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PotAccountDto {
    pub id: i64,
    pub name: String,
    pub account_type: String,
    pub latest_balance: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SpendingPlanSectionDto {
    pub heading: String,
    pub amount: f32,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PotDto {
    pub id: i64,
    pub pot_type: String,
    pub created_at_human: String,
    pub spending_plan_name: Option<String>,
    pub spending_plan_food: Option<f32>,
    pub spending_plan_fuel: Option<f32>,
    pub spending_plan_transport: Option<f32>,
    pub spending_plan_fun: Option<f32>,
    pub sections: Vec<SpendingPlanSectionDto>,
    pub accounts: Vec<PotAccountDto>,
    pub pot_total_balance: f32,
    pub food_account_name: Option<String>,
    pub fun_account_name: Option<String>,
    pub fuel_account_name: Option<String>,
    pub transport_account_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomPlanDto {
    pub id: i64,
    pub name: String,
    pub sections_json: String,
}

#[derive(Template)]
#[template(path = "pots.html")]
pub struct MonthlyPotTemplate {
    pub all_accounts: Vec<PotAccountDto>,
    pub active_pot: Option<PotDto>,
    pub archived_pots: Vec<PotDto>,
    pub surplus: f32,
    pub spent_this_month: f32,
    pub saved_custom_plans: Vec<CustomPlanDto>,
    pub monthly_pot_total_balance: f32,
}

fn deserialize_single_or_vec_i64<'de, D>(deserializer: D) -> Result<Option<Vec<i64>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Helper {
        Seq(Vec<i64>),
        SeqStr(Vec<String>),
        Single(i64),
        SingleStr(String),
    }

    let opt = Option::<Helper>::deserialize(deserializer)?;
    match opt {
        None => Ok(None),
        Some(Helper::Seq(v)) => Ok(Some(v)),
        Some(Helper::SeqStr(v)) => {
            let parsed = v.into_iter().filter_map(|s| s.trim().parse::<i64>().ok()).collect();
            Ok(Some(parsed))
        }
        Some(Helper::Single(i)) => Ok(Some(vec![i])),
        Some(Helper::SingleStr(s)) => {
            let trimmed = s.trim();
            if trimmed.is_empty() {
                Ok(None)
            } else {
                let parsed = trimmed
                    .split(',')
                    .filter_map(|p| p.trim().parse::<i64>().ok())
                    .collect();
                Ok(Some(parsed))
            }
        }
    }
}

fn deserialize_empty_as_none_i64<'de, D>(deserializer: D) -> Result<Option<i64>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum NumOrStr {
        Num(i64),
        Str(String),
    }

    let opt = Option::<NumOrStr>::deserialize(deserializer)?;
    match opt {
        None => Ok(None),
        Some(NumOrStr::Num(i)) => Ok(Some(i)),
        Some(NumOrStr::Str(s)) => {
            let trimmed = s.trim();
            if trimmed.is_empty() {
                Ok(None)
            } else {
                trimmed.parse::<i64>().map(Some).map_err(serde::de::Error::custom)
            }
        }
    }
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct PotFormPayload {
    #[serde(default, deserialize_with = "deserialize_single_or_vec_i64")]
    pub accounts: Option<Vec<i64>>,
    pub preset: Option<String>,
    pub plan_name: Option<String>,
    pub sections_json: Option<String>,
    #[serde(default, alias = "foodAccountId", deserialize_with = "deserialize_empty_as_none_i64")]
    pub food_account_id: Option<i64>,
    #[serde(default, alias = "funAccountId", deserialize_with = "deserialize_empty_as_none_i64")]
    pub fun_account_id: Option<i64>,
    #[serde(default, alias = "fuelAccountId", deserialize_with = "deserialize_empty_as_none_i64")]
    pub fuel_account_id: Option<i64>,
    #[serde(default, alias = "transportAccountId", deserialize_with = "deserialize_empty_as_none_i64")]
    pub transport_account_id: Option<i64>,
    pub save_template: Option<String>,
}

impl PotFormPayload {
    pub fn from_form_body(body: &str) -> Self {
        let trimmed = body.trim();
        if trimmed.starts_with('{') {
            if let Ok(p) = serde_json::from_str::<PotFormPayload>(trimmed) {
                return p;
            }
        }

        let mut payload = PotFormPayload::default();
        let mut accounts = Vec::new();

        for (key, val) in form_urlencoded::parse(body.as_bytes()) {
            let key = key.as_ref();
            let val = val.trim();
            match key {
                "accounts" | "accounts[]" => {
                    for part in val.split(',') {
                        let part = part.trim();
                        if let Ok(id) = part.parse::<i64>() {
                            accounts.push(id);
                        }
                    }
                }
                "preset" => {
                    if !val.is_empty() {
                        payload.preset = Some(val.to_string());
                    }
                }
                "plan_name" | "planName" => {
                    if !val.is_empty() {
                        payload.plan_name = Some(val.to_string());
                    }
                }
                "sections_json" | "sectionsJson" => {
                    if !val.is_empty() {
                        payload.sections_json = Some(val.to_string());
                    }
                }
                "food_account_id" | "foodAccountId" => {
                    payload.food_account_id = val.parse::<i64>().ok();
                }
                "fun_account_id" | "funAccountId" => {
                    payload.fun_account_id = val.parse::<i64>().ok();
                }
                "fuel_account_id" | "fuelAccountId" => {
                    payload.fuel_account_id = val.parse::<i64>().ok();
                }
                "transport_account_id" | "transportAccountId" => {
                    payload.transport_account_id = val.parse::<i64>().ok();
                }
                "save_template" | "saveTemplate" => {
                    if !val.is_empty() {
                        payload.save_template = Some(val.to_string());
                    }
                }
                _ => {}
            }
        }

        if !accounts.is_empty() {
            payload.accounts = Some(accounts);
        }

        payload
    }
}

pub fn get_user_id_from_headers_or_default(headers: &HeaderMap, query_uid: Option<&str>) -> String {
    if let Some(uid) = query_uid {
        if !uid.trim().is_empty() {
            return uid.trim().to_string();
        }
    }
    if let Some(cookie_hdr) = headers.get(header::COOKIE) {
        if let Ok(cookie_str) = cookie_hdr.to_str() {
            for part in cookie_str.split(';') {
                let part = part.trim();
                if let Some(val) = part.strip_prefix("user_id=") {
                    let v = val.trim();
                    if !v.is_empty() {
                        return v.to_string();
                    }
                }
                if let Some(val) = part.strip_prefix("monmon_session=") {
                    let v = val.trim();
                    if !v.is_empty() && v != "authenticated" {
                        return v.to_string();
                    }
                }
            }
        }
    }
    "demo".to_string()
}

pub async fn get_monthly_pot_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Response, (StatusCode, String)> {
    if !crate::management::is_authenticated(&headers)
        && !query.contains_key("userId")
        && !query.contains_key("user_id")
    {
        return Ok(crate::landing::auth_redirect(Some("/myMonthlyPot")).await.into_response());
    }

    let user_id = get_user_id_from_headers_or_default(
        &headers,
        query.get("userId").or_else(|| query.get("user_id")).map(|s| s.as_str()),
    );
    let resolved = resolve_user_id(&state.pool, &user_id).await;

    // 1. Load all accounts with their latest balances
    let acc_rows = sqlx::query(
        r#"
        WITH latest_b AS (
            SELECT DISTINCT ON (account_id) account_id, value
            FROM balance
            ORDER BY account_id, timestamp DESC
        )
        SELECT a.id, a.name, a.type, COALESCE(b.value, 0.0)::real AS latest_balance
        FROM account a
        LEFT JOIN latest_b b ON b.account_id = a.id
        WHERE (a.userid = $1 OR a.user_id = $1)
          AND (a.archived IS NULL OR a.archived = false)
        ORDER BY a.name ASC
        "#,
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let mut all_accounts = Vec::new();
    let mut acc_map = std::collections::HashMap::new();
    for r in acc_rows {
        let id: i64 = r.get("id");
        let name: String = r.get("name");
        let account_type: String = r.get("type");
        let latest_balance: f32 = r.get("latest_balance");
        let dto = PotAccountDto {
            id,
            name: name.clone(),
            account_type,
            latest_balance,
        };
        acc_map.insert(id, name);
        all_accounts.push(dto);
    }

    // 2. Load pots
    let pot_rows = sqlx::query(
        r#"
        SELECT p.id, p.type, p.createdat, p.archived,
               p.spending_plan_name, p.spending_plan_food, p.spending_plan_fuel,
               p.spending_plan_transport, p.spending_plan_fun, p.spending_plan_sections_json,
               p.food_account_id, p.fun_account_id, p.fuel_account_id, p.transport_account_id
        FROM pot p
        WHERE (p.userid = $1 OR p.user_id = $1)
        ORDER BY p.createdat DESC
        "#,
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let mut active_pot = None;
    let mut archived_pots = Vec::new();

    for r in pot_rows {
        let id: i64 = r.get("id");
        let p_type: String = r.get("type");
        let created_at: i64 = r.get("createdat");
        let archived: bool = r.get("archived");
        let sp_name: Option<String> = r.get("spending_plan_name");
        let sp_food: Option<f32> = r.get("spending_plan_food");
        let sp_fuel: Option<f32> = r.get("spending_plan_fuel");
        let sp_trans: Option<f32> = r.get("spending_plan_transport");
        let sp_fun: Option<f32> = r.get("spending_plan_fun");
        let sp_sections_json: Option<String> = r.get("spending_plan_sections_json");
        let food_acc_id: Option<i64> = r.get("food_account_id");
        let fun_acc_id: Option<i64> = r.get("fun_account_id");
        let fuel_acc_id: Option<i64> = r.get("fuel_account_id");
        let trans_acc_id: Option<i64> = r.get("transport_account_id");

        // Load linked accounts
        let linked_acc_rows = sqlx::query(
            r#"
            WITH latest_b AS (
                SELECT DISTINCT ON (account_id) account_id, value
                FROM balance
                ORDER BY account_id, timestamp DESC
            )
            SELECT a.id, a.name, a.type, COALESCE(b.value, 0.0)::real AS latest_balance
            FROM pot_accounts pa
            JOIN account a ON a.id = pa.account_id
            LEFT JOIN latest_b b ON b.account_id = a.id
            WHERE pa.pot_id = $1
            ORDER BY a.name ASC
            "#,
        )
        .bind(id)
        .fetch_all(&state.pool)
        .await
        .unwrap_or_default();

        let mut pot_accs = Vec::new();
        let mut pot_sum = 0.0f32;
        for ar in linked_acc_rows {
            let aid: i64 = ar.get("id");
            let aname: String = ar.get("name");
            let atype: String = ar.get("type");
            let abal: f32 = ar.get("latest_balance");
            pot_sum += abal;
            pot_accs.push(PotAccountDto {
                id: aid,
                name: aname,
                account_type: atype,
                latest_balance: abal,
            });
        }

        let mut sections = Vec::new();
        if let Some(ref j) = sp_sections_json {
            if let Ok(parsed) = serde_json::from_str::<Vec<serde_json::Value>>(j) {
                for s in parsed {
                    let heading = s.get("heading").and_then(|v| v.as_str()).unwrap_or("").to_string();
                    let amount = s.get("amount").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
                    let acc_id = s.get("accountId").and_then(|v| v.as_i64());
                    let acc_name = acc_id.and_then(|aid| acc_map.get(&aid).cloned());
                    sections.push(SpendingPlanSectionDto {
                        heading,
                        amount,
                        account_id: acc_id,
                        account_name: acc_name,
                    });
                }
            }
        }
        if sections.is_empty() {
            if let Some(f) = sp_food {
                if f > 0.0 {
                    sections.push(SpendingPlanSectionDto {
                        heading: "Food".to_string(),
                        amount: f,
                        account_id: food_acc_id,
                        account_name: food_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
                    });
                }
            }
            if let Some(f) = sp_fuel {
                if f > 0.0 {
                    sections.push(SpendingPlanSectionDto {
                        heading: "Fuel".to_string(),
                        amount: f,
                        account_id: fuel_acc_id,
                        account_name: fuel_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
                    });
                }
            }
            if let Some(t) = sp_trans {
                if t > 0.0 {
                    sections.push(SpendingPlanSectionDto {
                        heading: "Transport".to_string(),
                        amount: t,
                        account_id: trans_acc_id,
                        account_name: trans_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
                    });
                }
            }
        }

        let created_ts = if created_at > 100_000_000_000 {
            created_at / 1000
        } else {
            created_at
        };
        let created_dt = chrono::DateTime::from_timestamp(created_ts, 0)
            .map(|dt| dt.format("%d %b %Y, %H:%M").to_string())
            .unwrap_or_else(|| "Recently".to_string());

        let dto = PotDto {
            id,
            pot_type: p_type,
            created_at_human: created_dt,
            spending_plan_name: sp_name,
            spending_plan_food: sp_food,
            spending_plan_fuel: sp_fuel,
            spending_plan_transport: sp_trans,
            spending_plan_fun: sp_fun,
            sections,
            accounts: pot_accs,
            pot_total_balance: pot_sum,
            food_account_name: food_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
            fun_account_name: fun_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
            fuel_account_name: fuel_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
            transport_account_name: trans_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
        };

        if !archived && active_pot.is_none() {
            active_pot = Some(dto);
        } else {
            archived_pots.push(dto);
        }
    }

    // 3. Load saved custom spending plan templates
    let custom_rows = sqlx::query(
        "SELECT id, name, COALESCE(sectionsjson, sections_json, '[]') AS sec_json FROM custom_spending_plan WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false)",
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let mut saved_custom_plans = Vec::new();
    for cr in custom_rows {
        let cid: i64 = cr.get("id");
        let cname: String = cr.get("name");
        let cjson: String = cr.get("sec_json");
        saved_custom_plans.push(CustomPlanDto {
            id: cid,
            name: cname,
            sections_json: cjson,
        });
    }

    // Calculate metrics & pace
    let spog_metrics = calculate_spog(&state.pool, &user_id).await.unwrap_or_else(|_| crate::spog::SpogMetrics {
        incoming_total: crate::money::Pence::ZERO,
        outgoing_total: crate::money::Pence::ZERO,
        surplus: crate::money::Pence::ZERO,
        next_pay_day: 1,
        next_pay_date: "".to_string(),
        days_between_paydays: 30,
        max_per_day: crate::money::Pence::ZERO,
        max_per_week: crate::money::Pence::ZERO,
        max_per_month: crate::money::Pence::ZERO,
        completed_outgoings: crate::money::Pence::ZERO,
        pending_outgoings: crate::money::Pence::ZERO,
        bills_cost: crate::money::Pence::ZERO,
        rent_cost: crate::money::Pence::ZERO,
        monthly_pot_left_per_day: crate::money::Pence::ZERO,
        baseline_diff_total: crate::money::Pence::ZERO,
        is_behind_baseline: false,
        absolute_baseline_diff_total: crate::money::Pence::ZERO,
        total_available_debit: crate::money::Pence::ZERO,
        total_available_credit: crate::money::Pence::ZERO,
        credit_balance: crate::money::Pence::ZERO,
        credit_limit: crate::money::Pence::ZERO,
        credit_usage: crate::money::Pence::ZERO,
        liquid_savings_pot: crate::money::Pence::ZERO,
        total_savings_pot: crate::money::Pence::ZERO,
        account_groups: vec![],
        spending_plan: None,
        spent_this_month: crate::money::Pence::ZERO,
        days_until_next_payday: 30,
        days_since_last_payday: 1,
    });

    let pace = crate::spog::calculate_spending_pace(&state.pool, &user_id).await.unwrap_or_else(|_| crate::spog::SpendingPaceMetrics {
        spent_this_month: crate::money::Pence::ZERO,
        actual_daily_spend: crate::money::Pence::ZERO,
        daily_overspend: crate::money::Pence::ZERO,
        is_overspent_daily: false,
        absolute_daily_overspend: crate::money::Pence::ZERO,
        baseline_diff_total: crate::money::Pence::ZERO,
        is_behind_baseline: false,
        absolute_baseline_diff_total: crate::money::Pence::ZERO,
        days_since_last_payday: 1,
    });

    let monthly_pot_total_balance = active_pot.as_ref().map(|p| p.pot_total_balance).unwrap_or(0.0);

    let tmpl = MonthlyPotTemplate {
        all_accounts,
        active_pot,
        archived_pots,
        surplus: spog_metrics.surplus.to_pounds_f32(),
        spent_this_month: pace.spent_this_month.to_pounds_f32(),
        saved_custom_plans,
        monthly_pot_total_balance,
    };

    tmpl.render()
        .map(|h| Html(h).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_pot_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: String,
) -> Result<Redirect, (StatusCode, String)> {
    let payload = PotFormPayload::from_form_body(&body);
    let user_id = get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &user_id).await;
    let now = chrono::Utc::now().timestamp();

    // 1. Archive previous active monthly pots
    let _ = sqlx::query("UPDATE pot SET archived = true WHERE (userid = $1 OR user_id = $1) AND type = 'MONTHLY'")
        .bind(&resolved)
        .execute(&state.pool)
        .await;

    // 2. Parse spending plan fields
    let preset = payload.preset.unwrap_or_else(|| "none".to_string());
    let mut sp_name: Option<String> = None;
    let mut sp_food: Option<f32> = None;
    let mut sp_fuel: Option<f32> = None;
    let mut sp_trans: Option<f32> = None;
    let mut sp_fun: Option<f32> = None;
    let mut sp_sections_json: Option<String> = None;

    if preset == "gruelling" {
        sp_name = Some("Gruelling".to_string());
        sp_food = Some(216.67);
        sp_fuel = Some(0.0);
        sp_trans = Some(0.0);
        sp_sections_json = Some(r#"[{"heading":"Food","amount":216.67,"accountId":null}]"#.to_string());
    } else if preset == "custom" || preset.starts_with("saved_") {
        sp_name = payload.plan_name.filter(|s| !s.trim().is_empty()).or_else(|| Some("Custom Plan".to_string()));
        sp_sections_json = payload.sections_json.filter(|s| !s.trim().is_empty());
        if let Some(ref sec_str) = sp_sections_json {
            if let Ok(parsed) = serde_json::from_str::<Vec<serde_json::Value>>(sec_str) {
                for s in parsed {
                    let h = s.get("heading").and_then(|v| v.as_str()).unwrap_or("").to_lowercase();
                    let amt = s.get("amount").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
                    if h == "food" {
                        sp_food = Some(amt);
                    } else if h == "fuel" {
                        sp_fuel = Some(amt);
                    } else if h == "transport" {
                        sp_trans = Some(amt);
                    } else if h == "fun" {
                        sp_fun = Some(amt);
                    }
                }
            }
        }
    }

    let food_id = payload.food_account_id.filter(|&id| id > 0);
    let fun_id = payload.fun_account_id.filter(|&id| id > 0);
    let fuel_id = payload.fuel_account_id.filter(|&id| id > 0);
    let trans_id = payload.transport_account_id.filter(|&id| id > 0);

    // Insert new pot
    let row = sqlx::query(
        r#"
        INSERT INTO pot (
            id, userid, user_id, "userId", type, createdat, lastupdated, archived,
            spending_plan_name, spending_plan_food, spending_plan_fuel, spending_plan_transport, spending_plan_fun,
            spending_plan_sections_json, food_account_id, fun_account_id, fuel_account_id, transport_account_id
        )
        VALUES (
            nextval('hibernate_sequence'), $1, $1, $1, 'MONTHLY', $2, $2, false,
            $3, $4, $5, $6, $7, $8, $9, $10, $11, $12
        )
        RETURNING id
        "#,
    )
    .bind(&resolved)
    .bind(now)
    .bind(sp_name.as_deref())
    .bind(sp_food)
    .bind(sp_fuel)
    .bind(sp_trans)
    .bind(sp_fun)
    .bind(sp_sections_json.as_deref())
    .bind(food_id)
    .bind(fun_id)
    .bind(fuel_id)
    .bind(trans_id)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let pot_id: i64 = row.get("id");

    // Link accounts in pot_accounts
    if let Some(account_ids) = payload.accounts {
        for aid in account_ids {
            let _ = sqlx::query("INSERT INTO pot_accounts (pot_id, account_id) VALUES ($1, $2)")
                .bind(pot_id)
                .bind(aid)
                .execute(&state.pool)
                .await;
        }
    }

    // Save custom plan template if requested
    if payload.save_template.as_deref() == Some("true") || payload.save_template.as_deref() == Some("on") {
        if let (Some(ref name), Some(ref json)) = (&sp_name, &sp_sections_json) {
            let _ = sqlx::query(
                r#"
                INSERT INTO custom_spending_plan (
                    id, userid, user_id, name, sectionsjson, sections_json, createdat, lastupdated, archived
                )
                VALUES (nextval('hibernate_sequence'), $1, $1, $2, $3, $3, $4, $4, false)
                "#,
            )
            .bind(&resolved)
            .bind(name)
            .bind(json)
            .bind(now)
            .execute(&state.pool)
            .await;
        }
    }

    Ok(Redirect::to("/myMonthlyPot"))
}

pub async fn post_apply_gruelling(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &user_id).await;
    let now = chrono::Utc::now().timestamp();

    // Find currently active pot's accounts to retain them
    let active_pot_id: Option<i64> = sqlx::query_scalar(
        "SELECT id FROM pot WHERE (userid = $1 OR user_id = $1) AND type = 'MONTHLY' AND (archived IS NULL OR archived = false) LIMIT 1",
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    let mut account_ids = Vec::new();
    if let Some(pid) = active_pot_id {
        let aids: Vec<i64> = sqlx::query_scalar("SELECT account_id FROM pot_accounts WHERE pot_id = $1")
            .bind(pid)
            .fetch_all(&state.pool)
            .await
            .unwrap_or_default();
        account_ids = aids;
    }

    // Archive current monthly pots
    let _ = sqlx::query("UPDATE pot SET archived = true WHERE (userid = $1 OR user_id = $1) AND type = 'MONTHLY'")
        .bind(&resolved)
        .execute(&state.pool)
        .await;

    // Insert new pot with Gruelling preset
    let row = sqlx::query(
        r#"
        INSERT INTO pot (
            id, userid, user_id, "userId", type, createdat, lastupdated, archived,
            spending_plan_name, spending_plan_food, spending_plan_fuel, spending_plan_transport, spending_plan_fun,
            spending_plan_sections_json
        )
        VALUES (
            nextval('hibernate_sequence'), $1, $1, $1, 'MONTHLY', $2, $2, false,
            'Gruelling', 216.67, 0.0, 0.0, NULL, '[{"heading":"Food","amount":216.67,"accountId":null}]'
        )
        RETURNING id
        "#,
    )
    .bind(&resolved)
    .bind(now)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let new_pot_id: i64 = row.get("id");

    for aid in account_ids {
        let _ = sqlx::query("INSERT INTO pot_accounts (pot_id, account_id) VALUES ($1, $2)")
            .bind(new_pot_id)
            .bind(aid)
            .execute(&state.pool)
            .await;
    }

    Ok(Redirect::to("/myMonthlyPot"))
}

pub async fn get_bin_pot(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    headers: HeaderMap,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let res = sqlx::query("UPDATE pot SET archived = true WHERE id = $1 AND (userid = $2 OR user_id = $2)")
        .bind(id)
        .bind(&user_id)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Pot", id)?;

    Ok(Redirect::to("/myMonthlyPot"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_single_account_string_deserialization() {
        // User's exact error case: "invalid type: string \"18\", expected a sequence"
        let body = "accounts=18&preset=custom&plan_name=Essentials&food_account_id=5&fun_account_id=";
        let payload = PotFormPayload::from_form_body(body);
        assert_eq!(payload.accounts, Some(vec![18]));
        assert_eq!(payload.preset.as_deref(), Some("custom"));
        assert_eq!(payload.plan_name.as_deref(), Some("Essentials"));
        assert_eq!(payload.food_account_id, Some(5));
        assert_eq!(payload.fun_account_id, None);
    }

    #[test]
    fn test_multiple_accounts_deserialization() {
        let body = "accounts=18&accounts=19&accounts=20&preset=gruelling";
        let payload = PotFormPayload::from_form_body(body);
        assert_eq!(payload.accounts, Some(vec![18, 19, 20]));
        assert_eq!(payload.preset.as_deref(), Some("gruelling"));
    }

    #[test]
    fn test_comma_separated_accounts_deserialization() {
        let body = "accounts=18%2C19%2C20&preset=custom";
        let payload = PotFormPayload::from_form_body(body);
        assert_eq!(payload.accounts, Some(vec![18, 19, 20]));
    }

    #[test]
    fn test_empty_accounts_and_selects() {
        let body = "preset=none&food_account_id=&fuel_account_id=";
        let payload = PotFormPayload::from_form_body(body);
        assert_eq!(payload.accounts, None);
        assert_eq!(payload.food_account_id, None);
        assert_eq!(payload.fuel_account_id, None);
    }

    #[test]
    fn test_json_payload_compatibility() {
        let json = r#"{"accounts":[18,19],"preset":"custom","plan_name":"My Plan","food_account_id":18}"#;
        let payload = PotFormPayload::from_form_body(json);
        assert_eq!(payload.accounts, Some(vec![18, 19]));
        assert_eq!(payload.plan_name.as_deref(), Some("My Plan"));
        assert_eq!(payload.food_account_id, Some(18));
    }
}
