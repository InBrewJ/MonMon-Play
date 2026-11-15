use axum::{
    extract::{Form, Request},
    http::{header, HeaderMap, Method, StatusCode},
    middleware::Next,
    response::{IntoResponse, Redirect, Response},
};
use chrono::{Local, NaiveDate};
use serde::Deserialize;

pub const TIME_TRAVEL_COOKIE: &str = "monmon_time_travel";
pub const MIN_TIME_TRAVEL_YEAR: i32 = 2022;

/// Validates that a string is a valid ISO date (`YYYY-MM-DD`), is on or after January 1, 2022,
/// and is strictly backwards (<= today). Future dates are rejected.
pub fn parse_and_validate_time_travel_date(s: &str, today: NaiveDate) -> Option<NaiveDate> {
    let clean = s.trim();
    if clean.is_empty() {
        return None;
    }
    let parsed = NaiveDate::parse_from_str(clean, "%Y-%m-%d").ok()?;
    let min_date = NaiveDate::from_ymd_opt(MIN_TIME_TRAVEL_YEAR, 1, 1)?;
    if parsed >= min_date && parsed <= today {
        Some(parsed)
    } else {
        None
    }
}

/// Extracts and validates the time travel date from the `Cookie` header.
pub fn get_time_travel_date(headers: &HeaderMap) -> Option<NaiveDate> {
    let cookie_header = headers.get(header::COOKIE)?.to_str().ok()?;
    let today = Local::now().date_naive();
    for part in cookie_header.split(';') {
        let mut key_val = part.trim().splitn(2, '=');
        let key = key_val.next()?.trim();
        let val = key_val.next()?.trim();
        if key == TIME_TRAVEL_COOKIE {
            return parse_and_validate_time_travel_date(val, today);
        }
    }
    None
}

/// Returns the effective date (`as_of_date`) and a boolean flag indicating whether
/// Time Travel mode is currently active.
pub fn get_effective_date(headers: &HeaderMap) -> (NaiveDate, bool) {
    let today = Local::now().date_naive();
    match get_time_travel_date(headers) {
        Some(historical_date) => (historical_date, true),
        None => (today, false),
    }
}

#[derive(Debug, Deserialize)]
pub struct TimeTravelStartForm {
    pub date: String,
    #[serde(default)]
    pub return_to: Option<String>,
}

/// POST /timeTravel/start
/// Initiates Time Travel mode to a specified historical date (backwards only).
pub async fn post_start_time_travel(
    headers: HeaderMap,
    Form(form): Form<TimeTravelStartForm>,
) -> Response {
    let today = Local::now().date_naive();
    let validated = parse_and_validate_time_travel_date(&form.date, today);

    let redirect_target = form
        .return_to
        .filter(|t| t.starts_with('/') && !t.starts_with("//"))
        .unwrap_or_else(|| {
            headers
                .get(header::REFERER)
                .and_then(|r| r.to_str().ok())
                .and_then(|r| {
                    if let Ok(uri) = r.parse::<axum::http::Uri>() {
                        let path = uri.path();
                        if path.starts_with('/') && !path.starts_with("/login") {
                            return Some(path.to_string());
                        }
                    }
                    None
                })
                .unwrap_or_else(|| "/spog".to_string())
        });

    if let Some(target_date) = validated {
        let cookie_val = format!(
            "{}={}; Path=/; SameSite=Lax",
            TIME_TRAVEL_COOKIE,
            target_date.format("%Y-%m-%d")
        );
        Response::builder()
            .status(StatusCode::SEE_OTHER)
            .header(header::LOCATION, redirect_target)
            .header(header::SET_COOKIE, cookie_val)
            .body(axum::body::Body::empty())
            .unwrap()
    } else {
        // Invalid or future date: do not activate Time Travel
        Redirect::to(&redirect_target).into_response()
    }
}

/// GET or POST /timeTravel/stop
/// Clears the Time Travel cookie and returns to today's live mode.
pub async fn get_stop_time_travel(headers: HeaderMap) -> Response {
    let redirect_target = headers
        .get(header::REFERER)
        .and_then(|r| r.to_str().ok())
        .and_then(|r| {
            if let Ok(uri) = r.parse::<axum::http::Uri>() {
                let path = uri.path();
                if path.starts_with('/') && !path.starts_with("/login") {
                    return Some(path.to_string());
                }
            }
            None
        })
        .unwrap_or_else(|| "/spog".to_string());

    let clear_cookie = format!(
        "{}=; Path=/; Max-Age=0; SameSite=Lax",
        TIME_TRAVEL_COOKIE
    );

    Response::builder()
        .status(StatusCode::SEE_OTHER)
        .header(header::LOCATION, redirect_target)
        .header(header::SET_COOKIE, clear_cookie)
        .body(axum::body::Body::empty())
        .unwrap()
}

