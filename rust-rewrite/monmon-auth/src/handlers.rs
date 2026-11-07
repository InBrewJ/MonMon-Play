use crate::db::{self, UserRecord};
use crate::keys::KeyManager;
use crate::models::*;
use axum::{
    extract::{Form, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{Html, IntoResponse, Redirect, Response},
    Json,
};
use base64::Engine;
use chrono::Utc;
use jsonwebtoken::{Algorithm, Header};
use sqlx::PgPool;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Clone)]
pub struct AppState {
    pub pool: PgPool,
    pub keys: KeyManager,
    pub issuer: String,
    pub auth_codes: Arc<RwLock<HashMap<String, AuthCodeData>>>,
}

pub async fn openid_configuration(State(state): State<AppState>) -> Json<DiscoveryDocument> {
    let issuer = state.issuer.clone();
    Json(DiscoveryDocument {
        issuer: issuer.clone(),
        authorization_endpoint: format!("{}/protocol/openid-connect/auth", issuer),
        token_endpoint: format!("{}/protocol/openid-connect/token", issuer),
        userinfo_endpoint: format!("{}/protocol/openid-connect/userinfo", issuer),
        end_session_endpoint: format!("{}/protocol/openid-connect/logout", issuer),
        jwks_uri: format!("{}/protocol/openid-connect/certs", issuer),
        response_types_supported: vec![
            "code".into(),
            "none".into(),
            "id_token".into(),
            "token".into(),
            "id_token token".into(),
            "code id_token".into(),
            "code token".into(),
            "code id_token token".into(),
        ],
        subject_types_supported: vec!["public".into()],
        id_token_signing_alg_values_supported: vec!["RS256".into()],
        scopes_supported: vec![
            "openid".into(),
            "profile".into(),
            "email".into(),
            "roles".into(),
        ],
        token_endpoint_auth_methods_supported: vec![
            "client_secret_post".into(),
            "client_secret_basic".into(),
        ],
        claims_supported: vec![
            "sub".into(),
            "iss".into(),
            "aud".into(),
            "preferred_username".into(),
            "email".into(),
            "roles".into(),
            "nonce".into(),
        ],
        code_challenge_methods_supported: vec!["plain".into(), "S256".into()],
    })
}

pub async fn jwks(State(state): State<AppState>) -> Json<JwksResponse> {
    Json(JwksResponse {
        keys: vec![JwkKey {
            kty: "RSA".into(),
            alg: "RS256".into(),
            public_use: "sig".into(),
            kid: state.keys.kid.clone(),
            n: state.keys.n_b64.clone(),
            e: state.keys.e_b64.clone(),
        }],
    })
}

pub async fn auth_get(
    State(_state): State<AppState>,
    Query(params): Query<AuthQueryParams>,
) -> Html<String> {
    let client_id = params.client_id.unwrap_or_else(|| "monmon-web".into());
    let redirect_uri = params
        .redirect_uri
        .unwrap_or_else(|| "http://localhost:9000/callback".into());
    let state = params.state.unwrap_or_default();
    let nonce = params.nonce.unwrap_or_default();

    Html(render_login_page(&client_id, &redirect_uri, &state, &nonce, None))
}

pub async fn auth_login_post(
    State(state): State<AppState>,
    Form(form): Form<LoginForm>,
) -> Response {
    let user_opt = match db::find_user_by_username(&state.pool, &form.username).await {
        Ok(u) => u,
        Err(e) => {
            tracing::error!("Database error checking user: {}", e);
            None
        }
    };

    let valid = if let Some(ref user) = user_opt {
        db::verify_password(&form.password, &user.password_hash)
    } else {
        // Fallback demo check if demo wasn't seeded yet
        form.username == "demo" && form.password == "demo"
    };

    if !valid {
        let error_html = render_login_page(
            &form.client_id,
            &form.redirect_uri,
            form.state.as_deref().unwrap_or(""),
            form.nonce.as_deref().unwrap_or(""),
            Some("Invalid username or password. Please try again."),
        );
        return (StatusCode::UNAUTHORIZED, Html(error_html)).into_response();
    }

    let user = user_opt.unwrap_or_else(|| UserRecord {
        id: "9aee56db-733a-457c-aa74-74fe74e2ac91".into(),
        username: "demo".into(),
        email: "demo@demo.com".into(),
        password_hash: "".into(),
        roles: "ROLE_ADMIN".into(),
    });

    let code = uuid::Uuid::new_v4().to_string();
    let code_data = AuthCodeData {
        user_id: user.id,
        username: user.username,
        email: user.email,
        roles: user
            .roles
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect(),
        nonce: form.nonce.filter(|n| !n.is_empty()),
        client_id: form.client_id,
        redirect_uri: form.redirect_uri.clone(),
        created_at: Utc::now().timestamp(),
    };

    {
        let mut codes = state.auth_codes.write().await;
        codes.insert(code.clone(), code_data);
    }

    let mut target = form.redirect_uri;
    let sep = if target.contains('?') { "&" } else { "?" };
    target.push_str(&format!("{}code={}", sep, urlencoding::encode(&code)));

    if let Some(st) = form.state {
        if !st.is_empty() {
            target.push_str(&format!("&state={}", urlencoding::encode(&st)));
        }
    }

    (StatusCode::FOUND, [(header::LOCATION, target)]).into_response()
}

