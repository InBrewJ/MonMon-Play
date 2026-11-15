use askama::Template;
use axum::{
    extract::{Form, Path, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Redirect, Response},
};
use serde::Deserialize;
use std::sync::Arc;

use crate::AppState;
use crate::pot::get_user_id_from_headers_or_default;
use crate::spog::resolve_user_id;

#[derive(Debug, sqlx::FromRow, serde::Serialize)]
pub struct UserAccessRow {
    pub id: i64,
    pub userid: String,
    pub username: Option<String>,
    pub email: Option<String>,
    pub status: String,
    pub createdat: chrono::NaiveDateTime,
    pub approvedat: Option<chrono::NaiveDateTime>,
    pub approvedby: Option<String>,
}

#[derive(Template)]
#[template(path = "settings.html")]
pub struct SettingsTemplate {
    pub pending_users: Vec<UserAccessRow>,
    pub approved_users: Vec<UserAccessRow>,
    pub region: String,
    #[allow(dead_code)]
    pub bank_holidays: Vec<crate::anchoring::BankHolidayEvent>,
    pub all_bank_holidays_json: String,
    pub max_year: String,
    pub build_commit: String,
    pub build_number: String,
    pub build_timestamp: String,
}

#[derive(Template)]
#[template(path = "waitinglist.html")]
pub struct WaitingListTemplate {
    pub status: String,
    pub username: String,
    pub userid: String,
}

#[derive(Deserialize)]
pub struct SettingsForm {
    #[allow(dead_code)]
    pub region: Option<String>,
}

pub async fn get_settings(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;

    let region = sqlx::query_scalar::<_, Option<String>>(
        "SELECT region FROM user_access WHERE (userid = $1 OR user_id = $1 OR username = $1) LIMIT 1",
    )
    .bind(&user_id)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None)
    .flatten()
    .unwrap_or_else(|| "UK".to_string());

    let pending_users = sqlx::query_as::<_, UserAccessRow>(
        "SELECT id, userid, username, email, status, createdat, approvedat, approvedby 
         FROM user_access 
         WHERE status = 'PENDING' 
         ORDER BY createdat DESC",
    )
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let approved_users = sqlx::query_as::<_, UserAccessRow>(
        "SELECT id, userid, username, email, status, createdat, approvedat, approvedby 
         FROM user_access 
         WHERE status = 'APPROVED' 
         ORDER BY createdat DESC",
    )
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let bank_holidays = crate::anchoring::get_bank_holidays_for_region(&region);
    let max_year = bank_holidays.last().and_then(|e| e.date.split('-').next()).unwrap_or("2030").to_string();
    let all_bank_holidays_json = serde_json::to_string(crate::anchoring::get_all_bank_holidays()).unwrap_or_default();

    let tmpl = SettingsTemplate {
        pending_users,
        approved_users,
        region,
        bank_holidays,
        all_bank_holidays_json,
        max_year,
        build_commit: crate::BUILD_COMMIT.to_string(),
        build_number: crate::BUILD_NUMBER.to_string(),
        build_timestamp: crate::BUILD_TIMESTAMP.to_string(),
    };

    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_settings(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(form): Form<SettingsForm>,
) -> Redirect {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    if let Some(r) = form.region {
        let region = match r.to_uppercase().as_str() {
            "SCOTLAND" => "SCOTLAND",
            "NI" | "NORTHERN IRELAND" => "NI",
            _ => "UK",
        };
        let _ = sqlx::query(
            "UPDATE user_access SET region = $1 WHERE (userid = $2 OR user_id = $2 OR username = $2)",
        )
        .bind(region)
        .bind(&user_id)
        .execute(&state.pool)
        .await;
    }
    Redirect::to("/settings")
}

pub async fn approve_user_handler(
    Path(id): Path<i64>,
    State(state): State<Arc<AppState>>,
) -> Result<Redirect, (StatusCode, String)> {
    sqlx::query(
        "UPDATE user_access 
         SET status = 'APPROVED', approvedat = NOW(), approvedby = 'admin' 
         WHERE id = $1",
    )
    .bind(id)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/settings"))
}

pub async fn reject_user_handler(
    Path(id): Path<i64>,
    State(state): State<Arc<AppState>>,
) -> Result<Redirect, (StatusCode, String)> {
    sqlx::query(
        "UPDATE user_access 
         SET status = 'REJECTED' 
         WHERE id = $1",
    )
    .bind(id)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/settings"))
}

pub async fn get_waiting_list(
    State(state): State<Arc<AppState>>,
) -> Result<Response, (StatusCode, String)> {
    // Check demo user
    let user = sqlx::query_as::<_, UserAccessRow>(
        "SELECT id, userid, username, email, status, createdat, approvedat, approvedby 
         FROM user_access 
         WHERE username = 'demo' OR userid = 'demo'
         LIMIT 1",
    )
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    if let Some(ref u) = user {
        if u.status == "APPROVED" {
            return Ok(Redirect::to("/spog").into_response());
        }
    }

    let tmpl = WaitingListTemplate {
        status: user.as_ref().map(|u| u.status.clone()).unwrap_or_else(|| "PENDING".to_string()),
        username: user.as_ref().and_then(|u| u.username.clone()).unwrap_or_else(|| "demo".to_string()),
        userid: user.as_ref().map(|u| u.userid.clone()).unwrap_or_else(|| "demo".to_string()),
    };

    tmpl.render()
        .map(|h| Html(h).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}
