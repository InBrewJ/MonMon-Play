use askama::Template;
use axum::{
    extract::{Form, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Redirect, Response},
};
use serde::Deserialize;
use std::sync::Arc;

use crate::AppState;
use crate::db;
use crate::models::{
    Account, Balance, Incoming, NewAccount, NewBalance, NewIncoming, NewOutgoing, Outgoing,
};
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
    pub outgoings: Vec<Outgoing>,
    pub editing: bool,
    pub editing_outgoing: Option<Outgoing>,
}

#[derive(Template)]
#[template(path = "incomings.html")]
pub struct IncomingsTemplate {
    pub incomings: Vec<Incoming>,
    pub editing: bool,
    pub editing_incoming: Option<Incoming>,
}

#[derive(Template)]
#[template(path = "balances.html")]
pub struct BalancesTemplate {
    pub balances: Vec<Balance>,
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

#[derive(Debug, Deserialize)]
pub struct IncomingForm {
    pub id: Option<i64>,
    pub name: String,
    #[serde(alias = "netValue")]
    pub net_value: f32,
    #[serde(alias = "incomingMonthDay")]
    pub incoming_month_day: i32,
    pub payday: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct OutgoingForm {
    pub id: Option<i64>,
    pub name: String,
    pub cost: f32,
    #[serde(alias = "outgoingDay")]
    pub outgoing_day: i32,
    #[serde(alias = "accountId", alias = "account_id")]
    pub account_id: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct BalanceForm {
    #[serde(alias = "accountId")]
    pub account_id: i64,
    pub balance: f32,
}

pub async fn get_accounts_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(crate::landing::auth_redirect(Some("/myAccounts")).await.into_response());
    }
    let user_id = resolve_user_id(&state.pool, "demo").await;
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
    let user_id = resolve_user_id(&state.pool, "demo").await;
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
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(form): Form<AccountForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let update = crate::models::UpdateAccount {
        name: form.name,
        nickname: form.nickname,
        account_type: form.account_type.unwrap_or_else(|| "CURRENT".to_string()),
        account_limit: form.available_limit,
        interest_rate: form.interest_rate,
    };
    db::update_account(&state.pool, id, &update)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myAccounts"))
}

pub async fn post_update_account_form_direct(
    State(state): State<Arc<AppState>>,
    Form(form): Form<AccountForm>,
) -> Result<Redirect, (StatusCode, String)> {
    if let Some(id) = form.id {
        let update = crate::models::UpdateAccount {
            name: form.name,
            nickname: form.nickname,
            account_type: form.account_type.unwrap_or_else(|| "CURRENT".to_string()),
            account_limit: form.available_limit,
            interest_rate: form.interest_rate,
        };
        db::update_account(&state.pool, id, &update)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    }
    Ok(Redirect::to("/myAccounts"))
}

pub async fn post_account_form(
    State(state): State<Arc<AppState>>,
    Form(form): Form<AccountForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, "demo").await;
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
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let outgoings = db::list_outgoings(&state.pool, &user_id)
        .await
        .unwrap_or_default();

