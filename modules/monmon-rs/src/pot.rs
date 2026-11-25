use crate::money::Pence;
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AllowanceComparison {
    pub planned_total: Pence,
    pub max_allowance: Pence,
    pub difference: Pence,
    pub is_over_allowance: bool,
    pub absolute_difference: Pence,
}

pub fn parse_sections_json(sections_json: Option<&str>) -> Vec<(String, Pence, Option<i64>)> {
    let Some(raw) = sections_json else {
        return Vec::new();
    };
    let Ok(parsed) = serde_json::from_str::<Vec<serde_json::Value>>(raw) else {
        return Vec::new();
    };
    let mut out = Vec::with_capacity(parsed.len());
    for s in parsed {
        let heading = s
            .get("heading")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let amount_f64 = s
            .get("amount")
            .and_then(|v| {
                v.as_f64()
                    .or_else(|| v.as_str().and_then(|st| st.trim().parse::<f64>().ok()))
            })
            .filter(|n| n.is_finite())
            .map(|n| n.max(0.0))
            .unwrap_or(0.0);
        let amount = Pence::from_pounds_f64(amount_f64);
        let acc_id = s.get("accountId").and_then(|v| v.as_i64());
        out.push((heading, amount, acc_id));
    }
    out
}

pub fn parse_sections_total_pence(
    sections_json: Option<&str>,
    legacy_food: Option<f32>,
    legacy_fuel: Option<f32>,
    legacy_transport: Option<f32>,
) -> Pence {
    let parsed = parse_sections_json(sections_json);
    if !parsed.is_empty() {
        return parsed.into_iter().map(|(_, amt, _)| amt).sum();
    }
    let mut total = Pence::ZERO;
    for legacy in [legacy_food, legacy_fuel, legacy_transport].into_iter().flatten() {
        if legacy.is_finite() && legacy > 0.0 {
            total += Pence::from_pounds_f32(legacy);
        }
    }
    total
}

pub fn compare_to_allowance(planned_total: Pence, max_allowance: Pence) -> AllowanceComparison {
    let difference = max_allowance - planned_total;
    let is_over_allowance = difference.is_negative();
    let absolute_difference = difference.abs();
    AllowanceComparison {
        planned_total,
        max_allowance,
        difference,
        is_over_allowance,
        absolute_difference,
    }
}

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
    pub amount: Pence,
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
    pub sections_total: Pence,
    pub allowance_comparison: AllowanceComparison,
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
    pub sections_count: usize,
    pub sections_total: Pence,
    pub allowance_comparison: AllowanceComparison,
}

