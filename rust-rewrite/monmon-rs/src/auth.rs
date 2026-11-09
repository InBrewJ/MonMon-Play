use argon2::{
    password_hash::{
        phc::PasswordHash,
        PasswordHasher, PasswordVerifier,
    },
    Argon2,
};
use sqlx::{PgPool, Row};
use tracing::{info, warn};

#[derive(Debug, Clone)]
pub struct AppUser {
    pub id: String,
    pub username: String,
    pub email: Option<String>,
    pub password_hash: String,
    pub roles: String,
}

pub fn hash_password(password: &str) -> Result<String, String> {
    let argon2 = Argon2::default();
    argon2
        .hash_password(password.as_bytes())
        .map(|h| h.to_string())
        .map_err(|e| e.to_string())
}

pub fn verify_password(password: &str, password_hash: &str) -> bool {
    // Direct match if plain text stored
    if password_hash == password {
        return true;
    }
    // Argon2 verification
    if let Ok(parsed_hash) = PasswordHash::new(password_hash) {
        Argon2::default()
            .verify_password(password.as_bytes(), &parsed_hash)
            .is_ok()
    } else {
        false
    }
}

pub struct SeedUser {
    pub id: &'static str,
    pub username: &'static str,
    pub email: &'static str,
    pub plain_password: &'static str,
    pub status: &'static str,
}

pub const SEED_USERS: &[SeedUser] = &[
    SeedUser { id: "2ccc203f-a98c-488f-a5ba-8d20a384a9fc", username: "inbrewj", email: "jason@urawizard.com", plain_password: "G5YTPjgB3bbWrvrkbPPcpcmXPkupYC2QRVA", status: "APPROVED" },
    SeedUser { id: "ed085f28-1b72-4485-8564-73671a0b406d", username: "admin", email: "admin@monmon.local", plain_password: "G5YTPjgB3bbWrvrkbPPcpcmXPkupYC2QRVA", status: "APPROVED" },
    SeedUser { id: "434268bd-0c8a-41f6-b191-3768a7fe0048", username: "demo", email: "demo@urawizard.com", plain_password: "demo", status: "APPROVED" },
    SeedUser { id: "2d81294f-7866-4337-8fe3-1a1170d371af", username: "demo2", email: "demo2@urawizard.com", plain_password: "demo2123", status: "APPROVED" },
    SeedUser { id: "9f9c7b68-39e4-4088-b6e9-e33dd48e46e0", username: "caity", email: "c@d.com", plain_password: "CngELcybSQkS76MKUeEdQXZ8sr3XjexEVVM", status: "APPROVED" },
    SeedUser { id: "a3077849-5e8c-41bb-893b-8e3f945a06fb", username: "demo3", email: "demo3@urawizard.com", plain_password: "demo3123", status: "APPROVED" },
    SeedUser { id: "6d1c304f-8ebe-4620-bd28-4d56a5b3d59a", username: "rorywebber1000", email: "rorywebber1000@gmail.com", plain_password: "rorywebber1000@gmail.com123", status: "APPROVED" },
    SeedUser { id: "7558b874-4cdf-43ab-8b97-a8a9cacf04f1", username: "norman", email: "norman-brewer@urawizard.com", plain_password: "norman123", status: "APPROVED" },
    SeedUser { id: "04635238-dcae-4bcb-921e-3b9cecc7291e", username: "margotb", email: "margot.berrezaie5@gmail.com", plain_password: "margotb123", status: "APPROVED" },
    SeedUser { id: "6ad83865-6bf5-4b06-a668-5ded561ebeed", username: "demo4", email: "demo4@4.com", plain_password: "demo4123", status: "APPROVED" },
    SeedUser { id: "231f3a3b-34d9-4d14-94bd-9abfe62ffa96", username: "demo5", email: "demo5@5.com", plain_password: "demo5123", status: "APPROVED" },
    SeedUser { id: "9f930b9f-a761-42e0-b9be-27dbddcbddac", username: "demo6", email: "demo6@6.com", plain_password: "demo6123", status: "APPROVED" },
    SeedUser { id: "b8bfd89e-a4ae-4463-a6cc-7c2e1cd0e0aa", username: "demo10", email: "demo10@lalalalala.com", plain_password: "demo10123", status: "APPROVED" },
    SeedUser { id: "b449472c-8bb3-4cea-87c1-2c2fc58899c6", username: "demoinflow", email: "demoinflow@urawizard.com", plain_password: "demoinflow123", status: "APPROVED" },
    SeedUser { id: "1fe7548a-fdd3-4d25-b1f2-9e43dbaf8337", username: "newguy1000", email: "newguy@dryark.uk", plain_password: "newguy1000123", status: "APPROVED" },
    SeedUser { id: "ebeae119-8dac-4175-a4fc-5906c5031780", username: "waitinglist", email: "waitinglist@dryark.uk", plain_password: "waitinglist123", status: "REJECTED" },
];

