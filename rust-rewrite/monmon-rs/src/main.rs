mod admin;
pub mod authz;
mod bank_sync;
mod db;
mod dig;
mod ed;
mod historic_spent;
mod landing;
mod management;
mod models;
mod net_worth;
mod nommon;
mod passkey;
mod plans;
mod pot;
mod savings_plan;
mod search;
mod spog;
mod statement;

use askama::Template;
use axum::{
    extract::{Path, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{Html, IntoResponse, Json, Response},
    routing::{delete, get, post},
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

fn base64_url_decode(input: &str) -> Option<Vec<u8>> {
    let mut s = input.replace('-', "+").replace('_', "/");
    match s.len() % 4 {
        2 => s.push_str("=="),
        3 => s.push('='),
        _ => {}
    }
    const B64_TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut lookup = [255u8; 256];
    for (i, &b) in B64_TABLE.iter().enumerate() {
        lookup[b as usize] = i as u8;
    }
    let bytes = s.as_bytes();
    if bytes.len() % 4 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity((bytes.len() / 4) * 3);
    for chunk in bytes.chunks(4) {
        let b0 = lookup[chunk[0] as usize];
        let b1 = lookup[chunk[1] as usize];
        let b2 = if chunk[2] == b'=' { 0 } else { lookup[chunk[2] as usize] };
        let b3 = if chunk[3] == b'=' { 0 } else { lookup[chunk[3] as usize] };
        if b0 == 255 || b1 == 255 || (chunk[2] != b'=' && b2 == 255) || (chunk[3] != b'=' && b3 == 255) {
            return None;
        }
        let triple = ((b0 as u32) << 18) | ((b1 as u32) << 12) | ((b2 as u32) << 6) | (b3 as u32);
        out.push(((triple >> 16) & 0xFF) as u8);
        if chunk[2] != b'=' {
            out.push(((triple >> 8) & 0xFF) as u8);
        }
        if chunk[3] != b'=' {
            out.push((triple & 0xFF) as u8);
        }
    }
    Some(out)
}

fn extract_username_from_id_token(id_token: &str) -> Option<String> {
    let parts: Vec<&str> = id_token.split('.').collect();
    if parts.len() < 2 {
        return None;
    }
    let decoded = base64_url_decode(parts[1])?;
    let val: serde_json::Value = serde_json::from_slice(&decoded).ok()?;
    val.get("preferred_username")
        .and_then(|v| v.as_str())
        .or_else(|| val.get("email").and_then(|v| v.as_str()))
        .or_else(|| val.get("sub").and_then(|v| v.as_str()))
        .map(|s| s.to_string())
}

#[derive(Debug, Deserialize)]
pub struct CallbackQuery {
    pub code: Option<String>,
    pub state: Option<String>,
    pub user_id: Option<String>,
    pub username: Option<String>,
}

pub async fn callback_handler(Query(params): Query<CallbackQuery>) -> Response {
    let target = params.state.unwrap_or_else(|| "/spog".to_string());
    let clean_target = if target.starts_with('/') {
        target
    } else {
        "/spog".to_string()
    };

    let mut username = params.user_id.or(params.username).unwrap_or_default();

    if username.is_empty() {
        if let Some(ref code) = params.code {
            let client = reqwest::Client::new();
            let rauthy_hosts = [
                "http://rauthy:8085",
                "http://127.0.0.1:8085",
                "http://localhost:8085",
            ];
            let redirect_uri = std::env::var("REDIRECT_URI")
                .unwrap_or_else(|_| "http://localhost:9001/callback".to_string());
            let client_secret = std::env::var("OIDC_CLIENT_SECRET")
                .unwrap_or_else(|_| "aaa6a201a8a41810e2e33118bed1bddaba722902e89e959cb6cc0a2949445d78".to_string());

            for host in rauthy_hosts {
                let token_url = format!("{}/auth/v1/oidc/token", host);
                let form = [
                    ("grant_type", "authorization_code"),
                    ("code", code.as_str()),
                    ("redirect_uri", redirect_uri.as_str()),
                    ("client_id", "monmon-web"),
                    ("client_secret", client_secret.as_str()),
                ];
                if let Ok(res) = client.post(&token_url).form(&form).send().await {
                    if res.status().is_success() {
                        if let Ok(json) = res.json::<serde_json::Value>().await {
                            if let Some(id_token) = json.get("id_token").and_then(|v| v.as_str()) {
                                if let Some(extracted) = extract_username_from_id_token(id_token) {
                                    username = extracted;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if username.is_empty() {
        username = "inbrewj".to_string();
    }

    let cookie_val = format!("user_id={}; Path=/; HttpOnly; SameSite=Lax", username);
    let session_val = format!("monmon_session={}; Path=/; SameSite=Lax", username);
    let auth_val = "authenticated=true; Path=/; SameSite=Lax";

    Response::builder()
        .status(StatusCode::SEE_OTHER)
        .header(header::LOCATION, clean_target)
        .header(header::SET_COOKIE, cookie_val)
        .header(header::SET_COOKIE, session_val)
        .header(header::SET_COOKIE, auth_val)
        .header(header::SET_COOKIE, "PLAY_SESSION=authenticated; Path=/")
        .body(axum::body::Body::empty())
        .unwrap()
}

#[tokio::main]
async fn main() {
    // Load .env variables from current directory or project root
    let _ = dotenvy::dotenv();
    let _ = dotenvy::from_filename(".env");
    let _ = dotenvy::from_filename("../../.env");
    let _ = dotenvy::from_filename("../.env");
    let _ = dotenvy::from_filename("/app/.env");

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
        .route("/api/search", get(search::search_api))
        .route("/login", get(get_login_page).post(login_form_handler))
        .route("/signup", get(get_signup_page).post(signup_form_handler))
        .route("/logout", get(logout_handler))
        .route("/centralLogout", get(logout_handler))
        .route("/callback", get(callback_handler))
        // Ed Says AI Financial Copilot
        .route("/api/ed-says", get(ed::ed_says_spog_api))
        .route("/api/ed-says-outgoings", get(ed::ed_says_outgoings_api))
        .route("/api/ed-says-networth", get(ed::ed_says_networth_api))
        // Settings & Waiting List
        .route("/settings", get(admin::get_settings).post(admin::post_settings))
        .route("/settings/approveUser/:id", post(admin::approve_user_handler))
        .route("/settings/rejectUser/:id", post(admin::reject_user_handler))
        .route("/waitinglist", get(admin::get_waiting_list))
        .route("/myOutgoings", get(management::get_outgoings_page))
        .route("/editOutgoing/:id", get(management::get_edit_outgoing_page))
        .route("/updateOutgoing/:id", post(management::post_update_outgoing_form))
        .route("/updateOutgoing", post(management::post_update_outgoing_form_direct))
        .route("/myAccounts", get(management::get_accounts_page))
        .route("/editAccount/:id", get(management::get_edit_account_page))
        .route("/updateAccount/:id", post(management::post_update_account_form))
        .route("/updateAccount", post(management::post_update_account_form_direct))
        .route("/myIncomings", get(management::get_incomings_page))
        .route("/editIncoming/:id", get(management::get_edit_incoming_page))
        .route("/updateIncoming/:id", post(management::post_update_incoming_form))
        .route("/updateIncoming", post(management::post_update_incoming_form_direct))
        .route("/binIncoming/:id", get(management::get_bin_incoming).post(management::get_bin_incoming))
        .route("/myBalances", get(management::get_balances_page))
        .route("/account", post(management::post_account_form))
        .route("/incoming", post(management::post_incoming_form))
        .route("/outgoing", post(management::post_outgoing_form))
        .route("/balance", post(management::post_balance_form))
        .route("/balance/:id/delete", post(management::post_delete_balance).get(management::post_delete_balance))
        .route("/api/balances/erratic", get(management::get_erratic_balances_api))
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
        .route("/historicSpent", get(historic_spent::get_historic_spent_page))
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
            "/bank/monzo/connect",
            get(bank_sync::connect_monzo_oauth).post(bank_sync::connect_monzo_oauth),
        )
        .route(
            "/bank/monzo/callback",
            get(bank_sync::monzo_oauth_callback),
        )
        .route(
            "/bank/monzo/refresh",
            post(bank_sync::refresh_monzo_token),
        )
        .route(
            "/bank/monzo/verify",
            post(bank_sync::verify_monzo_connection),
        )
        .route(
            "/bank/monzo/disconnect",
            post(bank_sync::disconnect_monzo),
        )
        .route(
            "/bank/monzo/webhooks/sync",
            post(bank_sync::sync_monzo_webhooks),
        )
        .route(
            "/bank/monzo/webhooks/status",
            get(bank_sync::get_monzo_webhook_status_api),
        )
        .route(
            "/api/monzo/webhook",
            post(bank_sync::handle_monzo_webhook_api),
        )
        // Plans & Sharing Hub
        .route("/myPlans", get(plans::get_plans_page))
        .route("/plans", get(plans::get_plans_page))
        .route("/plan", post(plans::post_add_plan))
        .route("/binPlan/:id", get(plans::get_bin_plan).post(plans::get_bin_plan))
        .route("/plans/updateSavingsPayoffPercentage", get(plans::get_update_savings_payoff_percentage))
        // Monthly Pot & Spending Plan
        .route("/myMonthlyPot", get(pot::get_monthly_pot_page))
        .route("/pot", post(pot::post_pot_form))
        .route("/pot/applyGruelling", post(pot::post_apply_gruelling))
        .route("/binPot/:id", get(pot::get_bin_pot).post(pot::get_bin_pot))
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
        .route("/mySavingsPlan", get(savings_plan::get_savings_plan_page))
        .route("/savingsPlan/addItem", post(savings_plan::add_item_form_handler))
        .route("/savingsPlan/moveUp/:id", post(savings_plan::move_up_form_handler))
        .route("/savingsPlan/moveDown/:id", post(savings_plan::move_down_form_handler))
        .route("/savingsPlan/moveToTop/:id", post(savings_plan::move_to_top_form_handler))
        .route("/savingsPlan/moveToBottom/:id", post(savings_plan::move_to_bottom_form_handler))
        .route("/savingsPlan/toggleLock/:id", post(savings_plan::toggle_lock_form_handler))
        .route("/savingsPlan/updatePercentage", post(savings_plan::update_percentage_form_handler))
        .route("/savingsPlan/balanceEvenly/:id", post(savings_plan::balance_evenly_form_handler))
        .route("/savingsPlan/trimDeficit/:id", post(savings_plan::trim_deficit_form_handler))
        .route("/editSavingsPlanItem/:id", get(savings_plan::get_edit_savings_plan_item_page))
        .route("/savingsPlan/editItem/:id", get(savings_plan::get_edit_savings_plan_item_page))
        .route("/savingsPlan/updateItem/:id", post(savings_plan::update_item_form_handler))
        .route("/binSavingsPlanItem/:id", get(savings_plan::bin_item_handler))
        .route("/binSavingsPlan/:id", get(savings_plan::bin_plan_handler))
        // Passkey / WebAuthn APIs
        .route("/api/auth/passkey/options", get(passkey::get_passkey_options_api))
        .route("/api/auth/passkey/register", post(passkey::register_passkey_api))
        .route("/api/auth/passkey/login", post(passkey::login_passkey_api))
        .route("/api/auth/passkey/list", get(passkey::list_user_passkeys_api))
        .route("/api/auth/passkey/:id", delete(passkey::delete_passkey_api))
        // Net Worth & Capital Assets
        .route("/netWorth", get(net_worth::get_net_worth_page))
        .route("/api/networth", get(net_worth::get_net_worth_api))
        .route("/api/networth/asset", post(net_worth::add_asset_api))
        .route("/netWorth/addAsset", post(net_worth::add_asset_form))
        .route("/netWorth/updateAsset/:id", post(net_worth::update_asset_endpoint))
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
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<Account>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::get_account(&state.pool, id, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Account not found or access denied: {}", e)))
}

async fn create_account_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(mut payload): Json<NewAccount>,
) -> Result<Json<Account>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, Some(&payload.user_id));
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    payload.user_id = user_id;
    db::create_account(&state.pool, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn update_account_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Json(payload): Json<UpdateAccount>,
) -> Result<Json<Account>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::update_account(&state.pool, id, &user_id, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Account not found or access denied: {}", e)))
}

async fn archive_account_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<Account>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::archive_account(&state.pool, id, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Account not found or access denied: {}", e)))
}

async fn toggle_ledger_account_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<Account>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::toggle_ledger_mode(&state.pool, id, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Account not found or access denied: {}", e)))
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
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<Incoming>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::get_incoming(&state.pool, id, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Incoming not found or access denied: {}", e)))
}

async fn create_incoming_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(mut payload): Json<NewIncoming>,
) -> Result<Json<Incoming>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, Some(&payload.user_id));
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    payload.user_id = user_id;
    db::create_incoming(&state.pool, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn update_incoming_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Json(payload): Json<UpdateIncoming>,
) -> Result<Json<Incoming>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::update_incoming(&state.pool, id, &user_id, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Incoming not found or access denied: {}", e)))
}

async fn archive_incoming_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<Incoming>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::archive_incoming(&state.pool, id, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Incoming not found or access denied: {}", e)))
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
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::get_outgoing(&state.pool, id, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Outgoing not found or access denied: {}", e)))
}

async fn create_outgoing_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(mut payload): Json<NewOutgoing>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, Some(&payload.user_id));
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    payload.user_id = user_id;
    db::create_outgoing(&state.pool, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn update_outgoing_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Json(payload): Json<UpdateOutgoing>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::update_outgoing(&state.pool, id, &user_id, &payload)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Outgoing not found or access denied: {}", e)))
}

async fn archive_outgoing_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<Outgoing>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    db::archive_outgoing(&state.pool, id, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::FORBIDDEN, format!("Outgoing not found or access denied: {}", e)))
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

#[derive(Debug, Deserialize)]
pub struct AuthForm {
    pub username: String,
    pub email: Option<String>,
    pub password: Option<String>,
    pub return_to: Option<String>,
}

#[derive(Template)]
#[template(path = "signup.html")]
struct SignupTemplate {}

#[derive(Template)]
#[template(path = "login.html")]
struct LoginTemplate {
    pub return_to: String,
}

async fn get_signup_page() -> Html<String> {
    SignupTemplate {}
        .render()
        .map(Html)
        .unwrap_or_else(|_| Html(String::new()))
}

async fn get_login_page(
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Html<String> {
    let return_to = query.get("return_to").cloned().unwrap_or_default();
    LoginTemplate { return_to }
        .render()
        .map(Html)
        .unwrap_or_else(|_| Html(String::new()))
}

async fn signup_form_handler(
    State(state): State<Arc<AppState>>,
    axum::extract::Form(form): axum::extract::Form<AuthForm>,
) -> Result<Response, (StatusCode, String)> {
    let username = form.username.trim().to_string();
    let email = form.email.unwrap_or_else(|| format!("{}@example.com", username));

    let _ = sqlx::query(
        r#"
        INSERT INTO user_access (id, approvedat, approvedby, createdat, email, status, userid, username)
        VALUES (nextval('hibernate_sequence'), NOW(), 'SYSTEM', NOW(), $1, 'APPROVED', $2, $2)
        ON CONFLICT (userid) DO UPDATE SET status = 'APPROVED'
        "#,
    )
    .bind(&email)
    .bind(&username)
    .execute(&state.pool)
    .await;

    let cookie_val = format!("user_id={}; Path=/; HttpOnly; SameSite=Lax", username);
    let session_val = format!("monmon_session={}; Path=/; SameSite=Lax", username);
    let auth_val = "authenticated=true; Path=/; SameSite=Lax";

    let mut response = axum::response::Redirect::to("/spog").into_response();
    let headers = response.headers_mut();
    headers.append(axum::http::header::SET_COOKIE, cookie_val.parse().unwrap());
    headers.append(axum::http::header::SET_COOKIE, session_val.parse().unwrap());
    headers.append(axum::http::header::SET_COOKIE, auth_val.parse().unwrap());

    Ok(response)
}

async fn login_form_handler(
    axum::extract::Form(form): axum::extract::Form<AuthForm>,
) -> Result<Response, (StatusCode, String)> {
    let username = if form.username.trim().is_empty() {
        "inbrewj".to_string()
    } else {
        form.username.trim().to_string()
    };

    let cookie_val = format!("user_id={}; Path=/; HttpOnly; SameSite=Lax", username);
    let session_val = format!("monmon_session={}; Path=/; SameSite=Lax", username);
    let auth_val = "authenticated=true; Path=/; SameSite=Lax";

    let target = form.return_to
        .filter(|t| !t.trim().is_empty() && t.starts_with('/'))
        .unwrap_or_else(|| "/spog".to_string());

    let mut response = axum::response::Redirect::to(&target).into_response();
    let headers = response.headers_mut();
    headers.append(axum::http::header::SET_COOKIE, cookie_val.parse().unwrap());
    headers.append(axum::http::header::SET_COOKIE, session_val.parse().unwrap());
    headers.append(axum::http::header::SET_COOKIE, auth_val.parse().unwrap());

    Ok(response)
}

async fn logout_handler() -> Response {
    let mut response = axum::response::Redirect::to("/").into_response();
    let headers = response.headers_mut();
    headers.append(axum::http::header::SET_COOKIE, "user_id=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT".parse().unwrap());
    headers.append(axum::http::header::SET_COOKIE, "monmon_session=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT".parse().unwrap());
    headers.append(axum::http::header::SET_COOKIE, "authenticated=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT".parse().unwrap());
    headers.append(axum::http::header::SET_COOKIE, "PLAY_SESSION=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT".parse().unwrap());
    response
}

#[derive(Template)]
#[template(path = "spog.html")]
struct SpogTemplate {
    username: String,
    metrics: spog::SpogMetrics,
    pace: spog::SpendingPaceMetrics,
    is_fresh_account: bool,
}

// SPOG Handlers
async fn get_spog_metrics(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<spog::SpogMetrics>, (StatusCode, String)> {
    let user_id = crate::pot::get_user_id_from_headers_or_default(
        &headers,
        query.get("userId").or_else(|| query.get("user_id")).map(|s| s.as_str()),
    );

    spog::calculate_spog(&state.pool, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_spending_pace(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<spog::SpendingPaceMetrics>, (StatusCode, String)> {
    let user_id = crate::pot::get_user_id_from_headers_or_default(
        &headers,
        query.get("userId").or_else(|| query.get("user_id")).map(|s| s.as_str()),
    );

    spog::calculate_spending_pace(&state.pool, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_spog_page(
    State(state): State<Arc<AppState>>,
    headers: axum::http::HeaderMap,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Response, (StatusCode, String)> {
    if !crate::management::is_authenticated(&headers)
        && !query.contains_key("userId")
        && !query.contains_key("user_id")
    {
        return Ok(crate::landing::auth_redirect(Some("/spog")).await.into_response());
    }

    let user_id = crate::pot::get_user_id_from_headers_or_default(
        &headers,
        query.get("userId").or_else(|| query.get("user_id")).map(|s| s.as_str()),
    );

    let metrics = spog::calculate_spog(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let pace = spog::calculate_spending_pace(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let resolved = spog::resolve_user_id(&state.pool, &user_id).await;
    let accounts_count: i64 = sqlx::query_scalar("SELECT count(*) FROM account WHERE (userid = $1 OR user_id = $1)")
        .bind(&resolved)
        .fetch_one(&state.pool)
        .await
        .unwrap_or(0);
    let is_fresh_account = accounts_count == 0;

    let tmpl = SpogTemplate {
        username: user_id,
        metrics,
        pace,
        is_fresh_account,
    };

    tmpl.render()
        .map(|h| Html(h).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

async fn get_spending_pace_page_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Html<String>, (StatusCode, String)> {
    if !crate::management::is_authenticated(&headers) {
        return Ok(Html(format!(
            "<script>window.location.href = '/login?return_to=/spendingPace';</script>"
        )));
    }
    let user_id = crate::pot::get_user_id_from_headers_or_default(
        &headers,
        query.get("userId").or_else(|| query.get("user_id")).map(|s| s.as_str()),
    );
    let cycle_offset: i32 = query
        .get("cycle")
        .and_then(|c| c.parse().ok())
        .unwrap_or(0);
    spog::get_spending_pace_page(&state, &user_id, cycle_offset).await
}
