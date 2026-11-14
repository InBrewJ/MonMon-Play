use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    Json,
};
use serde::{Deserialize, Serialize};
use sqlx::Row;
use std::sync::Arc;
use crate::AppState;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub entity_type: String,
    pub entity_id: String,
    pub title: String,
    pub subtitle: String,
    pub target_url: String,
    pub dom_id: String,
    pub icon: String,
    pub badge: String,
}

#[derive(Debug, Deserialize)]
pub struct SearchQuery {
    pub q: Option<String>,
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
    pub limit: Option<i64>,
}

pub async fn search_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(params): Query<SearchQuery>,
) -> Result<Json<Vec<SearchResult>>, (StatusCode, String)> {
    let query_str = params.q.unwrap_or_default().trim().to_string();
    if query_str.is_empty() {
        return Ok(Json(vec![]));
    }

    let user_id = crate::pot::get_user_id_from_headers_or_default(&headers, params.user_id.as_deref());

    let resolved_user = crate::spog::resolve_user_id(&state.pool, &user_id).await;
    let limit = params.limit.unwrap_or(25).clamp(1, 50);

    let lower_query = query_str.to_lowercase();
    let terms: Vec<String> = lower_query
        .split_whitespace()
        .filter(|t| !t.is_empty())
        .map(|t| t.to_string())
        .collect();

    if terms.is_empty() {
        return Ok(Json(vec![]));
    }

    // Build dynamic SQL query matching JPASearchRepository
    let mut sql = String::from(
        "SELECT entity_type, entity_id, title, subtitle, target_url, dom_id, icon, badge \
         FROM app_search_view \
         WHERE user_id = $1 "
    );

    let mut param_idx = 2;
    for _ in &terms {
        sql.push_str(&format!("AND search_text LIKE ${} ", param_idx));
        param_idx += 1;
    }

    let exact_idx = param_idx;
    param_idx += 1;
    let prefix_idx = param_idx;
    param_idx += 1;
    let sub_idx = param_idx;
    param_idx += 1;
    let limit_idx = param_idx;

    sql.push_str(&format!(
        "ORDER BY \
         CASE \
           WHEN LOWER(title) = ${exact} THEN 1 \
           WHEN LOWER(title) LIKE ${prefix} THEN 2 \
           WHEN LOWER(title) LIKE ${sub} THEN 3 \
           ELSE 4 \
         END, title ASC \
         LIMIT ${limit}",
        exact = exact_idx,
        prefix = prefix_idx,
        sub = sub_idx,
        limit = limit_idx
    ));

    let mut query = sqlx::query(&sql).bind(&resolved_user);
    for t in &terms {
        query = query.bind(format!("%{}%", t));
    }
    query = query.bind(&lower_query);
    query = query.bind(format!("{}%", &lower_query));
    query = query.bind(format!("%{}%", &lower_query));
    query = query.bind(limit);

    let rows = query.fetch_all(&state.pool).await.map_err(|e| {
        tracing::error!("Error executing app search query '{}' for user '{}': {}", query_str, resolved_user, e);
        (StatusCode::INTERNAL_SERVER_ERROR, e.to_string())
    })?;

    let results = rows
        .into_iter()
        .map(|row| {
            let icon: Option<String> = row.try_get("icon").ok();
            let badge: Option<String> = row.try_get("badge").ok();
            SearchResult {
                entity_type: row.try_get("entity_type").unwrap_or_default(),
                entity_id: row.try_get("entity_id").unwrap_or_default(),
                title: row.try_get("title").unwrap_or_default(),
                subtitle: row.try_get("subtitle").unwrap_or_default(),
                target_url: row.try_get("target_url").unwrap_or_default(),
                dom_id: row.try_get("dom_id").unwrap_or_default(),
                icon: icon.unwrap_or_else(|| "fa-search".to_string()),
                badge: badge.unwrap_or_else(|| "Item".to_string()),
            }
        })
        .collect();

    Ok(Json(results))
}
