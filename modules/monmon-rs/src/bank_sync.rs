use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Form, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Json, Redirect, Response},
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::Row;
use std::collections::HashMap;
use std::sync::{Arc, OnceLock};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BankSyncQuery {
    #[serde(alias = "user_id")]
    pub user_id: Option<String>,
    #[serde(alias = "account_type")]
    pub account_type: Option<String>,
    pub info: Option<String>,
    pub error: Option<String>,
}

impl BankSyncQuery {
    pub fn get_user_id(&self) -> String {
        self.user_id
            .clone()
            .unwrap_or_else(|| "demo".to_string())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BankTransactionDto {
    pub id: i64,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub external_id: Option<String>,
    pub timestamp: i64,
    pub date: String,
    pub amount: f32,
    pub description: String,
    pub merchant_name: Option<String>,
    pub category: Option<String>,
    pub source: String,
    pub pending: bool,
    pub merchant_address: Option<String>,
    pub merchant_emoji: Option<String>,
    pub latitude: Option<f64>,
    pub longitude: Option<f64>,
}

#[derive(Debug, Clone, Default)]
pub struct MonzoConnectionRow {
    #[allow(dead_code)]
    pub id: i64,
    #[allow(dead_code)]
    pub userid: String,
    pub accesstoken: Option<String>,
    pub refreshtoken: Option<String>,
    pub monzouserid: Option<String>,
    pub expiresat: Option<i64>,
    pub clientid: Option<String>,
    pub clientsecret: Option<String>,
    pub redirecturi: Option<String>,
    pub webhook_debit_id: Option<String>,
    pub webhook_flex_id: Option<String>,
    pub webhook_url: Option<String>,
    pub last_received_at: Option<i64>,
    pub monzo_debit_account_id: Option<String>,
    pub monzo_flex_account_id: Option<String>,
}

impl MonzoConnectionRow {
    pub fn has_active_webhooks(&self) -> bool {
        self.webhook_debit_id.as_ref().map(|s| !s.trim().is_empty()).unwrap_or(false)
            || self.webhook_flex_id.as_ref().map(|s| !s.trim().is_empty()).unwrap_or(false)
    }

    pub fn has_refresh_token(&self) -> bool {
        self.refreshtoken.as_ref().map(|s| !s.trim().is_empty()).unwrap_or(false)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingStatementDto {
    pub id: i64,
    pub job_id: String,
    pub bank_name: String,
    pub start_date: String,
    pub end_date: String,
    pub opening_balance: Option<f32>,
    pub closing_balance: Option<f32>,
    pub transaction_count: i32,
    pub total_paid_in: Option<f32>,
    pub total_paid_out: Option<f32>,
    pub matched_account_id: Option<i64>,
    pub status: String,
    pub is_tx_list: bool,
}

impl PendingStatementDto {
    pub fn has_paid_in(&self) -> bool {
        self.total_paid_in.map(|v| v > 0.0).unwrap_or(false)
    }
    pub fn has_paid_out(&self) -> bool {
        self.total_paid_out.map(|v| v > 0.0).unwrap_or(false)
    }
    pub fn formatted_paid_in(&self) -> String {
        format!("{:.2}", self.total_paid_in.unwrap_or(0.0))
    }
    pub fn formatted_paid_out(&self) -> String {
        format!("{:.2}", self.total_paid_out.unwrap_or(0.0))
    }
    pub fn formatted_closing(&self) -> String {
        format!("{:.2}", self.closing_balance.unwrap_or(0.0))
    }
    pub fn formatted_opening(&self) -> String {
        format!("{:.2}", self.opening_balance.unwrap_or(0.0))
    }
    pub fn matched_account_name<'a>(&self, accounts: &'a [AccountOptionDto]) -> &'a str {
        if let Some(mid) = self.matched_account_id {
            for a in accounts {
                if a.id == mid {
                    return &a.name;
                }
            }
        }
        "Unlinked Account"
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountOptionDto {
    pub id: i64,
    pub name: String,
    pub account_type: String,
    pub ledger_mode: String,
}

impl AccountOptionDto {
    pub fn is_matched(&self, matched: &Option<i64>) -> bool {
        *matched == Some(self.id)
    }
}

#[derive(Template)]
#[template(path = "bank_sync.html")]
pub struct BankSyncTemplate {
    pub is_monzo_connected: bool,
    pub has_refresh_token: bool,
    pub has_active_webhooks: bool,
    pub monzo_user_id: Option<String>,
    pub client_id: Option<String>,
    #[allow(dead_code)]
    pub client_secret_masked: Option<String>,
    pub redirect_uri: String,
    pub webhook_url: Option<String>,
    #[allow(dead_code)]
    pub webhook_debit_id: Option<String>,
    #[allow(dead_code)]
    pub webhook_flex_id: Option<String>,
    pub last_received_text: String,
    pub accounts_count: usize,
    pub txs_count: usize,
    pub recent_transactions: Vec<BankTransactionDto>,
    pub pending_statements: Vec<PendingStatementDto>,
    pub confirmed_statements: Vec<PendingStatementDto>,
    pub paydays_json: String,
    pub accounts: Vec<AccountOptionDto>,
    pub accounts_json: String,
    pub info_message: Option<String>,
    pub error_message: Option<String>,
}

pub async fn get_monzo_connection(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<Option<MonzoConnectionRow>, sqlx::Error> {
    let row = sqlx::query(
        r#"
        SELECT
            id, userid, accesstoken, refreshtoken, monzouserid, expiresat,
            clientid, clientsecret, redirecturi,
            COALESCE(webhook_debit_id, webhookdebitid) as wh_debit,
            COALESCE(webhook_flex_id, webhookflexid) as wh_flex,
            COALESCE(webhook_url, webhookurl) as wh_url,
            COALESCE(last_received_at, lastreceivedat) as last_recv,
            monzo_debit_account_id,
            monzo_flex_account_id
        FROM monzo_connection
        WHERE userid = $1
        LIMIT 1
        "#,
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await?;

    Ok(row.map(|r| MonzoConnectionRow {
        id: r.get("id"),
        userid: r.get("userid"),
        accesstoken: r.get("accesstoken"),
        refreshtoken: r.get("refreshtoken"),
        monzouserid: r.get("monzouserid"),
        expiresat: r.get("expiresat"),
        clientid: r.get("clientid"),
        clientsecret: r.get("clientsecret"),
        redirecturi: r.get("redirecturi"),
        webhook_debit_id: r.get("wh_debit"),
        webhook_flex_id: r.get("wh_flex"),
        webhook_url: r.get("wh_url"),
        last_received_at: r.get("last_recv"),
        monzo_debit_account_id: r.get("monzo_debit_account_id"),
        monzo_flex_account_id: r.get("monzo_flex_account_id"),
    }))
}

pub fn resolve_client_id(conn: Option<&MonzoConnectionRow>) -> String {
    if let Some(c) = conn {
        if let Some(ref cid) = c.clientid {
            if !cid.trim().is_empty() {
                return cid.trim().to_string();
            }
        }
    }
    std::env::var("MONZO_CLIENT_ID").unwrap_or_default().trim().to_string()
}

pub fn resolve_client_secret(conn: Option<&MonzoConnectionRow>) -> String {
    if let Some(c) = conn {
        if let Some(ref sec) = c.clientsecret {
            if !sec.trim().is_empty() {
                return sec.trim().to_string();
            }
        }
    }
    std::env::var("MONZO_CLIENT_SECRET").unwrap_or_default().trim().to_string()
}

pub fn resolve_redirect_uri(conn: Option<&MonzoConnectionRow>, headers: &HeaderMap) -> String {
    if let Some(c) = conn {
        if let Some(ref ruri) = c.redirecturi {
            if !ruri.trim().is_empty() {
                return ruri.trim().to_string();
            }
        }
    }
    if let Ok(env_uri) = std::env::var("MONZO_REDIRECT_URI") {
        if !env_uri.trim().is_empty() {
            return env_uri.trim().to_string();
        }
    }
    let host = headers.get("host").and_then(|h| h.to_str().ok()).unwrap_or("monmon.dryark.uk");
    let scheme = headers.get("x-forwarded-proto").and_then(|h| h.to_str().ok()).unwrap_or("https");
    format!("{}://{}/bank/monzo/callback", scheme, host)
}

pub fn resolve_base_url(headers: &HeaderMap) -> String {
    let host = headers.get("host").and_then(|h| h.to_str().ok()).unwrap_or("monmon.dryark.uk");
    let scheme = headers.get("x-forwarded-proto").and_then(|h| h.to_str().ok()).unwrap_or("https");
    format!("{}://{}", scheme, host)
}

pub async fn get_bank_sync_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<BankSyncQuery>,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let conn_opt = get_monzo_connection(&state.pool, &resolved).await.unwrap_or(None);
    let is_monzo_connected = conn_opt.as_ref().map(|c| c.accesstoken.as_ref().map(|t| !t.trim().is_empty()).unwrap_or(false)).unwrap_or(false);
    let has_refresh_token = conn_opt.as_ref().map(|c| c.has_refresh_token()).unwrap_or(false);
    let has_active_webhooks = conn_opt.as_ref().map(|c| c.has_active_webhooks()).unwrap_or(false);
    let monzo_user_id = conn_opt.as_ref().and_then(|c| c.monzouserid.clone());
    let client_id = conn_opt.as_ref().and_then(|c| c.clientid.clone()).or_else(|| std::env::var("MONZO_CLIENT_ID").ok());
    let client_secret_masked = conn_opt.as_ref().and_then(|c| c.clientsecret.as_ref().map(|s| if s.len() > 8 { format!("{}...", &s[..8]) } else { "********".to_string() }));
    let redirect_uri = resolve_redirect_uri(conn_opt.as_ref(), &headers);
    let webhook_url = conn_opt.as_ref().and_then(|c| c.webhook_url.clone());
    let webhook_debit_id = conn_opt.as_ref().and_then(|c| c.webhook_debit_id.clone());
    let webhook_flex_id = conn_opt.as_ref().and_then(|c| c.webhook_flex_id.clone());

    let last_received_text = if let Some(last_ts) = conn_opt.as_ref().and_then(|c| c.last_received_at) {
        let diff = (Utc::now().timestamp_millis() - last_ts) / 1000;
        if diff < 60 {
            "Last received from Monzo just now".to_string()
        } else if diff < 3600 {
            format!("Last received from Monzo {}m ago", diff / 60)
        } else if diff < 86400 {
            format!("Last received from Monzo {}h ago", diff / 3600)
        } else {
            format!("Last received from Monzo {}d ago", diff / 86400)
        }
    } else {
        "Awaiting first transaction event".to_string()
    };

    // Query pending statements
    let stmt_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(job_id, '') AS job_id, COALESCE(bank_name, 'Unknown Bank') AS bank_name,
               COALESCE(start_date, '') AS start_date, COALESCE(end_date, '') AS end_date,
               opening_balance, closing_balance, COALESCE(transaction_count, 0) AS transaction_count,
               total_paid_in, total_paid_out, matched_account_id, COALESCE(status, 'PENDING') AS status
        FROM pending_statement
        WHERE (userid = $1 OR user_id = $1) AND status = 'PENDING'
        ORDER BY created_at DESC
        "#,
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let pending_statements: Vec<PendingStatementDto> = stmt_rows
        .into_iter()
        .map(|r| {
            let bank: String = r.get("bank_name");
            let is_tx_list = bank.to_lowercase().contains("transaction") || bank.to_lowercase().contains("txn");
            PendingStatementDto {
                id: r.get("id"),
                job_id: r.get("job_id"),
                bank_name: bank,
                start_date: r.get("start_date"),
                end_date: r.get("end_date"),
                opening_balance: r.get("opening_balance"),
                closing_balance: r.get("closing_balance"),
                transaction_count: r.get("transaction_count"),
                total_paid_in: r.get("total_paid_in"),
                total_paid_out: r.get("total_paid_out"),
                matched_account_id: r.get("matched_account_id"),
                status: r.get("status"),
                is_tx_list,
            }
        })
        .collect();

    // Query confirmed statements (for Feature A Rollback / Revert)
    let conf_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(job_id, '') AS job_id, COALESCE(bank_name, 'Unknown Bank') AS bank_name,
               COALESCE(start_date, '') AS start_date, COALESCE(end_date, '') AS end_date,
               opening_balance, closing_balance, COALESCE(transaction_count, 0) AS transaction_count,
               total_paid_in, total_paid_out, matched_account_id, COALESCE(status, 'CONFIRMED') AS status
        FROM pending_statement
        WHERE (userid = $1 OR user_id = $1) AND status = 'CONFIRMED'
        ORDER BY applied_at DESC NULLS LAST, id DESC
        LIMIT 5
        "#,
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let confirmed_statements: Vec<PendingStatementDto> = conf_rows
        .into_iter()
        .map(|r| {
            let bank: String = r.get("bank_name");
            let is_tx_list = bank.to_lowercase().contains("transaction") || bank.to_lowercase().contains("txn");
            PendingStatementDto {
                id: r.get("id"),
                job_id: r.get("job_id"),
                bank_name: bank,
                start_date: r.get("start_date"),
                end_date: r.get("end_date"),
                opening_balance: r.get("opening_balance"),
                closing_balance: r.get("closing_balance"),
                transaction_count: r.get("transaction_count"),
                total_paid_in: r.get("total_paid_in"),
                total_paid_out: r.get("total_paid_out"),
                matched_account_id: r.get("matched_account_id"),
                status: r.get("status"),
                is_tx_list,
            }
        })
        .collect();

    let paydays_json = crate::management::compute_paydays_json(&state.pool, &resolved).await;

    // Query accounts
    let acc_rows = sqlx::query(
        r#"
        SELECT id, name, type, COALESCE(ledger_mode, 'ABL') AS ledger_mode
        FROM account
        WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false)
        ORDER BY name ASC
        "#,
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let accounts_count = acc_rows.len();
    let accounts: Vec<AccountOptionDto> = acc_rows
        .into_iter()
        .map(|r| AccountOptionDto {
            id: r.get("id"),
            name: r.get("name"),
            account_type: r.get("type"),
            ledger_mode: r.get("ledger_mode"),
        })
        .collect();
    let accounts_json = serde_json::to_string(&accounts).unwrap_or_else(|_| "[]".to_string());

    // Query transactions
    let txs = list_bank_transactions_internal(&state.pool, &resolved).await.unwrap_or_default();
    let txs_count = txs.len();

    let tmpl = BankSyncTemplate {
        is_monzo_connected,
        has_refresh_token,
        has_active_webhooks,
        monzo_user_id,
        client_id,
        client_secret_masked,
        redirect_uri,
        webhook_url,
        webhook_debit_id,
        webhook_flex_id,
        last_received_text,
        accounts_count,
        txs_count,
        recent_transactions: txs.into_iter().take(20).collect(),
        pending_statements,
        confirmed_statements,
        paydays_json,
        accounts,
        accounts_json,
        info_message: query.info,
        error_message: query.error,
    };

    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn list_bank_transactions_internal(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<Vec<BankTransactionDto>, sqlx::Error> {
    let rows = sqlx::query(
        r#"
        SELECT 
            id, userid, COALESCE(account_id, accountid) AS account_id,
            externalid, timestamp, date, amount, description,
            COALESCE(merchant_name, merchantname) AS merchant_name,
            category, source, pending,
            COALESCE(merchant_address, merchantaddress) AS merchant_address,
            COALESCE(merchant_emoji, merchantemoji) AS merchant_emoji,
            latitude, longitude
        FROM bank_transaction
        WHERE (userid = $1 OR user_id = $1)
        ORDER BY timestamp DESC NULLS LAST, id DESC
        LIMIT 100
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    Ok(rows
        .into_iter()
        .map(|r| BankTransactionDto {
            id: r.get("id"),
            user_id: r.get("userid"),
            account_id: r.get("account_id"),
            external_id: r.get("externalid"),
            timestamp: r.get("timestamp"),
            date: r.get("date"),
            amount: r.get("amount"),
            description: r.get("description"),
            merchant_name: r.get("merchant_name"),
            category: r.get("category"),
            source: r.get("source"),
            pending: r.get("pending"),
            merchant_address: r.get("merchant_address"),
            merchant_emoji: r.get("merchant_emoji"),
            latitude: r.get("latitude"),
            longitude: r.get("longitude"),
        })
        .collect())
}

pub async fn list_bank_transactions_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<BankSyncQuery>,
) -> Result<Json<Vec<BankTransactionDto>>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    list_bank_transactions_internal(&state.pool, &resolved)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

// ==========================================
// Monzo / Flex Account Separation Types & Helpers
// ==========================================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MonzoAccountKind {
    Debit,
    Flex,
}

impl MonzoAccountKind {
    pub fn from_monzo_type(monzo_type: &str) -> Option<Self> {
        match monzo_type.trim().to_ascii_lowercase().as_str() {
            "uk_monzo_flex" => Some(Self::Flex),
            "uk_retail" | "uk_retail_joint" => Some(Self::Debit),
            _ => None,
        }
    }

    pub fn from_query_hint(hint: Option<&str>) -> Option<Self> {
        let h = hint?.trim().to_ascii_lowercase();
        match h.as_str() {
            "flex" | "credit" | "uk_monzo_flex" => Some(Self::Flex),
            "debit" | "current" | "uk_retail" | "uk_retail_joint" => Some(Self::Debit),
            _ => None,
        }
    }

    pub fn query_param(self) -> &'static str {
        match self {
            Self::Debit => "debit",
            Self::Flex => "flex",
        }
    }

    pub fn display_name(self) -> &'static str {
        match self {
            Self::Debit => "Monzo Current Account (Debit)",
            Self::Flex => "Monzo Flex (Credit Card)",
        }
    }
}

pub fn build_webhook_callback_url(base_url: &str, user_id: &str, kind: MonzoAccountKind) -> String {
    format!(
        "{}/api/monzo/webhook?userId={}&accountType={}",
        base_url.trim_end_matches('/'),
        urlencoding::encode(user_id),
        kind.query_param()
    )
}

fn json_value_is_truthy(val: &Value) -> bool {
    match val {
        Value::Bool(b) => *b,
        Value::String(s) => s.trim().eq_ignore_ascii_case("true"),
        _ => false,
    }
}

fn json_value_non_empty_str(val: &Value) -> bool {
    val.as_str().map(|s| !s.trim().is_empty()).unwrap_or(false)
}

pub fn payload_has_flex_signals(data: &Value) -> bool {
    if data
        .get("scheme")
        .and_then(|v| v.as_str())
        .map(|s| s.eq_ignore_ascii_case("monzo_flex"))
        .unwrap_or(false)
    {
        return true;
    }

    if data
        .get("dedupe_id")
        .and_then(|v| v.as_str())
        .map(|s| s.to_ascii_lowercase().contains("monzoflex_"))
        .unwrap_or(false)
    {
        return true;
    }

    if let Some(meta) = data.get("metadata") {
        if meta.get("is_flex_direct_virtual_card_tx").map(json_value_is_truthy).unwrap_or(false)
            || meta.get("flex_dedupe_suffix_applied").map(json_value_is_truthy).unwrap_or(false)
            || meta.get("monzo_flex_id").map(json_value_non_empty_str).unwrap_or(false)
            || meta.get("backing_loan_id").map(json_value_non_empty_str).unwrap_or(false)
            || meta.get("flex_transaction_type").map(json_value_non_empty_str).unwrap_or(false)
        {
            return true;
        }
    }

    false
}

pub fn is_declined_transaction(data: &Value) -> bool {
    data.get("decline_reason")
        .and_then(|v| v.as_str())
        .map(|s| !s.trim().is_empty())
        .unwrap_or(false)
}

/// Authoritative classification of a Monzo webhook event into `Debit` vs `Flex`.
///
/// Precedence:
/// 1. Known Monzo `account_id` (persisted cache or live `/accounts` API). This MUST beat payload
///    metadata because Flex repayments debited from the current account carry `scheme="monzo_flex"`
///    and `metadata.monzo_flex_id` even though they belong to the Debit account.
/// 2. Explicit per-account webhook callback query parameter (`?accountType=debit|flex`).
/// 3. Flex-specific payload metadata / scheme signals.
/// 4. Fail-closed (`None`) whenever the user has an active Monzo connection — NEVER default an
///    unclassified webhook into Debit and risk writing a Flex balance onto the current account.
///    Only disconnected / synthetic test webhooks fall back to `Debit`.
pub fn classify_webhook_account_kind_pure(
    monzo_acc_id: &str,
    query_hint: Option<&str>,
    cached_debit_id: Option<&str>,
    cached_flex_id: Option<&str>,
    api_resolved: Option<MonzoAccountKind>,
    has_active_connection: bool,
    data: &Value,
) -> Option<MonzoAccountKind> {
    let acc = monzo_acc_id.trim();
    if !acc.is_empty() {
        if cached_flex_id.map(|s| s.trim() == acc).unwrap_or(false) {
            return Some(MonzoAccountKind::Flex);
        }
        if cached_debit_id.map(|s| s.trim() == acc).unwrap_or(false) {
            return Some(MonzoAccountKind::Debit);
        }
    }

    if let Some(kind) = api_resolved {
        return Some(kind);
    }

    if let Some(kind) = MonzoAccountKind::from_query_hint(query_hint) {
        return Some(kind);
    }

    if payload_has_flex_signals(data) {
        return Some(MonzoAccountKind::Flex);
    }

    if has_active_connection {
        None
    } else {
        Some(MonzoAccountKind::Debit)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct AccountCandidate {
    pub id: i64,
    pub name: String,
    pub account_type: String,
    pub account_limit: f64,
    pub ledger_mode: String,
}

/// Strictly separates Monzo Debit (`DEBIT`) and Monzo Flex (`CREDIT`) MonMon accounts.
/// Never matches `DEBIT_SHARED_BILLS`, savings, pensions, or loans, and never matches a Flex-named
/// account for Debit or a Debit account for Flex.
pub fn select_target_account<'a>(
    accounts: &'a [AccountCandidate],
    kind: MonzoAccountKind,
    allow_unnamed_fallback: bool,
) -> Option<&'a AccountCandidate> {
    match kind {
        MonzoAccountKind::Flex => {
            let credits: Vec<&AccountCandidate> = accounts
                .iter()
                .filter(|a| a.account_type.eq_ignore_ascii_case("CREDIT"))
                .collect();
            credits
                .iter()
                .copied()
                .find(|a| a.name.to_ascii_lowercase().contains("flex"))
                .or_else(|| {
                    credits
                        .iter()
                        .copied()
                        .find(|a| a.name.to_ascii_lowercase().contains("monzo"))
                })
                .or_else(|| {
                    if allow_unnamed_fallback {
                        credits.into_iter().next()
                    } else {
                        None
                    }
                })
        }
        MonzoAccountKind::Debit => {
            let debits: Vec<&AccountCandidate> = accounts
                .iter()
                .filter(|a| a.account_type.eq_ignore_ascii_case("DEBIT"))
                .collect();
            debits
                .iter()
                .copied()
                .find(|a| {
                    let lower = a.name.to_ascii_lowercase();
                    lower.contains("monzo") && !lower.contains("flex")
                })
                .or_else(|| {
                    debits
                        .iter()
                        .copied()
                        .find(|a| a.name.to_ascii_lowercase().contains("monzo"))
                })
                .or_else(|| {
                    if allow_unnamed_fallback {
                        debits
                            .iter()
                            .copied()
                            .find(|a| !a.name.to_ascii_lowercase().contains("flex"))
                            .or_else(|| debits.into_iter().next())
                    } else {
                        None
                    }
                })
        }
    }
}

// ==========================================
// Monzo OAuth Token Refresh & Helpers (Deduplicated per User)
// ==========================================

static MONZO_REFRESH_LOCKS: OnceLock<std::sync::Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>> =
    OnceLock::new();

fn user_refresh_lock(user_id: &str) -> Arc<tokio::sync::Mutex<()>> {
    let map = MONZO_REFRESH_LOCKS.get_or_init(|| std::sync::Mutex::new(HashMap::new()));
    let mut guard = map.lock().unwrap_or_else(|e| e.into_inner());
    guard
        .entry(user_id.to_string())
        .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(())))
        .clone()
}

async fn refresh_monzo_token_locked(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<String, String> {
    let row = get_monzo_connection(pool, user_id).await.map_err(|e| e.to_string())?;
    let conn = row.ok_or_else(|| "No Monzo connection found for user".to_string())?;
    let refresh_tok = conn.refreshtoken.clone().filter(|t| !t.trim().is_empty())
        .ok_or_else(|| "No Monzo refresh_token available. Please re-authenticate via OAuth.".to_string())?;

    let client_id = resolve_client_id(Some(&conn));
    let client_secret = resolve_client_secret(Some(&conn));

    if client_id.is_empty() || client_secret.is_empty() {
        return Err("Monzo OAuth Client ID or Client Secret is missing.".to_string());
    }

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    let params = [
        ("grant_type", "refresh_token"),
        ("client_id", client_id.as_str()),
        ("client_secret", client_secret.as_str()),
        ("refresh_token", refresh_tok.as_str()),
    ];

    let resp = client.post("https://api.monzo.com/oauth2/token")
        .form(&params)
        .send()
        .await
        .map_err(|e| format!("Failed to call Monzo token refresh endpoint: {}", e))?;

    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(format!("Monzo token refresh failed (HTTP {}): {}", status, body));
    }

    let json: Value = serde_json::from_str(&body).map_err(|e| format!("Failed to parse Monzo refresh JSON: {}", e))?;
    let new_access = json.get("access_token").and_then(|v| v.as_str()).ok_or_else(|| "Missing access_token".to_string())?;
    let new_refresh = json.get("refresh_token").and_then(|v| v.as_str()).unwrap_or(&refresh_tok);
    let expires_in = json.get("expires_in").and_then(|v| v.as_i64()).unwrap_or(21600);
    let monzo_uid = json.get("user_id").and_then(|v| v.as_str());

    let now_ms = Utc::now().timestamp_millis();
    let expires_at = now_ms + (expires_in * 1000);

    sqlx::query(
        r#"
        UPDATE monzo_connection
        SET accesstoken = $1, refreshtoken = $2, expiresat = $3, lastsyncedat = $4, updated_at = $4,
            monzouserid = COALESCE($5, monzouserid)
        WHERE userid = $6
        "#,
    )
    .bind(new_access)
    .bind(new_refresh)
    .bind(expires_at)
    .bind(now_ms)
    .bind(monzo_uid)
    .bind(user_id)
    .execute(pool)
    .await
    .map_err(|e| e.to_string())?;

    tracing::info!("[Monzo] Successfully refreshed OAuth token for user {}", user_id);
    Ok(new_access.to_string())
}

pub async fn refresh_monzo_token_internal(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<String, String> {
    let lock = user_refresh_lock(user_id);
    let _guard = lock.lock().await;
    refresh_monzo_token_locked(pool, user_id).await
}

pub async fn ensure_valid_token(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<Option<String>, String> {
    let row = get_monzo_connection(pool, user_id).await.map_err(|e| e.to_string())?;
    let Some(conn) = row else {
        return Ok(None);
    };

    let now_ms = Utc::now().timestamp_millis();
    let expires_at = conn.expiresat.unwrap_or(0);
    let has_refresh = conn.has_refresh_token();

    if has_refresh && (expires_at == 0 || expires_at < now_ms + 300_000) {
        let lock = user_refresh_lock(user_id);
        let _guard = lock.lock().await;

        // Re-check DB after acquiring the per-user lock: a concurrent webhook or request may
        // have already rotated Monzo's single-use refresh token while we waited.
        let fresh_row = get_monzo_connection(pool, user_id).await.map_err(|e| e.to_string())?;
        let Some(fresh_conn) = fresh_row else {
            return Ok(None);
        };
        let now_after_lock = Utc::now().timestamp_millis();
        let fresh_exp = fresh_conn.expiresat.unwrap_or(0);
        if fresh_exp >= now_after_lock + 300_000 {
            if let Some(tok) = fresh_conn.accesstoken.as_ref().filter(|t| !t.trim().is_empty()) {
                return Ok(Some(tok.clone()));
            }
        }

        match refresh_monzo_token_locked(pool, user_id).await {
            Ok(new_tok) => return Ok(Some(new_tok)),
            Err(err) => {
                tracing::warn!("[Monzo] Proactive token refresh failed for user {}: {}", user_id, err);
                // If the token is already expired, do NOT silently hand back a dead token.
                if fresh_exp > 0 && fresh_exp <= now_after_lock {
                    return Err(err);
                }
                return Ok(fresh_conn.accesstoken);
            }
        }
    }

    Ok(conn.accesstoken)
}

async fn refresh_after_unauthorized(
    pool: &sqlx::PgPool,
    user_id: &str,
    failed_token: &str,
) -> Result<String, String> {
    let lock = user_refresh_lock(user_id);
    let _guard = lock.lock().await;

    if let Ok(Some(conn)) = get_monzo_connection(pool, user_id).await {
        if let Some(tok) = conn.accesstoken.filter(|t| !t.trim().is_empty() && t != failed_token) {
            return Ok(tok);
        }
    }
    refresh_monzo_token_locked(pool, user_id).await
}

async fn monzo_get_json_with_retry(
    pool: &sqlx::PgPool,
    user_id: &str,
    url: &str,
    query: &[(&str, &str)],
) -> Result<(reqwest::StatusCode, Value), String> {
    let token = ensure_valid_token(pool, user_id)
        .await?
        .ok_or_else(|| "No Monzo access token available".to_string())?;
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    let mut resp = client
        .get(url)
        .bearer_auth(&token)
        .query(query)
        .send()
        .await
        .map_err(|e| format!("Failed to call Monzo {}: {}", url, e))?;

    if resp.status().as_u16() == 401 {
        if let Ok(new_tok) = refresh_after_unauthorized(pool, user_id, &token).await {
            resp = client
                .get(url)
                .bearer_auth(&new_tok)
                .query(query)
                .send()
                .await
                .map_err(|e| format!("Failed to retry Monzo {}: {}", url, e))?;
        }
    }

    let status = resp.status();
    let json = resp.json::<Value>().await.unwrap_or_else(|_| serde_json::json!({}));
    Ok((status, json))
}

async fn persist_monzo_account_ids(
    pool: &sqlx::PgPool,
    user_id: &str,
    debit_id: Option<&str>,
    flex_id: Option<&str>,
) {
    if debit_id.is_none() && flex_id.is_none() {
        return;
    }
    let now_ms = Utc::now().timestamp_millis();
    let _ = sqlx::query(
        r#"
        UPDATE monzo_connection
        SET monzo_debit_account_id = COALESCE($1, monzo_debit_account_id),
            monzo_flex_account_id = COALESCE($2, monzo_flex_account_id),
            updated_at = $3
        WHERE userid = $4
        "#,
    )
    .bind(debit_id)
    .bind(flex_id)
    .bind(now_ms)
    .bind(user_id)
    .execute(pool)
    .await;
}

/// Fetches active (non-closed) Monzo accounts as `(id, type)` pairs using the deduplicated token
/// with automatic 401 retry, and caches the discovered Debit and Flex account IDs in Postgres.
async fn fetch_monzo_accounts_and_cache(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<(reqwest::StatusCode, Vec<(String, String)>), String> {
    let (status, json) = monzo_get_json_with_retry(pool, user_id, "https://api.monzo.com/accounts", &[]).await?;
    if !status.is_success() {
        return Ok((status, vec![]));
    }
    let monzo_accounts: Vec<(String, String)> = json
        .get("accounts")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter(|a| !a.get("closed").and_then(|v| v.as_bool()).unwrap_or(false))
                .filter_map(|a| {
                    Some((
                        a.get("id")?.as_str()?.to_string(),
                        a.get("type")?.as_str()?.to_string(),
                    ))
                })
                .collect()
        })
        .unwrap_or_default();

    let debit_id = match_monzo_account_id_for_kind(MonzoAccountKind::Debit, &monzo_accounts);
    let flex_id = match_monzo_account_id_for_kind(MonzoAccountKind::Flex, &monzo_accounts);
    persist_monzo_account_ids(pool, user_id, debit_id.as_deref(), flex_id.as_deref()).await;

    Ok((status, monzo_accounts))
}

async fn delete_monzo_webhook_http(client: &reqwest::Client, token: &str, webhook_id: &str) -> bool {
    if webhook_id.trim().is_empty() {
        return false;
    }
    let url = format!("https://api.monzo.com/webhooks/{}", urlencoding::encode(webhook_id));
    match client.delete(&url).bearer_auth(token).send().await {
        Ok(resp) => resp.status().is_success(),
        Err(_) => false,
    }
}

pub async fn register_monzo_webhooks(
    pool: &sqlx::PgPool,
    user_id: &str,
    base_url: &str,
) -> Result<(Option<String>, Option<String>), String> {
    let (status, monzo_accounts) = fetch_monzo_accounts_and_cache(pool, user_id).await?;
    if !status.is_success() {
        return Err(format!("Monzo accounts API returned HTTP {}", status));
    }

    let token = ensure_valid_token(pool, user_id)
        .await?
        .ok_or_else(|| "No Monzo access token available".to_string())?;
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    let mut debit_hook_id = None;
    let mut flex_hook_id = None;

    for kind in [MonzoAccountKind::Debit, MonzoAccountKind::Flex] {
        let Some(acc_id) = match_monzo_account_id_for_kind(kind, &monzo_accounts) else {
            continue;
        };
        let target_url = build_webhook_callback_url(base_url, user_id, kind);
        let mut kept_existing_id: Option<String> = None;

        // List existing webhooks: keep at most one matching the exact per-account URL and
        // HTTP DELETE all duplicates and stale MonMon webhooks (including legacy shared URLs).
        if let Ok(wh_list_resp) = client
            .get("https://api.monzo.com/webhooks")
            .bearer_auth(&token)
            .query(&[("account_id", acc_id.as_str())])
            .send()
            .await
        {
            if let Ok(wh_json) = wh_list_resp.json::<Value>().await {
                if let Some(hooks) = wh_json.get("webhooks").and_then(|v| v.as_array()) {
                    for h in hooks {
                        let h_id = h.get("id").and_then(|v| v.as_str()).unwrap_or("");
                        let h_url = h.get("url").and_then(|v| v.as_str()).unwrap_or("");
                        if h_id.is_empty() {
                            continue;
                        }
                        if h_url == target_url && kept_existing_id.is_none() {
                            kept_existing_id = Some(h_id.to_string());
                        } else if h_url.contains("/api/monzo/webhook")
                            || h_url.contains("dryark.uk")
                            || h_url.contains("monmon")
                        {
                            let _ = delete_monzo_webhook_http(&client, &token, h_id).await;
                        }
                    }
                }
            }
        }

        let hook_id = if let Some(existing) = kept_existing_id {
            Some(existing)
        } else {
            let reg_params = [("account_id", acc_id.as_str()), ("url", target_url.as_str())];
            match client
                .post("https://api.monzo.com/webhooks")
                .bearer_auth(&token)
                .form(&reg_params)
                .send()
                .await
            {
                Ok(resp) if resp.status().is_success() => resp
                    .json::<Value>()
                    .await
                    .ok()
                    .and_then(|j| {
                        j.get("webhook")
                            .and_then(|w| w.get("id"))
                            .and_then(|v| v.as_str())
                            .map(|s| s.to_string())
                    }),
                _ => None,
            }
        };

        match kind {
            MonzoAccountKind::Debit => debit_hook_id = hook_id,
            MonzoAccountKind::Flex => flex_hook_id = hook_id,
        }
    }

    let webhook_base_summary = format!(
        "{}/api/monzo/webhook?userId={}",
        base_url.trim_end_matches('/'),
        urlencoding::encode(user_id)
    );
    let now_ms_wh = Utc::now().timestamp_millis();
    sqlx::query(
        r#"
        UPDATE monzo_connection
        SET webhook_debit_id = $1, webhookdebitid = $1,
            webhook_flex_id = $2, webhookflexid = $2,
            webhook_url = $3, webhookurl = $3,
            updated_at = $4
        WHERE userid = $5
        "#,
    )
    .bind(&debit_hook_id)
    .bind(&flex_hook_id)
    .bind(&webhook_base_summary)
    .bind(now_ms_wh)
    .bind(user_id)
    .execute(pool)
    .await
    .map_err(|e| e.to_string())?;

    Ok((debit_hook_id, flex_hook_id))
}

// ==========================================
// Handlers: Connect, Callback, Refresh, Verify, Disconnect, Webhook Sync
// ==========================================

#[derive(Debug, Deserialize)]
pub struct MonzoConnectForm {
    #[serde(alias = "clientId")]
    pub client_id: Option<String>,
    #[serde(alias = "clientSecret")]
    pub client_secret: Option<String>,
    #[serde(alias = "redirectUri")]
    pub redirect_uri: Option<String>,
}

pub async fn connect_monzo_oauth(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    form_opt: Option<Form<MonzoConnectForm>>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let existing = get_monzo_connection(&state.pool, &resolved).await.unwrap_or(None);

    let form_client_id = form_opt.as_ref().and_then(|f| f.client_id.as_deref()).map(|s| s.trim()).filter(|s| !s.is_empty());
    let form_client_secret = form_opt.as_ref().and_then(|f| f.client_secret.as_deref()).map(|s| s.trim()).filter(|s| !s.is_empty());
    let form_redirect_uri = form_opt.as_ref().and_then(|f| f.redirect_uri.as_deref()).map(|s| s.trim()).filter(|s| !s.is_empty());

    let client_id = form_client_id
        .map(|s| s.to_string())
        .unwrap_or_else(|| resolve_client_id(existing.as_ref()));

    let client_secret = form_client_secret
        .map(|s| s.to_string())
        .unwrap_or_else(|| resolve_client_secret(existing.as_ref()));

    let redirect_uri = form_redirect_uri
        .map(|s| s.to_string())
        .unwrap_or_else(|| resolve_redirect_uri(existing.as_ref(), &headers));

    if client_id.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            "Monzo OAuth Client ID is required. Please provide your Confidential Client ID from developers.monzo.com or set MONZO_CLIENT_ID.".to_string(),
        ));
    }

