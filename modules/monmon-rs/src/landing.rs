use std::sync::Arc;
use askama::Template;
use axum::{
    extract::State,
    http::{header, HeaderMap, StatusCode},
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

pub async fn get_version_api() -> Response {
    let mut response = Json(serde_json::json!({
        "buildHash": crate::BUILD_HASH,
        "buildCommit": crate::BUILD_COMMIT,
        "buildNumber": crate::BUILD_NUMBER,
        "buildTimestamp": crate::BUILD_TIMESTAMP
    }))
    .into_response();

    let headers = response.headers_mut();
    headers.insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static("no-cache, no-store, must-revalidate, max-age=0"),
    );
    headers.insert(header::PRAGMA, header::HeaderValue::from_static("no-cache"));
    headers.insert(header::EXPIRES, header::HeaderValue::from_static("0"));

    response
}

#[derive(Template)]
#[template(path = "offline.html")]
pub struct OfflineTemplate {}

pub async fn get_offline_page() -> Result<Html<String>, (StatusCode, String)> {
    let tmpl = OfflineTemplate {};
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
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

    #[tokio::test]
    async fn test_version_api_returns_build_metadata_and_no_store() {
        let resp = super::get_version_api().await;
        assert_eq!(resp.status(), axum::http::StatusCode::OK);

        let cache_control = resp
            .headers()
            .get(axum::http::header::CACHE_CONTROL)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        assert!(
            cache_control.contains("no-store"),
            "Expected Cache-Control to contain no-store, got: {}",
            cache_control
        );

        let bytes = axum::body::to_bytes(resp.into_body(), usize::MAX)
            .await
            .expect("should read version body");
        let json: serde_json::Value =
            serde_json::from_slice(&bytes).expect("version response should be valid JSON");

        assert_eq!(json["buildHash"], crate::BUILD_HASH);
        assert_eq!(json["buildCommit"], crate::BUILD_COMMIT);
        assert_eq!(json["buildNumber"], crate::BUILD_NUMBER);
        assert_eq!(json["buildTimestamp"], crate::BUILD_TIMESTAMP);
    }

    #[tokio::test]
    async fn test_offline_page_renders_with_local_assets() {
        use axum::response::IntoResponse;

        let html_resp = super::get_offline_page()
            .await
            .expect("offline page should render")
            .into_response();
        assert_eq!(html_resp.status(), axum::http::StatusCode::OK);

        let bytes = axum::body::to_bytes(html_resp.into_body(), usize::MAX)
            .await
            .expect("should read offline page body");
        let html = String::from_utf8(bytes.to_vec()).expect("offline page should be valid UTF-8");

        assert!(html.contains("/spog"), "offline page should link to /spog");
        assert!(html.contains("/nommon"), "offline page should link to /nommon");
        assert!(html.contains("Retry"), "offline page should contain Retry button");
        assert!(
            !html.contains("https://"),
            "offline page must not contain external https:// CDN URLs"
        );
    }
}


