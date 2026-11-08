use askama::Template;
use axum::{
    extract::State,
    http::StatusCode,
    response::Html,
};
use std::sync::Arc;

use crate::AppState;
use crate::db;
use crate::models::{Account, Balance, Incoming, Outgoing};

#[derive(Template)]
#[template(path = "accounts.html")]
pub struct AccountsTemplate {
    pub accounts: Vec<Account>,
}

#[derive(Template)]
#[template(path = "outgoings.html")]
pub struct OutgoingsTemplate {
    pub outgoings: Vec<Outgoing>,
}

#[derive(Template)]
#[template(path = "incomings.html")]
pub struct IncomingsTemplate {
    pub incomings: Vec<Incoming>,
}

#[derive(Template)]
#[template(path = "balances.html")]
pub struct BalancesTemplate {
    pub balances: Vec<Balance>,
}

pub async fn get_accounts_page(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
    let accounts = db::list_accounts(&state.pool, "demo")
        .await
        .unwrap_or_default();

    let tmpl = AccountsTemplate { accounts };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_outgoings_page(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
    let outgoings = db::list_outgoings(&state.pool, "demo")
        .await
        .unwrap_or_default();

    let tmpl = OutgoingsTemplate { outgoings };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_incomings_page(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
    let incomings = db::list_incomings(&state.pool, "demo")
        .await
        .unwrap_or_default();

    let tmpl = IncomingsTemplate { incomings };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_balances_page(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
    let balances = db::list_balances(&state.pool, "demo")
        .await
        .unwrap_or_default();

    let tmpl = BalancesTemplate { balances };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}
