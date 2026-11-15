use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::Json,
    routing::{delete, get},
    Router,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tower_http::trace::TraceLayer;
use tracing::info;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CacheEntry {
    pub text: String,
    pub fingerprint: u64,
    pub timestamp_millis: i64,
    pub created_at_epoch_secs: u64,
}

pub fn category_ttl(category: &str) -> Duration {
    match category {
        "spog" => Duration::from_secs(24 * 60 * 60),          // 1 day
        "outgoings" => Duration::from_secs(7 * 24 * 60 * 60),  // 1 week
        "networth" => Duration::from_secs(30 * 24 * 60 * 60),  // 30 days
        _ => Duration::from_secs(24 * 60 * 60),               // default 1 day
    }
}

pub fn category_display_name(category: &str) -> &'static str {
    match category {
        "spog" => "SPOG Overview Advice",
        "outgoings" => "Outgoings Audit Advice",
        "networth" => "Net Worth Radar Advice",
        _ => "AI Advice",
    }
}

pub fn mask_user_key(key: &str) -> String {
    let trimmed = key.trim();
    if trimmed.eq_ignore_ascii_case("demo") {
        return "demo".to_string();
    }
    let chars: Vec<char> = trimmed.chars().collect();
    if chars.len() <= 4 {
        if let Some(&first) = chars.first() {
            format!("{first}***")
        } else {
            "***".to_string()
        }
    } else {
        let prefix: String = chars.iter().take(2).collect();
        let suffix: String = chars.iter().skip(chars.len() - 3).collect();
        format!("{prefix}***{suffix}")
    }
}

#[derive(Clone)]
pub struct AppState {
    pub data: Arc<RwLock<HashMap<String, HashMap<String, CacheEntry>>>>,
    pub start_time: Instant,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            data: Arc::new(RwLock::new(HashMap::new())),
            start_time: Instant::now(),
        }
    }
}

