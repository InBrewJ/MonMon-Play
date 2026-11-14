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

#[derive(Debug, Deserialize, Default, Clone)]
pub struct StatementQuery {
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
    #[serde(alias = "accountId")]
    pub account_id: Option<i64>,
    #[serde(alias = "anchorBalance")]
    pub anchor_balance: Option<f32>,
    #[serde(alias = "anchorDate")]
    pub anchor_date: Option<String>,
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

#[derive(Debug, Clone)]
pub struct ParsedStatementTx {
    pub id: String,
    pub date: String,
    pub timestamp: i64,
    pub amount: f32, // MonMon convention: positive is spend, negative is income
    pub description: String,
    pub merchant: String,
    pub category: String,
}

pub fn parse_transactions_from_payload(raw_json: &Value) -> Vec<ParsedStatementTx> {
    let mut result = Vec::new();
    let txs = match raw_json.get("transactions").and_then(|v| v.as_array()) {
        Some(arr) => arr,
        None => return result,
    };

    for (idx, t) in txs.iter().enumerate() {
        let data = t.get("data").unwrap_or(t);
        let tx_id = data
            .get("id")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .unwrap_or_else(|| format!("legmon_tx_{}", idx));

        let desc = data
            .get("description")
            .and_then(|v| v.as_str())
            .unwrap_or("Transaction")
            .to_string();

        let merchant = data
            .get("merchant")
            .and_then(|v| {
                if let Some(m_obj) = v.as_object() {
                    m_obj.get("name").and_then(|n| n.as_str())
                } else {
                    v.as_str()
                }
            })
            .unwrap_or(&desc)
            .to_string();

        let category = data
            .get("category")
            .and_then(|v| v.as_str())
            .unwrap_or("general")
            .to_string();

        let amount: f32 = if let Some(a) = data.get("amount").and_then(|v| v.as_f64()) {
            a as f32
        } else if let Some(s) = data.get("amount").and_then(|v| v.as_str()) {
            if let Ok(pence) = s.parse::<i64>() {
                // Monzo webhook pence: negative is spend (positive in MonMon), positive is income (negative in MonMon)
                (pence.abs() as f32 / 100.0) * if pence > 0 { -1.0 } else { 1.0 }
            } else {
                s.parse::<f32>().unwrap_or(0.0)
            }
        } else {
            0.0
        };

        let created_str = data
            .get("created")
            .or_else(|| data.get("date"))
            .and_then(|v| v.as_str())
            .unwrap_or("2026-10-01");

        let (epoch_sec, date_str) = if created_str.contains('T') {
            if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(created_str) {
                (dt.timestamp(), dt.format("%Y-%m-%d").to_string())
            } else {
                (Utc::now().timestamp(), "2026-10-01".to_string())
            }
        } else if let Ok(nd) = chrono::NaiveDate::parse_from_str(created_str, "%Y-%m-%d") {
            (
                nd.and_hms_opt(12, 0, 0).unwrap().and_utc().timestamp(),
                created_str.to_string(),
            )
        } else {
            (Utc::now().timestamp(), "2026-10-01".to_string())
        };

        result.push(ParsedStatementTx {
            id: tx_id,
            date: date_str,
            timestamp: epoch_sec,
            amount,
            description: desc,
            merchant,
            category,
        });
    }

    result
}

#[derive(Debug, Clone)]
pub struct TransactionDelta {
    pub timestamp: i64,
    pub delta: f32, // Positive for inflow (credit), negative for outflow (spend)
    pub description: String,
}

pub fn normalize_intraday_deltas(txs: &[ParsedStatementTx]) -> Vec<TransactionDelta> {
    let mut by_date: std::collections::BTreeMap<String, Vec<&ParsedStatementTx>> =
        std::collections::BTreeMap::new();
    for tx in txs {
        by_date.entry(tx.date.clone()).or_default().push(tx);
    }

    let mut result = Vec::new();
    for (date_str, day_txs) in by_date {
        let base_date = chrono::NaiveDate::parse_from_str(&date_str, "%Y-%m-%d")
            .unwrap_or_else(|_| chrono::NaiveDate::from_ymd_opt(2026, 10, 1).unwrap());

        let mut inflow_sec = base_date.and_hms_opt(9, 0, 0).unwrap().and_utc().timestamp();
        let mut outflow_sec = base_date.and_hms_opt(17, 0, 0).unwrap().and_utc().timestamp();

        // Process inflows (amount < 0 -> delta > 0) first in the morning
        for tx in &day_txs {
            let delta = -tx.amount;
            if delta > 0.0 {
                result.push(TransactionDelta {
                    timestamp: inflow_sec,
                    delta,
                    description: tx.description.clone(),
                });
                inflow_sec += 1;
            }
        }

        // Process outflows (amount > 0 -> delta <= 0) next in the afternoon
        for tx in &day_txs {
            let delta = -tx.amount;
            if delta <= 0.0 {
                result.push(TransactionDelta {
                    timestamp: outflow_sec,
                    delta,
                    description: tx.description.clone(),
                });
                outflow_sec += 1;
            }
        }
    }

    result.sort_by_key(|d| d.timestamp);
    result
}

pub async fn handle_statement_webhook_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<StatementQuery>,
    Json(payload): Json<Value>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = q
        .user_id
        .or_else(|| payload.get("userId").and_then(|v| v.as_str()).map(|s| s.to_string()))
        .unwrap_or_else(|| "demo".to_string());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let job_id = payload
        .get("jobId")
        .and_then(|v| v.as_str())
        .unwrap_or("stmt_default");
    let bank = payload
        .get("bank")
        .and_then(|v| v.as_str())
        .unwrap_or("Unknown Bank");
    let start_date = payload
        .get("startDate")
        .and_then(|v| v.as_str())
        .unwrap_or("2026-10-01");
    let end_date = payload
        .get("endDate")
        .and_then(|v| v.as_str())
        .unwrap_or("2026-10-31");

