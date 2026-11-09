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

pub async fn init_passkey_table(pool: &sqlx::PgPool) -> Result<(), sqlx::Error> {
    sqlx::query(
        r#"
        CREATE TABLE IF NOT EXISTS user_passkey (
            id SERIAL PRIMARY KEY,
            user_id VARCHAR(255) NOT NULL,
            credential_id VARCHAR(512) NOT NULL UNIQUE,
            public_key TEXT,
            device_name VARCHAR(255),
            created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()
        );
        CREATE INDEX IF NOT EXISTS idx_user_passkey_user_id ON user_passkey(user_id);
        "#,
    )
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn get_passkey_options_api(
    headers: HeaderMap,
    Query(query): Query<PasskeyOptionsQuery>,
) -> Json<serde_json::Value> {
    let host = headers.get(header::HOST)
        .and_then(|h| h.to_str().ok())
        .map(|h| h.split(':').next().unwrap_or(h).trim().to_string())
        .filter(|h| !h.is_empty())
        .unwrap_or_else(|| "localhost".to_string());

    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.username.as_deref());
    let username = if raw_uid.is_empty() || raw_uid == "demo" {
        query.username.filter(|u| !u.trim().is_empty() && u != "demo").unwrap_or_else(|| "inbrewj".to_string())
    } else {
        raw_uid
    };
    let req_type = query.r#type.unwrap_or_else(|| "register".to_string());

    // Generate random 32-byte challenge using uuid
    let challenge = format!("challenge_{}_{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());

    if req_type == "login" {
        Json(serde_json::json!({
            "challenge": challenge,
            "timeout": 60000,
            "rpId": host,
            "userVerification": "preferred"
        }))
    } else {
        Json(serde_json::json!({
            "challenge": challenge,
            "rp": {
                "name": "MonMon",
                "id": host
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
    headers: HeaderMap,
    Json(payload): Json<PasskeyRegisterPayload>,
) -> Result<Response, (StatusCode, Json<serde_json::Value>)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(
        &headers,
        if payload.username.trim().is_empty() || payload.username == "demo" {
            None
        } else {
            Some(&payload.username)
        },
    );
    let resolved = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;
    let username = if resolved.is_empty() || resolved == "demo" {
        if !payload.username.trim().is_empty() && payload.username != "demo" {
            payload.username.trim().to_string()
        } else {
            "inbrewj".to_string()
        }
    } else {
        resolved
    };

    let public_key = payload.public_key.unwrap_or_else(|| "mock_pubkey_bytes".to_string());
    let device_name = payload.device_name.unwrap_or_else(|| "Browser Authenticator".to_string());

    let _ = init_passkey_table(&state.pool).await;

    // Ensure user exists in user_access
    let user_email = format!("{}@example.com", username);
    let _ = sqlx::query(
        r#"
        INSERT INTO user_access (id, approvedat, approvedby, createdat, email, status, userid, username)
        VALUES (nextval('hibernate_sequence'), NOW(), 'SYSTEM', NOW(), $1, 'APPROVED', $2, $2)
        ON CONFLICT (userid) DO UPDATE SET status = 'APPROVED'
        "#,
    )
    .bind(&user_email)
    .bind(&username)
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
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "success": false, "message": e.to_string() }))))?;

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

    let h = response.headers_mut();
    h.append(header::SET_COOKIE, cookie_val.parse().unwrap());
    h.append(header::SET_COOKIE, session_val.parse().unwrap());
    h.append(header::SET_COOKIE, auth_val.parse().unwrap());

    Ok(response)
}

pub async fn login_passkey_api(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<PasskeyLoginPayload>,
) -> Result<Response, (StatusCode, Json<serde_json::Value>)> {
    let _ = init_passkey_table(&state.pool).await;

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
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "success": false, "message": e.to_string() }))))?;

    let username = match row {
        Some((uid,)) => uid,
        None => {
            if let Some(user) = payload.username.filter(|u| !u.trim().is_empty()) {
                user
            } else {
                return Err((
                    StatusCode::UNAUTHORIZED,
                    Json(serde_json::json!({ "success": false, "message": "Unknown passkey credential" })),
                ));
            }
        }
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
) -> Result<Json<Vec<EnrolledPasskey>>, (StatusCode, Json<serde_json::Value>)> {
    let _ = init_passkey_table(&state.pool).await;
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.user_id.as_deref());
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;

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
    headers: HeaderMap,
    Path(id): Path<i32>,
) -> Result<Json<serde_json::Value>, (StatusCode, Json<serde_json::Value>)> {
    let _ = init_passkey_table(&state.pool).await;
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;

    let res = sqlx::query("DELETE FROM user_passkey WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(&user_id)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "success": false, "message": e.to_string() }))))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Passkey", id as i64)
        .map_err(|(sc, msg)| (sc, Json(serde_json::json!({ "success": false, "message": msg }))))?;

    Ok(Json(serde_json::json!({ "success": true })))
}