#[derive(Deserialize)]
pub struct GetCacheQuery {
    pub fingerprint: Option<u64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CacheLookupResponse {
    pub hit: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp_millis: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

#[derive(Deserialize)]
pub struct SetCacheRequest {
    pub text: String,
    pub fingerprint: u64,
    pub timestamp_millis: Option<i64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct StatusResponse {
    pub service: String,
    pub status: String,
    pub uptime_seconds: u64,
    pub total_entries: usize,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct StatsResponse {
    pub service: String,
    pub version: String,
    pub uptime_seconds: u64,
    pub total_entries: usize,
    pub categories: Vec<CategoryStats>,
    pub items: Vec<ItemStats>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CategoryStats {
    pub name: String,
    pub display_name: String,
    pub count: usize,
    pub ttl_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ItemStats {
    pub category: String,
    pub user_masked: String,
    pub age_seconds: u64,
    pub ttl_remaining_seconds: u64,
    pub char_count: usize,
    pub timestamp_millis: i64,
}

fn now_epoch_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn now_millis() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

async fn health_handler(State(state): State<AppState>) -> Json<StatusResponse> {
    let count: usize = if let Ok(data) = state.data.read() {
        data.values().map(|cat| cat.len()).sum()
    } else {
        0
    };

    Json(StatusResponse {
        service: "memmon".to_string(),
        status: "ok".to_string(),
        uptime_seconds: state.start_time.elapsed().as_secs(),
        total_entries: count,
    })
}

async fn get_cache_handler(
    State(state): State<AppState>,
    Path((category, key)): Path<(String, String)>,
    Query(q): Query<GetCacheQuery>,
) -> Json<CacheLookupResponse> {
    let now = now_epoch_secs();
    let ttl = category_ttl(&category);

    let mut is_expired = false;
    let mut result = None;

    if let Ok(data) = state.data.read() {
        if let Some(cat_map) = data.get(&category) {
            if let Some(entry) = cat_map.get(&key) {
                let age = now.saturating_sub(entry.created_at_epoch_secs);
                if age >= ttl.as_secs() {
                    is_expired = true;
                } else if let Some(req_fp) = q.fingerprint {
                    if entry.fingerprint != req_fp {
                        return Json(CacheLookupResponse {
                            hit: false,
                            text: None,
                            timestamp_millis: None,
                            reason: Some("fingerprint_mismatch".to_string()),
                        });
                    } else {
                        result = Some((entry.text.clone(), entry.timestamp_millis));
                    }
                } else {
                    result = Some((entry.text.clone(), entry.timestamp_millis));
                }
            }
        }
    }

    if is_expired {
        if let Ok(mut data) = state.data.write() {
            if let Some(cat_map) = data.get_mut(&category) {
                cat_map.remove(&key);
            }
        }
        return Json(CacheLookupResponse {
            hit: false,
            text: None,
            timestamp_millis: None,
            reason: Some("expired".to_string()),
        });
    }

    if let Some((text, ts)) = result {
        Json(CacheLookupResponse {
            hit: true,
            text: Some(text),
            timestamp_millis: Some(ts),
            reason: None,
        })
    } else {
        Json(CacheLookupResponse {
            hit: false,
            text: None,
            timestamp_millis: None,
            reason: Some("not_found".to_string()),
        })
    }
}

async fn set_cache_handler(
    State(state): State<AppState>,
    Path((category, key)): Path<(String, String)>,
    Json(payload): Json<SetCacheRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    let now_secs = now_epoch_secs();
    let ts_millis = payload.timestamp_millis.unwrap_or_else(now_millis);

    let entry = CacheEntry {
        text: payload.text,
        fingerprint: payload.fingerprint,
        timestamp_millis: ts_millis,
        created_at_epoch_secs: now_secs,
    };

    if let Ok(mut data) = state.data.write() {
        let cat_map = data.entry(category.clone()).or_default();
        cat_map.insert(key.clone(), entry);
    }

    (
        StatusCode::OK,
        Json(serde_json::json!({
            "status": "ok",
            "category": category,
            "key": key,
        })),
    )
}

async fn delete_cache_for_key_handler(
    State(state): State<AppState>,
    Path(key): Path<String>,
) -> (StatusCode, Json<serde_json::Value>) {
    let mut cleared = 0;
    if let Ok(mut data) = state.data.write() {
        for cat_map in data.values_mut() {
            if cat_map.remove(&key).is_some() {
                cleared += 1;
            }
        }
    }

    (
        StatusCode::OK,
        Json(serde_json::json!({
            "status": "ok",
            "key": key,
            "cleared_entries": cleared,
        })),
    )
}

async fn delete_cache_entry_handler(
    State(state): State<AppState>,
    Path((category, key)): Path<(String, String)>,
) -> (StatusCode, Json<serde_json::Value>) {
    let mut removed = false;
    if let Ok(mut data) = state.data.write() {
        if let Some(cat_map) = data.get_mut(&category) {
            removed = cat_map.remove(&key).is_some();
        }
    }

    (
        StatusCode::OK,
        Json(serde_json::json!({
            "status": "ok",
            "category": category,
            "key": key,
            "removed": removed,
        })),
    )
}

async fn stats_handler(State(state): State<AppState>) -> Json<StatsResponse> {
    let now = now_epoch_secs();
    let mut total_entries = 0;
    let mut categories_stats = Vec::new();
    let mut item_stats = Vec::new();

    let standard_cats = ["spog", "outgoings", "networth"];

    if let Ok(data) = state.data.read() {
        for &cat_name in &standard_cats {
            let cat_ttl = category_ttl(cat_name);
            let count = data.get(cat_name).map(|m| m.len()).unwrap_or(0);
            categories_stats.push(CategoryStats {
                name: cat_name.to_string(),
                display_name: category_display_name(cat_name).to_string(),
                count,
                ttl_seconds: cat_ttl.as_secs(),
            });
        }

        for (cat, cat_map) in data.iter() {
            let cat_ttl = category_ttl(cat);
            if !standard_cats.contains(&cat.as_str()) {
                categories_stats.push(CategoryStats {
                    name: cat.clone(),
                    display_name: category_display_name(cat).to_string(),
                    count: cat_map.len(),
                    ttl_seconds: cat_ttl.as_secs(),
                });
            }

            for (user_key, entry) in cat_map.iter() {
                let age = now.saturating_sub(entry.created_at_epoch_secs);
                let remaining = cat_ttl.as_secs().saturating_sub(age);
                total_entries += 1;
                item_stats.push(ItemStats {
                    category: cat.clone(),
                    user_masked: mask_user_key(user_key),
                    age_seconds: age,
                    ttl_remaining_seconds: remaining,
                    char_count: entry.text.len(),
                    timestamp_millis: entry.timestamp_millis,
                });
            }
        }
    }

    // Sort items newest first
    item_stats.sort_by(|a, b| a.age_seconds.cmp(&b.age_seconds));

    Json(StatsResponse {
        service: "memmon".to_string(),
        version: "1.0.0".to_string(),
        uptime_seconds: state.start_time.elapsed().as_secs(),
        total_entries,
        categories: categories_stats,
        items: item_stats,
    })
}

// ---------------------------------------------------------------------------
// App & Server
// ---------------------------------------------------------------------------

pub fn build_app(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health_handler))
        .route("/status", get(health_handler))
        .route("/api/cache/stats", get(stats_handler))
        .route("/api/cache/:key", delete(delete_cache_for_key_handler))
        .route(
            "/api/cache/:category/:key",
            get(get_cache_handler)
                .post(set_cache_handler)
                .delete(delete_cache_entry_handler),
        )
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    let host = std::env::var("HOST")
        .or_else(|_| std::env::var("MEMMON_HOST"))
        .unwrap_or_else(|_| "127.0.0.1".to_string());

    let port: u16 = std::env::var("PORT")
        .or_else(|_| std::env::var("MEMMON_PORT"))
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(9006);

    let ip: std::net::IpAddr = host.parse().unwrap_or_else(|_| {
        std::net::IpAddr::V4(std::net::Ipv4Addr::new(127, 0, 0, 1))
    });

    let addr = SocketAddr::from((ip, port));
    info!("MemMon in-memory cache service listening on http://{}", addr);

    let app = build_app(AppState::default());
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use tower::util::ServiceExt;

    #[test]
    fn test_user_masking() {
        assert_eq!(mask_user_key("demo"), "demo");
        assert_eq!(mask_user_key("DEMO"), "demo");
        assert_eq!(mask_user_key("a"), "a***");
        assert_eq!(mask_user_key("user"), "u***");
        assert_eq!(mask_user_key("2ccc203f-a98c-488f-a5ba-8d20a384a9fc"), "2c***9fc");
        assert_eq!(mask_user_key("inbrewj"), "in***ewj");
    }

    #[test]
    fn test_category_ttls() {
        assert_eq!(category_ttl("spog"), Duration::from_secs(86400));
        assert_eq!(category_ttl("outgoings"), Duration::from_secs(604800));
        assert_eq!(category_ttl("networth"), Duration::from_secs(2592000));
    }

    #[tokio::test]
    async fn test_cache_set_and_get_lifecycle() {
        let state = AppState::default();
        let app = build_app(state);

        // 1. Initially empty
        let req = Request::builder()
            .uri("/api/cache/spog/user123?fingerprint=999")
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let lookup: CacheLookupResponse = serde_json::from_slice(&body).unwrap();
        assert!(!lookup.hit);
        assert_eq!(lookup.reason.as_deref(), Some("not_found"));

        // 2. Set cache entry
        let set_payload = serde_json::json!({
            "text": "Ed says: Budget looking awesome today!",
            "fingerprint": 999,
            "timestamp_millis": 1731670000000_i64
        });
        let req = Request::builder()
            .method("POST")
            .uri("/api/cache/spog/user123")
            .header("Content-Type", "application/json")
            .body(Body::from(set_payload.to_string()))
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);

        // 3. Get with matching fingerprint -> HIT
        let req = Request::builder()
            .uri("/api/cache/spog/user123?fingerprint=999")
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let lookup: CacheLookupResponse = serde_json::from_slice(&body).unwrap();
        assert!(lookup.hit);
        assert_eq!(lookup.text.as_deref(), Some("Ed says: Budget looking awesome today!"));
        assert_eq!(lookup.timestamp_millis, Some(1731670000000_i64));

        // 4. Get with mismatched fingerprint -> MISS (fingerprint_mismatch)
        let req = Request::builder()
            .uri("/api/cache/spog/user123?fingerprint=888")
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let lookup: CacheLookupResponse = serde_json::from_slice(&body).unwrap();
        assert!(!lookup.hit);
        assert_eq!(lookup.reason.as_deref(), Some("fingerprint_mismatch"));

        // 5. Stats check
        let req = Request::builder()
            .uri("/api/cache/stats")
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let stats: StatsResponse = serde_json::from_slice(&body).unwrap();
        assert_eq!(stats.total_entries, 1);
        assert_eq!(stats.items.len(), 1);
        assert_eq!(stats.items[0].category, "spog");
        assert_eq!(stats.items[0].user_masked, "us***123");

        // 6. Delete user across categories
        let req = Request::builder()
            .method("DELETE")
            .uri("/api/cache/user123")
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);

        // 7. Verify cleared
        let req = Request::builder()
            .uri("/api/cache/spog/user123?fingerprint=999")
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let lookup: CacheLookupResponse = serde_json::from_slice(&body).unwrap();
        assert!(!lookup.hit);
        assert_eq!(lookup.reason.as_deref(), Some("not_found"));
    }

    #[tokio::test]
    async fn test_cache_ttl_expiration() {
        let state = AppState::default();
        let app = build_app(state.clone());

        // Insert entry with created_at_epoch_secs in the past (older than 1 day)
        let old_time = now_epoch_secs() - (25 * 3600);
        {
            let mut data = state.data.write().unwrap();
            let cat = data.entry("spog".to_string()).or_default();
            cat.insert(
                "expired_user".to_string(),
                CacheEntry {
                    text: "Old Advice".to_string(),
                    fingerprint: 111,
                    timestamp_millis: 1000,
                    created_at_epoch_secs: old_time,
                },
            );
        }

        // Query entry -> should return hit: false and reason: expired
        let req = Request::builder()
            .uri("/api/cache/spog/expired_user?fingerprint=111")
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let lookup: CacheLookupResponse = serde_json::from_slice(&body).unwrap();
        assert!(!lookup.hit);
        assert_eq!(lookup.reason.as_deref(), Some("expired"));

        // Verify it was purged from state
        {
            let data = state.data.read().unwrap();
            let cat = data.get("spog").unwrap();
            assert!(!cat.contains_key("expired_user"));
        }
    }

    #[tokio::test]
    async fn test_health_and_single_category_delete() {
        let state = AppState::default();
        let app = build_app(state);

        // Health check initially 0 entries
        let req = Request::builder().uri("/health").body(Body::empty()).unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let status: StatusResponse = serde_json::from_slice(&body).unwrap();
        assert_eq!(status.service, "memmon");
        assert_eq!(status.total_entries, 0);

        // Insert two entries in different categories for same user
        for cat in &["spog", "outgoings"] {
            let set_payload = serde_json::json!({
                "text": "Advice",
                "fingerprint": 123,
                "timestamp_millis": 1000_i64
            });
            let req = Request::builder()
                .method("POST")
                .uri(&format!("/api/cache/{}/userA", cat))
                .header("Content-Type", "application/json")
                .body(Body::from(set_payload.to_string()))
                .unwrap();
            let res = app.clone().oneshot(req).await.unwrap();
            assert_eq!(res.status(), StatusCode::OK);
        }

        // Delete only spog for userA
        let req = Request::builder()
            .method("DELETE")
            .uri("/api/cache/spog/userA")
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);

        // Check spog is gone
        let req = Request::builder().uri("/api/cache/spog/userA").body(Body::empty()).unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let lookup: CacheLookupResponse = serde_json::from_slice(&body).unwrap();
        assert!(!lookup.hit);

        // Check outgoings still exists
        let req = Request::builder().uri("/api/cache/outgoings/userA").body(Body::empty()).unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let lookup: CacheLookupResponse = serde_json::from_slice(&body).unwrap();
        assert!(lookup.hit);
    }
}
