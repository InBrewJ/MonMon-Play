use axum::http::StatusCode;

/// Centralized Authorization & BOLA / IDOR Protection
///
/// Ensures all mutations and entity queries are strictly bound to the authenticated user ID.
/// In accordance with OWASP API Security Top 10 (API1:2023 Broken Object Level Authorization),
/// no object can be modified, queried, or deleted by ID without proving ownership in the WHERE clause.
pub struct Authz;

impl Authz {
    /// Verifies that a database mutation (UPDATE or DELETE) affected at least one row owned by the user.
    /// If zero rows were affected, returns a 403 Forbidden error and logs a security warning.
    pub fn verify_affected(
        rows_affected: u64,
        entity_name: &str,
        entity_id: i64,
    ) -> Result<(), (StatusCode, String)> {
        if rows_affected == 0 {
            tracing::warn!(
                "[AUTHZ BOLA BLOCKED] 0 rows affected for {} ID {}. Object not found or owned by another user.",
                entity_name,
                entity_id
            );
            return Err((
                StatusCode::FORBIDDEN,
                format!(
                    "Access denied: {} #{} was not found or belongs to another user account.",
                    entity_name, entity_id
                ),
            ));
        }
        Ok(())
    }

    /// Verifies that an entity fetched by ID is owned by the user.
    pub fn verify_ownership<T>(
        entity: Option<T>,
        entity_name: &str,
        entity_id: i64,
    ) -> Result<T, (StatusCode, String)> {
        match entity {
            Some(e) => Ok(e),
            None => {
                tracing::warn!(
                    "[AUTHZ IDOR BLOCKED] Lookup for {} ID {} yielded no result for the authenticated user.",
                    entity_name,
                    entity_id
                );
                Err((
                    StatusCode::FORBIDDEN,
                    format!(
                        "Access denied: {} #{} was not found or belongs to another user account.",
                        entity_name, entity_id
                    ),
                ))
            }
        }
    }

    /// Verifies that the requested user_id matches the authenticated user.
    pub fn verify_user(
        target_user_id: &str,
        auth_user_id: &str,
    ) -> Result<(), (StatusCode, String)> {
        if target_user_id != auth_user_id {
            tracing::warn!(
                "[AUTHZ BOLA BLOCKED] User mismatch: target='{}' auth='{}'. Access denied.",
                target_user_id,
                auth_user_id
            );
            return Err((
                StatusCode::FORBIDDEN,
                "Access denied: you cannot access or modify resources belonging to another user.".to_string(),
            ));
        }
        Ok(())
    }
}