#[derive(Template)]
#[template(path = "pots.html")]
pub struct MonthlyPotTemplate {
    pub all_accounts: Vec<PotAccountDto>,
    pub active_pot: Option<PotDto>,
    pub archived_pots: Vec<PotDto>,
    pub surplus: Pence,
    #[allow(dead_code)]
    pub spent_this_month: f32,
    pub saved_custom_plans: Vec<CustomPlanDto>,
    #[allow(dead_code)]
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
    #[serde(default, alias = "customPlanId", deserialize_with = "deserialize_empty_as_none_i64")]
    pub custom_plan_id: Option<i64>,
    pub preset_save_mode: Option<String>,
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
                "custom_plan_id" | "customPlanId" => {
                    payload.custom_plan_id = val.parse::<i64>().ok();
                }
                "preset_save_mode" | "presetSaveMode" => {
                    if !val.is_empty() {
                        payload.preset_save_mode = Some(val.to_string());
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

    pub fn collect_all_pot_accounts(&self) -> std::collections::HashSet<i64> {
        let mut final_accounts: std::collections::HashSet<i64> = self.accounts.clone().unwrap_or_default().into_iter().collect();
        if let Some(fid) = self.food_account_id.filter(|&id| id > 0) {
            final_accounts.insert(fid);
        }
        if let Some(fid) = self.fun_account_id.filter(|&id| id > 0) {
            final_accounts.insert(fid);
        }
        if let Some(fid) = self.fuel_account_id.filter(|&id| id > 0) {
            final_accounts.insert(fid);
        }
        if let Some(tid) = self.transport_account_id.filter(|&id| id > 0) {
            final_accounts.insert(tid);
        }
        if let Some(ref s_json) = self.sections_json {
            if let Ok(sections) = serde_json::from_str::<Vec<serde_json::Value>>(s_json) {
                for sec in sections {
                    if let Some(aid) = sec.get("accountId").and_then(|v| v.as_i64()).filter(|&id| id > 0) {
                        final_accounts.insert(aid);
                    }
                }
            }
        }
        final_accounts
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
        SELECT a.id, a.name, a.type, COALESCE(b.value, 0.0)::real AS latest_balance
        FROM account a
        LEFT JOIN LATERAL (
            SELECT value
            FROM balance
            WHERE account_id = a.id
              AND (archived IS NULL OR archived = false)
            ORDER BY timestamp DESC, id DESC
            LIMIT 1
        ) b ON true
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

    // Calculate SPOG metrics up front so every pot and saved preset compares against current max monthly allowance
    let spog_metrics = calculate_spog(&state.pool, &user_id).await.unwrap_or_else(|_| crate::spog::SpogMetrics {
        incoming_total: crate::money::Pence::ZERO,
        outgoing_total: crate::money::Pence::ZERO,
        surplus: crate::money::Pence::ZERO,
        baseline: crate::money::Pence::ZERO,
        bills_only_baseline: crate::money::Pence::ZERO,
        savings_plan_total: crate::money::Pence::ZERO,
        savings_plan_items: vec![],
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
        is_payday_today: false,
        payday_transfers_count: 0,
        payday_transfers_total: crate::money::Pence::ZERO,
    });
    let surplus_pence = spog_metrics.surplus;

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
            SELECT a.id, a.name, a.type, COALESCE(b.value, 0.0)::real AS latest_balance
            FROM pot_accounts pa
            JOIN account a ON a.id = pa.account_id
            LEFT JOIN LATERAL (
                SELECT value
                FROM balance
                WHERE account_id = a.id
                  AND (archived IS NULL OR archived = false)
                ORDER BY timestamp DESC, id DESC
                LIMIT 1
            ) b ON true
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

        let mut sections: Vec<SpendingPlanSectionDto> = parse_sections_json(sp_sections_json.as_deref())
            .into_iter()
            .map(|(heading, amount, acc_id)| SpendingPlanSectionDto {
                heading,
                amount,
                account_id: acc_id,
                account_name: acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
            })
            .collect();
        if sections.is_empty() {
            if let Some(f) = sp_food {
                if f.is_finite() && f > 0.0 {
                    sections.push(SpendingPlanSectionDto {
                        heading: "Food".to_string(),
                        amount: Pence::from_pounds_f32(f),
                        account_id: food_acc_id,
                        account_name: food_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
                    });
                }
            }
            if let Some(f) = sp_fuel {
                if f.is_finite() && f > 0.0 {
                    sections.push(SpendingPlanSectionDto {
                        heading: "Fuel".to_string(),
                        amount: Pence::from_pounds_f32(f),
                        account_id: fuel_acc_id,
                        account_name: fuel_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
                    });
                }
            }
            if let Some(t) = sp_trans {
                if t.is_finite() && t > 0.0 {
                    sections.push(SpendingPlanSectionDto {
                        heading: "Transport".to_string(),
                        amount: Pence::from_pounds_f32(t),
                        account_id: trans_acc_id,
                        account_name: trans_acc_id.and_then(|aid| acc_map.get(&aid).cloned()),
                    });
                }
            }
        }
        let sections_total: Pence = sections.iter().map(|s| s.amount).sum();
        let allowance_comparison = compare_to_allowance(sections_total, surplus_pence);

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
            sections_total,
            allowance_comparison,
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
        "SELECT id, name, COALESCE(sectionsjson, sections_json, '[]') AS sec_json FROM custom_spending_plan WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false) ORDER BY name ASC",
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
        let parsed_sections = parse_sections_json(Some(&cjson));
        let sections_count = parsed_sections.len();
        let sections_total: Pence = parsed_sections.into_iter().map(|(_, amt, _)| amt).sum();
        let allowance_comparison = compare_to_allowance(sections_total, surplus_pence);
        saved_custom_plans.push(CustomPlanDto {
            id: cid,
            name: cname,
            sections_json: cjson,
            sections_count,
            sections_total,
            allowance_comparison,
        });
    }

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
        surplus: surplus_pence,
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
    let final_accounts = payload.collect_all_pot_accounts();
    let user_id = get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &user_id).await;
    let now = chrono::Utc::now().timestamp();
    let now_ms = chrono::Utc::now().timestamp_millis();

    // 1. Archive previous active monthly pots
    let _ = sqlx::query("UPDATE pot SET archived = true, archived_at = $1, updated_at = $1 WHERE (userid = $2 OR user_id = $2) AND type = 'MONTHLY' AND (archived IS NULL OR archived = false)")
        .bind(now_ms)
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
        sp_name = payload.plan_name.clone().filter(|s| !s.trim().is_empty()).or_else(|| Some("Custom Plan".to_string()));
        sp_sections_json = payload.sections_json.clone().filter(|s| !s.trim().is_empty());
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
            id, userid, user_id, "userId", type, createdat, lastupdated, created_at, updated_at, archived,
            spending_plan_name, spending_plan_food, spending_plan_fuel, spending_plan_transport, spending_plan_fun,
            spending_plan_sections_json, food_account_id, fun_account_id, fuel_account_id, transport_account_id
        )
        VALUES (
            nextval('hibernate_sequence'), $1, $1, $1, 'MONTHLY', $2, $2, $13, $13, false,
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
    .bind(now_ms)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let pot_id: i64 = row.get("id");

    // Link accounts in pot_accounts: include all checked accounts PLUS any accounts
    // nominated in category dropdowns (Food, Fun, Fuel, Transport) or custom plan sections.
    for aid in final_accounts {
        let _ = sqlx::query("INSERT INTO pot_accounts (pot_id, account_id, created_at, updated_at) VALUES ($1, $2, $3, $3)")
            .bind(pot_id)
            .bind(aid)
            .bind(now_ms)
            .execute(&state.pool)
            .await;
    }

    // Save or update custom plan template if requested
    let save_requested = payload.save_template.as_deref() == Some("true")
        || payload.save_template.as_deref() == Some("on")
        || payload.preset_save_mode.as_deref() == Some("update")
        || payload.preset_save_mode.as_deref() == Some("new");

    if save_requested {
        if let (Some(ref name), Some(ref json)) = (&sp_name, &sp_sections_json) {
            let is_update = payload.preset_save_mode.as_deref() == Some("update")
                || (payload.preset_save_mode.is_none() && payload.custom_plan_id.is_some());

            if is_update && payload.custom_plan_id.is_some() {
                if let Some(cid) = payload.custom_plan_id.filter(|&id| id > 0) {
                    let _ = sqlx::query(
                        r#"
                        UPDATE custom_spending_plan
                        SET name = $1, sectionsjson = $2, sections_json = $2, lastupdated = $3, last_updated = $3, updated_at = $4
                        WHERE id = $5 AND (userid = $6 OR user_id = $6 OR userid = $7 OR user_id = $7)
                        "#,
                    )
                    .bind(name)
                    .bind(json)
                    .bind(now)
                    .bind(now_ms)
                    .bind(cid)
                    .bind(&resolved)
                    .bind(&user_id)
                    .execute(&state.pool)
                    .await;
                }
            } else {
                let _ = sqlx::query(
                    r#"
                    INSERT INTO custom_spending_plan (
                        id, userid, user_id, name, sectionsjson, sections_json, createdat, lastupdated, created_at, updated_at, archived
                    )
                    VALUES (nextval('hibernate_sequence'), $1, $1, $2, $3, $3, $4, $4, $5, $5, false)
                    "#,
                )
                .bind(&resolved)
                .bind(name)
                .bind(json)
                .bind(now)
                .bind(now_ms)
                .execute(&state.pool)
                .await;
            }
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
    let now_ms = chrono::Utc::now().timestamp_millis();

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
    let _ = sqlx::query("UPDATE pot SET archived = true, archived_at = $1, updated_at = $1 WHERE (userid = $2 OR user_id = $2) AND type = 'MONTHLY' AND (archived IS NULL OR archived = false)")
        .bind(now_ms)
        .bind(&resolved)
        .execute(&state.pool)
        .await;

    // Insert new pot with Gruelling preset
    let row = sqlx::query(
        r#"
        INSERT INTO pot (
            id, userid, user_id, "userId", type, createdat, lastupdated, created_at, updated_at, archived,
            spending_plan_name, spending_plan_food, spending_plan_fuel, spending_plan_transport, spending_plan_fun,
            spending_plan_sections_json
        )
        VALUES (
            nextval('hibernate_sequence'), $1, $1, $1, 'MONTHLY', $2, $2, $3, $3, false,
            'Gruelling', 216.67, 0.0, 0.0, NULL, '[{"heading":"Food","amount":216.67,"accountId":null}]'
        )
        RETURNING id
        "#,
    )
    .bind(&resolved)
    .bind(now)
    .bind(now_ms)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let new_pot_id: i64 = row.get("id");

    for aid in account_ids {
        let _ = sqlx::query("INSERT INTO pot_accounts (pot_id, account_id, created_at, updated_at) VALUES ($1, $2, $3, $3)")
            .bind(new_pot_id)
            .bind(aid)
            .bind(now_ms)
            .execute(&state.pool)
            .await;
    }

    Ok(Redirect::to("/myMonthlyPot"))
}

pub async fn get_bin_pot(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let now_ms = chrono::Utc::now().timestamp_millis();
    let res = sqlx::query("UPDATE pot SET archived = true, archived_at = $1, updated_at = $1 WHERE id = $2 AND (userid = $3 OR user_id = $3)")
        .bind(now_ms)
        .bind(id)
        .bind(&user_id)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Pot", id)?;

    Ok(crate::mutation_redirect_or_json(&headers, "/myMonthlyPot"))
}

pub async fn get_bin_preset(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let now_ms = chrono::Utc::now().timestamp_millis();
    let res = sqlx::query(
        "UPDATE custom_spending_plan SET archived = true, archived_at = $1, updated_at = $1 WHERE id = $2 AND (userid = $3 OR user_id = $3 OR userid = $4 OR user_id = $4)"
    )
    .bind(now_ms)
    .bind(id)
    .bind(&user_id)
    .bind(&raw_uid)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "CustomSpendingPlan", id)?;

    Ok(crate::mutation_redirect_or_json(&headers, "/myMonthlyPot"))
}

#[derive(Debug, Deserialize)]
pub struct SavePresetPayload {
    pub id: Option<i64>,
    pub plan_name: Option<String>,
    pub sections_json: Option<String>,
}

pub async fn post_save_preset(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: String,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    let now = chrono::Utc::now().timestamp();
    let now_ms = chrono::Utc::now().timestamp_millis();

    let (id, name, sections_json) = if body.trim().starts_with('{') {
        let p: SavePresetPayload = serde_json::from_str(&body).map_err(|e| (StatusCode::BAD_REQUEST, e.to_string()))?;
        (p.id, p.plan_name.unwrap_or_else(|| "Custom Plan".to_string()), p.sections_json.unwrap_or_else(|| "[]".to_string()))
    } else {
        let mut id_opt = None;
        let mut name_opt = None;
        let mut sec_opt = None;
        for (k, v) in form_urlencoded::parse(body.as_bytes()) {
            match k.as_ref() {
                "id" | "custom_plan_id" | "customPlanId" => {
                    id_opt = v.trim().parse::<i64>().ok();
                }
                "name" | "plan_name" | "planName" => {
                    let s = v.trim().to_string();
                    if !s.is_empty() {
                        name_opt = Some(s);
                    }
                }
                "sections_json" | "sectionsJson" => {
                    let s = v.trim().to_string();
                    if !s.is_empty() {
                        sec_opt = Some(s);
                    }
                }
                _ => {}
            }
        }
        (id_opt, name_opt.unwrap_or_else(|| "Custom Plan".to_string()), sec_opt.unwrap_or_else(|| "[]".to_string()))
    };

    if let Some(preset_id) = id.filter(|&i| i > 0) {
        let res = sqlx::query(
            r#"
            UPDATE custom_spending_plan
            SET name = $1, sectionsjson = $2, sections_json = $2, lastupdated = $3, last_updated = $3, updated_at = $4
            WHERE id = $5 AND (userid = $6 OR user_id = $6 OR userid = $7 OR user_id = $7)
            "#,
        )
        .bind(&name)
        .bind(&sections_json)
        .bind(now)
        .bind(now_ms)
        .bind(preset_id)
        .bind(&resolved)
        .bind(&raw_uid)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

        crate::authz::Authz::verify_affected(res.rows_affected(), "CustomSpendingPlan", preset_id)?;
    } else {
        sqlx::query(
            r#"
            INSERT INTO custom_spending_plan (
                id, userid, user_id, name, sectionsjson, sections_json, createdat, lastupdated, created_at, updated_at, archived
            )
            VALUES (nextval('hibernate_sequence'), $1, $1, $2, $3, $3, $4, $4, $5, $5, false)
            "#,
        )
        .bind(&resolved)
        .bind(&name)
        .bind(&sections_json)
        .bind(now)
        .bind(now_ms)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    }

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

    #[test]
    fn test_preset_fields_form_body_deserialization() {
        let body = "preset=saved_42&custom_plan_id=42&plan_name=Household+Essentials&preset_save_mode=update&save_template=true";
        let payload = PotFormPayload::from_form_body(body);
        assert_eq!(payload.preset.as_deref(), Some("saved_42"));
        assert_eq!(payload.custom_plan_id, Some(42));
        assert_eq!(payload.plan_name.as_deref(), Some("Household Essentials"));
        assert_eq!(payload.preset_save_mode.as_deref(), Some("update"));
        assert_eq!(payload.save_template.as_deref(), Some("true"));
    }

    #[test]
    fn test_save_preset_payload_json_and_form() {
        let json = r#"{"id":55,"plan_name":"Holiday Fund","sections_json":"[{\"heading\":\"Hotels\",\"amount\":150}]"}"#;
        let p: SavePresetPayload = serde_json::from_str(json).expect("deserialize SavePresetPayload");
        assert_eq!(p.id, Some(55));
        assert_eq!(p.plan_name.as_deref(), Some("Holiday Fund"));
        assert!(p.sections_json.unwrap().contains("Hotels"));
    }

    #[test]
    fn test_collect_all_pot_accounts_includes_nominated_and_checked_accounts() {
        // accounts checklist has 10, but Food has 12, Fun has 14, Fuel has 16, Transport has 18,
        // and sections_json has section with accountId 20.
        let body = "accounts=10&food_account_id=12&fun_account_id=14&fuel_account_id=16&transport_account_id=18&sections_json=%5B%7B%22heading%22%3A%22Groceries%22%2C%22amount%22%3A100%2C%22accountId%22%3A20%7D%5D";
        let payload = PotFormPayload::from_form_body(body);
        let all_accounts = payload.collect_all_pot_accounts();
        assert!(all_accounts.contains(&10));
        assert!(all_accounts.contains(&12));
        assert!(all_accounts.contains(&14));
        assert!(all_accounts.contains(&16));
        assert!(all_accounts.contains(&18));
        assert!(all_accounts.contains(&20));
        assert_eq!(all_accounts.len(), 6);
    }

    #[test]
    fn test_compare_to_allowance_under_exact_over_and_negative_surplus() {
        let under = compare_to_allowance(Pence(29000), Pence(50000));
        assert!(!under.is_over_allowance);
        assert_eq!(under.difference, Pence(21000));
        assert_eq!(under.absolute_difference, Pence(21000));

        let exact = compare_to_allowance(Pence(50000), Pence(50000));
        assert!(!exact.is_over_allowance);
        assert_eq!(exact.difference, Pence::ZERO);
        assert_eq!(exact.absolute_difference, Pence::ZERO);

        let over = compare_to_allowance(Pence(62550), Pence(50000));
        assert!(over.is_over_allowance);
        assert_eq!(over.difference, Pence(-12550));
        assert_eq!(over.absolute_difference, Pence(12550));

        let neg_surplus = compare_to_allowance(Pence(10000), Pence(-5000));
        assert!(neg_surplus.is_over_allowance);
        assert_eq!(neg_surplus.difference, Pence(-15000));
        assert_eq!(neg_surplus.absolute_difference, Pence(15000));
    }

    #[test]
    fn test_parse_sections_total_pence_precision_clamping_fallback_and_malformed() {
        // Fractional pence precision (0.10 + 0.20 + 216.67 + 33.33 = 250.30)
        let json = r#"[
            {"heading":"A","amount":0.10},
            {"heading":"B","amount":0.20},
            {"heading":"C","amount":216.67},
            {"heading":"D","amount":"33.33"}
        ]"#;
        assert_eq!(parse_sections_total_pence(Some(json), None, None, None), Pence(25030));

        // Negative / invalid amounts clamped to 0
        let weird = r#"[{"heading":"Neg","amount":-25},{"heading":"Bad","amount":"nope"},{"heading":"Ok","amount":40.5}]"#;
        assert_eq!(parse_sections_total_pence(Some(weird), None, None, None), Pence(4050));

        // Legacy fallback when sections_json is None or empty array
        assert_eq!(
            parse_sections_total_pence(None, Some(200.0), Some(50.0), Some(40.25)),
            Pence(29025)
        );
        assert_eq!(
            parse_sections_total_pence(Some("[]"), Some(216.67), Some(0.0), None),
            Pence(21667)
        );

        // Malformed JSON without legacy columns safely returns 0
        assert_eq!(parse_sections_total_pence(Some("{not-valid"), None, None, None), Pence::ZERO);
    }
}
