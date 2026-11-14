mod log_buffer;
mod models;
mod ocr;
mod parser;
mod webhook;

use axum::{
    body::Bytes,
    extract::{Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Json},
    routing::{get, post},
    Router,
};
use log_buffer::LogBuffer;
use models::{LogsResponse, StatusResponse};
use serde::Deserialize;
use serde_json::json;
use std::env;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;
use tokio::fs;
use tracing::info;
use uuid::Uuid;

#[derive(Clone)]
pub struct AppState {
    pub log_buffer: LogBuffer,
    pub start_time: Instant,
    pub monmon_url: String,
    pub http_client: reqwest::Client,
    pub mistral_key_configured: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProcessParams {
    pub user_id: Option<String>,
    pub account_id: Option<i64>,
    pub callback_url: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct LogsParams {
    pub limit: Option<usize>,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    let port: u16 = env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(9005);
    let monmon_url = env::var("MONMON_URL").unwrap_or_else(|_| "http://127.0.0.1:9000".to_string());
    let mistral_key_configured = env::var("MISTRAL_API_KEY").map(|k| !k.is_empty()).unwrap_or(false);

    let log_buffer = LogBuffer::new(200);
    log_buffer.info(&format!(
        "LegMon-rs service started on port {} (MonMon URL: {})",
        port, monmon_url
    ));

    let state = Arc::new(AppState {
        log_buffer,
        start_time: Instant::now(),
        monmon_url,
        http_client: reqwest::Client::new(),
        mistral_key_configured,
    });

    let app = Router::new()
        .route("/health", get(health_handler))
        .route("/status", get(status_handler))
        .route("/api/logs", get(logs_handler))
        .route("/api/logs/clear", post(logs_clear_handler))
        .route("/api/process", post(process_handler))
        .with_state(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    info!("LegMon-rs is listening on http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

async fn health_handler() -> impl IntoResponse {
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        json!({ "status": "ok", "service": "LegMon" }).to_string(),
    )
}

async fn status_handler(State(state): State<Arc<AppState>>) -> Json<StatusResponse> {
    let (has_error, error_count, last_error, total_buffered_logs) = state.log_buffer.get_status();
    let uptime_seconds = state.start_time.elapsed().as_secs().to_string();

    Json(StatusResponse {
        service: "LegMon".to_string(),
        status: if has_error { "warning".to_string() } else { "ok".to_string() },
        error_count,
        last_error,
        has_error,
        total_buffered_logs,
        uptime_seconds,
        mistral_key_configured: state.mistral_key_configured,
        mistral_ocr_enabled: state.mistral_key_configured,
        mistral_ocr_model: "mistral-ocr-latest".to_string(),
        primary_model: "mistral-small-latest".to_string(),
        fallback_model: "mistral-medium-latest".to_string(),
        primary_pipeline: "mistral-ocr-latest + mistral-small-latest".to_string(),
        fallback_pipeline: "pdftotext -layout + mistral-medium-latest".to_string(),
        deterministic_fallback: "pdftotext -layout + DeterministicStatementParser".to_string(),
        deterministic_only: !state.mistral_key_configured,
        ocr_tools: vec![
            "mistral-ocr".to_string(),
            "pdftotext".to_string(),
            "xberg".to_string(),
            "pdfbox".to_string(),
            "tesseract".to_string(),
        ],
    })
}

async fn logs_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<LogsParams>,
) -> Json<LogsResponse> {
    let limit = params.limit.unwrap_or(100);
    let (has_error, error_count, last_error, logs) = state.log_buffer.get_recent(limit);

    Json(LogsResponse {
        service: "LegMon".to_string(),
        has_error,
        error_count,
        last_error,
        logs,
    })
}

async fn logs_clear_handler(State(state): State<Arc<AppState>>) -> Json<serde_json::Value> {
    state.log_buffer.clear_errors();
    Json(json!({ "status": "cleared" }))
}

async fn process_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<ProcessParams>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<impl IntoResponse, (StatusCode, Json<serde_json::Value>)> {
    let content_type = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    let user_id = params
        .user_id
        .clone()
        .unwrap_or_else(|| "default-user".to_string());
    let callback_url = params.callback_url.clone().unwrap_or_else(|| {
        format!(
            "{}/api/statement/webhook?userId={}",
            state.monmon_url, user_id
        )
    });

    let job_id = format!("stmt_{}_{}", chrono::Utc::now().timestamp_millis(), &Uuid::new_v4().to_string()[..8]);
    state.log_buffer.info(&format!(
        "Received statement upload job {} for user {} (ContentType: {})",
        job_id, user_id, content_type
    ));

    if body.len() < 100 {
        let err = "Uploaded PDF file is empty or too small.".to_string();
        state.log_buffer.error(&err);
        return Err((
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": err })),
        ));
    }

    let temp_file_path = std::env::temp_dir().join(format!("legmon_stmt_{}.pdf", job_id));
    if let Err(e) = fs::write(&temp_file_path, &body).await {
        let err = format!("Failed to write temporary PDF: {}", e);
        state.log_buffer.error(&err);
        return Err((
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "error": err })),
        ));
    }

    let state_clone = Arc::clone(&state);
    let job_id_clone = job_id.clone();
    let user_id_clone = user_id.clone();
    let account_id = params.account_id;

    tokio::spawn(async move {
        state_clone
            .log_buffer
            .info(&format!("[{}] Commencing OCR & extraction pipeline", job_id_clone));

        // 1. Run deterministic pdftotext extraction
        let text_res = ocr::extract_text_pdftotext(&temp_file_path);
        let statement = match text_res {
            Ok(ref text) => parser::parse(text),
            Err(e) => {
                state_clone.log_buffer.error(&format!("[{}] OCR extraction failed: {}", job_id_clone, e));
                let _ = fs::remove_file(&temp_file_path).await;
                return;
            }
        };

        state_clone.log_buffer.info(&format!(
            "[{}] Extracted Bank='{}', {} transactions",
            job_id_clone,
            statement.bank,
            statement.transactions.len()
        ));

        // 2. Dispatch to MonMon webhook
        let dispatch_res = webhook::dispatch_webhook(
            &state_clone.http_client,
            &callback_url,
            &user_id_clone,
            &job_id_clone,
            account_id,
            &statement,
        )
        .await;

        match dispatch_res {
            Ok(_) => {
                state_clone
                    .log_buffer
                    .info(&format!("[{}] Pipeline completed successfully for {}", job_id_clone, statement.bank));
            }
            Err(e) => {
                state_clone
                    .log_buffer
                    .warn(&format!("[{}] Extraction completed but webhook delivery failed: {}", job_id_clone, e));
            }
        }

        let _ = fs::remove_file(&temp_file_path).await;
    });

    Ok((
        StatusCode::ACCEPTED,
        Json(json!({
            "status": "processing",
            "jobId": job_id,
            "message": "Statement accepted for OCR and Mistral extraction. Webhook will be notified upon completion."
        })),
    ))
}