    // Persist configuration to monzo_connection
    let now_ms = Utc::now().timestamp_millis();
    let _ = sqlx::query(
        r#"
        INSERT INTO monzo_connection (
            id, userid, clientid, clientsecret, redirecturi, createdat, lastsyncedat, created_at, updated_at
        ) VALUES (
            nextval('hibernate_sequence'), $1, $2, $3, $4, $5, $5, $5, $5
        )
        ON CONFLICT (userid) DO UPDATE SET
            clientid = EXCLUDED.clientid,
            clientsecret = CASE WHEN EXCLUDED.clientsecret IS NOT NULL AND EXCLUDED.clientsecret != '' THEN EXCLUDED.clientsecret ELSE monzo_connection.clientsecret END,
            redirecturi = EXCLUDED.redirecturi,
            lastsyncedat = EXCLUDED.lastsyncedat,
            updated_at = EXCLUDED.updated_at
        "#,
    )
    .bind(&resolved)
    .bind(&client_id)
    .bind(&client_secret)
    .bind(&redirect_uri)
    .bind(now_ms)
    .execute(&state.pool)
    .await;

    let encoded_redirect = urlencoding::encode(&redirect_uri);
    let state_val = urlencoding::encode(&resolved);
    let monzo_auth_url = format!(
        "https://auth.monzo.com/?client_id={}&redirect_uri={}&response_type=code&state={}",
        urlencoding::encode(&client_id),
        encoded_redirect,
        state_val
    );

