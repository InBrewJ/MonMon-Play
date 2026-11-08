use crate::spog::resolve_user_id;
use crate::AppState;
use axum::{
    extract::{Multipart, Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Json, Redirect},
};
use chrono::Utc;
use serde::Deserialize;
use serde_json::Value;
use sqlx::Row;
use std::env;
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct StatementQuery {
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
}

impl StatementQuery {
    pub fn get_user_id(&self) -> String {
        self.user_id.clone().unwrap_or_else(|| "demo".to_string())
    }
}

fn legmon_base_url() -> String {
    env::var("LEGMON_URL").unwrap_or_else(|_| "http://localhost:9005".to_string())
}

pub async fn get_legmon_status_api() -> Result<Json<Value>, (StatusCode, String)> {
    let url = format!("{}/status", legmon_base_url());
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(3))
        .build()
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    match client.get(&url).send().await {
        Ok(resp) => {
            let json = resp
                .json::<Value>()
                .await
                .unwrap_or_else(|_| serde_json::json!({ "service": "LegMon", "status": "unknown" }));
            Ok(Json(json))
        }
        Err(err) => Ok(Json(serde_json::json!({
            "service": "LegMon",
            "status": "offline",
            "error": err.to_string()
        }))),
    }
}

pub async fn get_legmon_logs_api() -> Result<Json<Value>, (StatusCode, String)> {
    let url = format!("{}/api/logs", legmon_base_url());
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(3))
        .build()
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    match client.get(&url).send().await {
        Ok(resp) => {
            let json = resp
                .json::<Value>()
                .await
                .unwrap_or_else(|_| serde_json::json!({ "service": "LegMon", "logs": [] }));
            Ok(Json(json))
        }
        Err(err) => Ok(Json(serde_json::json!({
            "service": "LegMon",
            "logs": [],
            "error": err.to_string()
        }))),
    }
}

pub async fn handle_statement_webhook_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<StatementQuery>,
    Json(payload): Json<Value>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = q.user_id.or_else(|| payload.get("userId").and_then(|v| v.as_str()).map(|s| s.to_string()))
        .unwrap_or_else(|| "demo".to_string());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let job_id = payload.get("jobId")
        .and_then(|v| v.as_str())
        .unwrap_or("stmt_default");
    let bank = payload.get("bank")
        .and_then(|v| v.as_str())
        .unwrap_or("Unknown Bank");
    let start_date = payload.get("startDate")
        .and_then(|v| v.as_str())
        .unwrap_or("2026-10-01");
    let end_date = payload.get("endDate")
        .and_then(|v| v.as_str())
        .unwrap_or("2026-10-31");

    let opening_bal = payload.get("openingBalance").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
    let closing_bal = payload.get("closingBalance").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;

    let tx_array = payload.get("transactions").and_then(|v| v.as_array());
    let tx_count = tx_array.map(|a| a.len()).unwrap_or(0) as i32;

    let mut total_in = 0.0f32;
    let mut total_out = 0.0f32;
    if let Some(arr) = tx_array {
        for t in arr {
            let amt = t.get("amount").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
            if amt > 0.0 {
                total_in += amt;
            } else {
                total_out += amt.abs();
            }
        }
    }

    let now = Utc::now().timestamp_millis();
    let raw_str = payload.to_string();

    let row = sqlx::query(
        r#"
        INSERT INTO pending_statement (
            userid, user_id, "userId", job_id, bank_name, start_date, end_date,
            opening_balance, closing_balance, transaction_count, total_paid_in, total_paid_out,
            raw_payload, status, created_at
        ) VALUES (
            $1, $1, $1, $2, $3, $4, $5,
            $6, $7, $8, $9, $10,
            $11, 'PENDING', $12
        )
        RETURNING id
        "#,
    )
    .bind(&resolved)
    .bind(job_id)
    .bind(bank)
    .bind(start_date)
    .bind(end_date)
    .bind(opening_bal)
    .bind(closing_bal)
    .bind(tx_count)
    .bind(total_in)
    .bind(total_out)
    .bind(&raw_str)
    .bind(now)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let statement_id: i64 = row.get("id");

    Ok(Json(serde_json::json!({
        "status": "ok",
        "statementId": statement_id,
        "jobId": job_id,
        "bank": bank,
        "totalPaidIn": total_in,
        "totalPaidOut": total_out
    })))
}