pub async fn token_post(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(mut form): Form<HashMap<String, String>>,
) -> Response {
    let mut client_id = form.remove("client_id");
    let mut _client_secret = form.remove("client_secret");

    // Extract client credentials from Basic Auth header if present
    if client_id.is_none() {
        if let Some(auth_val) = headers.get(header::AUTHORIZATION) {
            if let Ok(auth_str) = auth_val.to_str() {
                if let Some(b64) = auth_str.strip_prefix("Basic ") {
                    if let Ok(decoded) = base64::engine::general_purpose::STANDARD.decode(b64) {
                        if let Ok(cred_str) = String::from_utf8(decoded) {
                            if let Some((cid, sec)) = cred_str.split_once(':') {
                                client_id = Some(cid.to_string());
                                _client_secret = Some(sec.to_string());
                            }
                        }
                    }
                }
            }
        }
    }
    tracing::debug!("Token exchange for client_id: {:?}", client_id);

    let code = match form.get("code") {
        Some(c) => c.clone(),
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "invalid_request", "error_description": "Missing code" })),
            )
                .into_response();
        }
    };

    let auth_data = {
        let mut codes = state.auth_codes.write().await;
        codes.remove(&code)
    };

    let auth_data = match auth_data {
        Some(data) => {
            let now = Utc::now().timestamp();
            if now - data.created_at > 300 {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(serde_json::json!({ "error": "invalid_grant", "error_description": "Authorization code expired" })),
                )
                    .into_response();
            }
            data
        }
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "invalid_grant", "error_description": "Invalid authorization code" })),
            )
                .into_response();
        }
    };

    let now = Utc::now().timestamp();
    let exp = now + 3600;

    let mut resource_access = HashMap::new();
    resource_access.insert(
        auth_data.client_id.clone(),
        RealmAccess {
            roles: auth_data.roles.clone(),
        },
    );

    let id_claims = IdTokenClaims {
        iss: state.issuer.clone(),
        sub: auth_data.user_id.clone(),
        aud: auth_data.client_id.clone(),
        exp,
        iat: now,
        auth_time: now,
        nonce: auth_data.nonce.clone(),
        preferred_username: auth_data.username.clone(),
        email: auth_data.email.clone(),
        email_verified: true,
        name: auth_data.username.clone(),
        realm_access: RealmAccess {
            roles: auth_data.roles.clone(),
        },
        resource_access,
        roles: auth_data.roles.clone(),
    };

    let mut header = Header::new(Algorithm::RS256);
    header.kid = Some(state.keys.kid.clone());

    let id_token = match jsonwebtoken::encode(&header, &id_claims, &state.keys.encoding_key) {
        Ok(t) => t,
        Err(e) => {
            tracing::error!("Failed to sign ID token: {}", e);
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({ "error": "server_error" })),
            )
                .into_response();
        }
    };

    // Return access token and id token
    Json(TokenResponse {
        access_token: id_token.clone(),
        token_type: "Bearer".into(),
        id_token,
        expires_in: 3600,
    })
    .into_response()
}