    Ok(Redirect::to(&monzo_auth_url))
}

#[derive(Debug, Deserialize)]
pub struct MonzoCallbackQuery {
    pub code: Option<String>,
    pub state: Option<String>,
}

pub async fn monzo_oauth_callback(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(params): Query<MonzoCallbackQuery>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, params.state.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let code = params.code.filter(|s| !s.trim().is_empty())
        .ok_or_else(|| (StatusCode::BAD_REQUEST, "Missing OAuth authorization code from Monzo".to_string()))?;

    let conn_opt = get_monzo_connection(&state.pool, &resolved).await.unwrap_or(None);
    let client_id = resolve_client_id(conn_opt.as_ref());
    let client_secret = resolve_client_secret(conn_opt.as_ref());
    let redirect_uri = resolve_redirect_uri(conn_opt.as_ref(), &headers);

    if client_id.is_empty() || client_secret.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            "OAuth Client ID or Client Secret not found during callback. Please configure your Monzo credentials first.".to_string(),
        ));
    }

    let http_client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(12))
        .build()
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let form_params = [
        ("grant_type", "authorization_code"),
        ("client_id", client_id.as_str()),
        ("client_secret", client_secret.as_str()),
        ("redirect_uri", redirect_uri.as_str()),
        ("code", code.trim()),
    ];

    let resp = http_client.post("https://api.monzo.com/oauth2/token")
        .form(&form_params)
        .send()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("Failed to reach Monzo token endpoint: {}", e)))?;

    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err((
            StatusCode::BAD_GATEWAY,
            format!("Monzo token exchange failed (HTTP {}): {}", status, body),
        ));
    }

    let json: Value = serde_json::from_str(&body)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("Failed to parse token response: {}", e)))?;

    let access_token = json.get("access_token").and_then(|v| v.as_str())
        .ok_or_else(|| (StatusCode::INTERNAL_SERVER_ERROR, "Missing access_token in response".to_string()))?;
    let refresh_token = json.get("refresh_token").and_then(|v| v.as_str());
    let monzo_user_id = json.get("user_id").and_then(|v| v.as_str()).unwrap_or("monzo-user");
    let expires_in = json.get("expires_in").and_then(|v| v.as_i64()).unwrap_or(21600);

    let now_ms = Utc::now().timestamp_millis();
    let expires_at = now_ms + (expires_in * 1000);

    sqlx::query(
        r#"
        INSERT INTO monzo_connection (
            id, userid, accesstoken, refreshtoken, monzouserid, expiresat,
            clientid, clientsecret, redirecturi, lastsyncedat, createdat, created_at, updated_at
        ) VALUES (
            nextval('hibernate_sequence'), $1, $2, $3, $4, $5,
            $6, $7, $8, $9, $9, $9, $9
        )
        ON CONFLICT (userid) DO UPDATE SET
            accesstoken = EXCLUDED.accesstoken,
            refreshtoken = COALESCE(EXCLUDED.refreshtoken, monzo_connection.refreshtoken),
            monzouserid = EXCLUDED.monzouserid,
            expiresat = EXCLUDED.expiresat,
            clientid = COALESCE(EXCLUDED.clientid, monzo_connection.clientid),
            clientsecret = COALESCE(EXCLUDED.clientsecret, monzo_connection.clientsecret),
            redirecturi = COALESCE(EXCLUDED.redirecturi, monzo_connection.redirecturi),
            lastsyncedat = EXCLUDED.lastsyncedat,
            updated_at = EXCLUDED.updated_at
        "#,
    )
    .bind(&resolved)
    .bind(access_token)
    .bind(refresh_token)
    .bind(monzo_user_id)
    .bind(expires_at)
    .bind(&client_id)
    .bind(&client_secret)
    .bind(&redirect_uri)
    .bind(now_ms)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    // Attempt to register real-time webhooks immediately
    let base_url = resolve_base_url(&headers);
    let _ = register_monzo_webhooks(&state.pool, &resolved, &base_url).await;

    Ok(Redirect::to("/bank?info=Monzo+OAuth+authorization+completed+successfully!"))
}