pub async fn init_auth_db(pool: &PgPool) -> Result<(), sqlx::Error> {
    // 1. Create app_user table
    sqlx::query(
        r#"
        CREATE TABLE IF NOT EXISTS app_user (
            id VARCHAR(255) PRIMARY KEY,
            username VARCHAR(128) NOT NULL UNIQUE,
            email VARCHAR(255),
            password_hash VARCHAR(255) NOT NULL,
            roles VARCHAR(255) NOT NULL DEFAULT 'ROLE_ADMIN',
            created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()
        );
        CREATE INDEX IF NOT EXISTS idx_app_user_username ON app_user(username);
        CREATE INDEX IF NOT EXISTS idx_app_user_email ON app_user(email);
        "#,
    )
    .execute(pool)
    .await?;

    // 2. Clean up corrupted duplicate rows in user_access where userid == username
    // when a proper UUID row exists for the same user
    let _ = sqlx::query(
        r#"
        DELETE FROM user_access ua1
        WHERE ua1.userid = ua1.username
          AND EXISTS (
              SELECT 1 FROM user_access ua2
              WHERE ua2.username = ua1.username
                AND ua2.userid != ua2.username
                AND length(ua2.userid) > 20
          );
        "#,
    )
    .execute(pool)
    .await;

    // 3. Seed users into app_user if missing
    for u in SEED_USERS {
        let existing = sqlx::query("SELECT id FROM app_user WHERE username = $1 LIMIT 1")
            .bind(u.username)
            .fetch_optional(pool)
            .await?;

        if existing.is_none() {
            let hash = hash_password(u.plain_password).unwrap_or_else(|_| u.plain_password.to_string());
            let _ = sqlx::query(
                r#"
                INSERT INTO app_user (id, username, email, password_hash, roles, created_at)
                VALUES ($1, $2, $3, $4, 'ROLE_ADMIN', NOW())
                ON CONFLICT (username) DO NOTHING
                "#,
            )
            .bind(u.id)
            .bind(u.username)
            .bind(u.email)
            .bind(&hash)
            .execute(pool)
            .await;
            info!("Seeded app_user for {}", u.username);
        }

        // Ensure user_access has the matching approved entry
        let _ = sqlx::query(
            r#"
            INSERT INTO user_access (id, approvedat, approvedby, createdat, email, status, userid, username)
            VALUES (nextval('hibernate_sequence'), NOW(), 'SYSTEM', NOW(), $1, $2, $3, $4)
            ON CONFLICT (userid) DO UPDATE SET status = EXCLUDED.status, username = EXCLUDED.username
            "#,
        )
        .bind(u.email)
        .bind(u.status)
        .bind(u.id)
        .bind(u.username)
        .execute(pool)
        .await;
    }

    Ok(())
}

pub async fn authenticate_user(
    pool: &PgPool,
    username_or_email: &str,
    password: &str,
) -> Result<Option<AppUser>, sqlx::Error> {
    let clean = username_or_email.trim();
    if clean.is_empty() || password.is_empty() {
        return Ok(None);
    }

    let row = sqlx::query(
        r#"
        SELECT id, username, email, password_hash, roles
        FROM app_user
        WHERE LOWER(username) = LOWER($1) OR LOWER(email) = LOWER($1)
        LIMIT 1
        "#,
    )
    .bind(clean)
    .fetch_optional(pool)
    .await?;

    if let Some(r) = row {
        let id: String = r.get("id");
        let username: String = r.get("username");
        let email: Option<String> = r.get("email");
        let password_hash: String = r.get("password_hash");
        let roles: String = r.get("roles");

        if verify_password(password, &password_hash) {
            // If the password was plain text, upgrade to Argon2
            if password_hash == password {
                if let Ok(new_hash) = hash_password(password) {
                    let _ = sqlx::query("UPDATE app_user SET password_hash = $1 WHERE id = $2")
                        .bind(&new_hash)
                        .bind(&id)
                        .execute(pool)
                        .await;
                }
            }
            return Ok(Some(AppUser {
                id,
                username,
                email,
                password_hash,
                roles,
            }));
        } else {
            warn!("Password verification failed for user {}", clean);
            return Ok(None);
        }
    }

    warn!("User {} not found in app_user", clean);
    Ok(None)
}

pub async fn get_canonical_user_id(pool: &PgPool, username_or_id: &str) -> String {
    let clean = username_or_id.trim();
    if clean.is_empty() {
        return "2ccc203f-a98c-488f-a5ba-8d20a384a9fc".to_string(); // fallback to inbrewj
    }

    // 1. Check app_user by username or email or id
    if let Ok(Some(row)) = sqlx::query(
        "SELECT id FROM app_user WHERE LOWER(username) = LOWER($1) OR LOWER(email) = LOWER($1) OR id = $1 LIMIT 1",
    )
    .bind(clean)
    .fetch_optional(pool)
    .await
    {
        return row.get::<String, _>("id");
    }

    // 2. Check user_access
    if let Ok(Some(row)) = sqlx::query(
        r#"
        SELECT userid FROM user_access
        WHERE userid = $1
           OR (LOWER(username) = LOWER($1) AND userid != username)
           OR (LOWER(email) = LOWER($1) AND userid != email)
        ORDER BY CASE WHEN userid = $1 THEN 1 WHEN length(userid) > 20 THEN 2 ELSE 3 END, id ASC
        LIMIT 1
        "#,
    )
    .bind(clean)
    .fetch_optional(pool)
    .await
    {
        return row.get::<String, _>("userid");
    }

    clean.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_plain_password_fallback() {
        assert!(verify_password("demo", "demo"));
        assert!(!verify_password("wrong", "demo"));
    }

    #[test]
    fn test_argon2_hash_and_verify() {
        let plain = "my_secret_passphrase_123";
        let hash = hash_password(plain).expect("hashing should succeed");
        assert!(verify_password(plain, &hash));
        assert!(!verify_password("wrong_password", &hash));
    }
}
