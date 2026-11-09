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
use std::sync::Arc;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BankSyncQuery {
    #[serde(alias = "user_id")]
    pub user_id: Option<String>,
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
    pub id: i64,
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
    pub client_secret_masked: Option<String>,
    pub redirect_uri: String,
    pub webhook_url: Option<String>,
    pub webhook_debit_id: Option<String>,
    pub webhook_flex_id: Option<String>,
    pub last_received_text: String,
    pub accounts_count: usize,
    pub txs_count: usize,
    pub recent_transactions: Vec<BankTransactionDto>,
    pub pending_statements: Vec<PendingStatementDto>,
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
            COALESCE(last_received_at, lastreceivedat) as last_recv
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
// Monzo OAuth Token Refresh & Helpers
// ==========================================

pub async fn refresh_monzo_token_internal(
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
        SET accesstoken = $1, refreshtoken = $2, expiresat = $3, lastsyncedat = $4,
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

pub async fn ensure_valid_token(
    pool: &sqlx::PgPool,
    user_id: &str,
) -> Result<Option<String>, String> {
    let row = get_monzo_connection(pool, user_id).await.map_err(|e| e.to_string())?;
    if let Some(conn) = row {
        let now_ms = Utc::now().timestamp_millis();
        let expires_at = conn.expiresat.unwrap_or(0);
        let has_refresh = conn.refreshtoken.as_ref().map(|t| !t.trim().is_empty()).unwrap_or(false);

        if has_refresh && (expires_at == 0 || expires_at < now_ms + 300_000) {
            match refresh_monzo_token_internal(pool, user_id).await {
                Ok(new_tok) => return Ok(Some(new_tok)),
                Err(err) => {
                    tracing::warn!("[Monzo] Proactive token refresh failed for user {}: {}", user_id, err);
                }
            }
        }
        return Ok(conn.accesstoken);
    }
    Ok(None)
}

pub async fn register_monzo_webhooks(
    pool: &sqlx::PgPool,
    user_id: &str,
    base_url: &str,
) -> Result<(Option<String>, Option<String>), String> {
    let token = ensure_valid_token(pool, user_id).await?
        .ok_or_else(|| "No Monzo access token available".to_string())?;

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    let accs_resp = client.get("https://api.monzo.com/accounts")
        .bearer_auth(&token)
        .send()
        .await
        .map_err(|e| format!("Failed to fetch Monzo accounts: {}", e))?;

    if !accs_resp.status().is_success() {
        let body = accs_resp.text().await.unwrap_or_default();
        return Err(format!("Monzo accounts API returned error: {}", body));
    }

    let accs_json: Value = accs_resp.json().await.map_err(|e| e.to_string())?;
    let accounts = accs_json.get("accounts").and_then(|v| v.as_array())
        .ok_or_else(|| "No accounts array in Monzo response".to_string())?;

    let webhook_target_url = format!("{}/api/monzo/webhook?userId={}", base_url.trim_end_matches('/'), user_id);

    let mut debit_hook_id = None;
    let mut flex_hook_id = None;

    for acc in accounts {
        let closed = acc.get("closed").and_then(|v| v.as_bool()).unwrap_or(false);
        if closed {
            continue;
        }
        let acc_id = acc.get("id").and_then(|v| v.as_str()).unwrap_or("");
        let acc_type = acc.get("type").and_then(|v| v.as_str()).unwrap_or("");

        if acc_id.is_empty() {
            continue;
        }

        // List existing webhooks to remove old ones pointing to our domain
        if let Ok(wh_list_resp) = client.get("https://api.monzo.com/webhooks")
            .bearer_auth(&token)
            .query(&[("account_id", acc_id)])
            .send()
            .await
        {
            if let Ok(wh_json) = wh_list_resp.json::<Value>().await {
                if let Some(hooks) = wh_json.get("webhooks").and_then(|v| v.as_array()) {
                    for h in hooks {
                        let h_id = h.get("id").and_then(|v| v.as_str()).unwrap_or("");
                        let h_url = h.get("url").and_then(|v| v.as_str()).unwrap_or("");
                        if (h_url.contains("dryark.uk") || h_url.contains("monmon")) && !h_id.is_empty() {
                            let _ = client.post(&format!("https://api.monzo.com/webhooks/{}/delete", h_id))
                                .bearer_auth(&token)
                                .send()
                                .await;
                        }
                    }
                }
            }
        }

        // Register new webhook
        let reg_params = [
            ("account_id", acc_id),
            ("url", &webhook_target_url),
        ];

        let reg_resp = client.post("https://api.monzo.com/webhooks")
            .bearer_auth(&token)
            .form(&reg_params)
            .send()
            .await;

        if let Ok(resp) = reg_resp {
            if resp.status().is_success() {
                if let Ok(resp_json) = resp.json::<Value>().await {
                    let hook_id = resp_json.get("webhook").and_then(|w| w.get("id")).and_then(|v| v.as_str()).map(|s| s.to_string());
                    if acc_type == "uk_monzo_flex" {
                        flex_hook_id = hook_id;
                    } else {
                        debit_hook_id = hook_id;
                    }
                }
            }
        }
    }

    // Save to DB
    sqlx::query(
        r#"
        UPDATE monzo_connection
        SET webhook_debit_id = $1, webhookdebitid = $1,
            webhook_flex_id = $2, webhookflexid = $2,
            webhook_url = $3, webhookurl = $3
        WHERE userid = $4
        "#,
    )
    .bind(&debit_hook_id)
    .bind(&flex_hook_id)
    .bind(&webhook_target_url)
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
            id, userid, clientid, clientsecret, redirecturi, createdat, lastsyncedat
        ) VALUES (
            nextval('hibernate_sequence'), $1, $2, $3, $4, $5, $5
        )
        ON CONFLICT (userid) DO UPDATE SET
            clientid = EXCLUDED.clientid,
            clientsecret = CASE WHEN EXCLUDED.clientsecret IS NOT NULL AND EXCLUDED.clientsecret != '' THEN EXCLUDED.clientsecret ELSE monzo_connection.clientsecret END,
            redirecturi = EXCLUDED.redirecturi,
            lastsyncedat = EXCLUDED.lastsyncedat
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
            clientid, clientsecret, redirecturi, lastsyncedat, createdat
        ) VALUES (
            nextval('hibernate_sequence'), $1, $2, $3, $4, $5,
            $6, $7, $8, $9, $9
        )
        ON CONFLICT (userid) DO UPDATE SET
            accesstoken = EXCLUDED.accesstoken,
            refreshtoken = COALESCE(EXCLUDED.refreshtoken, monzo_connection.refreshtoken),
            monzouserid = EXCLUDED.monzouserid,
            expiresat = EXCLUDED.expiresat,
            clientid = COALESCE(EXCLUDED.clientid, monzo_connection.clientid),
            clientsecret = COALESCE(EXCLUDED.clientsecret, monzo_connection.clientsecret),
            redirecturi = COALESCE(EXCLUDED.redirecturi, monzo_connection.redirecturi),
            lastsyncedat = EXCLUDED.lastsyncedat
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
pub struct TblAccountVerification {
    pub monmon_account_id: i64,
    pub monmon_account_name: String,
    pub ledger_mode: String,
    pub matched_to_monzo: bool,
    pub monzo_account_id: Option<String>,
    pub monzo_account_type: Option<String>,
    pub live_monzo_balance: Option<f64>,
    pub latest_recorded_balance: Option<f64>,
    pub working: bool,
}

#[derive(Debug, Serialize)]
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

    let token = match ensure_valid_token(&state.pool, &resolved).await {
        Ok(Some(t)) => t,
        _ => conn.accesstoken.unwrap_or_default(),
    };

    let http_client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    // Check accounts endpoint
    let accs_resp = http_client.get("https://api.monzo.com/accounts")
        .bearer_auth(&token)
        .send()
        .await;

    let (api_permissions_granted, awaiting_in_app_approval, monzo_accounts) = match accs_resp {
        Ok(resp) if resp.status().is_success() => {
            let json: Value = resp.json().await.unwrap_or(serde_json::json!({}));
            let accs = json.get("accounts").and_then(|v| v.as_array()).cloned().unwrap_or_default();
            (true, false, accs)
        }
        Ok(resp) if resp.status().as_u16() == 403 => {
            (false, true, vec![])
        }
        _ => (false, false, vec![]),
    };

    // Load user MonMon accounts
    let db_accs = sqlx::query(
        r#"
        SELECT id, name, type, ledger_mode
        FROM account
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY id ASC
        "#,
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let mut tbl_accounts = Vec::new();

    for dba in db_accs {
        let aid: i64 = dba.get("id");
        let aname: String = dba.get("name");
        let atype: String = dba.get("type");
        let lmode: String = dba.get("ledger_mode");

        // Try to match with monzo accounts
        let mut matched_m_acc = None;
        let is_credit = atype == "CREDIT";

        for ma in &monzo_accounts {
            let m_type = ma.get("type").and_then(|v| v.as_str()).unwrap_or("");
            if is_credit && m_type == "uk_monzo_flex" {
                matched_m_acc = Some(ma);
                break;
            } else if !is_credit && (m_type == "uk_retail" || m_type == "uk_retail_joint") {
                matched_m_acc = Some(ma);
                break;
            }
        }

        let mut live_bal = None;
        if let Some(ma) = matched_m_acc {
            let mid = ma.get("id").and_then(|v| v.as_str()).unwrap_or("");
            if !mid.is_empty() {
                if let Ok(b_resp) = http_client.get("https://api.monzo.com/balance")
                    .bearer_auth(&token)
                    .query(&[("account_id", mid)])
                    .send()
                    .await
                {
                    if let Ok(b_json) = b_resp.json::<Value>().await {
                        if let Some(pence) = b_json.get("balance").and_then(|v| v.as_i64()) {
                            live_bal = Some(pence as f64 / 100.0);
                        }
                    }
                }
            }
        }

        let working = api_permissions_granted && matched_m_acc.is_some();

        tbl_accounts.push(TblAccountVerification {
            monmon_account_id: aid,
            monmon_account_name: aname,
            ledger_mode: lmode,
            matched_to_monzo: matched_m_acc.is_some(),
            monzo_account_id: matched_m_acc.and_then(|m| m.get("id")).and_then(|v| v.as_str()).map(|s| s.to_string()),
            monzo_account_type: matched_m_acc.and_then(|m| m.get("type")).and_then(|v| v.as_str()).map(|s| s.to_string()),
            live_monzo_balance: live_bal,
            latest_recorded_balance: None,
            working,
        });
    }

    let summary_message = if api_permissions_granted {
        "Monzo OAuth connection verified! Real-time API permissions granted and accounts verified.".to_string()
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

pub async fn handle_monzo_webhook_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<BankSyncQuery>,
    Json(payload): Json<Value>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;

    let event_type = payload.get("type").and_then(|v| v.as_str()).unwrap_or("");
    if event_type == "transaction.created" {
        if let Some(data) = payload.get("data") {
            let tx_id = data.get("id").and_then(|v| v.as_str()).unwrap_or("");
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
                    merchant_name = Some(name.to_string());
                    description = name.to_string();
                }
                if let Some(cat) = m.get("category").and_then(|v| v.as_str()) {
                    category = cat.to_string();
                }
                if let Some(emoji) = m.get("emoji").and_then(|v| v.as_str()) {
                    merchant_emoji = Some(emoji.to_string());
                }
                if let Some(addr) = m.get("address") {
                    merchant_address = addr.get("formatted").and_then(|v| v.as_str()).map(|s| s.to_string());
                    latitude = addr.get("latitude").and_then(|v| v.as_f64());
                    longitude = addr.get("longitude").and_then(|v| v.as_f64());
                }
            }

            // Find matching account (debit or flex)
            let acc_row = sqlx::query(
                "SELECT id FROM account WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false) ORDER BY id ASC LIMIT 1",
            )
            .bind(&resolved)
            .fetch_optional(&state.pool)
            .await
            .unwrap_or(None);

            let account_id: Option<i64> = acc_row.map(|r| r.get("id"));

            // Insert into bank_transaction
            let _ = sqlx::query(
                r#"
                INSERT INTO bank_transaction (
                    id, userid, user_id, "userId", accountid, account_id, "accountId",
                    externalid, timestamp, date, amount, description,
                    merchantname, merchant_name, category, source, pending,
                    merchantaddress, merchant_address, merchantemoji, merchant_emoji,
                    latitude, longitude, createdat
                )
                VALUES (
                    nextval('hibernate_sequence'), $1, $1, $1, $2, $2, $2,
                    $3, $4, $5, $6, $7,
                    $8, $8, $9, 'MONZO', false,
                    $10, $10, $11, $11,
                    $12, $13, $4
                )
                ON CONFLICT (externalid) DO UPDATE SET
                    amount = EXCLUDED.amount,
                    description = EXCLUDED.description,
                    merchant_name = EXCLUDED.merchant_name
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
            .execute(&state.pool)
            .await;

            // Update last_received_at on monzo_connection
            let now_ms = Utc::now().timestamp_millis();
            let _ = sqlx::query(
                "UPDATE monzo_connection SET last_received_at = $1, lastreceivedat = $1 WHERE userid = $2"
            )
            .bind(now_ms)
            .bind(&resolved)
            .execute(&state.pool)
            .await;
        }
    }

    Ok(Json(serde_json::json!({ "status": "ok" })))
}