pub async fn refresh_monzo_token(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    match refresh_monzo_token_internal(&state.pool, &resolved).await {
        Ok(_) => Ok(Redirect::to("/bank?info=Successfully+rotated+and+refreshed+Monzo+OAuth+token.")),
        Err(e) => Ok(Redirect::to(&format!("/bank?error=Failed+to+rotate+token:+{}", urlencoding::encode(&e)))),
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TblAccountVerification {
    pub monmon_account_id: i64,
    pub monmon_account_name: String,
    pub account_type: String,
    pub ledger_mode: String,
    pub matched_to_monzo: bool,
    pub monzo_account_id: Option<String>,
    pub monzo_account_type: Option<String>,
    pub live_monzo_balance: Option<f64>,
    pub available_balance: Option<f64>,
    pub latest_recorded_balance: Option<f64>,
    pub working: bool,
    pub status_detail: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MonzoVerificationReport {
    pub oauth_client_configured: bool,
    pub client_id: String,
    pub oauth_connected: bool,
    pub has_refresh_token: bool,
    pub awaiting_in_app_approval: bool,
    pub api_permissions_granted: bool,
    pub monzo_user_id: String,
    pub token_expires_at: Option<i64>,
    pub summary_message: String,
    pub tbl_accounts: Vec<TblAccountVerification>,
}

pub async fn verify_monzo_connection(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let conn_opt = get_monzo_connection(&state.pool, &resolved).await.unwrap_or(None);
    let client_id = resolve_client_id(conn_opt.as_ref());
    let oauth_client_configured = !client_id.is_empty();

    if conn_opt.is_none() || conn_opt.as_ref().and_then(|c| c.accesstoken.as_ref()).is_none() {
        let rep = MonzoVerificationReport {
            oauth_client_configured,
            client_id,
            oauth_connected: false,
            has_refresh_token: false,
            awaiting_in_app_approval: false,
            api_permissions_granted: false,
            monzo_user_id: "".to_string(),
            token_expires_at: None,
            summary_message: "Monzo is not connected yet. Click 'Authorize with Monzo OAuth' to start.".to_string(),
            tbl_accounts: vec![],
        };
        return Ok(Json(rep).into_response());
    }

    let conn = conn_opt.unwrap();
    let has_refresh_token = conn.has_refresh_token();
    let monzo_user_id = conn.monzouserid.clone().unwrap_or_default();
    let token_expires_at = conn.expiresat;

    let (api_permissions_granted, awaiting_in_app_approval, monzo_accounts) =
        match fetch_monzo_accounts_and_cache(&state.pool, &resolved).await {
            Ok((status, accs)) if status.is_success() => (true, false, accs),
            Ok((status, _)) if status.as_u16() == 403 => (false, true, vec![]),
            _ => (false, false, vec![]),
        };

    // Fetch live Monzo balances separately for Debit and Flex
    let debit_monzo_id = match_monzo_account_id_for_kind(MonzoAccountKind::Debit, &monzo_accounts);
    let flex_monzo_id = match_monzo_account_id_for_kind(MonzoAccountKind::Flex, &monzo_accounts);
    let debit_live_bal = match debit_monzo_id.as_deref() {
        Some(id) if api_permissions_granted => fetch_monzo_balance(&state.pool, &resolved, id).await,
        _ => None,
    };
    let flex_live_bal = match flex_monzo_id.as_deref() {
        Some(id) if api_permissions_granted => fetch_monzo_balance(&state.pool, &resolved, id).await,
        _ => None,
    };

    let candidates = load_active_account_candidates(&state.pool, &resolved).await;
    let matched_debit_monmon_id = select_target_account(&candidates, MonzoAccountKind::Debit, false).map(|a| a.id);
    let matched_flex_monmon_id = select_target_account(&candidates, MonzoAccountKind::Flex, false).map(|a| a.id);

    let mut tbl_accounts = Vec::new();
    for acc in &candidates {
        let matched_kind = if Some(acc.id) == matched_flex_monmon_id {
            Some(MonzoAccountKind::Flex)
        } else if Some(acc.id) == matched_debit_monmon_id {
            Some(MonzoAccountKind::Debit)
        } else {
            None
        };

        let is_tbl = acc.ledger_mode.eq_ignore_ascii_case("TBL");
        // Show Monzo/Flex accounts plus any TBL accounts (matching Java verifyConnectionAndTblAccounts)
        if matched_kind.is_none() && !is_tbl {
            continue;
        }

        let (monzo_id, monzo_type, live_bal, avail_bal, working, status_detail) = match matched_kind {
            Some(MonzoAccountKind::Flex) => {
                let m_id = flex_monzo_id.clone();
                let m_type = m_id.as_ref().map(|_| "uk_monzo_flex".to_string());
                let avail = flex_live_bal
                    .map(|raw| convert_to_available_for_kind(MonzoAccountKind::Flex, &acc.account_type, acc.account_limit, raw));
                let ok = api_permissions_granted && m_id.is_some() && avail.is_some();
                let detail = match (&m_id, avail) {
                    (Some(id), Some(v)) => format!("Connected to {} ({}), available £{:.2}", MonzoAccountKind::Flex.display_name(), id, v),
                    (Some(id), None) => format!("Connected to {} ({}), awaiting balance response", MonzoAccountKind::Flex.display_name(), id),
                    (None, _) => "No active uk_monzo_flex account found on Monzo".to_string(),
                };
                (m_id, m_type, flex_live_bal, avail, ok, detail)
            }
            Some(MonzoAccountKind::Debit) => {
                let m_id = debit_monzo_id.clone();
                let m_type = m_id.as_ref().and_then(|id| {
                    monzo_accounts.iter().find(|(mid, _)| mid == id).map(|(_, t)| t.clone())
                });
                let avail = debit_live_bal
                    .map(|raw| convert_to_available_for_kind(MonzoAccountKind::Debit, &acc.account_type, acc.account_limit, raw));
                let ok = api_permissions_granted && m_id.is_some() && avail.is_some();
                let detail = match (&m_id, avail) {
                    (Some(id), Some(v)) => format!("Connected to {} ({}), available £{:.2}", MonzoAccountKind::Debit.display_name(), id, v),
                    (Some(id), None) => format!("Connected to {} ({}), awaiting balance response", MonzoAccountKind::Debit.display_name(), id),
                    (None, _) => "No active uk_retail account found on Monzo".to_string(),
                };
                (m_id, m_type, debit_live_bal, avail, ok, detail)
            }
            None => (
                None,
                None,
                None,
                None,
                false,
                "Non-Monzo TBL account (manual / statement sync)".to_string(),
            ),
        };

        tbl_accounts.push(TblAccountVerification {
            monmon_account_id: acc.id,
            monmon_account_name: acc.name.clone(),
            account_type: acc.account_type.clone(),
            ledger_mode: acc.ledger_mode.clone(),
            matched_to_monzo: monzo_id.is_some(),
            monzo_account_id: monzo_id,
            monzo_account_type: monzo_type,
            live_monzo_balance: live_bal,
            available_balance: avail_bal,
            latest_recorded_balance: avail_bal,
            working,
            status_detail,
        });
    }

    let summary_message = if api_permissions_granted {
        "Monzo OAuth connection verified! Real-time API permissions granted and Debit/Flex accounts verified separately.".to_string()
    } else if awaiting_in_app_approval {
        "Awaiting SCA approval: open your Monzo mobile app and tap 'Approve' to grant MonMon API access, then click Verify again.".to_string()
    } else {
        "Could not verify Monzo API permissions. Try rotating your token or reconnecting OAuth.".to_string()
    };

    let report = MonzoVerificationReport {
        oauth_client_configured,
        client_id,
        oauth_connected: true,
        has_refresh_token,
        awaiting_in_app_approval,
        api_permissions_granted,
        monzo_user_id,
        token_expires_at,
        summary_message,
        tbl_accounts,
    };

    let wants_json = headers.get("accept").and_then(|h| h.to_str().ok()).map(|s| s.contains("application/json")).unwrap_or(false);
    if wants_json {
        Ok(Json(report).into_response())
    } else {
        let msg = format!("OAuth verification complete: {}", report.summary_message);
        Ok(Redirect::to(&format!("/bank?info={}", urlencoding::encode(&msg))).into_response())
    }
}

pub async fn disconnect_monzo(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    // Best-effort HTTP DELETE of registered webhooks on Monzo before deleting the connection
    if let Ok(Some(conn)) = get_monzo_connection(&state.pool, &resolved).await {
        if let Ok(Some(token)) = ensure_valid_token(&state.pool, &resolved).await {
            if let Ok(client) = reqwest::Client::builder().timeout(std::time::Duration::from_secs(10)).build() {
                if let Some(ref hid) = conn.webhook_debit_id {
                    let _ = delete_monzo_webhook_http(&client, &token, hid).await;
                }
                if let Some(ref hid) = conn.webhook_flex_id {
                    let _ = delete_monzo_webhook_http(&client, &token, hid).await;
                }
            }
        }
    }

    // Delete monzo_connection
    let _ = sqlx::query("DELETE FROM monzo_connection WHERE userid = $1")
        .bind(&resolved)
        .execute(&state.pool)
        .await;

    Ok(Redirect::to("/bank?info=Disconnected+Monzo+integration+and+removed+registered+webhooks."))
}

pub async fn sync_monzo_webhooks(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let base_url = resolve_base_url(&headers);
    match register_monzo_webhooks(&state.pool, &resolved, &base_url).await {
        Ok(_) => Ok(Redirect::to("/bank?info=Webhooks+synchronized+successfully+with+Monzo.")),
        Err(e) => Ok(Redirect::to(&format!("/bank?error=Failed+to+sync+webhooks:+{}", urlencoding::encode(&e)))),
    }
}

pub async fn get_monzo_webhook_status_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<BankSyncQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let conn_opt = get_monzo_connection(&state.pool, &resolved).await.unwrap_or(None);

    let (connected, has_active_webhooks, wh_url, wh_debit, wh_flex, last_recv) = match conn_opt {
        Some(ref c) => {
            let conn = c.accesstoken.as_ref().map(|t| !t.trim().is_empty()).unwrap_or(false);
            let active = c.has_active_webhooks();
            (conn, active, c.webhook_url.clone(), c.webhook_debit_id.clone(), c.webhook_flex_id.clone(), c.last_received_at)
        }
        None => (false, false, None, None, None, None),
    };

    let last_received_text = if let Some(last_ts) = last_recv {
        let diff = (Utc::now().timestamp_millis() - last_ts) / 1000;
        if diff < 60 {
            "Last received from Monzo just now".to_string()
        } else if diff < 3600 {
            format!("Last received from Monzo {}m ago", diff / 60)
        } else if diff < 86400 {
            format!("Last received from Monzo {}h ago", diff / 3600)
        } else {
            format!("Last received from Monzo {}d ago", diff / 86400)
        }
    } else {
        "Awaiting first transaction event".to_string()
    };

    Ok(Json(serde_json::json!({
        "connected": connected,
        "hasActiveWebhooks": has_active_webhooks,
        "webhookUrl": wh_url,
        "webhookDebitId": wh_debit,
        "webhookFlexId": wh_flex,
        "lastReceivedAt": last_recv,
        "lastReceivedText": last_received_text
    })))
}

/// Converts Monzo's integer pence `account_balance` into the pounds value stored in `balance`.
#[allow(dead_code)]
pub fn balance_pounds_from_pence(pence: i64) -> f32 {
    (pence as f64 / 100.0) as f32
}

/// Converts a raw Monzo balance (pounds) into MonMon's "available" figure for a known kind.
/// Flex / CREDIT: Monzo reports the amount owed, so available = limit - |balance|.
/// Debit: raw balance is already the available balance.
pub fn convert_to_available_for_kind(
    kind: MonzoAccountKind,
    account_type: &str,
    account_limit: f64,
    monzo_balance: f64,
) -> f64 {
    if kind == MonzoAccountKind::Flex || account_type.eq_ignore_ascii_case("CREDIT") {
        ((account_limit - monzo_balance.abs()) * 100.0).round() / 100.0
    } else {
        monzo_balance
    }
}

/// Converts a raw Monzo balance (pounds) into MonMon's "available" figure.
/// CREDIT (Flex): Monzo reports the amount owed, so available = limit - |balance|.
#[allow(dead_code)]
pub fn convert_to_available(account_type: &str, account_limit: f64, monzo_balance: f64) -> f64 {
    let kind = if account_type.eq_ignore_ascii_case("CREDIT") {
        MonzoAccountKind::Flex
    } else {
        MonzoAccountKind::Debit
    };
    convert_to_available_for_kind(kind, account_type, account_limit, monzo_balance)
}

/// Picks the Monzo account id (from `(id, type)` pairs) matching `kind`:
/// Flex -> `uk_monzo_flex`, Debit -> `uk_retail` / `uk_retail_joint`.
pub fn match_monzo_account_id_for_kind(
    kind: MonzoAccountKind,
    monzo_accounts: &[(String, String)],
) -> Option<String> {
    monzo_accounts
        .iter()
        .find(|(_, t)| MonzoAccountKind::from_monzo_type(t) == Some(kind))
        .map(|(id, _)| id.clone())
}

/// Picks the Monzo account id (from `(id, type)` pairs) matching a MonMon account:
/// CREDIT -> `uk_monzo_flex`, Debit -> `uk_retail` / `uk_retail_joint`.
#[allow(dead_code)]
pub fn match_monzo_account_id(is_credit: bool, monzo_accounts: &[(String, String)]) -> Option<String> {
    let kind = if is_credit {
        MonzoAccountKind::Flex
    } else {
        MonzoAccountKind::Debit
    };
    match_monzo_account_id_for_kind(kind, monzo_accounts)
}

async fn load_active_account_candidates(pool: &sqlx::PgPool, user_id: &str) -> Vec<AccountCandidate> {
    let rows = sqlx::query(
        r#"SELECT id, COALESCE(name, '') AS name, COALESCE(type, '') AS type,
                  account_limit, COALESCE(ledger_mode, 'ABL') AS ledger_mode
           FROM account
           WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false)
           ORDER BY id ASC"#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .unwrap_or_default();

    rows.into_iter()
        .map(|r| AccountCandidate {
            id: r.get("id"),
            name: r.get("name"),
            account_type: r.get("type"),
            account_limit: r.get::<Option<f32>, _>("account_limit").unwrap_or(0.0) as f64,
            ledger_mode: r.get("ledger_mode"),
        })
        .collect()
}

/// Finds the MonMon account that Monzo Debit or Monzo Flex data belongs to.
/// Strictly separates `DEBIT` (Monzo current) from `CREDIT` (Flex).
async fn find_target_account(
    pool: &sqlx::PgPool,
    user_id: &str,
    kind: MonzoAccountKind,
    allow_unnamed_fallback: bool,
) -> Option<AccountCandidate> {
    let candidates = load_active_account_candidates(pool, user_id).await;
    select_target_account(&candidates, kind, allow_unnamed_fallback).cloned()
}

/// Fetches live Monzo balances for every matched MonMon account (Debit and Flex separately)
/// and records each as a new "available" balance.
pub async fn refresh_balances_internal(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<Vec<(i64, String, f64)>, String> {
    let conn = get_monzo_connection(pool, user_id).await.unwrap_or(None);
    if conn.as_ref().and_then(|c| c.accesstoken.as_ref()).is_none() {
        return Ok(vec![]);
    }

    let (status, monzo_accounts) = fetch_monzo_accounts_and_cache(pool, user_id).await?;
    if !status.is_success() {
        return Err(format!("Monzo accounts API returned {}", status));
    }

    let mut refreshed = Vec::new();
    for kind in [MonzoAccountKind::Debit, MonzoAccountKind::Flex] {
        let Some(monzo_id) = match_monzo_account_id_for_kind(kind, &monzo_accounts) else { continue };
        // Strict name+type match during bulk refresh so unrelated accounts (e.g. Lloyds/NatWest) are never overwritten.
        let Some(target) = find_target_account(pool, user_id, kind, false).await else { continue };
        let Some(raw) = fetch_monzo_balance(pool, user_id, &monzo_id).await else { continue };
        let available = convert_to_available_for_kind(kind, &target.account_type, target.account_limit, raw);
        let now_ms_bal = Utc::now().timestamp_millis();
        sqlx::query(
            r#"INSERT INTO balance (id, value, timestamp, account_id, user_id, userid, "userId", created_at, updated_at)
               VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4, $5, $5)"#,
        )
        .bind(available as f32)
        .bind(Utc::now().timestamp())
        .bind(target.id)
        .bind(user_id)
        .bind(now_ms_bal)
        .execute(pool)
        .await
        .map_err(|e| e.to_string())?;
        refreshed.push((target.id, target.name, available));
    }
    Ok(refreshed)
}

/// Human-readable summary of a balance refresh, naming each account and the value recorded.
pub fn format_refresh_message(refreshed: &[(i64, String, f64)]) -> String {
    let parts: Vec<String> = refreshed
        .iter()
        .map(|(id, name, v)| format!("{} (#{}) £{:.2}", name, id, v))
        .collect();
    format!("Refreshed available balance from Monzo: {}.", parts.join(", "))
}

pub async fn refresh_monzo_balances(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<BankSyncQuery>,
) -> Result<Response, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    let wants_json = headers
        .get(axum::http::header::ACCEPT)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.contains("application/json"))
        .unwrap_or(false);

    let result = refresh_balances_internal(&state.pool, &resolved).await;
    if wants_json {
        return Ok(match result {
            Ok(list) => Json(serde_json::json!({
                "refreshed": list.iter().map(|(id, name, v)| serde_json::json!({
                    "accountId": id, "accountName": name, "available": v
                })).collect::<Vec<_>>()
            }))
            .into_response(),
            Err(e) => (StatusCode::BAD_GATEWAY, Json(serde_json::json!({ "error": e }))).into_response(),
        });
    }
    let target = match result {
        Ok(list) if list.is_empty() => {
            "/bank?error=No+Monzo+accounts+matched+-+nothing+refreshed.".to_string()
        }
        Ok(list) => format!("/bank?info={}", urlencoding::encode(&format_refresh_message(&list))),
        Err(e) => format!("/bank?error={}", urlencoding::encode(&format!("Balance refresh failed: {}", e))),
    };
    Ok(Redirect::to(&target).into_response())
}

