use askama::Template;
use axum::{
    extract::{Form, Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Redirect, Response},
    Json,
};
use chrono::Datelike;
use serde::{Deserialize, Serialize};
use sqlx::Row;
use std::sync::Arc;

use crate::AppState;
use crate::db;
use crate::models::{
    Account, Incoming, NewAccount, NewBalance, NewIncoming, NewOutgoing, Outgoing,
};
use crate::pot::get_user_id_from_headers_or_default;
use crate::spog::resolve_user_id;

pub fn is_authenticated(headers: &HeaderMap) -> bool {
    if let Some(cookie_hdr) = headers.get(axum::http::header::COOKIE) {
        if let Ok(cookie_str) = cookie_hdr.to_str() {
            for part in cookie_str.split(';') {
                let part = part.trim();
                if let Some((name, val)) = part.split_once('=') {
                    let val = val.trim();
                    if !val.is_empty() {
                        if name == "monmon_session" || name == "PLAY_SESSION" || name == "user_id" {
                            return true;
                        }
                        if name == "authenticated" && val == "true" {
                            return true;
                        }
                    }
                }
            }
        }
    }
    if let Some(auth_hdr) = headers.get(axum::http::header::AUTHORIZATION) {
        if let Ok(auth_str) = auth_hdr.to_str() {
            if !auth_str.trim().is_empty() {
                return true;
            }
        }
    }
    false
}

#[derive(Template)]
#[template(path = "accounts.html")]
pub struct AccountsTemplate {
    pub accounts: Vec<Account>,
    pub editing: bool,
    pub editing_account: Option<Account>,
}

