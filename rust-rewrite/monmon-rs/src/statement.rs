use crate::spog::resolve_user_id;
use crate::AppState;
use axum::{
    extract::{Multipart, Path, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Json, Redirect, Response},
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

pub async fn get_pending_statements_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<StatementQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let rows = sqlx::query(
        r#"
        SELECT id, COALESCE(job_id, '') AS job_id, COALESCE(bank_name, 'Unknown Bank') AS bank_name,
               COALESCE(start_date, '') AS start_date, COALESCE(end_date, '') AS end_date,
               opening_balance, closing_balance, COALESCE(transaction_count, 0) AS transaction_count,
               total_paid_in, total_paid_out, matched_account_id, COALESCE(status, 'PENDING') AS status,
               error_message
        FROM pending_statement
        WHERE (userid = $1 OR user_id = $1)
        ORDER BY created_at DESC
        LIMIT 10
        "#,
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let pending: Vec<Value> = rows.into_iter().map(|r| {
        let bank: String = r.get("bank_name");
        let is_tx_list = bank.to_lowercase().contains("transaction") || bank.to_lowercase().contains("txn");
        serde_json::json!({
            "id": r.get::<i64, _>("id"),
            "jobId": r.get::<String, _>("job_id"),
            "bankName": bank,
            "startDate": r.get::<String, _>("start_date"),
            "endDate": r.get::<String, _>("end_date"),
            "openingBalance": r.get::<Option<f32>, _>("opening_balance"),
            "closingBalance": r.get::<Option<f32>, _>("closing_balance"),
            "transactionCount": r.get::<i32, _>("transaction_count"),
            "totalPaidIn": r.get::<Option<f32>, _>("total_paid_in"),
            "totalPaidOut": r.get::<Option<f32>, _>("total_paid_out"),
            "matchedAccountId": r.get::<Option<i64>, _>("matched_account_id"),
            "status": r.get::<String, _>("status"),
            "errorMessage": r.get::<Option<String>, _>("error_message"),
            "isTxList": is_tx_list,
        })
    }).collect();

    Ok(Json(serde_json::json!({
        "status": "ok",
        "pendingStatements": pending
    })))
}

pub async fn get_statement_preview_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<StatementQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let row = sqlx::query(
        "SELECT id, bank_name, start_date, end_date, opening_balance, closing_balance, raw_payload FROM pending_statement WHERE id = $1 AND (user_id = $2 OR userid = $2)"
    )
    .bind(id)
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let r = match row {
        Some(r) => r,
        None => return Err((StatusCode::FORBIDDEN, "Statement not found or access denied".to_string())),
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
    let is_tx_list = bank.to_lowercase().contains("transaction") || bank.to_lowercase().contains("txn");

    let mut points: Vec<Value> = Vec::new();
    let mut net_delta = 0.0f32;
    let anchor_val = closing.or(opening).unwrap_or(0.0);

    if let Some(arr) = txs.as_array() {
        let mut running = anchor_val;
        for t in arr {
            let amt = t.get("amount").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
            let date = t.get("date").and_then(|v| v.as_str()).unwrap_or("2026-10-01");
            let desc = t.get("description").and_then(|v| v.as_str()).unwrap_or("Transaction");
            let delta = -amt;
            net_delta += delta;
            running += delta;
            points.push(serde_json::json!({
                "timestamp": chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d")
                    .map(|d| d.and_hms_opt(12, 0, 0).unwrap().and_utc().timestamp())
                    .unwrap_or(Utc::now().timestamp()),
                "dateStr": date,
                "balance": (running * 100.0).round() / 100.0,
                "delta": delta,
                "description": desc,
                "isAnchor": false
            }));
        }
    }

    Ok(Json(serde_json::json!({
        "status": "ok",
        "statementId": id,
        "bankName": bank,
        "startDate": start_date,
        "endDate": end_date,
        "isTxList": is_tx_list,
        "transactionCount": txs.as_array().map(|a| a.len()).unwrap_or(0),
        "totalPaidIn": opening.unwrap_or(0.0),
        "totalPaidOut": closing.unwrap_or(0.0),
        "existingBalancesToOverwrite": 0,
        "accountId": null,
        "accountName": "Matched Account",
        "hasAnchor": true,
        "anchorBalance": anchor_val,
        "anchorDateStr": end_date.clone().unwrap_or_default(),
        "openingBalance": opening.unwrap_or(0.0),
        "closingBalance": closing.unwrap_or(0.0),
        "netDelta": (net_delta * 100.0).round() / 100.0,
        "points": points,
        "transactions": txs
    })))
}

#[derive(Debug, Deserialize, Default)]
pub struct ConfirmPayload {
    #[serde(alias = "accountId")]
    pub account_id: Option<i64>,
    #[serde(alias = "anchorBalance")]
    pub anchor_balance: Option<f32>,
    #[serde(alias = "anchorDate")]
    pub anchor_date: Option<String>,
}

pub async fn confirm_statement_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<StatementQuery>,
    body: axum::body::Bytes,
) -> Result<Response, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let payload: ConfirmPayload = if let Ok(p) = serde_json::from_slice::<ConfirmPayload>(&body) {
        p
    } else {
        let mut p = ConfirmPayload::default();
        for (k, v) in form_urlencoded::parse(&body) {
            if k == "accountId" || k == "account_id" {
                p.account_id = v.parse::<i64>().ok();
            } else if k == "anchorBalance" || k == "anchor_balance" {
                p.anchor_balance = v.parse::<f32>().ok();
            } else if k == "anchorDate" || k == "anchor_date" {
                p.anchor_date = Some(v.to_string());
            }
        }
        p
    };
    let target_account_id = payload.account_id;

    let row = sqlx::query(
        "SELECT raw_payload FROM pending_statement WHERE id = $1 AND (user_id = $2 OR userid = $2)"
    )
    .bind(id)
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let r = match row {
        Some(r) => r,
        None => return Err((StatusCode::FORBIDDEN, "Statement not found or access denied".to_string())),
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
    let res = sqlx::query(
        "UPDATE pending_statement SET status = 'CONFIRMED', matched_account_id = COALESCE($1, matched_account_id), applied_at = $2 WHERE id = $3 AND (user_id = $4 OR userid = $4)"
    )
    .bind(target_account_id)
    .bind(now_ms)
    .bind(id)
    .bind(&resolved)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Statement", id)?;

    let accepts_json = headers
        .get(header::ACCEPT)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.contains("application/json"))
        .unwrap_or(false);

    if accepts_json {
        Ok(Json(serde_json::json!({
            "status": "confirmed",
            "id": id
        }))
        .into_response())
    } else {
        Ok(Redirect::to("/bank?info=Statement+confirmed+and+balances+imported+successfully.").into_response())
    }
}

