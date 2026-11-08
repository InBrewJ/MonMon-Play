use askama::Template;
use axum::{
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Json, Redirect, Response},
};
use serde_json::Value;

#[derive(Template)]
#[template(path = "landing.html")]
pub struct LandingTemplate {}

pub async fn get_landing_page(headers: HeaderMap) -> Result<Response, (StatusCode, String)> {
    if crate::management::is_authenticated(&headers) {
        return Ok(Redirect::temporary("/spog").into_response());
    }
    let tmpl = LandingTemplate {};
    tmpl.render()
        .map(|h| Html(h).into_response())
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

pub async fn auth_redirect(target_path: Option<&str>) -> Redirect {
    let auth_base = std::env::var("AUTH_URL").unwrap_or_else(|_| {
        "http://localhost:8080/auth/realms/monmon/protocol/openid-connect/auth".to_string()
    });
    let port = std::env::var("PORT").unwrap_or_else(|_| "9001".to_string());
    let redirect_uri = std::env::var("REDIRECT_URI").unwrap_or_else(|_| {
        format!("http://localhost:{}/callback", port)
    });
    let state = target_path.unwrap_or("/myOutgoings");
    let sep = if auth_base.contains('?') { "&" } else { "?" };
    let full_url = format!(
        "{}{}client_id=monmon-web&response_type=code&redirect_uri={}&state={}&scope=openid+profile+email",
        auth_base,
        sep,
        urlencoding::encode(&redirect_uri),
        urlencoding::encode(state)
    );
    Redirect::temporary(&full_url)
}

