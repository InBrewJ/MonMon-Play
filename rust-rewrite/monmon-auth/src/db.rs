use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use sqlx::{PgPool, Row};

pub struct UserRecord {
    pub id: String,
    pub username: String,
    pub email: String,
    pub password_hash: String,
    pub roles: String,
}

pub async fn init_db(pool: &PgPool) -> Result<(), sqlx::Error> {
    sqlx::query(
        r#"
        CREATE TABLE IF NOT EXISTS app_user (
            id VARCHAR(255) PRIMARY KEY,
            username VARCHAR(128) NOT NULL UNIQUE,
            email VARCHAR(255),
            password_hash VARCHAR(255) NOT NULL,
            roles VARCHAR(255) NOT NULL DEFAULT 'ROLE_ADMIN',
            created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()
        )
        "#,
    )
    .execute(pool)
    .await?;

    sqlx::query(
        "CREATE INDEX IF NOT EXISTS idx_app_user_username ON app_user(username)"
    )
    .execute(pool)
    .await?;

    // Seed demo user if not exists
    let row = sqlx::query("SELECT COUNT(*) as cnt FROM app_user WHERE username = 'demo'")
        .fetch_one(pool)
        .await?;
    let count: i64 = row.get("cnt");

    if count == 0 {
        let demo_uuid = "9aee56db-733a-457c-aa74-74fe74e2ac91";
        let hash = hash_password("demo").expect("hash demo password");
        sqlx::query(
            r#"
            INSERT INTO app_user (id, username, email, password_hash, roles)
            VALUES ($1, 'demo', 'demo@demo.com', $2, 'ROLE_ADMIN')
            ON CONFLICT (username) DO NOTHING
            "#,
        )
        .bind(demo_uuid)
        .bind(hash)
        .execute(pool)
        .await?;
        tracing::info!("Seeded default user 'demo' with UUID {}", demo_uuid);
    }

    Ok(())
}

pub fn hash_password(password: &str) -> Result<String, String> {
    let salt = SaltString::generate(&mut OsRng);
    let argon2 = Argon2::default();
    argon2
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| e.to_string())
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    if let Ok(parsed_hash) = PasswordHash::new(hash) {
        Argon2::default()
            .verify_password(password.as_bytes(), &parsed_hash)
            .is_ok()
    } else {
        false
    }
}

pub async fn find_user_by_username(
    pool: &PgPool,
    username: &str,
) -> Result<Option<UserRecord>, sqlx::Error> {
    let raw = sqlx::query(
        "SELECT id, username, COALESCE(email, '') as email, password_hash, roles FROM app_user WHERE username = $1"
    )
    .bind(username)
    .fetch_optional(pool)
    .await?;

    Ok(raw.map(|r| UserRecord {
        id: r.get("id"),
        username: r.get("username"),
        email: r.get("email"),
        password_hash: r.get("password_hash"),
        roles: r.get("roles"),
    }))
}
