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
    if let Some(target) = target_path {
        Redirect::temporary(&format!("/login?return_to={}", urlencoding::encode(target)))
    } else {
        Redirect::temporary("/login")
    }
}