    let opening_bal = payload
        .get("openingBalance")
        .and_then(|v| v.as_f64())
        .unwrap_or(0.0) as f32;
    let closing_bal = payload
        .get("closingBalance")
        .and_then(|v| v.as_f64())
        .unwrap_or(0.0) as f32;

    let tx_parsed = parse_transactions_from_payload(&payload);
    let tx_count = tx_parsed.len() as i32;

    let mut total_in = 0.0f32;
    let mut total_out = 0.0f32;
    for t in &tx_parsed {
        if t.amount < 0.0 {
            total_in += -t.amount;
        } else {
            total_out += t.amount;
        }
    }

    if total_in == 0.0 && total_out == 0.0 {
        if let Some(p_in) = payload.get("totalPaidIn").and_then(|v| v.as_f64()) {
            total_in = p_in as f32;
        }
        if let Some(p_out) = payload.get("totalPaidOut").and_then(|v| v.as_f64()) {
            total_out = p_out as f32;
        }
    }

    let mut target_acc_id = payload
        .get("targetAccountId")
        .and_then(|v| v.as_i64())
        .or(q.account_id);

    if target_acc_id.is_none() {
        let b_lower = bank.to_lowercase();
        let pattern = if b_lower.contains("ns&i") || b_lower.contains("premium bonds") || b_lower.contains("nsandi") {
            Some("%premium bonds%")
        } else if b_lower.contains("first direct") || b_lower.contains("1st direct") {
            Some("%first direct%")
        } else if b_lower.contains("natwest") {
            Some("%natwest%")
        } else if b_lower.contains("lloyds") {
            Some("%lloyds%")
        } else if b_lower.contains("monzo") {
            Some("%monzo%")
        } else {
            None
        };

        if let Some(p) = pattern {
            if let Ok(matched_row) = sqlx::query(
                "SELECT id FROM account WHERE (userid = $1 OR user_id = $1) AND (LOWER(name) LIKE $2 OR LOWER(nickname) LIKE $2) ORDER BY archived ASC, id ASC LIMIT 1"
            )
            .bind(&resolved)
            .bind(p)
            .fetch_optional(&state.pool)
            .await {
                target_acc_id = matched_row.map(|r| r.get("id"));
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
            matched_account_id, raw_payload, status, created_at
        ) VALUES (
            $1, $1, $1, $2, $3, $4, $5,
            $6, $7, $8, $9, $10,
            $11, $12, 'PENDING', $13
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
    .bind(target_acc_id)
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
        "totalPaidIn": (total_in * 100.0).round() / 100.0,
        "totalPaidOut": (total_out * 100.0).round() / 100.0
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

    let pending: Vec<Value> = rows
        .into_iter()
        .map(|r| {
            let bank_name = r.get::<String, _>("bank_name");
            let is_tx_list = bank_name.to_lowercase().contains("transaction")
                || bank_name.to_lowercase().contains("txn");
            serde_json::json!({
                "id": r.get::<i64, _>("id"),
                "jobId": r.get::<String, _>("job_id"),
                "bankName": bank_name,
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
        })
        .collect();

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
        "SELECT id, bank_name, start_date, end_date, opening_balance, closing_balance, matched_account_id, raw_payload FROM pending_statement WHERE id = $1 AND (user_id = $2 OR userid = $2)"
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
    let start_date: String = r.get::<Option<String>, _>("start_date").unwrap_or_else(|| "2026-10-01".to_string());
    let end_date: String = r.get::<Option<String>, _>("end_date").unwrap_or_else(|| "2026-10-31".to_string());
    let opening: Option<f32> = r.get("opening_balance");
    let closing: Option<f32> = r.get("closing_balance");
    let matched_acc_id: Option<i64> = r.get("matched_account_id");
    let raw_payload: Option<String> = r.get("raw_payload");

    let parsed_json: Value = raw_payload
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(serde_json::json!({}));

    let tx_list = parse_transactions_from_payload(&parsed_json);
    let is_tx_list = bank.to_lowercase().contains("transaction") || bank.to_lowercase().contains("txn");

    // Account metadata
    let target_account_id = q.account_id.or(matched_acc_id);
    let mut account_name = "Matched Account".to_string();
    let mut existing_balances_count: i64 = 0;

    let start_ts = chrono::NaiveDate::parse_from_str(&start_date, "%Y-%m-%d")
        .map(|d| d.and_hms_opt(0, 0, 0).unwrap().and_utc().timestamp())
        .unwrap_or(0);
    let end_ts = chrono::NaiveDate::parse_from_str(&end_date, "%Y-%m-%d")
        .map(|d| d.and_hms_opt(23, 59, 59).unwrap().and_utc().timestamp())
        .unwrap_or(Utc::now().timestamp());

    if let Some(acc_id) = target_account_id {
        if let Ok(acc_row) = sqlx::query("SELECT name FROM account WHERE id = $1")
            .bind(acc_id)
            .fetch_optional(&state.pool)
            .await
        {
            if let Some(ar) = acc_row {
                account_name = ar.get("name");
            }
        }

        if let Ok(count_row) = sqlx::query(
            "SELECT count(*) as cnt FROM balance WHERE account_id = $1 AND (user_id = $2 OR userid = $2) AND timestamp >= $3 AND timestamp <= $4"
        )
        .bind(acc_id)
        .bind(&resolved)
        .bind(start_ts)
        .bind(end_ts)
        .fetch_one(&state.pool)
        .await
        {
            existing_balances_count = count_row.get("cnt");
        }
    }

    // Determine Anchor Balance and Anchor Timestamp
    let anchor_val = q
        .anchor_balance
        .or(closing)
        .or(opening)
        .unwrap_or(0.0);

    let anchor_ts = if let Some(ref ad) = q.anchor_date {
        chrono::NaiveDate::parse_from_str(ad, "%Y-%m-%d")
            .map(|d| d.and_hms_opt(23, 59, 59).unwrap().and_utc().timestamp())
            .unwrap_or(end_ts)
    } else {
        end_ts
    };

    let anchor_date_str = if let Some(ref ad) = q.anchor_date {
        ad.clone()
    } else {
        end_date.clone()
    };

    // Calculate chronological deltas and running balances
    let deltas = normalize_intraday_deltas(&tx_list);
    let net_delta: f32 = deltas.iter().map(|d| d.delta).sum();

    // Backward reconstruction from anchor to start
    let backward_sum: f32 = deltas
        .iter()
        .filter(|d| d.timestamp <= anchor_ts && d.timestamp >= start_ts)
        .map(|d| d.delta)
        .sum();
    let calculated_opening = anchor_val - backward_sum;

    // Forward reconstruction from anchor to end
    let forward_sum: f32 = deltas
        .iter()
        .filter(|d| d.timestamp > anchor_ts && d.timestamp <= end_ts)
        .map(|d| d.delta)
        .sum();
    let calculated_closing = anchor_val + forward_sum;

    let mut points: Vec<Value> = Vec::new();

    // 1. Opening balance point
    points.push(serde_json::json!({
        "timestamp": start_ts,
        "dateStr": start_date,
        "balance": (calculated_opening * 100.0).round() / 100.0,
        "delta": 0.0,
        "description": "Opening Balance",
        "isAnchor": (start_ts - anchor_ts).abs() < 86400
    }));

    // 2. Transaction running balance points
    let mut running = calculated_opening;
    for d in &deltas {
        running += d.delta;
        let date_str = chrono::DateTime::from_timestamp(d.timestamp, 0)
            .map(|dt| dt.format("%Y-%m-%d").to_string())
            .unwrap_or_else(|| start_date.clone());

        points.push(serde_json::json!({
            "timestamp": d.timestamp,
            "dateStr": date_str,
            "balance": (running * 100.0).round() / 100.0,
            "delta": d.delta,
            "description": d.description,
            "isAnchor": (d.timestamp - anchor_ts).abs() < 86400
        }));
    }

    // 3. Closing balance point
    points.push(serde_json::json!({
        "timestamp": end_ts,
        "dateStr": end_date,
        "balance": (calculated_closing * 100.0).round() / 100.0,
        "delta": 0.0,
        "description": "Closing Balance",
        "isAnchor": (end_ts - anchor_ts).abs() < 86400
    }));

    let mut total_in = 0.0f32;
    let mut total_out = 0.0f32;
    for t in &tx_list {
        if t.amount < 0.0 {
            total_in += -t.amount;
        } else {
            total_out += t.amount;
        }
    }

    Ok(Json(serde_json::json!({
        "status": "ok",
        "statementId": id,
        "bankName": bank,
        "startDate": start_date,
        "endDate": end_date,
        "isTxList": is_tx_list,
        "transactionCount": tx_list.len(),
        "totalPaidIn": (total_in * 100.0).round() / 100.0,
        "totalPaidOut": (total_out * 100.0).round() / 100.0,
        "existingBalancesToOverwrite": existing_balances_count,
        "accountId": target_account_id,
        "accountName": account_name,
        "hasAnchor": true,
        "anchorBalance": anchor_val,
        "anchorDateStr": anchor_date_str,
        "openingBalance": (calculated_opening * 100.0).round() / 100.0,
        "closingBalance": (calculated_closing * 100.0).round() / 100.0,
        "netDelta": (net_delta * 100.0).round() / 100.0,
        "points": points,
        "transactions": parsed_json.get("transactions").cloned().unwrap_or(serde_json::json!([]))
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

    let row = sqlx::query(
        "SELECT raw_payload, closing_balance, opening_balance, start_date, end_date, matched_account_id FROM pending_statement WHERE id = $1 AND (user_id = $2 OR userid = $2)"
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

    let target_account_id = payload.account_id.or_else(|| r.get("matched_account_id"));
    let raw_payload: Option<String> = r.get("raw_payload");
    let stmt_closing: Option<f32> = r.get("closing_balance");
    let stmt_opening: Option<f32> = r.get("opening_balance");
    let start_date: String = r.get::<Option<String>, _>("start_date").unwrap_or_else(|| "2026-10-01".to_string());
    let end_date: String = r.get::<Option<String>, _>("end_date").unwrap_or_else(|| "2026-10-31".to_string());

    let parsed: Value = raw_payload
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(serde_json::json!({}));

    let tx_list = parse_transactions_from_payload(&parsed);

    // 1. Insert bank transactions
    for tx in &tx_list {
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
        .bind(&tx.id)
        .bind(tx.timestamp)
        .bind(&tx.date)
        .bind(tx.amount)
        .bind(&tx.description)
        .execute(&state.pool)
        .await;
    }

    // 2. Overwrite balances in the date range if target account is selected
    if let Some(acc_id) = target_account_id {
        let start_ts = chrono::NaiveDate::parse_from_str(&start_date, "%Y-%m-%d")
            .map(|d| d.and_hms_opt(0, 0, 0).unwrap().and_utc().timestamp())
            .unwrap_or(0);
        let end_ts = chrono::NaiveDate::parse_from_str(&end_date, "%Y-%m-%d")
            .map(|d| d.and_hms_opt(23, 59, 59).unwrap().and_utc().timestamp())
            .unwrap_or(Utc::now().timestamp());

        let anchor_val = payload
            .anchor_balance
            .or(stmt_closing)
            .or(stmt_opening)
            .unwrap_or(0.0);

        let anchor_ts = if let Some(ref ad) = payload.anchor_date {
            chrono::NaiveDate::parse_from_str(ad, "%Y-%m-%d")
                .map(|d| d.and_hms_opt(23, 59, 59).unwrap().and_utc().timestamp())
                .unwrap_or(end_ts)
        } else {
            end_ts
        };

        let deltas = normalize_intraday_deltas(&tx_list);

        let backward_sum: f32 = deltas
            .iter()
            .filter(|d| d.timestamp <= anchor_ts && d.timestamp >= start_ts)
            .map(|d| d.delta)
            .sum();
        let calculated_opening = anchor_val - backward_sum;

        let forward_sum: f32 = deltas
            .iter()
            .filter(|d| d.timestamp > anchor_ts && d.timestamp <= end_ts)
            .map(|d| d.delta)
            .sum();
        let calculated_closing = anchor_val + forward_sum;

        // Delete existing balances in this range for this account
        let _ = sqlx::query(
            r#"DELETE FROM balance
               WHERE account_id = $1 AND (user_id = $2 OR userid = $2)
                 AND timestamp >= $3 AND timestamp <= $4"#
        )
        .bind(acc_id)
        .bind(&resolved)
        .bind(start_ts)
        .bind(end_ts)
        .execute(&state.pool)
        .await;

        // Insert opening balance
        let _ = sqlx::query(
            r#"INSERT INTO balance (id, value, timestamp, account_id, user_id, userid, "userId")
               VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4)"#,
        )
        .bind(calculated_opening)
        .bind(start_ts)
        .bind(acc_id)
        .bind(&resolved)
        .execute(&state.pool)
        .await;

        // Group intermediate running balances by date
        let mut daily_closing: std::collections::BTreeMap<String, (i64, f32)> =
            std::collections::BTreeMap::new();
        let mut cur_bal = calculated_opening;
        for d in &deltas {
            cur_bal += d.delta;
            let d_str = chrono::DateTime::from_timestamp(d.timestamp, 0)
                .map(|dt| dt.format("%Y-%m-%d").to_string())
                .unwrap_or_default();
            daily_closing.insert(d_str, (d.timestamp, (cur_bal * 100.0).round() / 100.0));
        }

        for (_, (ts, val)) in daily_closing {
            if ts > start_ts && ts < end_ts {
                let _ = sqlx::query(
                    r#"INSERT INTO balance (id, value, timestamp, account_id, user_id, userid, "userId")
                       VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4)"#,
                )
                .bind(val)
                .bind(ts)
                .bind(acc_id)
                .bind(&resolved)
                .execute(&state.pool)
                .await;
            }
        }

        // Insert closing balance
        let _ = sqlx::query(
            r#"INSERT INTO balance (id, value, timestamp, account_id, user_id, userid, "userId")
               VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4)"#,
        )
        .bind(calculated_closing)
        .bind(end_ts)
        .bind(acc_id)
        .bind(&resolved)
        .execute(&state.pool)
        .await;
    }

    let now_ms = Utc::now().timestamp_millis();
    let res = sqlx::query(
        "UPDATE pending_statement SET status = 'CONFIRMED', matched_account_id = COALESCE($1, matched_account_id), applied_at = $2 WHERE id = $3 AND (user_id = $4 OR userid = $4)",
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
        "UPDATE pending_statement SET status = 'DISCARDED', applied_at = $1 WHERE id = $2 AND (user_id = $3 OR userid = $3)",
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
                    "/bank?statementProcessing=true&info=Statement+PDF+uploaded+successfully.+LegMon+OCR+extraction+in+progress...",
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_transactions_from_monzo_webhook_payload() {
        let payload = serde_json::json!({
            "transactions": [
                {
                    "type": "transaction.created",
                    "data": {
                        "id": "tx_1",
                        "created": "2026-10-14T12:00:00Z",
                        "amount": "-50000",
                        "description": "Tesco Groceries",
                        "category": "groceries"
                    }
                },
                {
                    "type": "transaction.created",
                    "data": {
                        "id": "tx_2",
                        "created": "2026-10-15T12:00:00Z",
                        "amount": "250000",
                        "description": "Salary Employer",
                        "category": "income"
                    }
                }
            ]
        });

        let txs = parse_transactions_from_payload(&payload);
        assert_eq!(txs.len(), 2);
        // In MonMon: spend is positive, income is negative
        assert_eq!(txs[0].amount, 500.0);
        assert_eq!(txs[0].date, "2026-10-14");
        assert_eq!(txs[1].amount, -2500.0);
        assert_eq!(txs[1].date, "2026-10-15");
    }

    #[test]
    fn test_normalize_intraday_deltas_inflows_before_outflows() {
        let txs = vec![
            ParsedStatementTx {
                id: "tx_out".to_string(),
                date: "2026-10-28".to_string(),
                timestamp: 1729000000,
                amount: 150.0, // spend -> delta -150
                description: "Rent".to_string(),
                merchant: "Landlord".to_string(),
                category: "bills".to_string(),
            },
            ParsedStatementTx {
                id: "tx_in".to_string(),
                date: "2026-10-28".to_string(),
                timestamp: 1729000000,
                amount: -2000.0, // income -> delta +2000
                description: "Salary".to_string(),
                merchant: "Boss".to_string(),
                category: "income".to_string(),
            },
        ];

        let deltas = normalize_intraday_deltas(&txs);
        assert_eq!(deltas.len(), 2);
        // Morning inflow first
        assert_eq!(deltas[0].delta, 2000.0);
        assert_eq!(deltas[1].delta, -150.0);
        assert!(deltas[0].timestamp < deltas[1].timestamp);
    }

    #[test]
    fn test_parse_transactions_empty_payload() {
        let payload = serde_json::json!({
            "bank": "NS&I",
            "startDate": "2026-11-02",
            "endDate": "2026-11-02",
            "openingBalance": 2175.0,
            "closingBalance": 2175.0,
            "transactions": []
        });
        let txs = parse_transactions_from_payload(&payload);
        assert_eq!(txs.len(), 0);
        let deltas = normalize_intraday_deltas(&txs);
        assert_eq!(deltas.len(), 0);
    }
}
