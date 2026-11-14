#![allow(dead_code)]
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Serialize, Deserialize)]
pub struct DiscoveryDocument {
    pub issuer: String,
    pub authorization_endpoint: String,
    pub token_endpoint: String,
    pub userinfo_endpoint: String,
    pub end_session_endpoint: String,
    pub jwks_uri: String,
    pub response_types_supported: Vec<String>,
    pub subject_types_supported: Vec<String>,
    pub id_token_signing_alg_values_supported: Vec<String>,
    pub scopes_supported: Vec<String>,
    pub token_endpoint_auth_methods_supported: Vec<String>,
    pub claims_supported: Vec<String>,
    pub code_challenge_methods_supported: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct JwksResponse {
    pub keys: Vec<JwkKey>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct JwkKey {
    pub kty: String,
    pub alg: String,
    #[serde(rename = "use")]
    pub public_use: String,
    pub kid: String,
    pub n: String,
    pub e: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RealmAccess {
    pub roles: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct IdTokenClaims {
    pub iss: String,
    pub sub: String,
    pub aud: String,
    pub exp: i64,
    pub iat: i64,
    pub auth_time: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub nonce: Option<String>,
    pub preferred_username: String,
    pub email: String,
    pub email_verified: bool,
    pub name: String,
    pub realm_access: RealmAccess,
    pub resource_access: HashMap<String, RealmAccess>,
    pub roles: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct TokenResponse {
    pub access_token: String,
    pub token_type: String,
    pub id_token: String,
    pub expires_in: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct UserInfoResponse {
    pub sub: String,
    pub preferred_username: String,
    pub email: String,
    pub roles: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct AuthCodeData {
    pub user_id: String,
    pub username: String,
    pub email: String,
    pub roles: Vec<String>,
    pub nonce: Option<String>,
    pub client_id: String,
    pub redirect_uri: String,
    pub created_at: i64,
}

#[derive(Debug, Deserialize)]
pub struct AuthQueryParams {
    pub client_id: Option<String>,
    pub redirect_uri: Option<String>,
    pub response_type: Option<String>,
    pub scope: Option<String>,
    pub state: Option<String>,
    pub nonce: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct LoginForm {
    pub username: String,
    pub password: String,
    pub client_id: String,
    pub redirect_uri: String,
    pub state: Option<String>,
    pub nonce: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct TokenRequestForm {
    pub grant_type: String,
    pub code: String,
    pub redirect_uri: Option<String>,
    pub client_id: Option<String>,
    pub client_secret: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct LogoutQueryParams {
    pub post_logout_redirect_uri: Option<String>,
    pub redirect_uri: Option<String>,
}
