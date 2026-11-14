mod db;
mod handlers;
mod keys;
mod models;

use axum::{
    routing::{get, post},
    Router,
};
use handlers::*;
use keys::KeyManager;
use sqlx::postgres::PgPoolOptions;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::RwLock;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();

    let port: u16 = std::env::var("PORT")
        .unwrap_or_else(|_| "8085".to_string())
        .parse()
        .expect("PORT must be a valid u16 number");

    let db_url = std::env::var("DB_URL")
        .or_else(|_| std::env::var("DATABASE_URL"))
        .unwrap_or_else(|_| "postgres://postgres:docker@127.0.0.1:5432/monmon-play".to_string());

    let key_path = std::env::var("KEY_PATH")
        .unwrap_or_else(|_| "keys/jwt_private_key.pem".to_string());

    let issuer = std::env::var("ISSUER_URL").unwrap_or_else(|_| {
        format!("http://localhost:{}/auth/realms/monmon", port)
    });

    tracing::info!("Connecting to PostgreSQL database at {}", db_url);
    let pool = PgPoolOptions::new()
        .max_connections(10)
        .connect(&db_url)
        .await
        .expect("Failed to connect to PostgreSQL");

    tracing::info!("Initializing database schema & seeding default users...");
    db::init_db(&pool).await.expect("Failed to initialize database");

    tracing::info!("Initializing RSA key manager (key path: {})...", key_path);
    let keys = KeyManager::new_or_load(&key_path);

    let state = AppState {
        pool,
        keys,
        issuer: issuer.clone(),
        auth_codes: Arc::new(RwLock::new(HashMap::new())),
    };

    let app = Router::new()
        // Health
        .route("/health", get(|| async { axum::Json(serde_json::json!({ "status": "UP" })) }))
        // Standard OIDC endpoints
        .route("/.well-known/openid-configuration", get(openid_configuration))
        .route("/certs", get(jwks))
        .route("/auth", get(auth_get))
        .route("/auth/login", post(auth_login_post))
        .route("/token", post(token_post))
        .route("/userinfo", get(userinfo_get))
        .route("/logout", get(logout))
        // Protocol openid-connect paths
        .route("/protocol/openid-connect/certs", get(jwks))
        .route("/protocol/openid-connect/auth", get(auth_get))
        .route("/protocol/openid-connect/token", post(token_post))
        .route("/protocol/openid-connect/userinfo", get(userinfo_get))
        .route("/protocol/openid-connect/logout", get(logout))
        // Keycloak compatibility paths (/auth/realms/monmon/...)
        .route(
            "/auth/realms/monmon/.well-known/openid-configuration",
            get(openid_configuration),
        )
        .route("/auth/realms/monmon/protocol/openid-connect/certs", get(jwks))
        .route("/auth/realms/monmon/protocol/openid-connect/auth", get(auth_get))
        .route("/auth/realms/monmon/login-actions/authenticate", post(auth_login_post))
        .route("/auth/realms/monmon/protocol/openid-connect/token", post(token_post))
        .route("/auth/realms/monmon/protocol/openid-connect/userinfo", get(userinfo_get))
        .route("/auth/realms/monmon/protocol/openid-connect/logout", get(logout))
        .with_state(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    tracing::info!("monmon-auth listening on http://0.0.0.0:{} (issuer: {})", port, issuer);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