/// Live balance (pounds) for a Monzo account via `/balance`, with deduplicated token + 401 retry.
async fn fetch_monzo_balance(pool: &sqlx::PgPool, user_id: &str, monzo_acc_id: &str) -> Option<f64> {
    if monzo_acc_id.trim().is_empty() {
        return None;
    }
    let (status, json) = monzo_get_json_with_retry(
        pool,
        user_id,
        "https://api.monzo.com/balance",
        &[("account_id", monzo_acc_id)],
    )
    .await
    .ok()?;
    if !status.is_success() {
        return None;
    }
    json.get("balance").and_then(|v| v.as_i64()).map(|p| p as f64 / 100.0)
}

/// Resolves whether a Monzo `account_id` is Debit or Flex via the Monzo `/accounts` API,
/// caching the discovered IDs on `monzo_connection`. Returns `None` on failure — never guesses!
async fn classify_monzo_account_via_api(
    pool: &sqlx::PgPool,
    user_id: &str,
    monzo_acc_id: &str,
) -> Option<MonzoAccountKind> {
    let acc_id = monzo_acc_id.trim();
    if acc_id.is_empty() {
        return None;
    }
    let (status, monzo_accounts) = fetch_monzo_accounts_and_cache(pool, user_id).await.ok()?;
    if !status.is_success() {
        return None;
    }
    monzo_accounts
        .iter()
        .find(|(id, _)| id == acc_id)
        .and_then(|(_, t)| MonzoAccountKind::from_monzo_type(t))
}