    let tmpl = OutgoingsTemplate {
        outgoings,
        editing: false,
        editing_outgoing: None,
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
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let outgoings = db::list_outgoings(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let editing_outgoing = outgoings.iter().find(|o| o.id == id).cloned();
    let editing = editing_outgoing.is_some();

    let tmpl = OutgoingsTemplate {
        outgoings,
        editing,
        editing_outgoing,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_update_outgoing_form(
    State(state): State<Arc<AppState>>,
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(form): Form<OutgoingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let update = crate::models::UpdateOutgoing {
        name: form.name,
        cost: form.cost,
        outgoing_day: form.outgoing_day,
        user_id: Some(user_id),
        account_id: form.account_id,
        outgoing_month: None,
        year_interval: None,
        starting_year: None,
        bill: Some(true),
        rent: Some(false),
        hidden_from_total: Some(false),
    };
    db::update_outgoing(&state.pool, id, &update)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myOutgoings"))
}

pub async fn post_update_outgoing_form_direct(
    State(state): State<Arc<AppState>>,
    Form(form): Form<OutgoingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    if let Some(id) = form.id {
        let user_id = resolve_user_id(&state.pool, "demo").await;
        let update = crate::models::UpdateOutgoing {
            name: form.name,
            cost: form.cost,
            outgoing_day: form.outgoing_day,
            user_id: Some(user_id),
            account_id: form.account_id,
            outgoing_month: None,
            year_interval: None,
            starting_year: None,
            bill: Some(true),
            rent: Some(false),
            hidden_from_total: Some(false),
        };
        db::update_outgoing(&state.pool, id, &update)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    }
    Ok(Redirect::to("/myOutgoings"))
}

pub async fn post_outgoing_form(
    State(state): State<Arc<AppState>>,
    Form(form): Form<OutgoingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let new_out = NewOutgoing {
        name: form.name,
        cost: form.cost,
        outgoing_day: form.outgoing_day,
        outgoing_month: None,
        year_interval: None,
        starting_year: None,
        bill: Some(true),
        rent: Some(false),
        hidden_from_total: Some(false),
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
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let incomings = db::list_incomings(&state.pool, &user_id)
        .await
        .unwrap_or_default();

    let tmpl = IncomingsTemplate {
        incomings,
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
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let incomings = db::list_incomings(&state.pool, &user_id)
        .await
        .unwrap_or_default();
    let editing_incoming = incomings.iter().find(|i| i.id == id).cloned();
    let editing = editing_incoming.is_some();

    let tmpl = IncomingsTemplate {
        incomings,
        editing,
        editing_incoming,
    };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_update_incoming_form(
    State(state): State<Arc<AppState>>,
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(form): Form<IncomingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let update = crate::models::UpdateIncoming {
        name: form.name,
        net_value: form.net_value,
        gross_value: Some(form.net_value),
        incoming_month_day: form.incoming_month_day,
        user_id: Some(user_id),
        account_id: None,
        payday: form.payday,
    };
    db::update_incoming(&state.pool, id, &update)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myIncomings"))
}

pub async fn post_update_incoming_form_direct(
    State(state): State<Arc<AppState>>,
    Form(form): Form<IncomingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    if let Some(id) = form.id {
        let user_id = resolve_user_id(&state.pool, "demo").await;
        let update = crate::models::UpdateIncoming {
            name: form.name,
            net_value: form.net_value,
            gross_value: Some(form.net_value),
            incoming_month_day: form.incoming_month_day,
            user_id: Some(user_id),
            account_id: None,
            payday: form.payday,
        };
        db::update_incoming(&state.pool, id, &update)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    }
    Ok(Redirect::to("/myIncomings"))
}

pub async fn post_incoming_form(
    State(state): State<Arc<AppState>>,
    Form(form): Form<IncomingForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let new_inc = NewIncoming {
        name: form.name,
        net_value: form.net_value,
        gross_value: Some(form.net_value),
        incoming_month_day: form.incoming_month_day,
        user_id,
        account_id: None,
        payday: form.payday,
    };
    db::create_incoming(&state.pool, &new_inc)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myIncomings"))
}

pub async fn get_balances_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    if !is_authenticated(&headers) {
        return Ok(crate::landing::auth_redirect(Some("/myBalances")).await.into_response());
    }
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let balances = db::list_balances(&state.pool, &user_id)
        .await
        .unwrap_or_default();

    let tmpl = BalancesTemplate { balances };
    tmpl.render()
        .map(|html| Html(html).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_balance_form(
    State(state): State<Arc<AppState>>,
    Form(form): Form<BalanceForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let new_bal = NewBalance {
        account_id: form.account_id,
        value: form.balance,
        timestamp: Some(chrono::Utc::now().timestamp_millis()),
        user_id,
    };
    db::create_balance(&state.pool, &new_bal)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myBalances"))
}

#[derive(Debug, Deserialize)]
pub struct PauseForm {
    pub reason: Option<String>,
}

pub async fn pause_outgoing_web(
    axum::extract::Path(id): axum::extract::Path<i64>,
    State(state): State<Arc<AppState>>,
    Form(form): Form<PauseForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, "demo").await;
    let reason = form.reason.unwrap_or_else(|| "User requested pause".to_string());
    db::pause_outgoing(&state.pool, id, &user_id, &reason)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myOutgoings"))
}

pub async fn unpause_outgoing_web(
    axum::extract::Path(id): axum::extract::Path<i64>,
    State(state): State<Arc<AppState>>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = resolve_user_id(&state.pool, "demo").await;
    db::unpause_outgoing(&state.pool, id, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myOutgoings"))
}