pub async fn discard_statement_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<StatementQuery>,
) -> Result<Response, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    let now_ms = Utc::now().timestamp_millis();
    let res = sqlx::query(
        "UPDATE pending_statement SET status = 'DISCARDED', applied_at = $1 WHERE id = $2 AND (user_id = $3 OR userid = $3)"
    )
    .bind(now_ms)
    .bind(id)
    .bind(&resolved)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Statement", id)?;

    let accepts_json = headers
        .get(header::ACCEPT)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.contains("application/json"))
        .unwrap_or(false);

    if accepts_json {
        Ok(Json(serde_json::json!({
            "status": "discarded",
            "id": id
        }))
        .into_response())
    } else {
        Ok(Redirect::to("/bank?info=Statement+discarded.").into_response())
    }
}

fn monmon_webhook_callback_url(resolved: &str) -> String {
    let port = std::env::var("MONMON_PORT")
        .or_else(|_| std::env::var("PORT"))
        .unwrap_or_else(|_| "9000".to_string());
    let host = std::env::var("MONMON_HOST").unwrap_or_else(|_| "127.0.0.1".to_string());
    format!("http://{}:{}/api/statement/webhook?userId={}", host, port, resolved)
}

pub async fn upload_statement_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Result<Response, (StatusCode, String)> {
    let mut file_bytes = Vec::new();
    let mut target_account_id = None;
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let accepts_json = headers
        .get(header::ACCEPT)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.contains("application/json"))
        .unwrap_or(false)
        || headers.contains_key("x-requested-with");

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

    if file_bytes.is_empty() {
        if accepts_json {
            return Err((StatusCode::BAD_REQUEST, "Please select a PDF statement file to upload.".to_string()));
        }
        return Ok(Redirect::to("/bank?error=Please+select+a+PDF+statement+file+to+upload.").into_response());
    }

    let legmon_url = legmon_base_url();
    let callback_url = monmon_webhook_callback_url(&resolved);
    let base_process = format!("{}/api/process", legmon_url);
    if let Ok(mut url) = reqwest::Url::parse(&base_process) {
        url.query_pairs_mut()
            .append_pair("userId", &resolved)
            .append_pair("callbackUrl", &callback_url);
        if let Some(acc_id) = target_account_id {
            url.query_pairs_mut().append_pair("accountId", &acc_id.to_string());
        }

        let client = reqwest::Client::new();
        match client
            .post(url)
            .header("Content-Type", "application/pdf")
            .body(file_bytes)
            .send()
            .await
        {
            Ok(res) if res.status().is_success() => {
                if accepts_json {
                    return Ok((
                        StatusCode::ACCEPTED,
                        Json(serde_json::json!({
                            "status": "processing",
                            "message": "Statement accepted by LegMon. OCR & transaction extraction in progress..."
                        })),
                    )
                        .into_response());
                }
                return Ok(Redirect::to(
                    "/bank?info=Statement+PDF+uploaded+successfully.+LegMon+OCR+extraction+in+progress...",
                )
                .into_response());
            }
            Ok(res) => {
                let err_msg = format!("LegMon returned status {}: extraction failed", res.status());
                if accepts_json {
                    return Err((StatusCode::BAD_GATEWAY, err_msg));
                }
                return Ok(Redirect::to(&format!("/bank?error={}", urlencoding::encode(&err_msg))).into_response());
            }
            Err(e) => {
                let err_msg = format!("Could not reach LegMon service: {}", e);
                if accepts_json {
                    return Err((StatusCode::BAD_GATEWAY, err_msg));
                }
                return Ok(Redirect::to(&format!("/bank?error={}", urlencoding::encode(&err_msg))).into_response());
            }
        }
    }

    if accepts_json {
        Ok(Json(serde_json::json!({ "status": "processing" })).into_response())
    } else {
        Ok(Redirect::to("/bank?info=Statement+uploaded+successfully.").into_response())
    }
}
