use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, Json},
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::Row;
use std::sync::Arc;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BankSyncQuery {
    #[serde(alias = "user_id")]
    pub user_id: Option<String>,
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

#[derive(Template)]
#[template(path = "bank_sync.html")]
pub struct BankSyncTemplate {
    pub is_monzo_connected: bool,
    pub accounts_count: usize,
    pub txs_count: usize,
    pub recent_transactions: Vec<BankTransactionDto>,
}

pub async fn get_bank_sync_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<BankSyncQuery>,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    // Check Monzo connection
    let conn_row = sqlx::query(
        "SELECT id FROM monzo_connection WHERE userid = $1 LIMIT 1",
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    let is_monzo_connected = conn_row.is_some();

    // Query accounts count
    let accounts_row = sqlx::query(
        "SELECT COUNT(*)::bigint as cnt FROM account WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false)",
    )
    .bind(&resolved)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let accounts_count = accounts_row.get::<i64, _>("cnt") as usize;

    // Query transactions
    let txs = list_bank_transactions_internal(&state.pool, &resolved).await.unwrap_or_default();
    let txs_count = txs.len();

    let tmpl = BankSyncTemplate {
        is_monzo_connected,
        accounts_count,
        txs_count,
        recent_transactions: txs.into_iter().take(10).collect(),
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

pub async fn get_monzo_webhook_status_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<BankSyncQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;

    let row = sqlx::query(
        r#"
        SELECT id, accesstoken, webhookurl, webhook_url, webhook_debit_id, webhookdebitid
        FROM monzo_connection
        WHERE userid = $1
        LIMIT 1
        "#,
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let (connected, webhooks_registered) = match row {
        Some(r) => {
            let token: Option<String> = r.get("accesstoken");
            let has_token = token.map(|t| !t.trim().is_empty()).unwrap_or(false);
            let hook: Option<String> = r.try_get("webhook_debit_id").ok().or_else(|| r.try_get("webhookdebitid").ok());
            (has_token, hook.is_some())
        }
        None => (false, false),
    };

    Ok(Json(serde_json::json!({
        "connected": connected,
        "webhooksRegistered": webhooks_registered
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
        }
    }

    Ok(Json(serde_json::json!({ "status": "ok" })))
}
