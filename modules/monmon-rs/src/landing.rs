use std::sync::Arc;
use askama::Template;
use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Json, Redirect, Response},
};
use serde_json::Value;

use crate::AppState;

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

pub async fn get_access_status_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Json<Value> {
    let auth = crate::management::is_authenticated(&headers);
    if !auth {
        return Json(serde_json::json!({
            "authenticated": false,
            "approved": false,
            "status": "GUEST",
            "userId": "guest",
            "username": "guest"
        }));
    }

    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;

    let row = sqlx::query_as::<_, crate::admin::UserAccessRow>(
        r#"
        SELECT id, userid, username, email, status, createdat, approvedat, approvedby 
        FROM user_access 
        WHERE userid = $1 
           OR userid = $2
           OR (username IS NOT NULL AND LOWER(username) = LOWER($1))
           OR (email IS NOT NULL AND LOWER(email) = LOWER($1))
        ORDER BY CASE WHEN status = 'APPROVED' THEN 1 ELSE 2 END, id ASC
        LIMIT 1
        "#,
    )
    .bind(&raw_uid)
    .bind(&user_id)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    let (status, username) = match row {
        Some(u) => (u.status, u.username.unwrap_or_else(|| raw_uid.clone())),
        None => ("PENDING".to_string(), raw_uid.clone()),
    };

    let approved = status == "APPROVED";

    Json(serde_json::json!({
        "authenticated": true,
        "approved": approved,
        "status": status,
        "userId": user_id,
        "username": username
    }))
}

pub async fn auth_redirect(target_path: Option<&str>) -> Redirect {
    if let Some(target) = target_path {
        Redirect::temporary(&format!("/login?return_to={}", urlencoding::encode(target)))
    } else {
        Redirect::temporary("/login")
    }
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    #[test]
    fn test_templates_have_no_external_cdn_links() {
        let templates = [
            ("nav_head.html", include_str!("../templates/nav_head.html")),
            ("landing.html", include_str!("../templates/landing.html")),
            ("dig.html", include_str!("../templates/dig.html")),
        ];
        let forbidden = [
            "w3schools.com",
            "cdnjs.cloudflare.com",
            "fonts.googleapis.com",
            "fonts.gstatic.com",
            "unpkg.com",
        ];

        for (name, content) in templates {
            for cdn in forbidden {
                assert!(
                    !content.contains(cdn),
                    "Template {} still contains external CDN reference: {}",
                    name,
                    cdn
                );
            }
        }
    }

    #[test]
    fn test_templates_include_build_hash_meta() {
        let nav_head = include_str!("../templates/nav_head.html");
        let landing = include_str!("../templates/landing.html");

        assert!(
            nav_head.contains(r#"name="monmon-build-hash""#),
            "nav_head.html must include monmon-build-hash meta tag"
        );
        assert!(
            landing.contains(r#"name="monmon-build-hash""#),
            "landing.html must include monmon-build-hash meta tag"
        );
    }

    #[test]
    fn test_vendor_assets_exist_on_disk() {
        let base_dir = if Path::new("public").exists() {
            "public"
        } else {
            "../../public"
        };

        let required_assets = [
            "vendor/w3css/w3.css",
            "vendor/fontawesome/css/all.min.css",
            "vendor/fonts/fonts.css",
            "vendor/leaflet/leaflet.css",
            "vendor/leaflet/leaflet.js",
        ];

        for asset in required_assets {
            let full_path = Path::new(base_dir).join(asset);
            let metadata = std::fs::metadata(&full_path).unwrap_or_else(|e| {
                panic!("Expected vendor asset {} to exist: {}", full_path.display(), e)
            });
            assert!(
                metadata.is_file() && metadata.len() > 0,
                "Expected vendor asset {} to be a non-empty file",
                full_path.display()
            );
        }
    }
}


