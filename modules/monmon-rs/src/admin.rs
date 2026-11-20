use askama::Template;
use axum::{
    extract::{Form, Path, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Redirect, Response},
};
use chrono::Datelike;
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

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct MemmonCategoryStats {
    pub name: String,
    pub display_name: String,
    pub count: usize,
    pub ttl_seconds: u64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct MemmonItemStats {
    pub category: String,
    pub user_masked: String,
    pub age_seconds: u64,
    pub ttl_remaining_seconds: u64,
    pub char_count: usize,
    pub timestamp_millis: i64,
}

impl MemmonItemStats {
    pub fn age_formatted(&self) -> String {
        format_duration_short(self.age_seconds)
    }

    pub fn ttl_remaining_formatted(&self) -> String {
        format_duration_short(self.ttl_remaining_seconds)
    }

    pub fn category_label(&self) -> &'static str {
        match self.category.as_str() {
            "spog" => "SPOG Overview",
            "outgoings" => "Outgoings Audit",
            "networth" => "Net Worth Radar",
            _ => "AI Advice",
        }
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct MemmonStats {
    pub service: String,
    pub version: String,
    pub uptime_seconds: u64,
    pub total_entries: usize,
    pub categories: Vec<MemmonCategoryStats>,
    pub items: Vec<MemmonItemStats>,
}

impl MemmonStats {
    pub fn uptime_formatted(&self) -> String {
        format_duration_short(self.uptime_seconds)
    }
}

pub fn format_duration_short(secs: u64) -> String {
    if secs < 60 {
        format!("{secs}s")
    } else if secs < 3600 {
        let mins = secs / 60;
        let rem_s = secs % 60;
        format!("{mins}m {rem_s}s")
    } else if secs < 86400 {
        let hours = secs / 3600;
        let mins = (secs % 3600) / 60;
        format!("{hours}h {mins}m")
    } else {
        let days = secs / 86400;
        let hours = (secs % 86400) / 3600;
        format!("{days}d {hours}h")
    }
}

pub async fn fetch_memmon_stats() -> Option<MemmonStats> {
    let memmon_base = crate::ed::get_memmon_url();
    let url = format!("{}/api/cache/stats", memmon_base.trim_end_matches('/'));
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_millis(800))
        .build()
        .ok()?;

    let resp = client.get(&url).send().await.ok()?;
    if resp.status().is_success() {
        resp.json::<MemmonStats>().await.ok()
    } else {
        None
    }
}

#[derive(Template)]
#[template(path = "settings.html")]
pub struct SettingsTemplate {
    pub pending_users: Vec<UserAccessRow>,
    pub approved_users: Vec<UserAccessRow>,
    pub region: String,
    pub birth_month: Option<i32>,
    pub birth_year: Option<i32>,
    pub age: Option<i32>,
    #[allow(dead_code)]
    pub bank_holidays: Vec<crate::anchoring::BankHolidayEvent>,
    pub all_bank_holidays_json: String,
    pub max_year: String,
    pub build_commit: String,
    pub build_number: String,
    pub build_timestamp: String,
    pub memmon_stats: Option<MemmonStats>,
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
    pub age: Option<String>,
    #[serde(alias = "birthMonth")]
    pub birth_month: Option<String>,
    #[serde(alias = "birthYear")]
    pub birth_year: Option<String>,
}

pub fn parse_age_input(raw: Option<&str>) -> Option<i32> {
    let trimmed = raw?.trim();
    if trimmed.is_empty() {
        return None;
    }
    let parsed: i32 = trimmed.parse().ok()?;
    if (16..=120).contains(&parsed) {
        Some(parsed)
    } else {
        None
    }
}

pub fn parse_birth_month_input(raw: Option<&str>) -> Option<i32> {
    let trimmed = raw?.trim();
    if trimmed.is_empty() {
        return None;
    }
    let parsed: i32 = trimmed.parse().ok()?;
    if (1..=12).contains(&parsed) {
        Some(parsed)
    } else {
        None
    }
}

pub fn parse_birth_year_input(raw: Option<&str>) -> Option<i32> {
    let trimmed = raw?.trim();
    if trimmed.is_empty() {
        return None;
    }
    let parsed: i32 = trimmed.parse().ok()?;
    if (1900..=2100).contains(&parsed) {
        Some(parsed)
    } else {
        None
    }
}

pub fn calculate_age_from_birth_month_year(
    birth_month: Option<i32>,
    birth_year: Option<i32>,
    today: chrono::NaiveDate,
) -> Option<i32> {
    let bm = birth_month?;
    let by = birth_year?;
    if !(1..=12).contains(&bm) || !(1900..=2100).contains(&by) {
        return None;
    }
    let mut years = today.year() - by;
    if (today.month() as i32) < bm {
        years -= 1;
    }
    if (1..=120).contains(&years) {
        Some(years)
    } else {
        None
    }
}

pub async fn get_settings(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let (today, _) = crate::time_travel::get_effective_date(&headers);

    let (region, birth_month, birth_year, legacy_age) = sqlx::query_as::<_, (Option<String>, Option<i32>, Option<i32>, Option<i32>)>(
        "SELECT region, birth_month, birth_year, age FROM user_access WHERE (userid = $1 OR user_id = $1 OR username = $1) LIMIT 1",
    )
    .bind(&user_id)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None)
    .map(|(r, bm, by, a)| (r.unwrap_or_else(|| "UK".to_string()), bm, by, a))
    .unwrap_or_else(|| ("UK".to_string(), None, None, None));

    let age = calculate_age_from_birth_month_year(birth_month, birth_year, today).or(legacy_age);

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
    let memmon_stats = fetch_memmon_stats().await;

    let tmpl = SettingsTemplate {
        pending_users,
        approved_users,
        region,
        birth_month,
        birth_year,
        age,
        bank_holidays,
        all_bank_holidays_json,
        max_year,
        build_commit: crate::BUILD_COMMIT.to_string(),
        build_number: crate::BUILD_NUMBER.to_string(),
        build_timestamp: crate::BUILD_TIMESTAMP.to_string(),
        memmon_stats,
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
    let (today, _) = crate::time_travel::get_effective_date(&headers);
    if let Some(r) = form.region {
        let region = match r.to_uppercase().as_str() {
            "SCOTLAND" => "SCOTLAND",
            "NI" | "NORTHERN IRELAND" => "NI",
            _ => "UK",
        };
        let _ = sqlx::query(
            "UPDATE user_access SET region = $1, updated_at = NOW() WHERE (userid = $2 OR user_id = $2 OR username = $2)",
        )
        .bind(region)
        .bind(&user_id)
        .execute(&state.pool)
        .await;
    }
    if form.birth_month.is_some() || form.birth_year.is_some() {
        let bm = parse_birth_month_input(form.birth_month.as_deref());
        let by = parse_birth_year_input(form.birth_year.as_deref());
        let computed_age = calculate_age_from_birth_month_year(bm, by, today);
        let _ = sqlx::query(
            "UPDATE user_access SET birth_month = $1, birth_year = $2, age = $3, updated_at = NOW() WHERE (userid = $4 OR user_id = $4 OR username = $4)",
        )
        .bind(bm)
        .bind(by)
        .bind(computed_age)
        .bind(&user_id)
        .execute(&state.pool)
        .await;
    } else if form.age.is_some() {
        let parsed_age = parse_age_input(form.age.as_deref());
        let _ = sqlx::query(
            "UPDATE user_access SET age = $1, updated_at = NOW() WHERE (userid = $2 OR user_id = $2 OR username = $2)",
        )
        .bind(parsed_age)
        .bind(&user_id)
        .execute(&state.pool)
        .await;
    }
    crate::ed::clear_ed_cache(&user_id);
    Redirect::to("/settings")
}

pub async fn approve_user_handler(
    Path(id): Path<i64>,
    State(state): State<Arc<AppState>>,
) -> Result<Redirect, (StatusCode, String)> {
    sqlx::query(
        "UPDATE user_access 
         SET status = 'APPROVED', approvedat = NOW(), approvedby = 'admin', updated_at = NOW() 
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
         SET status = 'REJECTED', updated_at = NOW() 
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
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;

    let user = sqlx::query_as::<_, UserAccessRow>(
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

    if let Some(ref u) = user {
        if u.status == "APPROVED" {
            return Ok(Redirect::to("/spog").into_response());
        }
    }

    let tmpl = WaitingListTemplate {
        status: user.as_ref().map(|u| u.status.clone()).unwrap_or_else(|| "PENDING".to_string()),
        username: user.as_ref().and_then(|u| u.username.clone()).unwrap_or_else(|| raw_uid.clone()),
        userid: user.as_ref().map(|u| u.userid.clone()).unwrap_or_else(|| raw_uid),
    };

    tmpl.render()
        .map(|h| Html(h).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_waiting_list_template_renders_pending() {
        let tmpl = WaitingListTemplate {
            status: "PENDING".to_string(),
            username: "newuser".to_string(),
            userid: "usr_12345".to_string(),
        };
        let rendered = tmpl.render().expect("Failed to render waiting list template");
        assert!(rendered.contains("Pending Administrator Approval"));
        assert!(rendered.contains("newuser"));
        assert!(rendered.contains("usr_12345"));
        assert!(rendered.contains("/logout"));
    }

    #[test]
    fn test_waiting_list_template_renders_rejected() {
        let tmpl = WaitingListTemplate {
            status: "REJECTED".to_string(),
            username: "blockeduser".to_string(),
            userid: "usr_67890".to_string(),
        };
        let rendered = tmpl.render().expect("Failed to render waiting list template");
        assert!(rendered.contains("Access Request Not Approved"));
        assert!(rendered.contains("blockeduser"));
        assert!(rendered.contains("/logout"));
    }

    #[test]
    fn test_parse_age_input() {
        assert_eq!(parse_age_input(Some("42")), Some(42));
        assert_eq!(parse_age_input(Some(" 57 ")), Some(57));
        assert_eq!(parse_age_input(Some("")), None);
        assert_eq!(parse_age_input(Some("0")), None);
        assert_eq!(parse_age_input(Some("-5")), None);
        assert_eq!(parse_age_input(Some("150")), None);
        assert_eq!(parse_age_input(None), None);
    }

    #[test]
    fn test_calculate_age_from_birth_month_year() {
        let today = chrono::NaiveDate::from_ymd_opt(2026, 11, 20).unwrap();
        // Birthday already reached this year (May 1984 -> 42 in Nov 2026)
        assert_eq!(calculate_age_from_birth_month_year(Some(5), Some(1984), today), Some(42));
        // Birthday is this month (Nov 1984 -> 42 in Nov 2026)
        assert_eq!(calculate_age_from_birth_month_year(Some(11), Some(1984), today), Some(42));
        // Birthday not yet reached this year (Dec 1984 -> 41 in Nov 2026)
        assert_eq!(calculate_age_from_birth_month_year(Some(12), Some(1984), today), Some(41));
        // Missing or out of range returns None
        assert_eq!(calculate_age_from_birth_month_year(None, Some(1984), today), None);
        assert_eq!(calculate_age_from_birth_month_year(Some(5), None, today), None);
        assert_eq!(calculate_age_from_birth_month_year(Some(13), Some(1984), today), None);
    }
}
