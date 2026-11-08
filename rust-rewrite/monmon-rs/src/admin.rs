use askama::Template;
use axum::{
    extract::{Form, Path, State},
    http::StatusCode,
    response::{Html, IntoResponse, Redirect, Response},
};
use serde::Deserialize;
use std::sync::Arc;

use crate::AppState;

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
    pub build_commit: String,
    pub build_number: String,
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
    pub region: Option<String>,
}

pub async fn get_settings(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
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

    let tmpl = SettingsTemplate {
        pending_users,
        approved_users,
        region: "UK".to_string(),
        build_commit: "f687677".to_string(),
        build_number: "v1.0.0-rs".to_string(),
    };

    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_settings(
    Form(_form): Form<SettingsForm>,
) -> Redirect {
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