pub async fn get_statement_preview_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Query(_q): Query<StatementQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let row = sqlx::query(
        "SELECT id, bank_name, start_date, end_date, opening_balance, closing_balance, raw_payload FROM pending_statement WHERE id = $1"
    )
    .bind(id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let r = match row {
        Some(r) => r,
        None => return Err((StatusCode::NOT_FOUND, "Statement not found".to_string())),
    };

    let bank: String = r.get("bank_name");
    let start_date: Option<String> = r.get("start_date");
    let end_date: Option<String> = r.get("end_date");
    let opening: Option<f32> = r.get("opening_balance");
    let closing: Option<f32> = r.get("closing_balance");
    let raw_payload: Option<String> = r.get("raw_payload");

    let parsed_json: Value = raw_payload
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(serde_json::json!({}));

    let txs = parsed_json.get("transactions").cloned().unwrap_or(serde_json::json!([]));

    Ok(Json(serde_json::json!({
        "id": id,
        "bank": bank,
        "startDate": start_date,
        "endDate": end_date,
        "openingBalance": opening,
        "closingBalance": closing,
        "transactions": txs
    })))
}

#[derive(Debug, Deserialize)]
pub struct ConfirmPayload {
    #[serde(alias = "accountId")]
    pub account_id: Option<i64>,
}

pub async fn confirm_statement_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Query(q): Query<StatementQuery>,
    payload: Option<Json<ConfirmPayload>>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;
    let target_account_id = payload.and_then(|p| p.account_id);

    let row = sqlx::query(
        "SELECT raw_payload FROM pending_statement WHERE id = $1"
    )
    .bind(id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let r = match row {
        Some(r) => r,
        None => return Err((StatusCode::NOT_FOUND, "Statement not found".to_string())),
    };

    let raw_payload: Option<String> = r.get("raw_payload");
    let parsed: Value = raw_payload
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(serde_json::json!({}));

    let now_sec = Utc::now().timestamp();
    if let Some(arr) = parsed.get("transactions").and_then(|v| v.as_array()) {
        for (idx, t) in arr.iter().enumerate() {
            let desc = t.get("description").and_then(|v| v.as_str()).unwrap_or("Statement Transaction");
            let date = t.get("date").and_then(|v| v.as_str()).unwrap_or("2026-10-01");
            let amt = t.get("amount").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
            let ext_id = format!("stmt_{}_{}_{}", id, date, idx);

            let _ = sqlx::query(
                r#"
                INSERT INTO bank_transaction (
                    id, userid, user_id, "userId", account_id, accountid, "accountId",
                    externalid, timestamp, date, amount, description, source, pending, createdat
                ) VALUES (
                    nextval('hibernate_sequence'), $1, $1, $1, $2, $2, $2,
                    $3, $4, $5, $6, $7, 'STATEMENT', false, $4
                )
                ON CONFLICT (externalid) DO UPDATE SET
                    amount = EXCLUDED.amount,
                    description = EXCLUDED.description
                "#,
            )
            .bind(&resolved)
            .bind(target_account_id)
            .bind(&ext_id)
            .bind(now_sec)
            .bind(date)
            .bind(amt)
            .bind(desc)
            .execute(&state.pool)
            .await;
        }
    }

    let now_ms = Utc::now().timestamp_millis();
    let _ = sqlx::query(
        "UPDATE pending_statement SET status = 'CONFIRMED', applied_at = $1 WHERE id = $2"
    )
    .bind(now_ms)
    .bind(id)
    .execute(&state.pool)
    .await;

    Ok(Json(serde_json::json!({
        "status": "confirmed",
        "id": id
    })))
}

pub async fn discard_statement_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let now_ms = Utc::now().timestamp_millis();
    let _ = sqlx::query(
        "UPDATE pending_statement SET status = 'DISCARDED', applied_at = $1 WHERE id = $2"
    )
    .bind(now_ms)
    .bind(id)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(serde_json::json!({
        "status": "discarded",
        "id": id
    })))
}

pub async fn upload_statement_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Result<Redirect, (StatusCode, String)> {
    let mut file_bytes = Vec::new();
    let mut target_account_id = None;
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    while let Ok(Some(field)) = multipart.next_field().await {
        let name = field.name().unwrap_or("").to_string();
        if name == "pdfFile" {
            if let Ok(bytes) = field.bytes().await {
                file_bytes = bytes.to_vec();
            }
        } else if name == "accountId" {
            if let Ok(text) = field.text().await {
                if let Ok(num) = text.trim().parse::<i64>() {
                    target_account_id = Some(num);
                }
            }
        }
    }

    if !file_bytes.is_empty() {
        let legmon_url = legmon_base_url();
        let callback_url = format!("http://localhost:9001/api/statement/webhook?userId={}", resolved);
        let base_process = format!("{}/api/process", legmon_url);
        if let Ok(mut url) = reqwest::Url::parse(&base_process) {
            url.query_pairs_mut()
                .append_pair("userId", &resolved)
                .append_pair("callbackUrl", &callback_url);
            if let Some(acc_id) = target_account_id {
                url.query_pairs_mut().append_pair("accountId", &acc_id.to_string());
            }

            let client = reqwest::Client::new();
            let _ = client
                .post(url)
                .header("Content-Type", "application/pdf")
                .body(file_bytes)
                .send()
                .await;
        }
    }

    Ok(Redirect::to("/bank"))
}