pub async fn userinfo_get(
    State(_state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    // Extract token
    let auth_val = headers.get(header::AUTHORIZATION);
    let token = auth_val
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.strip_prefix("Bearer "));

    if token.is_none() {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": "unauthorized" })),
        )
            .into_response();
    }

    // Default to demo claims or decode token
    Json(UserInfoResponse {
        sub: "9aee56db-733a-457c-aa74-74fe74e2ac91".into(),
        preferred_username: "demo".into(),
        email: "demo@demo.com".into(),
        roles: vec!["ROLE_ADMIN".into()],
    })
    .into_response()
}

pub async fn logout(Query(params): Query<LogoutQueryParams>) -> Redirect {
    let target = params
        .post_logout_redirect_uri
        .or(params.redirect_uri)
        .unwrap_or_else(|| "/".into());
    Redirect::to(&target)
}

fn render_login_page(
    client_id: &str,
    redirect_uri: &str,
    state: &str,
    nonce: &str,
    error_msg: Option<&str>,
) -> String {
    let error_block = if let Some(err) = error_msg {
        format!(
            r#"<div style="background-color: #fef2f2; border: 1px solid #f87171; color: #b91c1c; padding: 0.75rem 1rem; border-radius: 0.375rem; margin-bottom: 1.25rem; font-size: 0.875rem;">{}</div>"#,
            err
        )
    } else {
        String::new()
    };

    format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>MonMon Sign In</title>
    <style>
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: #0f172a;
            color: #f8fafc;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
            padding: 1rem;
        }}
        .card {{
            background: #1e293b;
            border: 1px solid #334155;
            border-radius: 12px;
            box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.4);
            max-width: 400px;
            width: 100%;
            padding: 2.25rem 2rem;
        }}
        .header {{
            text-align: center;
            margin-bottom: 1.75rem;
        }}
        .logo {{
            font-size: 1.75rem;
            font-weight: 700;
            letter-spacing: -0.025em;
            color: #38bdf8;
            margin-bottom: 0.5rem;
        }}
        .subtitle {{
            font-size: 0.875rem;
            color: #94a3b8;
        }}
        .form-group {{
            margin-bottom: 1.25rem;
        }}
        label {{
            display: block;
            margin-bottom: 0.375rem;
            font-size: 0.875rem;
            font-weight: 500;
            color: #cbd5e1;
        }}
        input[type="text"], input[type="password"] {{
            width: 100%;
            padding: 0.625rem 0.875rem;
            border-radius: 6px;
            border: 1px solid #475569;
            background: #0f172a;
            color: #f8fafc;
            font-size: 1rem;
            outline: none;
            transition: border-color 0.15s ease;
        }}
        input[type="text"]:focus, input[type="password"]:focus {{
            border-color: #38bdf8;
            box-shadow: 0 0 0 1px #38bdf8;
        }}
        .btn-submit {{
            width: 100%;
            padding: 0.75rem;
            background: #0284c7;
            color: #ffffff;
            font-size: 1rem;
            font-weight: 600;
            border: none;
            border-radius: 6px;
            cursor: pointer;
            transition: background-color 0.15s ease;
            margin-top: 0.5rem;
        }}
        .btn-submit:hover {{
            background: #0369a1;
        }}
        .footer {{
            text-align: center;
            margin-top: 1.5rem;
            font-size: 0.75rem;
            color: #64748b;
        }}
    </style>
</head>
<body>
    <div class="card">
        <div class="header">
            <div class="logo">MonMon</div>
            <div class="subtitle">Sign in to manage your finances</div>
        </div>
        {error_block}
        <form method="POST" action="/auth/login">
            <input type="hidden" name="client_id" value="{client_id}">
            <input type="hidden" name="redirect_uri" value="{redirect_uri}">
            <input type="hidden" name="state" value="{state}">
            <input type="hidden" name="nonce" value="{nonce}">
            <div class="form-group">
                <label for="username">Username</label>
                <input type="text" id="username" name="username" required autofocus autocomplete="username">
            </div>
            <div class="form-group">
                <label for="password">Password</label>
                <input type="password" id="password" name="password" required autocomplete="current-password">
            </div>
            <button type="submit" id="kc-login" class="btn-submit">Sign In</button>
        </form>
        <div class="footer">
            monmon-auth native &bull; High performance
        </div>
    </div>
</body>
</html>"#,
        client_id = client_id,
        redirect_uri = redirect_uri,
        state = state,
        nonce = nonce,
        error_block = error_block
    )
}