/// Axum middleware guard: strictly forbids state mutations (POST, PUT, DELETE, PATCH)
/// while MonMon is in Time Travel mode, preserving read-only access.
pub async fn enforce_time_travel_read_only(
    req: Request,
    next: Next,
) -> Result<Response, (StatusCode, String)> {
    let method = req.method();
    if method == Method::GET || method == Method::HEAD || method == Method::OPTIONS {
        return Ok(next.run(req).await);
    }

    let path = req.uri().path();
    if path == "/timeTravel/stop"
        || path == "/timeTravel/start"
        || path.starts_with("/login")
        || path.starts_with("/logout")
        || path.starts_with("/centralLogout")
        || path.starts_with("/callback")
    {
        return Ok(next.run(req).await);
    }

    if get_time_travel_date(req.headers()).is_some() {
        tracing::warn!(
            "Blocked mutating request {} {} due to active Time Travel read-only mode",
            method,
            path
        );
        return Err((
            StatusCode::FORBIDDEN,
            "MonMon is in Read-Only Time Travel mode. Please click 'Stop Time Travel' before modifying data.".to_string(),
        ));
    }

    Ok(next.run(req).await)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_and_validate_time_travel_date_past_is_valid() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 15).unwrap();
        let target = "2023-06-15";
        let res = parse_and_validate_time_travel_date(target, today);
        assert_eq!(res, Some(NaiveDate::from_ymd_opt(2023, 6, 15).unwrap()));
    }

    #[test]
    fn test_parse_and_validate_time_travel_date_today_is_valid() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 15).unwrap();
        let target = "2026-11-15";
        let res = parse_and_validate_time_travel_date(target, today);
        assert_eq!(res, Some(today));
    }

    #[test]
    fn test_parse_and_validate_time_travel_date_future_strictly_rejected() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 15).unwrap();
        let tomorrow = "2026-11-16";
        let res = parse_and_validate_time_travel_date(tomorrow, today);
        assert_eq!(res, None, "Time travel must be strictly backwards (no future dates)");

        let far_future = "2030-01-01";
        assert_eq!(parse_and_validate_time_travel_date(far_future, today), None);
    }

    #[test]
    fn test_parse_and_validate_time_travel_date_pre_2022_rejected() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 15).unwrap();
        let too_old = "2021-12-31";
        let res = parse_and_validate_time_travel_date(too_old, today);
        assert_eq!(res, None, "Dates prior to Jan 1, 2022 must be rejected");

        let jan_first_2022 = "2022-01-01";
        assert_eq!(
            parse_and_validate_time_travel_date(jan_first_2022, today),
            Some(NaiveDate::from_ymd_opt(2022, 1, 1).unwrap())
        );
    }

    #[test]
    fn test_parse_and_validate_garbage_date() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 15).unwrap();
        assert_eq!(parse_and_validate_time_travel_date("not-a-date", today), None);
        assert_eq!(parse_and_validate_time_travel_date("", today), None);
    }

    #[test]
    fn test_get_time_travel_date_from_headers() {
        let mut headers = HeaderMap::new();
        headers.insert(
            header::COOKIE,
            "session=abc; monmon_time_travel=2023-08-14; user_id=jason".parse().unwrap(),
        );
        let res = get_time_travel_date(&headers);
        assert_eq!(res, Some(NaiveDate::from_ymd_opt(2023, 8, 14).unwrap()));

        let (effective, is_tt) = get_effective_date(&headers);
        assert!(is_tt);
        assert_eq!(effective, NaiveDate::from_ymd_opt(2023, 8, 14).unwrap());
    }

    #[test]
    fn test_get_effective_date_when_cookie_absent() {
        let headers = HeaderMap::new();
        let (effective, is_tt) = get_effective_date(&headers);
        assert!(!is_tt);
        assert_eq!(effective, Local::now().date_naive());
    }
}