pub async fn handle_monzo_webhook_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<BankSyncQuery>,
    Json(payload): Json<Value>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;

    let event_type = payload.get("type").and_then(|v| v.as_str()).unwrap_or("");
    if event_type == "transaction.created" {
        if let Some(data) = payload.get("data") {
            // Skip declined transactions (matching Java MonzoBankSyncProvider)
            if is_declined_transaction(data) {
                return Ok(Json(serde_json::json!({ "status": "ok", "ignored": "declined" })));
            }

            let tx_id = data.get("id").and_then(|v| v.as_str()).unwrap_or("");
            let monzo_acc_id = data.get("account_id").and_then(|v| v.as_str()).unwrap_or("");

            // Load Monzo connection once to check persisted Debit/Flex Monzo account IDs
            let conn_opt = get_monzo_connection(&state.pool, &resolved).await.unwrap_or(None);
            let has_active_connection = conn_opt
                .as_ref()
                .and_then(|c| c.accesstoken.as_deref())
                .map(|t| !t.trim().is_empty())
                .unwrap_or(false);
            let cached_debit_id = conn_opt.as_ref().and_then(|c| c.monzo_debit_account_id.as_deref());
            let cached_flex_id = conn_opt.as_ref().and_then(|c| c.monzo_flex_account_id.as_deref());

            // Only hit Monzo /accounts API if connected and this account_id is not yet cached.
            let already_cached = !monzo_acc_id.trim().is_empty()
                && (cached_debit_id.map(|s| s.trim() == monzo_acc_id.trim()).unwrap_or(false)
                    || cached_flex_id.map(|s| s.trim() == monzo_acc_id.trim()).unwrap_or(false));

            let api_resolved = if has_active_connection && !already_cached && !monzo_acc_id.trim().is_empty() {
                classify_monzo_account_via_api(&state.pool, &resolved, monzo_acc_id).await
            } else {
                None
            };

            let Some(kind) = classify_webhook_account_kind_pure(
                monzo_acc_id,
                q.account_type.as_deref(),
                cached_debit_id,
                cached_flex_id,
                api_resolved,
                has_active_connection,
                data,
            ) else {
                tracing::warn!(
                    "[Monzo] Refusing to ingest unclassified webhook for user={} tx_id={} monzo_acc_id={} (could not positively determine Debit vs Flex)",
                    resolved, tx_id, monzo_acc_id
                );
                return Ok(Json(serde_json::json!({
                    "status": "ok",
                    "ignored": "unclassified_monzo_account"
                })));
            };

            let amount_pence = data.get("amount").and_then(|v| v.as_i64()).unwrap_or(0);
            let amount_pounds = if amount_pence < 0 {
                amount_pence.abs() as f32 / 100.0
            } else {
                -(amount_pence as f32 / 100.0)
            };

            let created_str = data.get("created").and_then(|v| v.as_str()).unwrap_or("");
            let epoch_sec = chrono::DateTime::parse_from_rfc3339(created_str)
                .map(|dt| dt.timestamp())
                .unwrap_or_else(|_| chrono::Utc::now().timestamp());
            let date_str = chrono::DateTime::parse_from_rfc3339(created_str)
                .map(|dt| dt.format("%Y-%m-%d").to_string())
                .unwrap_or_else(|_| chrono::Utc::now().format("%Y-%m-%d").to_string());

            let mut description = data.get("description").and_then(|v| v.as_str()).unwrap_or("Monzo Transaction").to_string();
            let mut category = data.get("category").and_then(|v| v.as_str()).unwrap_or("General").to_string();
            let mut merchant_name = None;
            let mut merchant_address = None;
            let mut merchant_emoji = None;
            let mut latitude = None;
            let mut longitude = None;

            if let Some(m) = data.get("merchant") {
                if let Some(name) = m.get("name").and_then(|v| v.as_str()) {
                    if !name.trim().is_empty() {
                        merchant_name = Some(name.to_string());
                        description = name.to_string();
                    }
                }
                if let Some(cat) = m.get("category").and_then(|v| v.as_str()) {
                    if !cat.trim().is_empty() {
                        category = cat.to_string();
                    }
                }
                if let Some(emoji) = m.get("emoji").and_then(|v| v.as_str()) {
                    if !emoji.trim().is_empty() {
                        merchant_emoji = Some(emoji.to_string());
                    }
                }
                if let Some(addr) = m.get("address") {
                    merchant_address = addr
                        .get("formatted")
                        .and_then(|v| v.as_str())
                        .filter(|s| !s.trim().is_empty())
                        .or_else(|| addr.get("short_formatted").and_then(|v| v.as_str()).filter(|s| !s.trim().is_empty()))
                        .map(|s| s.to_string());
                    latitude = addr.get("latitude").and_then(|v| v.as_f64());
                    longitude = addr.get("longitude").and_then(|v| v.as_f64());
                }
            }

            // Strictly match Debit -> DEBIT account and Flex -> CREDIT account
            let target = find_target_account(&state.pool, &resolved, kind, true).await;
            let account_id: Option<i64> = target.as_ref().map(|t| t.id);
            let acc_type: String = target.as_ref().map(|t| t.account_type.clone()).unwrap_or_default();
            let acc_limit: f64 = target.as_ref().map(|t| t.account_limit).unwrap_or(0.0);

            let now_ms = Utc::now().timestamp_millis();
            let raw_data_json = serde_json::to_string(data).ok();

            // Insert into bank_transaction, persisting raw_data and detecting duplicate deliveries
            let tx_inserted: bool = sqlx::query_scalar(
                r#"
                INSERT INTO bank_transaction (
                    id, userid, user_id, "userId", accountid, account_id, "accountId",
                    externalid, timestamp, date, amount, description,
                    merchantname, merchant_name, category, source, pending,
                    merchantaddress, merchant_address, merchantemoji, merchant_emoji,
                    latitude, longitude, raw_data, createdat, created_at, updated_at
                )
                VALUES (
                    nextval('hibernate_sequence'), $1, $1, $1, $2, $2, $2,
                    $3, $4, $5, $6, $7,
                    $8, $8, $9, 'MONZO', false,
                    $10, $10, $11, $11,
                    $12, $13, $14, $4, $15, $15
                )
                ON CONFLICT (externalid) DO UPDATE SET
                    amount = EXCLUDED.amount,
                    description = EXCLUDED.description,
                    merchant_name = EXCLUDED.merchant_name,
                    raw_data = COALESCE(EXCLUDED.raw_data, bank_transaction.raw_data),
                    updated_at = EXCLUDED.updated_at
                RETURNING (xmax = 0) AS inserted
                "#,
            )
            .bind(&resolved)
            .bind(account_id)
            .bind(tx_id)
            .bind(epoch_sec)
            .bind(&date_str)
            .bind(amount_pounds)
            .bind(&description)
            .bind(merchant_name)
            .bind(&category)
            .bind(merchant_address)
            .bind(merchant_emoji)
            .bind(latitude)
            .bind(longitude)
            .bind(&raw_data_json)
            .bind(now_ms)
            .fetch_optional(&state.pool)
            .await
            .ok()
            .flatten()
            .unwrap_or(true);

            // Record the account's latest "available" balance ONLY for newly ingested transactions
            // (so duplicate webhook deliveries do not spam duplicate balance rows).
            let should_record_balance = tx_id.is_empty() || tx_inserted;
            if should_record_balance {
                if let Some(acc_id) = account_id {
                    let raw = if has_active_connection {
                        // For real connected Monzo accounts, fetch live /balance with deduplicated
                        // token + 401 retry. Never fall back to a missing/zero webhook field.
                        fetch_monzo_balance(&state.pool, &resolved, monzo_acc_id).await
                    } else {
                        data.get("account_balance")
                            .and_then(|v| v.as_i64())
                            .map(|p| p as f64 / 100.0)
                    };
                    if let Some(raw) = raw {
                        let available = convert_to_available_for_kind(kind, &acc_type, acc_limit, raw);
                        let _ = sqlx::query(
                            r#"INSERT INTO balance (id, value, timestamp, account_id, user_id, userid, "userId", created_at, updated_at)
                               VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4, $5, $5)"#,
                        )
                        .bind(available as f32)
                        .bind(Utc::now().timestamp())
                        .bind(acc_id)
                        .bind(&resolved)
                        .bind(now_ms)
                        .execute(&state.pool)
                        .await;
                    }
                }
            }

            // Update last_received_at on monzo_connection
            let _ = sqlx::query(
                "UPDATE monzo_connection SET last_received_at = $1, lastreceivedat = $1, updated_at = $1 WHERE userid = $2"
            )
            .bind(now_ms)
            .bind(&resolved)
            .execute(&state.pool)
            .await;
        }
    }

    Ok(Json(serde_json::json!({ "status": "ok" })))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pence_balance_converts_to_pounds() {
        assert_eq!(balance_pounds_from_pence(123456), 1234.56_f32);
        assert_eq!(balance_pounds_from_pence(-5000), -50.0_f32);
    }

    #[test]
    fn flex_balance_becomes_limit_minus_owed() {
        assert_eq!(convert_to_available("CREDIT", 1000.0, -250.0), 750.0);
        assert_eq!(convert_to_available("CREDIT", 1000.0, 250.0), 750.0);
        assert_eq!(convert_to_available("CREDIT", 1000.0, 0.0), 1000.0);
        assert_eq!(convert_to_available("CREDIT", 0.0, -12.345), -12.35);
        // Exact production scenario: £2500 Flex limit with -£2167.77 owed -> £332.23 available
        assert_eq!(
            convert_to_available_for_kind(MonzoAccountKind::Flex, "CREDIT", 2500.0, -2167.77),
            332.23
        );
    }

    #[test]
    fn matches_monzo_accounts_by_type() {
        let accs = vec![
            ("acc_flex".to_string(), "uk_monzo_flex".to_string()),
            ("acc_debit".to_string(), "uk_retail".to_string()),
            ("acc_other".to_string(), "uk_business".to_string()),
        ];
        assert_eq!(match_monzo_account_id(true, &accs).as_deref(), Some("acc_flex"));
        assert_eq!(match_monzo_account_id(false, &accs).as_deref(), Some("acc_debit"));
        assert_eq!(match_monzo_account_id(true, &accs[1..]), None);
        assert_eq!(MonzoAccountKind::from_monzo_type("uk_business"), None);
        assert_eq!(MonzoAccountKind::from_monzo_type("uk_retail_joint"), Some(MonzoAccountKind::Debit));
    }

    #[test]
    fn refresh_message_names_accounts_and_values() {
        let msg = format_refresh_message(&[
            (8638, "Monzo".to_string(), 123.4),
            (8787, "Flex".to_string(), 2250.0),
        ]);
        assert_eq!(
            msg,
            "Refreshed available balance from Monzo: Monzo (#8638) £123.40, Flex (#8787) £2250.00."
        );
    }

    #[test]
    fn debit_balance_is_unchanged() {
        assert_eq!(convert_to_available("DEBIT", 500.0, 1234.56), 1234.56);
        assert_eq!(
            convert_to_available_for_kind(MonzoAccountKind::Debit, "DEBIT", 100.0, -19.61),
            -19.61
        );
    }

    #[test]
    fn webhook_callback_urls_separate_debit_and_flex() {
        let debit_url = build_webhook_callback_url("https://monmon.dryark.uk/", "u-1", MonzoAccountKind::Debit);
        let flex_url = build_webhook_callback_url("https://monmon.dryark.uk", "u-1", MonzoAccountKind::Flex);
        assert_eq!(debit_url, "https://monmon.dryark.uk/api/monzo/webhook?userId=u-1&accountType=debit");
        assert_eq!(flex_url, "https://monmon.dryark.uk/api/monzo/webhook?userId=u-1&accountType=flex");
        assert_eq!(MonzoAccountKind::from_query_hint(Some("debit")), Some(MonzoAccountKind::Debit));
        assert_eq!(MonzoAccountKind::from_query_hint(Some("flex")), Some(MonzoAccountKind::Flex));
        assert_eq!(MonzoAccountKind::from_query_hint(Some("unknown")), None);
        assert_eq!(MonzoAccountKind::from_query_hint(None), None);
    }

    #[test]
    fn flex_payload_signals_handle_string_and_bool_metadata() {
        // Real Monzo API sends "true" as a JSON string, not a JSON boolean
        let str_bool = serde_json::json!({
            "metadata": { "is_flex_direct_virtual_card_tx": "true" }
        });
        assert!(payload_has_flex_signals(&str_bool));

        let native_bool = serde_json::json!({
            "metadata": { "is_flex_direct_virtual_card_tx": true }
        });
        assert!(payload_has_flex_signals(&native_bool));

        let scheme_flex = serde_json::json!({ "scheme": "monzo_flex" });
        assert!(payload_has_flex_signals(&scheme_flex));

        let plain_debit = serde_json::json!({
            "scheme": "mastercard",
            "metadata": { "mcc": "5411" }
        });
        assert!(!payload_has_flex_signals(&plain_debit));
    }

    #[test]
    fn classify_webhook_never_cross_contaminates_monzo_and_flex() {
        let flex_repayment_on_debit = serde_json::json!({
            "account_id": "acc_debit_1",
            "scheme": "monzo_flex",
            "metadata": { "monzo_flex_id": "monzoflex_123" }
        });
        // Cached Debit account_id MUST win over monzo_flex metadata on a Flex repayment
        assert_eq!(
            classify_webhook_account_kind_pure(
                "acc_debit_1",
                None,
                Some("acc_debit_1"),
                Some("acc_flex_1"),
                None,
                true,
                &flex_repayment_on_debit,
            ),
            Some(MonzoAccountKind::Debit)
        );

        // Flex transaction with empty metadata still maps to Flex via cached flex account_id
        let bare_flex = serde_json::json!({ "account_id": "acc_flex_1", "metadata": {} });
        assert_eq!(
            classify_webhook_account_kind_pure(
                "acc_flex_1",
                None,
                Some("acc_debit_1"),
                Some("acc_flex_1"),
                None,
                true,
                &bare_flex,
            ),
            Some(MonzoAccountKind::Flex)
        );

        // CRITICAL REGRESSION GUARD: Connected user, unknown account_id, API lookup failed (401/timeout),
        // no query hint, no Flex metadata -> MUST FAIL CLOSED (None), NEVER default to Debit!
        let bare_unknown = serde_json::json!({ "account_id": "acc_flex_uncached", "metadata": {} });
        assert_eq!(
            classify_webhook_account_kind_pure(
                "acc_flex_uncached",
                None,
                None,
                None,
                None,
                true,
                &bare_unknown,
            ),
            None
        );

        // Per-account webhook URL hint still saves classification even if /accounts API failed
        assert_eq!(
            classify_webhook_account_kind_pure(
                "acc_flex_uncached",
                Some("flex"),
                None,
                None,
                None,
                true,
                &bare_unknown,
            ),
            Some(MonzoAccountKind::Flex)
        );

        // Disconnected synthetic test webhook without Flex signals maps to Debit
        assert_eq!(
            classify_webhook_account_kind_pure(
                "acc_test",
                None,
                None,
                None,
                None,
                false,
                &bare_unknown,
            ),
            Some(MonzoAccountKind::Debit)
        );
    }

    #[test]
    fn select_target_account_strictly_separates_monzo_and_flex() {
        let mk = |id: i64, name: &str, t: &str, limit: f64| AccountCandidate {
            id,
            name: name.to_string(),
            account_type: t.to_string(),
            account_limit: limit,
            ledger_mode: "ABL".to_string(),
        };
        let accounts = vec![
            mk(2, "Natwest Reward Debit", "DEBIT", 250.0),
            mk(47, "Lloyds Platinum", "CREDIT", 500.0),
            mk(51, "Therapy Escrow", "DEBIT_SHARED_BILLS", 0.0),
            mk(52, "Monzo Flex Pot", "SHORT_TERM_SAVINGS", 0.0),
            mk(7676, "Paypal Credit", "CREDIT", 2500.0),
            mk(8638, "Monzo", "DEBIT", 100.0),
            mk(8787, "Flex", "CREDIT", 2500.0),
        ];

        assert_eq!(select_target_account(&accounts, MonzoAccountKind::Debit, false).map(|a| a.id), Some(8638));
        assert_eq!(select_target_account(&accounts, MonzoAccountKind::Flex, false).map(|a| a.id), Some(8787));

        // Even if a DEBIT account has "Flex" in its name, Debit must prefer "Monzo", never "Flex Bills"
        let tricky = vec![
            mk(1, "Flex Bills", "DEBIT", 0.0),
            mk(2, "Monzo", "DEBIT", 100.0),
            mk(3, "Flex", "CREDIT", 2500.0),
        ];
        assert_eq!(select_target_account(&tricky, MonzoAccountKind::Debit, true).map(|a| a.id), Some(2));
        assert_eq!(select_target_account(&tricky, MonzoAccountKind::Flex, true).map(|a| a.id), Some(3));

        // Strict mode (used by bulk refresh & verify) NEVER touches unrelated accounts
        let no_monzo = vec![
            mk(1, "Lloyds", "DEBIT", 0.0),
            mk(2, "Paypal Credit", "CREDIT", 2500.0),
            mk(3, "Shared Monzo Bills", "DEBIT_SHARED_BILLS", 0.0),
        ];
        assert_eq!(select_target_account(&no_monzo, MonzoAccountKind::Debit, false), None);
        assert_eq!(select_target_account(&no_monzo, MonzoAccountKind::Flex, false), None);
        // Webhook fallback still refuses DEBIT_SHARED_BILLS and picks true DEBIT
        assert_eq!(select_target_account(&no_monzo, MonzoAccountKind::Debit, true).map(|a| a.id), Some(1));
    }

    #[test]
    fn declined_transaction_detection() {
        assert!(is_declined_transaction(&serde_json::json!({ "decline_reason": "INSUFFICIENT_FUNDS" })));
        assert!(!is_declined_transaction(&serde_json::json!({ "decline_reason": "" })));
        assert!(!is_declined_transaction(&serde_json::json!({ "id": "tx_1" })));
    }
}
