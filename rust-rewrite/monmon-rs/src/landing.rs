use askama::Template;
use axum::{
    http::StatusCode,
    response::{Html, Json, Redirect},
};
use serde_json::Value;

#[derive(Template)]
#[template(path = "landing.html")]
pub struct LandingTemplate {}

pub async fn get_landing_page() -> Result<Html<String>, (StatusCode, String)> {
    let tmpl = LandingTemplate {};
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_health_api() -> Json<Value> {
    Json(serde_json::json!({
        "status": "ok",
        "service": "monmon-rs",
        "timestamp": chrono::Utc::now().timestamp_millis()
    }))
}

pub async fn get_access_status_api() -> Json<Value> {
    Json(serde_json::json!({
        "authenticated": true,
        "approved": true,
        "status": "APPROVED",
        "userId": "demo"
    }))
}

pub async fn auth_redirect() -> Redirect {
    let auth_url = std::env::var("AUTH_URL").unwrap_or_else(|_| {
        "http://localhost:8085/auth/v1/oidc/authorize?client_id=monmon-web&response_type=code&redirect_uri=http://localhost:9001/callback&scope=openid+profile+email".to_string()
    });
    Redirect::temporary(&auth_url)
}

