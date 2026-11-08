mod admin;
mod bank_sync;
mod db;
mod dig;
mod landing;
mod management;
mod models;
mod net_worth;
mod nommon;
mod savings_plan;
mod spog;
mod statement;

use askama::Template;
use axum::{
    extract::{Path, Query, State},
    http::{header, StatusCode},
    response::{Html, IntoResponse, Json, Response},
    routing::{delete, get, post, put},
    Router,
};
use models::{
    Account, Balance, ErraticBalance, Incoming, NewAccount, NewBalance, NewIncoming, NewOutgoing,
    Outgoing, UpdateAccount, UpdateIncoming, UpdateOutgoing,
};
use serde::Deserialize;
use serde_json::json;
use sqlx::postgres::PgPoolOptions;
use sqlx::PgPool;
use std::env;
use std::net::SocketAddr;
use std::sync::Arc;
use tower_http::services::ServeDir;
use tracing::info;

#[derive(Clone)]
pub struct AppState {
    pub pool: PgPool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserQuery {
    pub user_id: String,
    pub threshold: Option<f32>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PausePayload {
    pub reason: String,
    pub user_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UnpausePayload {
    pub user_id: String,
}

#[derive(Debug, Deserialize)]
pub struct CallbackQuery {
    pub code: Option<String>,
    pub state: Option<String>,
}

pub async fn callback_handler(Query(params): Query<CallbackQuery>) -> Response {
    let target = params.state.unwrap_or_else(|| "/myOutgoings".to_string());
    let clean_target = if target.starts_with('/') {
        target
    } else {
        "/myOutgoings".to_string()
    };

    Response::builder()
        .status(StatusCode::SEE_OTHER)
        .header(header::LOCATION, clean_target)
        .header(
            header::SET_COOKIE,
            "monmon_session=authenticated; Path=/; HttpOnly; SameSite=Lax",
        )
        .header(
            header::SET_COOKIE,
            "PLAY_SESSION=authenticated; Path=/",
        )
        .body(axum::body::Body::empty())
        .unwrap()
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    let db_url = env::var("DB_URL")
        .or_else(|_| env::var("DATABASE_URL"))
        .unwrap_or_else(|_| "postgres://postgres:docker@127.0.0.1:5432/monmon-play".to_string());

    // Normalize jdbc:postgresql:// if present
    let clean_db_url = db_url.replace("jdbc:postgresql://", "postgres://");

    info!("Connecting to PostgreSQL at {}", clean_db_url);
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&clean_db_url)
        .await
        .expect("Failed to connect to PostgreSQL");

    let state = Arc::new(AppState { pool });

    let app = Router::new()
        // Landing & Public Gateway
        .route("/", get(landing::get_landing_page))
        .route("/about", get(landing::get_landing_page))
        .route("/health", get(landing::get_health_api))
        .route("/api/user/accessStatus", get(landing::get_access_status_api))
        .route("/login", get(|| async { landing::auth_redirect(None).await }))
        .route("/callback", get(callback_handler))
        // Settings & Waiting List
        .route("/settings", get(admin::get_settings).post(admin::post_settings))
        .route("/settings/approveUser/:id", post(admin::approve_user_handler))
        .route("/settings/rejectUser/:id", post(admin::reject_user_handler))
        .route("/waitinglist", get(admin::get_waiting_list))
        .route("/myOutgoings", get(management::get_outgoings_page))
        .route("/myAccounts", get(management::get_accounts_page))
        .route("/myIncomings", get(management::get_incomings_page))
        .route("/myBalances", get(management::get_balances_page))
        .route("/account", post(management::post_account_form))
        .route("/incoming", post(management::post_incoming_form))
        .route("/outgoing", post(management::post_outgoing_form))
        .route("/balance", post(management::post_balance_form))
        .route("/pauseOutgoing/:id", post(management::pause_outgoing_web))
        .route("/unpauseOutgoing/:id", get(management::unpause_outgoing_web).post(management::unpause_outgoing_web))
        // Accounts
        .route(
            "/internal/accounts",
            get(list_accounts_handler).post(create_account_handler),
        )
        .route(
            "/internal/accounts/:id",
            get(get_account_handler).put(update_account_handler),
        )
        .route("/internal/accounts/:id/archive", post(archive_account_handler))
        .route(
            "/internal/accounts/:id/toggleLedger",
            post(toggle_ledger_account_handler),
        )
        // Incomings
        .route(
            "/internal/incomings",
            get(list_incomings_handler).post(create_incoming_handler),
        )
        .route(
            "/internal/incomings/:id",
            get(get_incoming_handler).put(update_incoming_handler),
        )
        .route(
            "/internal/incomings/:id/archive",
            post(archive_incoming_handler),
        )
        // Outgoings
        .route(
            "/internal/outgoings",
            get(list_outgoings_handler).post(create_outgoing_handler),
        )
        .route("/internal/outgoings/rents", get(list_rents_handler))
        .route("/internal/outgoings/bills", get(list_bills_handler))
        .route(
            "/internal/outgoings/:id",
            get(get_outgoing_handler).put(update_outgoing_handler),
        )
        .route(
            "/internal/outgoings/:id/archive",
            post(archive_outgoing_handler),
        )
        .route("/internal/outgoings/:id/pause", post(pause_outgoing_handler))
        .route(
            "/internal/outgoings/:id/unpause",
            post(unpause_outgoing_handler),
        )
        // Balances
        .route(
            "/internal/balances",
            get(list_balances_handler).post(create_balance_handler),
        )
        .route("/internal/balances/erratic", get(erratic_balances_handler))
        // SPOG (Single Point of Glass) & Spending Pace
        .route("/api/spog/metrics", get(get_spog_metrics))
        .route("/api/spog/spending-pace", get(get_spending_pace))
        .route("/spog", get(get_spog_page))
        .route("/spendingPace", get(get_spending_pace_page_handler))
        // NomMon (Smart Grocery & Meal Planner)
        .route("/nommon", get(nommon::get_nommon_page))
        .route("/api/nommon/plan", get(nommon::get_nommon_plan_api))
        .route("/api/nommon/lock", post(nommon::lock_nommon_plan_api))
        .route("/api/nommon/unlock", post(nommon::unlock_nommon_plan_api))
        // Bank Sync & Monzo
        .route("/bank", get(bank_sync::get_bank_sync_page))
        .route(
            "/bank/transactions",
            get(bank_sync::list_bank_transactions_api),
        )
        .route(
            "/bank/monzo/webhooks/status",
            get(bank_sync::get_monzo_webhook_status_api),
        )
        .route(
            "/api/monzo/webhook",
            post(bank_sync::handle_monzo_webhook_api),
        )
        // Savings Plan & Priority Queue
        .route(
            "/api/savings-plan",
            get(savings_plan::get_savings_plan_api),
        )
        .route(
            "/api/savings-plan/item",
            post(savings_plan::create_savings_plan_item_api),
        )
        .route(
            "/api/savings-plan/item/:id",
            delete(savings_plan::delete_item_api),
        )
        .route(
            "/api/savings-plan/item/:id/move-up",
            post(savings_plan::move_item_up_api),
        )
        .route(
            "/api/savings-plan/item/:id/move-down",
            post(savings_plan::move_item_down_api),
        )
        .route(
            "/api/savings-plan/item/:id/toggle-lock",
            post(savings_plan::toggle_lock_api),
        )
        .route("/savingsPlan", get(savings_plan::get_savings_plan_page))
        .route("/savingsPlans", get(savings_plan::get_savings_plan_page))
        // Net Worth & Capital Assets
        .route("/netWorth", get(net_worth::get_net_worth_page))
        .route("/api/networth", get(net_worth::get_net_worth_api))
        .route("/api/networth/asset", post(net_worth::add_asset_api))
        .route("/netWorth/addAsset", post(net_worth::add_asset_form))
        .route("/netWorth/binAsset/:id", get(net_worth::bin_asset_endpoint))
        // Monzo Dig (Geotagged Heatmap & Category Histogram)
        .route("/dig", get(dig::get_dig_page))
        .route("/monzo-dig", get(dig::get_dig_page))
        .route("/api/dig/data", get(dig::get_dig_data_api))
        .route("/api/monzo-dig/data", get(dig::get_dig_data_api))
        // LegMon & Statement Upload Bridge
        .route("/api/legmon/status", get(statement::get_legmon_status_api))
        .route("/api/legmon/logs", get(statement::get_legmon_logs_api))
        .route("/api/statement/webhook", post(statement::handle_statement_webhook_api))
        .route("/api/bank/statement/:id/preview", get(statement::get_statement_preview_api))
        .route("/api/bank/statement/:id/confirm", post(statement::confirm_statement_api))
        .route("/bank/statement/upload", post(statement::upload_statement_form))
        .route("/bank/statement/:id/confirm", post(statement::confirm_statement_api))
        .route("/bank/statement/:id/discard", post(statement::discard_statement_api))
        .route("/mySavingsPlan", get(savings_plan::get_savings_plan_page))
        .route(
            "/savingsPlan/addItem",
            post(savings_plan::add_item_form_handler),
        )
        .nest_service(
            "/assets",
            ServeDir::new(if std::path::Path::new("public").exists() {
                "public"
            } else {
                "../../public"
            }),
        )
        .with_state(state);

    let port: u16 = env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(9001);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    info!("monmon-rs is listening on http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

async fn health_handler() -> Json<serde_json::Value> {
    Json(json!({ "status": "ok", "service": "monmon-rs" }))
}

// Accounts Handlers
async fn list_accounts_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<UserQuery>,
) -> Result<Json<Vec<Account>>, (StatusCode, String)> {
    db::list_accounts(&state.pool, &query.user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_account_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Json<Account>, (StatusCode, String)> {
    db::get_account(&state.pool, id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::NOT_FOUND, e.to_string()))
}

async fn create_account_handler(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<NewAccount>,
) -> Result<Json<Account>, (StatusCode, String)> {
    db::create_account(&state.pool, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn update_account_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Json(payload): Json<UpdateAccount>,
) -> Result<Json<Account>, (StatusCode, String)> {
    db::update_account(&state.pool, id, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn archive_account_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Json<Account>, (StatusCode, String)> {
    db::archive_account(&state.pool, id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn toggle_ledger_account_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Json<Account>, (StatusCode, String)> {
    db::toggle_ledger_mode(&state.pool, id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

// Incomings Handlers
async fn list_incomings_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<UserQuery>,
) -> Result<Json<Vec<Incoming>>, (StatusCode, String)> {
    db::list_incomings(&state.pool, &query.user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_incoming_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Json<Incoming>, (StatusCode, String)> {
    db::get_incoming(&state.pool, id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::NOT_FOUND, e.to_string()))
}

async fn create_incoming_handler(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<NewIncoming>,
) -> Result<Json<Incoming>, (StatusCode, String)> {
    db::create_incoming(&state.pool, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn update_incoming_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Json(payload): Json<UpdateIncoming>,
) -> Result<Json<Incoming>, (StatusCode, String)> {
    db::update_incoming(&state.pool, id, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn archive_incoming_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Json<Incoming>, (StatusCode, String)> {
    db::archive_incoming(&state.pool, id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

// Outgoings Handlers
async fn list_outgoings_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<UserQuery>,
) -> Result<Json<Vec<Outgoing>>, (StatusCode, String)> {
    db::list_outgoings(&state.pool, &query.user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn list_rents_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<UserQuery>,
) -> Result<Json<Vec<Outgoing>>, (StatusCode, String)> {
    db::list_rents(&state.pool, &query.user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn list_bills_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<UserQuery>,
) -> Result<Json<Vec<Outgoing>>, (StatusCode, String)> {
    db::list_bills(&state.pool, &query.user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_outgoing_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    db::get_outgoing(&state.pool, id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::NOT_FOUND, e.to_string()))
}

async fn create_outgoing_handler(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<NewOutgoing>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    db::create_outgoing(&state.pool, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn update_outgoing_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Json(payload): Json<UpdateOutgoing>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    db::update_outgoing(&state.pool, id, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn archive_outgoing_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    db::archive_outgoing(&state.pool, id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn pause_outgoing_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Json(payload): Json<PausePayload>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    db::pause_outgoing(&state.pool, id, &payload.user_id, &payload.reason)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn unpause_outgoing_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Json(payload): Json<UnpausePayload>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    db::unpause_outgoing(&state.pool, id, &payload.user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

// Balances Handlers
async fn list_balances_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<UserQuery>,
) -> Result<Json<Vec<Balance>>, (StatusCode, String)> {
    db::list_balances(&state.pool, &query.user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn create_balance_handler(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<NewBalance>,
) -> Result<Json<Balance>, (StatusCode, String)> {
    db::create_balance(&state.pool, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn erratic_balances_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<UserQuery>,
) -> Result<Json<Vec<ErraticBalance>>, (StatusCode, String)> {
    let threshold = query.threshold.unwrap_or(200.0);
    db::find_erratic_balances(&state.pool, &query.user_id, threshold)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

#[derive(Template)]
#[template(path = "spog.html")]
struct SpogTemplate {
    username: String,
    metrics: spog::SpogMetrics,
    pace: spog::SpendingPaceMetrics,
}

// SPOG Handlers
async fn get_spog_metrics(
    State(state): State<Arc<AppState>>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<spog::SpogMetrics>, (StatusCode, String)> {
    let user_id = query
        .get("userId")
        .or_else(|| query.get("user_id"))
        .cloned()
        .unwrap_or_else(|| "demo".to_string());

    spog::calculate_spog(&state.pool, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_spending_pace(
    State(state): State<Arc<AppState>>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<spog::SpendingPaceMetrics>, (StatusCode, String)> {
    let user_id = query
        .get("userId")
        .or_else(|| query.get("user_id"))
        .cloned()
        .unwrap_or_else(|| "demo".to_string());

    spog::calculate_spending_pace(&state.pool, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_spog_page(
    State(state): State<Arc<AppState>>,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Html<String>, (StatusCode, String)> {
    let user_id = query
        .get("userId")
        .or_else(|| query.get("user_id"))
        .cloned()
        .unwrap_or_else(|| "demo".to_string());

    let metrics = spog::calculate_spog(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let pace = spog::calculate_spending_pace(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let tmpl = SpogTemplate {
        username: user_id,
        metrics,
        pace,
    };

    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_spending_pace_page_handler(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
    spog::get_spending_pace_page(&state).await
}
