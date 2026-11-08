use axum::{
    extract::{Path, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Json, Response},
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use crate::AppState;

#[derive(Debug, Serialize, Deserialize)]
pub struct PasskeyOptionsQuery {
    pub r#type: Option<String>,
    pub username: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PasskeyRegisterPayload {
    pub username: String,
    pub credential_id: String,
    pub public_key: Option<String>,
    pub device_name: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PasskeyLoginPayload {
    pub username: Option<String>,
    pub credential_id: String,
    pub signature: Option<String>,
}

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct EnrolledPasskey {
    pub id: i32,
    pub user_id: String,
    pub credential_id: String,
    pub device_name: Option<String>,
    pub created_at: chrono::NaiveDateTime,
}

pub async fn get_passkey_options_api(
    Query(query): Query<PasskeyOptionsQuery>,
) -> Json<serde_json::Value> {
    let username = query.username.unwrap_or_else(|| "demo".to_string());
    let req_type = query.r#type.unwrap_or_else(|| "register".to_string());

    // Generate random 32-byte challenge using uuid
    let challenge = format!("challenge_{}_{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());

    if req_type == "login" {
        Json(serde_json::json!({
            "challenge": challenge,
            "timeout": 60000,
            "rpId": "localhost",
            "userVerification": "preferred"
        }))
    } else {
        Json(serde_json::json!({
            "challenge": challenge,
            "rp": {
                "name": "MonMon",
                "id": "localhost"
            },
            "user": {
                "id": format!("user_{}", username),
                "name": username,
                "displayName": username
            },
            "pubKeyCredParams": [
                { "type": "public-key", "alg": -7 },   // ES256
                { "type": "public-key", "alg": -257 }  // RS256
            ],
            "timeout": 60000,
            "attestation": "none",
            "authenticatorSelection": {
                "residentKey": "preferred",
                "userVerification": "preferred"
            }
        }))
    }
}

pub async fn register_passkey_api(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<PasskeyRegisterPayload>,
) -> Result<Response, (StatusCode, String)> {
    let username = payload.username.trim().to_string();
    if username.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "Username cannot be empty".to_string()));
    }

    let public_key = payload.public_key.unwrap_or_else(|| "mock_pubkey_bytes".to_string());
    let device_name = payload.device_name.unwrap_or_else(|| "Browser Authenticator".to_string());

    // Ensure user exists in user_access
    let _ = sqlx::query(
        r#"
        INSERT INTO user_access (user_id, email, approved, created_at)
        VALUES ($1, $2, true, NOW())
        ON CONFLICT (user_id) DO UPDATE SET approved = true
        "#,
    )
    .bind(&username)
    .bind(format!("{}@example.com", username))
    .execute(&state.pool)
    .await;

    // Save passkey in user_passkey
    sqlx::query(
        r#"
        INSERT INTO user_passkey (user_id, credential_id, public_key, device_name, created_at)
        VALUES ($1, $2, $3, $4, NOW())
        ON CONFLICT (credential_id) DO UPDATE SET public_key = EXCLUDED.public_key, device_name = EXCLUDED.device_name
        "#,
    )
    .bind(&username)
    .bind(&payload.credential_id)
    .bind(&public_key)
    .bind(&device_name)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    // Issue auth cookies
    let cookie_val = format!("user_id={}; Path=/; HttpOnly; SameSite=Lax", username);
    let session_val = format!("monmon_session={}; Path=/; SameSite=Lax", username);
    let auth_val = "authenticated=true; Path=/; SameSite=Lax";

    let mut response = Json(serde_json::json!({
        "success": true,
        "redirect": "/spog",
        "username": username,
        "device": device_name
    }))
    .into_response();

    let headers = response.headers_mut();
    headers.append(header::SET_COOKIE, cookie_val.parse().unwrap());
    headers.append(header::SET_COOKIE, session_val.parse().unwrap());
    headers.append(header::SET_COOKIE, auth_val.parse().unwrap());

    Ok(response)
}

pub async fn login_passkey_api(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<PasskeyLoginPayload>,
) -> Result<Response, (StatusCode, String)> {
    // Look up passkey by credential_id
    let row: Option<(String,)> = sqlx::query_as(
        r#"
        SELECT user_id FROM user_passkey
        WHERE credential_id = $1
        LIMIT 1
        "#,
    )
    .bind(&payload.credential_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let username = if let Some((uid,)) = row {
        uid
    } else if let Some(un) = payload.username {
        if !un.trim().is_empty() {
            un.trim().to_string()
        } else {
            "demo".to_string()
        }
    } else {
        "demo".to_string()
    };

    let cookie_val = format!("user_id={}; Path=/; HttpOnly; SameSite=Lax", username);
    let session_val = format!("monmon_session={}; Path=/; SameSite=Lax", username);
    let auth_val = "authenticated=true; Path=/; SameSite=Lax";

    let mut response = Json(serde_json::json!({
        "success": true,
        "redirect": "/spog",
        "username": username
    }))
    .into_response();

    let headers = response.headers_mut();
    headers.append(header::SET_COOKIE, cookie_val.parse().unwrap());
    headers.append(header::SET_COOKIE, session_val.parse().unwrap());
    headers.append(header::SET_COOKIE, auth_val.parse().unwrap());

    Ok(response)
}

#[derive(Deserialize)]
pub struct ListPasskeysQuery {
    pub user_id: Option<String>,
}

pub async fn list_user_passkeys_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<ListPasskeysQuery>,
) -> Result<Json<Vec<EnrolledPasskey>>, (StatusCode, String)> {
    let mut user_id = query.user_id.unwrap_or_default();
    if user_id.is_empty() {
        if let Some(cookie_hdr) = headers.get(header::COOKIE) {
            if let Ok(cookie_str) = cookie_hdr.to_str() {
                for part in cookie_str.split(';') {
                    let part = part.trim();
                    if let Some(val) = part.strip_prefix("user_id=") {
                        user_id = val.to_string();
                        break;
                    }
                }
            }
        }
    }
    if user_id.is_empty() {
        user_id = "demo".to_string();
    }

    let passkeys = sqlx::query_as::<_, EnrolledPasskey>(
        r#"
        SELECT id, user_id, credential_id, device_name, created_at
        FROM user_passkey
        WHERE user_id = $1
        ORDER BY created_at DESC
        "#,
    )
    .bind(&user_id)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    Ok(Json(passkeys))
}

pub async fn delete_passkey_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i32>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    sqlx::query("DELETE FROM user_passkey WHERE id = $1")
        .bind(id)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(serde_json::json!({ "success": true })))
}