#[derive(Template)]
#[template(path = "outgoings.html")]
pub struct OutgoingsTemplate {
    pub outgoings: Vec<OutgoingViewDto>,
    pub accounts: Vec<Account>,
    pub outgoing_total: f32,
    pub current_year: i32,
    pub current_month_name: String,
    pub editing: bool,
    pub editing_outgoing: Option<Outgoing>,
    pub yearly_bills_account_id: Option<i64>,
    pub yearly_bills_monthly_saving: f32,
    pub yearly_bills_count: usize,
    pub yearly_bills_target_balance: f32,
    pub yearly_bills_total_cost: f32,
    pub yearly_bills_remaining_deficit: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutgoingViewDto {
    pub id: i64,
    pub name: String,
    pub cost: f32,
    pub outgoing_day: i32,
    pub effective_day: u32,
    pub is_adjusted: bool,
    pub outgoing_month: Option<i32>,
    pub year_interval: Option<i32>,
    pub starting_year: Option<i32>,
    pub bill: bool,
    pub rent: bool,
    pub hidden_from_total: bool,
    pub paused: bool,
    pub pause_reason: Option<String>,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
    pub account_type: Option<String>,
    pub is_yearly: bool,
    pub is_due_now: bool,
    pub frequency_label: String,
    pub next_due_label: String,
    pub is_calculated: bool,
    pub calculated_tooltip: Option<String>,
    pub calculated_breakdown: Option<Vec<crate::models::YearlyBillItemBreakdown>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IncomingViewDto {
    pub id: i64,
    pub name: String,
    pub net_value: f32,
    pub gross_value: Option<f32>,
    pub incoming_month_day: i32,
    pub effective_day: u32,
    pub is_adjusted: bool,
    pub payday: Option<bool>,
    pub incoming_type: Option<String>,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
}

#[derive(Template)]
#[template(path = "incomings.html")]
pub struct IncomingsTemplate {
    pub incomings: Vec<IncomingViewDto>,
    pub accounts: Vec<Account>,
    pub editing: bool,
    pub editing_incoming: Option<Incoming>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BalanceViewDto {
    pub id: i64,
    pub value: f32,
    pub timestamp: i64,
    pub account_id: i64,
    pub account_name: String,
    pub account_type: String,
    pub date_str: String,
}

#[derive(Template)]
#[template(path = "balances.html")]
pub struct BalancesTemplate {
    pub balances: Vec<BalanceViewDto>,
    pub accounts: Vec<Account>,
    pub chart_balances_json: String,
    pub paydays_json: String,
    pub default_account_ids_json: String,
    pub selected_account_id: Option<i64>,
    pub selected_account_name: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
pub struct BalancesQuery {
    #[serde(alias = "accountId")]
    pub account_id: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct AccountForm {
    pub id: Option<i64>,
    pub name: String,
    pub nickname: Option<String>,
    #[serde(rename = "type")]
    pub account_type: Option<String>,
    #[serde(alias = "availableLimit")]
    pub available_limit: Option<f32>,
    #[serde(alias = "interestRate")]
    pub interest_rate: Option<f32>,
}

fn deserialize_checkbox<'de, D>(deserializer: D) -> Result<Option<bool>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    struct CheckboxVisitor;
    impl<'de> serde::de::Visitor<'de> for CheckboxVisitor {
        type Value = Option<bool>;
        fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
            formatter.write_str("a boolean or string representation of a boolean ('on', 'true', '1')")
        }
        fn visit_bool<E>(self, v: bool) -> Result<Self::Value, E> {
            Ok(Some(v))
        }
        fn visit_str<E>(self, v: &str) -> Result<Self::Value, E> {
            match v.to_ascii_lowercase().as_str() {
                "true" | "on" | "yes" | "1" => Ok(Some(true)),
                "false" | "off" | "no" | "0" | "" => Ok(Some(false)),
                _ => Ok(Some(false)),
            }
        }
        fn visit_none<E>(self) -> Result<Self::Value, E> {
            Ok(None)
        }
        fn visit_some<D2>(self, deserializer: D2) -> Result<Self::Value, D2::Error>
        where
            D2: serde::Deserializer<'de>,
        {
            deserializer.deserialize_any(self)
        }
    }
    deserializer.deserialize_option(CheckboxVisitor)
}

fn deserialize_empty_as_none<'de, D>(deserializer: D) -> Result<Option<i64>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let opt = Option::<String>::deserialize(deserializer)?;
    match opt.as_deref() {
        None | Some("") => Ok(None),
        Some(s) => s.trim().parse::<i64>().map(Some).map_err(serde::de::Error::custom),
    }
}

fn deserialize_empty_as_none_i32<'de, D>(deserializer: D) -> Result<Option<i32>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let opt = Option::<String>::deserialize(deserializer)?;
    match opt.as_deref() {
        None | Some("") => Ok(None),
        Some(s) => s.trim().parse::<i32>().map(Some).map_err(serde::de::Error::custom),
    }
}

#[derive(Debug, Deserialize)]
pub struct IncomingForm {
    pub id: Option<i64>,
    pub name: String,
    #[serde(alias = "netValue")]
    pub net_value: f32,
    #[serde(alias = "incomingMonthDay")]
    pub incoming_month_day: i32,
    #[serde(default, alias = "payDay", deserialize_with = "deserialize_checkbox")]
    pub payday: Option<bool>,
    #[serde(default, alias = "accountId", alias = "account_id", deserialize_with = "deserialize_empty_as_none")]
    pub account_id: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct OutgoingForm {
    pub id: Option<i64>,
    pub name: String,
    pub cost: f32,
    #[serde(alias = "outgoingDay")]
    pub outgoing_day: i32,
    #[serde(default, alias = "accountId", alias = "account_id", deserialize_with = "deserialize_empty_as_none")]
    pub account_id: Option<i64>,
    #[serde(default, alias = "outgoingMonth", alias = "outgoing_month", deserialize_with = "deserialize_empty_as_none_i32")]
    pub outgoing_month: Option<i32>,
    #[serde(default, alias = "yearInterval", alias = "year_interval", deserialize_with = "deserialize_empty_as_none_i32")]
    pub year_interval: Option<i32>,
    #[serde(default, alias = "startingYear", alias = "starting_year", deserialize_with = "deserialize_empty_as_none_i32")]
    pub starting_year: Option<i32>,
    #[serde(default)]
    pub frequency: Option<String>,
    #[serde(default, deserialize_with = "deserialize_checkbox")]
    pub bill: Option<bool>,
    #[serde(default, deserialize_with = "deserialize_checkbox")]
    pub rent: Option<bool>,
    #[serde(default, alias = "hiddenFromTotal", deserialize_with = "deserialize_checkbox")]
    pub hidden_from_total: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct BalanceForm {
    #[serde(alias = "accountId", alias = "account_id")]
    pub account_id: i64,
    #[serde(alias = "value")]
    pub balance: f32,
}

pub async fn get_accounts_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(crate::landing::auth_redirect(Some("/myAccounts")).await.into_response());
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let accounts = db::list_accounts(&state.pool, &user_id)
        .await
        .unwrap_or_default();

    let tmpl = AccountsTemplate {
        accounts,
        editing: false,
        editing_account: None,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_edit_account_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(crate::landing::auth_redirect(Some(&format!("/editAccount/{}", id))).await.into_response());
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let accounts = db::list_accounts(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let editing_account = accounts.iter().find(|a| a.id == id).cloned();
    let editing = editing_account.is_some();

    let tmpl = AccountsTemplate {
        accounts,
        editing,
        editing_account,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_update_account_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(form): Form<AccountForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let update = crate::models::UpdateAccount {
        name: form.name,
        nickname: form.nickname,
        account_type: form.account_type.unwrap_or_else(|| "CURRENT".to_string()),
        account_limit: form.available_limit,
        interest_rate: form.interest_rate,
    };
    db::update_account(&state.pool, id, &user_id, &update)
        .await
        .map_err(|e| match e {
            sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Account #{} not found or owned by another user.", id)),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
        })?;

    Ok(Redirect::to("/myAccounts"))
}

pub async fn post_update_account_form_direct(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<AccountForm>,
) -> Result<Redirect, (StatusCode, String)> {
    if let Some(id) = form.id {
        let raw_uid = get_user_id_from_headers_or_default(&headers, None);
        let user_id = resolve_user_id(&state.pool, &raw_uid).await;
        let update = crate::models::UpdateAccount {
            name: form.name,
            nickname: form.nickname,
            account_type: form.account_type.unwrap_or_else(|| "CURRENT".to_string()),
            account_limit: form.available_limit,
            interest_rate: form.interest_rate,
        };
        db::update_account(&state.pool, id, &user_id, &update)
            .await
            .map_err(|e| match e {
                sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Account #{} not found or owned by another user.", id)),
                _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
            })?;
    }
    Ok(Redirect::to("/myAccounts"))
}

pub async fn post_account_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<AccountForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let new_acc = NewAccount {
        name: form.name,
        nickname: form.nickname,
        account_type: form.account_type.unwrap_or_else(|| "CURRENT".to_string()),
        user_id,
        account_limit: form.available_limit,
        interest_rate: form.interest_rate,
    };
    db::create_account(&state.pool, &new_acc)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myAccounts"))
}

pub async fn get_outgoings_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(crate::landing::auth_redirect(Some("/myOutgoings")).await.into_response());
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let outgoings = db::list_outgoings(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let accounts = db::list_accounts(&state.pool, &user_id)
        .await
        .unwrap_or_default();

    let today = chrono::Local::now().date_naive();
    let current_year = today.year();
    let current_month_name = match today.month() {
        1 => "January", 2 => "February", 3 => "March", 4 => "April",
        5 => "May", 6 => "June", 7 => "July", 8 => "August",
        9 => "September", 10 => "October", 11 => "November", 12 => "December",
        _ => "",
    }.to_string();

    let outgoing_dtos: Vec<OutgoingViewDto> = outgoings.iter().map(|o| {
        let is_yearly = o.is_yearly();
        let is_due_now = o.is_due_in_month(today);
        let next_due_year = o.get_next_due_year(today);
        let next_due_label = if is_yearly {
            format!("{} {}", o.get_month_name(), next_due_year)
        } else {
            "Monthly".to_string()
        };
        let effective_day = crate::anchoring::get_effective_outgoing_day(o.outgoing_day, today);
        let is_adjusted = crate::anchoring::is_outgoing_adjusted(o.outgoing_day, today);
        OutgoingViewDto {
            id: o.id,
            name: o.name.clone(),
            cost: o.cost,
            outgoing_day: o.outgoing_day,
            effective_day,
            is_adjusted,
            outgoing_month: o.outgoing_month,
            year_interval: o.year_interval,
            starting_year: o.starting_year,
            bill: o.bill.unwrap_or(false),
            rent: o.rent.unwrap_or(false),
            hidden_from_total: o.hidden_from_total.unwrap_or(false),
            paused: o.paused.unwrap_or(false),
            pause_reason: o.pause_reason.clone(),
            account_id: o.account_id,
            account_name: o.account_name.clone(),
            account_type: o.account_type.clone(),
            is_yearly,
            is_due_now,
            frequency_label: o.get_frequency_label(),
            next_due_label,
            is_calculated: false,
            calculated_tooltip: None,
            calculated_breakdown: None,
        }
    }).collect();

    let yearly_bills_account_id: Option<i64> = sqlx::query_scalar(
        "SELECT yearly_bills_account_id FROM user_access WHERE (userid = $1 OR username = $1) LIMIT 1"
    )
    .bind(&user_id)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None)
    .flatten();

    let nominal_payday: i32 = sqlx::query_scalar(
        "SELECT incomingmonthday FROM incoming WHERE (userid = $1 OR user_id = $1) AND payday = true LIMIT 1"
    )
    .bind(&user_id)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None)
    .flatten()
    .unwrap_or(25);

    let target_balance: f32 = match yearly_bills_account_id {
        Some(acc_id) => sqlx::query_scalar(
            "SELECT COALESCE(value, 0.0)::real FROM balance WHERE account_id = $1 ORDER BY timestamp DESC LIMIT 1"
        )
        .bind(acc_id)
        .fetch_optional(&state.pool)
        .await
        .unwrap_or(None)
        .flatten()
        .unwrap_or(0.0),
        None => 0.0,
    };

    let calc = crate::models::calculate_yearly_bills_monthly_saving(
        today,
        nominal_payday,
        &outgoings,
        target_balance,
    );

    let actual_payday = crate::anchoring::get_actual_payday_date(nominal_payday, today.year(), today.month());
    let effective_day = actual_payday.day();
    let is_adjusted = effective_day as i32 != nominal_payday;
    let tooltip_str = calc.format_tooltip();

    let mut outgoing_dtos = outgoing_dtos;
    if let Some(target_acc_id) = yearly_bills_account_id {
        if calc.monthly_saving > 0.0 {
            let target_acc = accounts.iter().find(|a| a.id == target_acc_id);
            let (acc_name, acc_type) = match target_acc {
                Some(a) => (Some(a.name.clone()), Some(a.account_type.clone())),
                None => (None, None),
            };
            let calculated_dto = OutgoingViewDto {
                id: -999,
                name: "Yearly Bills Reserve".to_string(),
                cost: calc.monthly_saving,
                outgoing_day: nominal_payday,
                effective_day,
                is_adjusted,
                outgoing_month: None,
                year_interval: None,
                starting_year: None,
                bill: true,
                rent: false,
                hidden_from_total: false,
                paused: false,
                pause_reason: None,
                account_id: Some(target_acc_id),
                account_name: acc_name,
                account_type: acc_type,
                is_yearly: false,
                is_due_now: true,
                frequency_label: "Monthly (Calculated)".to_string(),
                next_due_label: "Every Payday".to_string(),
                is_calculated: true,
                calculated_tooltip: Some(tooltip_str.clone()),
                calculated_breakdown: Some(calc.breakdown.clone()),
            };
            outgoing_dtos.push(calculated_dto);
        }
    }

    let outgoing_total: f32 = outgoing_dtos.iter()
        .filter(|o| !o.paused && !o.hidden_from_total && o.is_due_now)
        .map(|o| o.cost)
        .sum();
    let outgoing_total = ((outgoing_total * 100.0).round()) / 100.0;

    let tmpl = OutgoingsTemplate {
        outgoings: outgoing_dtos,
        accounts,
        outgoing_total,
        current_year,
        current_month_name,
        editing: false,
        editing_outgoing: None,
        yearly_bills_account_id,
        yearly_bills_monthly_saving: calc.monthly_saving,
        yearly_bills_count: calc.upcoming_count,
        yearly_bills_target_balance: calc.target_account_balance,
        yearly_bills_total_cost: calc.total_upcoming_cost,
        yearly_bills_remaining_deficit: calc.remaining_deficit,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_edit_outgoing_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(crate::landing::auth_redirect(Some(&format!("/editOutgoing/{}", id))).await.into_response());
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let outgoings = db::list_outgoings(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let accounts = db::list_accounts(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let editing_outgoing = outgoings.iter().find(|o| o.id == id).cloned();
    let editing = editing_outgoing.is_some();

    let today = chrono::Local::now().date_naive();
    let current_year = today.year();
    let current_month_name = match today.month() {
        1 => "January", 2 => "February", 3 => "March", 4 => "April",
        5 => "May", 6 => "June", 7 => "July", 8 => "August",
        9 => "September", 10 => "October", 11 => "November", 12 => "December",
        _ => "",
    }.to_string();

    let outgoing_dtos: Vec<OutgoingViewDto> = outgoings.iter().map(|o| {
        let is_yearly = o.is_yearly();
        let is_due_now = o.is_due_in_month(today);
        let next_due_year = o.get_next_due_year(today);
        let next_due_label = if is_yearly {
            format!("{} {}", o.get_month_name(), next_due_year)
        } else {
            "Monthly".to_string()
        };
        let effective_day = crate::anchoring::get_effective_outgoing_day(o.outgoing_day, today);
        let is_adjusted = crate::anchoring::is_outgoing_adjusted(o.outgoing_day, today);
        OutgoingViewDto {
            id: o.id,
            name: o.name.clone(),
            cost: o.cost,
            outgoing_day: o.outgoing_day,
            effective_day,
            is_adjusted,
            outgoing_month: o.outgoing_month,
            year_interval: o.year_interval,
            starting_year: o.starting_year,
            bill: o.bill.unwrap_or(false),
            rent: o.rent.unwrap_or(false),
            hidden_from_total: o.hidden_from_total.unwrap_or(false),
            paused: o.paused.unwrap_or(false),
            pause_reason: o.pause_reason.clone(),
            account_id: o.account_id,
            account_name: o.account_name.clone(),
            account_type: o.account_type.clone(),
            is_yearly,
            is_due_now,
            frequency_label: o.get_frequency_label(),
            next_due_label,
            is_calculated: false,
            calculated_tooltip: None,
            calculated_breakdown: None,
        }
    }).collect();

    let yearly_bills_account_id: Option<i64> = sqlx::query_scalar(
        "SELECT yearly_bills_account_id FROM user_access WHERE (userid = $1 OR username = $1) LIMIT 1"
    )
    .bind(&user_id)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None)
    .flatten();

    let nominal_payday: i32 = sqlx::query_scalar(
        "SELECT incomingmonthday FROM incoming WHERE (userid = $1 OR user_id = $1) AND payday = true LIMIT 1"
    )
    .bind(&user_id)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None)
    .flatten()
    .unwrap_or(25);

    let target_balance: f32 = match yearly_bills_account_id {
        Some(acc_id) => sqlx::query_scalar(
            "SELECT COALESCE(value, 0.0)::real FROM balance WHERE account_id = $1 ORDER BY timestamp DESC LIMIT 1"
        )
        .bind(acc_id)
        .fetch_optional(&state.pool)
        .await
        .unwrap_or(None)
        .flatten()
        .unwrap_or(0.0),
        None => 0.0,
    };

    let calc = crate::models::calculate_yearly_bills_monthly_saving(
        today,
        nominal_payday,
        &outgoings,
        target_balance,
    );

    let actual_payday = crate::anchoring::get_actual_payday_date(nominal_payday, today.year(), today.month());
    let effective_day = actual_payday.day();
    let is_adjusted = effective_day as i32 != nominal_payday;
    let tooltip_str = calc.format_tooltip();

    let mut outgoing_dtos = outgoing_dtos;
    if let Some(target_acc_id) = yearly_bills_account_id {
        if calc.monthly_saving > 0.0 {
            let target_acc = accounts.iter().find(|a| a.id == target_acc_id);
            let (acc_name, acc_type) = match target_acc {
                Some(a) => (Some(a.name.clone()), Some(a.account_type.clone())),
                None => (None, None),
            };
            let calculated_dto = OutgoingViewDto {
                id: -999,
                name: "Yearly Bills Reserve".to_string(),
                cost: calc.monthly_saving,
                outgoing_day: nominal_payday,
                effective_day,
                is_adjusted,
                outgoing_month: None,
                year_interval: None,
                starting_year: None,
                bill: true,
                rent: false,
                hidden_from_total: false,
                paused: false,
                pause_reason: None,
                account_id: Some(target_acc_id),
                account_name: acc_name,
                account_type: acc_type,
                is_yearly: false,
                is_due_now: true,
                frequency_label: "Monthly (Calculated)".to_string(),
                next_due_label: "Every Payday".to_string(),
                is_calculated: true,
                calculated_tooltip: Some(tooltip_str.clone()),
                calculated_breakdown: Some(calc.breakdown.clone()),
            };
            outgoing_dtos.push(calculated_dto);
        }
    }

    let outgoing_total: f32 = outgoing_dtos.iter()
        .filter(|o| !o.paused && !o.hidden_from_total && o.is_due_now)
        .map(|o| o.cost)
        .sum();
    let outgoing_total = ((outgoing_total * 100.0).round()) / 100.0;

    let tmpl = OutgoingsTemplate {
        outgoings: outgoing_dtos,
        accounts,
        outgoing_total,
        current_year,
        current_month_name,
        editing,
        editing_outgoing,
        yearly_bills_account_id,
        yearly_bills_monthly_saving: calc.monthly_saving,
        yearly_bills_count: calc.upcoming_count,
        yearly_bills_target_balance: calc.target_account_balance,
        yearly_bills_total_cost: calc.total_upcoming_cost,
        yearly_bills_remaining_deficit: calc.remaining_deficit,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

#[derive(Debug, Deserialize)]
pub struct YearlyBillsTargetAccountForm {
    #[serde(alias = "accountId")]
    pub account_id: Option<String>,
}

pub async fn post_yearly_bills_target_account(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<YearlyBillsTargetAccountForm>,
) -> Result<Redirect, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(Redirect::to("/login"));
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;

    let acc_id: Option<i64> = form
        .account_id
        .as_deref()
        .and_then(|s| s.trim().parse::<i64>().ok())
        .filter(|&id| id > 0);

    let _ = sqlx::query(
        "UPDATE user_access SET yearly_bills_account_id = $1 WHERE (userid = $2 OR username = $2)",
    )
    .bind(acc_id)
    .bind(&user_id)
    .execute(&state.pool)
    .await;

    Ok(Redirect::to("/myOutgoings?tab=yearly"))
}

fn resolve_frequency_fields(form: &OutgoingForm) -> (Option<i32>, Option<i32>, Option<i32>) {
    if form.frequency.as_deref() == Some("monthly") {
        (None, None, None)
    } else if form.frequency.as_deref() == Some("yearly") || form.outgoing_month.map_or(false, |m| m > 0) {
        let month = form.outgoing_month.filter(|&m| m > 0 && m <= 12).or_else(|| {
            Some(chrono::Local::now().date_naive().month() as i32)
        });
        let interval = form.year_interval.map(|y| y.max(1)).or(Some(1));
        let start_year = form.starting_year.filter(|&y| y >= 1900).or_else(|| {
            Some(chrono::Local::now().date_naive().year())
        });
        (month, interval, start_year)
    } else {
        (None, None, None)
    }
}

pub async fn post_update_outgoing_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(form): Form<OutgoingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let (outgoing_month, year_interval, starting_year) = resolve_frequency_fields(&form);
    let update = crate::models::UpdateOutgoing {
        name: form.name,
        cost: form.cost,
        outgoing_day: form.outgoing_day,
        user_id: Some(user_id.clone()),
        account_id: form.account_id,
        outgoing_month,
        year_interval,
        starting_year,
        bill: Some(form.bill.unwrap_or(false)),
        rent: Some(form.rent.unwrap_or(false)),
        hidden_from_total: Some(form.hidden_from_total.unwrap_or(false)),
    };
    db::update_outgoing(&state.pool, id, &user_id, &update)
        .await
        .map_err(|e| match e {
            sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Outgoing #{} not found or owned by another user.", id)),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
        })?;

    Ok(Redirect::to("/myOutgoings"))
}

pub async fn post_update_outgoing_form_direct(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<OutgoingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    if let Some(id) = form.id {
        let raw_uid = get_user_id_from_headers_or_default(&headers, None);
        let user_id = resolve_user_id(&state.pool, &raw_uid).await;
        let (outgoing_month, year_interval, starting_year) = resolve_frequency_fields(&form);
        let update = crate::models::UpdateOutgoing {
            name: form.name,
            cost: form.cost,
            outgoing_day: form.outgoing_day,
            user_id: Some(user_id.clone()),
            account_id: form.account_id,
            outgoing_month,
            year_interval,
            starting_year,
            bill: Some(form.bill.unwrap_or(false)),
            rent: Some(form.rent.unwrap_or(false)),
            hidden_from_total: Some(form.hidden_from_total.unwrap_or(false)),
        };
        db::update_outgoing(&state.pool, id, &user_id, &update)
            .await
            .map_err(|e| match e {
                sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Outgoing #{} not found or owned by another user.", id)),
                _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
            })?;
    }
    Ok(Redirect::to("/myOutgoings"))
}

pub async fn post_outgoing_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<OutgoingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let (outgoing_month, year_interval, starting_year) = resolve_frequency_fields(&form);
    let new_out = NewOutgoing {
        name: form.name,
        cost: form.cost,
        outgoing_day: form.outgoing_day,
        outgoing_month,
        year_interval,
        starting_year,
        bill: Some(form.bill.unwrap_or(false)),
        rent: Some(form.rent.unwrap_or(false)),
        hidden_from_total: Some(form.hidden_from_total.unwrap_or(false)),
        account_id: form.account_id,
        user_id,
    };
    db::create_outgoing(&state.pool, &new_out)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myOutgoings"))
}

pub async fn get_incomings_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(crate::landing::auth_redirect(Some("/myIncomings")).await.into_response());
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let incomings = db::list_incomings(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let accounts = db::list_accounts(&state.pool, &user_id)
        .await
        .unwrap_or_default();

    let today = chrono::Local::now().date_naive();
    let incoming_dtos: Vec<IncomingViewDto> = incomings.iter().map(|i| {
        let is_payday = i.payday.unwrap_or(false);
        let effective_day = crate::anchoring::get_effective_incoming_day(i.incoming_month_day, is_payday, today);
        let is_adjusted = crate::anchoring::is_incoming_adjusted(i.incoming_month_day, is_payday, today);
        IncomingViewDto {
            id: i.id,
            name: i.name.clone(),
            net_value: i.net_value,
            gross_value: i.gross_value,
            incoming_month_day: i.incoming_month_day,
            effective_day,
            is_adjusted,
            payday: i.payday,
            incoming_type: i.incoming_type.clone(),
            user_id: i.user_id.clone(),
            account_id: i.account_id,
            account_name: i.account_name.clone(),
        }
    }).collect();

    let tmpl = IncomingsTemplate {
        incomings: incoming_dtos,
        accounts,
        editing: false,
        editing_incoming: None,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_edit_incoming_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(crate::landing::auth_redirect(Some(&format!("/editIncoming/{}", id))).await.into_response());
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let incomings = db::list_incomings(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let accounts = db::list_accounts(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let editing_incoming = incomings.iter().find(|i| i.id == id).cloned();
    let editing = editing_incoming.is_some();

    let today = chrono::Local::now().date_naive();
    let incoming_dtos: Vec<IncomingViewDto> = incomings.iter().map(|i| {
        let is_payday = i.payday.unwrap_or(false);
        let effective_day = crate::anchoring::get_effective_incoming_day(i.incoming_month_day, is_payday, today);
        let is_adjusted = crate::anchoring::is_incoming_adjusted(i.incoming_month_day, is_payday, today);
        IncomingViewDto {
            id: i.id,
            name: i.name.clone(),
            net_value: i.net_value,
            gross_value: i.gross_value,
            incoming_month_day: i.incoming_month_day,
            effective_day,
            is_adjusted,
            payday: i.payday,
            incoming_type: i.incoming_type.clone(),
            user_id: i.user_id.clone(),
            account_id: i.account_id,
            account_name: i.account_name.clone(),
        }
    }).collect();

    let tmpl = IncomingsTemplate {
        incomings: incoming_dtos,
        accounts,
        editing,
        editing_incoming,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_update_incoming_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(form): Form<IncomingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let update = crate::models::UpdateIncoming {
        name: form.name,
        net_value: form.net_value,
        gross_value: Some(form.net_value),
        incoming_month_day: form.incoming_month_day,
        user_id: Some(user_id.clone()),
        account_id: form.account_id,
        payday: Some(form.payday.unwrap_or(false)),
    };
    db::update_incoming(&state.pool, id, &user_id, &update)
        .await
        .map_err(|e| match e {
            sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Incoming #{} not found or owned by another user.", id)),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
        })?;

    Ok(Redirect::to("/myIncomings"))
}

pub async fn post_update_incoming_form_direct(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<IncomingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    if let Some(id) = form.id {
        let raw_uid = get_user_id_from_headers_or_default(&headers, None);
        let user_id = resolve_user_id(&state.pool, &raw_uid).await;
        let update = crate::models::UpdateIncoming {
            name: form.name,
            net_value: form.net_value,
            gross_value: Some(form.net_value),
            incoming_month_day: form.incoming_month_day,
            user_id: Some(user_id.clone()),
            account_id: form.account_id,
            payday: Some(form.payday.unwrap_or(false)),
        };
        db::update_incoming(&state.pool, id, &user_id, &update)
            .await
            .map_err(|e| match e {
                sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Incoming #{} not found or owned by another user.", id)),
                _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
            })?;
    }
    Ok(Redirect::to("/myIncomings"))
}

pub async fn post_incoming_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<IncomingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let new_inc = NewIncoming {
        name: form.name,
        net_value: form.net_value,
        gross_value: Some(form.net_value),
        incoming_month_day: form.incoming_month_day,
        user_id,
        account_id: form.account_id,
        payday: Some(form.payday.unwrap_or(false)),
    };
    db::create_incoming(&state.pool, &new_inc)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myIncomings"))
}

pub async fn get_bin_incoming(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(Redirect::to("/login"));
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    db::archive_incoming(&state.pool, id, &user_id)
        .await
        .map_err(|e| match e {
            sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Incoming #{} not found or owned by another user.", id)),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
        })?;

    Ok(Redirect::to("/myIncomings"))
}

pub async fn get_bin_account(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(Redirect::to("/login"));
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    db::archive_account(&state.pool, id, &user_id)
        .await
        .map_err(|e| match e {
            sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Account #{} not found or owned by another user.", id)),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
        })?;

    Ok(Redirect::to("/myAccounts"))
}

pub async fn get_bin_outgoing(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(Redirect::to("/login"));
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    db::archive_outgoing(&state.pool, id, &user_id)
        .await
        .map_err(|e| match e {
            sqlx::Error::RowNotFound => (StatusCode::FORBIDDEN, format!("Access denied: Outgoing #{} not found or owned by another user.", id)),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()),
        })?;

    Ok(Redirect::to("/myOutgoings"))
}

pub async fn get_balances_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<BalancesQuery>,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        let return_to = if let Some(acc_id) = query.account_id {
            format!("/myBalances?accountId={}", acc_id)
        } else {
            "/myBalances".to_string()
        };
        return Ok(crate::landing::auth_redirect(Some(&return_to)).await.into_response());
    }
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;

    // 1. Fetch active accounts
    let accounts = db::list_accounts(&state.pool, &user_id)
        .await
        .unwrap_or_default();

    let selected_account_id = query.account_id;
    let selected_account_name = selected_account_id
        .and_then(|id| accounts.iter().find(|a| a.id == id).map(|a| a.name.clone()));

    // 2. Fetch balances with account details
    let balance_rows = sqlx::query(
        r#"
        SELECT 
            b.id,
            COALESCE(b.value, 0.0)::real as value,
            COALESCE(b.timestamp, 0)::bigint as timestamp,
            COALESCE(b.account_id, 0)::bigint as account_id,
            COALESCE(a.name, 'Unknown Account') as account_name,
            COALESCE(a.type, 'CURRENT') as account_type
        FROM balance b
        LEFT JOIN account a ON b.account_id = a.id
        WHERE (b.user_id = $1 OR b.userid = $1 OR b."userId" = $1)
        ORDER BY b.timestamp DESC NULLS LAST, b.id DESC
        "#,
    )
    .bind(&user_id)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let mut chart_points = Vec::new();
    let now_ts = chrono::Utc::now().timestamp();
    let sixty_days_ago_sec = now_ts - (60 * 86400);
    let mut daily_sampled: std::collections::HashMap<String, serde_json::Value> = std::collections::HashMap::new();

    let ten_days_ago_date = (chrono::Utc::now() - chrono::Duration::days(10)).date_naive();
    let mut by_account: std::collections::HashMap<i64, Vec<BalanceViewDto>> = std::collections::HashMap::new();

    for r in &balance_rows {
        let id: i64 = r.get("id");
        let val: f32 = r.get::<Option<f32>, _>("value").unwrap_or(0.0);
        let raw_ts: i64 = r.get::<Option<i64>, _>("timestamp").unwrap_or(0);
        let acc_id: i64 = r.get::<Option<i64>, _>("account_id").unwrap_or(0);
        let acc_name: String = r.get::<Option<String>, _>("account_name").unwrap_or_else(|| "Unknown Account".to_string());
        let acc_type: String = r.get::<Option<String>, _>("account_type").unwrap_or_else(|| "CURRENT".to_string());

        let ts_sec = if raw_ts > 100_000_000_000 {
            raw_ts / 1000
        } else {
            raw_ts
        };

        let dt = chrono::DateTime::from_timestamp(ts_sec, 0)
            .unwrap_or_else(|| chrono::Utc::now());
        let date_str = dt.format("%d %b %Y, %H:%M").to_string();

        let item = BalanceViewDto {
            id,
            value: val,
            timestamp: ts_sec,
            account_id: acc_id,
            account_name: acc_name.clone(),
            account_type: acc_type.clone(),
            date_str: date_str.clone(),
        };

        by_account.entry(acc_id).or_default().push(item);

        let point = serde_json::json!({
            "id": id,
            "accountId": acc_id,
            "accountName": acc_name,
            "value": val,
            "timestamp": ts_sec,
            "dateStr": date_str
        });

        if ts_sec >= sixty_days_ago_sec {
            chart_points.push(point);
        } else {
            let day_key = format!("{}_{}", acc_id, dt.format("%Y-%m-%d"));
            daily_sampled.entry(day_key).or_insert(point);
        }
    }

    for (_, pt) in daily_sampled {
        chart_points.push(pt);
    }
    chart_points.sort_by_key(|p| p.get("timestamp").and_then(|t| t.as_i64()).unwrap_or(0));

    // Prepare table display: filter last 10 days per account (matching Java filterLastTenDaysBalancesPerAccount)
    let mut balances_dto = Vec::new();
    let mut existing_ids = std::collections::HashSet::new();

    for (_acc_id, acc_bals) in &by_account {
        let mut distinct_dates = Vec::new();
        for b in acc_bals {
            if let Some(dt) = chrono::DateTime::from_timestamp(b.timestamp, 0) {
                let d = dt.date_naive();
                if !distinct_dates.contains(&d) {
                    distinct_dates.push(d);
                }
            }
        }
        let mut allowed_dates = std::collections::HashSet::new();
        let mut count = 0;
        for d in distinct_dates {
            if d >= ten_days_ago_date || count < 10 {
                allowed_dates.insert(d);
                count += 1;
            }
            if count >= 10 && d < ten_days_ago_date {
                break;
            }
        }

        for b in acc_bals {
            if let Some(dt) = chrono::DateTime::from_timestamp(b.timestamp, 0) {
                if allowed_dates.contains(&dt.date_naive()) {
                    if existing_ids.insert(b.id) {
                        balances_dto.push(b.clone());
                    }
                }
            }
        }
    }

    // If preselected account, include up to 30 snapshots for that account
    if let Some(target_acc_id) = selected_account_id {
        if let Some(acc_bals) = by_account.get(&target_acc_id) {
            for b in acc_bals.iter().take(30) {
                if existing_ids.insert(b.id) {
                    balances_dto.push(b.clone());
                }
            }
        }
    }

    balances_dto.sort_by_key(|b| std::cmp::Reverse(b.timestamp));

    let chart_balances_json = serde_json::to_string(&chart_points).unwrap_or_else(|_| "[]".to_string());

    // 3. Compute paydays across the last 2 years up to 2 months into the future
    let payday_day: i32 = sqlx::query(
        r#"
        SELECT COALESCE(
            (SELECT incomingmonthday FROM incoming 
             WHERE (userid = $1 OR user_id = $1) 
               AND (archived IS NULL OR archived = false) 
               AND (payday = true OR type = 'SALARY') 
             ORDER BY id ASC LIMIT 1),
            25
        ) AS payday
        "#,
    )
    .bind(&user_id)
    .fetch_one(&state.pool)
    .await
    .map(|r| r.get::<Option<i32>, _>("payday").unwrap_or(25))
    .unwrap_or(25);

    let today = chrono::Local::now().date_naive();
    let mut paydays = Vec::new();
    let current_year = today.year();
    let current_month = today.month() as i32;
    for month_offset in -24..=2 {
        let mut target_m = current_month + month_offset;
        let mut target_y = current_year;
        while target_m < 1 {
            target_m += 12;
            target_y -= 1;
        }
        while target_m > 12 {
            target_m -= 12;
            target_y += 1;
        }
        let m = target_m as u32;
        let max_days = match m {
            4 | 6 | 9 | 11 => 30,
            2 => if (target_y % 4 == 0 && target_y % 100 != 0) || (target_y % 400 == 0) { 29 } else { 28 },
            _ => 31,
        };
        let d = (payday_day as u32).min(max_days);
        if let Some(date) = chrono::NaiveDate::from_ymd_opt(target_y, m, d) {
            let dt = date.and_hms_opt(9, 0, 0).unwrap().and_local_timezone(chrono::Utc).unwrap();
            let ts = dt.timestamp();
            paydays.push(serde_json::json!({
                "timestamp": ts,
                "label": "Payday",
                "dateStr": date.format("%d %b %Y").to_string()
            }));
        }
    }
    let paydays_json = serde_json::to_string(&paydays).unwrap_or_else(|_| "[]".to_string());

    // 4. Default account IDs (Monthly Pot accounts, or all accounts)
    let pot_accounts_rows = sqlx::query(
        r#"
        SELECT pa.account_id 
        FROM pot p
        JOIN pot_accounts pa ON pa.pot_id = p.id
        WHERE (p.userid = $1 OR p.user_id = $1)
          AND (p.archived IS NULL OR p.archived = false)
          AND p.type = 'MONTHLY'
        "#,
    )
    .bind(&user_id)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let mut default_account_ids: Vec<i64> = pot_accounts_rows
        .into_iter()
        .map(|r| r.get::<Option<i64>, _>("account_id").unwrap_or(0))
        .filter(|&id| id > 0)
        .collect();

    if default_account_ids.is_empty() {
        default_account_ids = accounts.iter().map(|a| a.id).collect();
    }
    let default_account_ids_json = serde_json::to_string(&default_account_ids).unwrap_or_else(|_| "[]".to_string());

    let tmpl = BalancesTemplate {
        balances: balances_dto,
        accounts,
        chart_balances_json,
        paydays_json,
        default_account_ids_json,
        selected_account_id,
        selected_account_name,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_balance_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<BalanceForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let new_bal = NewBalance {
        account_id: form.account_id,
        value: form.balance,
        timestamp: Some(chrono::Utc::now().timestamp()),
        user_id,
    };
    db::create_balance(&state.pool, &new_bal)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myBalances"))
}

pub async fn post_delete_balance(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let deleted = db::delete_balance(&state.pool, id, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    if !deleted {
        return Err((
            StatusCode::FORBIDDEN,
            format!("Access denied: Balance #{} was not found or belongs to another user account.", id),
        ));
    }

    Ok(Redirect::to("/myBalances"))
}

pub async fn get_erratic_balances_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<Vec<crate::models::ErraticBalance>>, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, params.get("userId").or_else(|| params.get("user_id")).map(|s| s.as_str()));
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let threshold: f32 = params
        .get("threshold")
        .and_then(|t| t.parse().ok())
        .unwrap_or(500.0);
    let list = db::find_erratic_balances(&state.pool, &user_id, threshold)
        .await
        .unwrap_or_default();
    Ok(Json(list))
}

#[derive(Debug, Deserialize)]
pub struct PauseForm {
    pub reason: Option<String>,
}

pub async fn pause_outgoing_web(
    axum::extract::Path(id): axum::extract::Path<i64>,
    headers: HeaderMap,
    State(state): State<Arc<AppState>>,
    Form(form): Form<PauseForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let reason = form.reason.unwrap_or_else(|| "User requested pause".to_string());
    db::pause_outgoing(&state.pool, id, &user_id, &reason)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myOutgoings"))
}

pub async fn unpause_outgoing_web(
    axum::extract::Path(id): axum::extract::Path<i64>,
    headers: HeaderMap,
    State(state): State<Arc<AppState>>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    db::unpause_outgoing(&state.pool, id, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myOutgoings"))
}
