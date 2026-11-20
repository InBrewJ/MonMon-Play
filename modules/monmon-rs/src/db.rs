use crate::models::{
    Account, Balance, ErraticBalance, Incoming, NewAccount, NewBalance, NewIncoming, NewOutgoing,
    Outgoing, UpdateAccount, UpdateIncoming, UpdateOutgoing,
};
use chrono::{Datelike, Utc};
use sqlx::{PgPool, Row};

// ==========================================
// Schema Migrations (Audit: created_at, updated_at, archived_at)
// ==========================================

pub async fn run_schema_migrations(pool: &PgPool) {
    let stmts = [
        // Legacy column ensurements
        r#"ALTER TABLE user_access ADD COLUMN IF NOT EXISTS user_id VARCHAR(255)"#,
        r#"UPDATE user_access SET user_id = userid WHERE user_id IS NULL AND userid IS NOT NULL"#,
        r#"ALTER TABLE user_access ADD COLUMN IF NOT EXISTS yearly_bills_account_id BIGINT REFERENCES account(id) ON DELETE SET NULL"#,
        r#"ALTER TABLE user_access ADD COLUMN IF NOT EXISTS region VARCHAR(64) DEFAULT 'UK'"#,
        r#"ALTER TABLE user_access ADD COLUMN IF NOT EXISTS age INTEGER"#,
        r#"ALTER TABLE user_access ADD COLUMN IF NOT EXISTS birth_month INTEGER"#,
        r#"ALTER TABLE user_access ADD COLUMN IF NOT EXISTS birth_year INTEGER"#,
        r#"ALTER TABLE pending_statement ADD COLUMN IF NOT EXISTS overwritten_balances_json TEXT"#,
        // Audit: created_at, updated_at, archived_at across all tables
        r#"ALTER TABLE account ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE account ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE account ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"ALTER TABLE app_user ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()"#,
        r#"ALTER TABLE balance ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE balance ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"UPDATE bank_transaction SET created_at = createdat WHERE created_at IS NULL AND createdat IS NOT NULL"#,
        r#"ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"UPDATE custom_spending_plan SET created_at = createdat WHERE created_at IS NULL AND createdat IS NOT NULL"#,
        r#"UPDATE custom_spending_plan SET updated_at = COALESCE(last_updated, lastupdated) WHERE updated_at IS NULL AND COALESCE(last_updated, lastupdated) IS NOT NULL"#,
        r#"ALTER TABLE incoming ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE incoming ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE incoming ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"ALTER TABLE locked_meal_plan ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE locked_meal_plan ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"UPDATE locked_meal_plan SET created_at = COALESCE(lockedat, "lockedAt") WHERE created_at IS NULL AND COALESCE(lockedat, "lockedAt") IS NOT NULL"#,
        r#"ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"UPDATE monzo_connection SET created_at = createdat WHERE created_at IS NULL AND createdat IS NOT NULL"#,
        r#"ALTER TABLE net_worth_asset ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"UPDATE net_worth_asset SET archived_at = deleted_at WHERE archived = true AND archived_at IS NULL AND deleted_at IS NOT NULL"#,
        r#"ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"ALTER TABLE pending_statement ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE plan ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE plan ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE plan ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"ALTER TABLE pot ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE pot ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE pot ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"UPDATE pot SET created_at = createdat WHERE created_at IS NULL AND createdat IS NOT NULL"#,
        r#"UPDATE pot SET updated_at = lastupdated WHERE updated_at IS NULL AND lastupdated IS NOT NULL"#,
        r#"ALTER TABLE pot_accounts ADD COLUMN IF NOT EXISTS created_at BIGINT DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT"#,
        r#"ALTER TABLE pot_accounts ADD COLUMN IF NOT EXISTS updated_at BIGINT DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT"#,
        r#"ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"UPDATE savings_plan SET created_at = createdat WHERE created_at IS NULL AND createdat IS NOT NULL"#,
        r#"UPDATE savings_plan SET updated_at = COALESCE(last_updated, lastupdated) WHERE updated_at IS NULL AND COALESCE(last_updated, lastupdated) IS NOT NULL"#,
        r#"ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS created_at BIGINT"#,
        r#"ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS updated_at BIGINT"#,
        r#"ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS archived_at BIGINT"#,
        r#"ALTER TABLE user_access ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()"#,
        r#"ALTER TABLE user_access ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()"#,
        r#"UPDATE user_access SET created_at = createdat WHERE created_at IS NULL AND createdat IS NOT NULL"#,
        r#"ALTER TABLE user_passkey ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()"#,
    ];

    for stmt in stmts {
        if let Err(e) = sqlx::query(stmt).execute(pool).await {
            tracing::debug!("Schema migration note for '{}': {}", stmt, e);
        }
    }
}

// ==========================================
// Accounts
// ==========================================

pub async fn list_accounts(pool: &PgPool, user_id: &str) -> Result<Vec<Account>, sqlx::Error> {
    list_accounts_as_of(pool, user_id, None).await
}

pub async fn list_accounts_as_of(
    pool: &PgPool,
    user_id: &str,
    as_of: Option<chrono::NaiveDate>,
) -> Result<Vec<Account>, sqlx::Error> {
    match as_of {
        Some(date) => {
            let cutoff_sec = date.and_hms_opt(23, 59, 59).unwrap().and_utc().timestamp();
            sqlx::query_as::<_, Account>(
                r#"
                SELECT 
                    id, name, nickname, type as account_type, 
                    COALESCE(user_id, userid, "userId") as user_id,
                    account_limit, interest_rate, ledger_mode, archived
                FROM account
                WHERE COALESCE(user_id, userid, "userId") = $1
                  AND (created_at IS NULL OR (CASE WHEN created_at > 100000000000 THEN created_at/1000 ELSE created_at END) <= $2)
                  AND (archived IS FALSE OR archived_at IS NULL OR (CASE WHEN archived_at > 100000000000 THEN archived_at/1000 ELSE archived_at END) > $2)
                ORDER BY id ASC
                "#,
            )
            .bind(user_id)
            .bind(cutoff_sec)
            .fetch_all(pool)
            .await
        }
        None => {
            sqlx::query_as::<_, Account>(
                r#"
                SELECT 
                    id, name, nickname, type as account_type, 
                    COALESCE(user_id, userid, "userId") as user_id,
                    account_limit, interest_rate, ledger_mode, archived
                FROM account
                WHERE COALESCE(user_id, userid, "userId") = $1
                  AND (archived IS FALSE OR archived IS NULL)
                ORDER BY id ASC
                "#,
            )
            .bind(user_id)
            .fetch_all(pool)
            .await
        }
    }
}

pub async fn get_account(pool: &PgPool, id: i64, user_id: &str) -> Result<Account, sqlx::Error> {
    sqlx::query_as::<_, Account>(
        r#"
        SELECT 
            id, name, nickname, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        FROM account
        WHERE id = $1 AND COALESCE(user_id, userid, "userId") = $2
        "#,
    )
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn create_account(pool: &PgPool, acc: &NewAccount) -> Result<Account, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Account>(
        r#"
        INSERT INTO account (id, name, nickname, type, user_id, userid, "userId", account_limit, interest_rate, archived, ledger_mode, created_at, updated_at)
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4, $5, $6, FALSE, 'TBL', $7, $7)
        RETURNING 
            id, name, nickname, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        "#,
    )
    .bind(&acc.name)
    .bind(&acc.nickname)
    .bind(&acc.account_type)
    .bind(&acc.user_id)
    .bind(acc.account_limit)
    .bind(acc.interest_rate)
    .bind(now_epoch)
    .fetch_one(pool)
    .await
}

pub async fn update_account(pool: &PgPool, id: i64, user_id: &str, acc: &UpdateAccount) -> Result<Account, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Account>(
        r#"
        UPDATE account
        SET name = $1, nickname = $2, type = $3, account_limit = $4, interest_rate = $5, updated_at = $6
        WHERE id = $7 AND COALESCE(user_id, userid, "userId") = $8
        RETURNING 
            id, name, nickname, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        "#,
    )
    .bind(&acc.name)
    .bind(&acc.nickname)
    .bind(&acc.account_type)
    .bind(acc.account_limit)
    .bind(acc.interest_rate)
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn archive_account(pool: &PgPool, id: i64, user_id: &str) -> Result<Account, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Account>(
        r#"
        UPDATE account
        SET archived = TRUE, archived_at = $1, updated_at = $1
        WHERE id = $2 AND COALESCE(user_id, userid, "userId") = $3
        RETURNING 
            id, name, nickname, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        "#,
    )
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn toggle_ledger_mode(pool: &PgPool, id: i64, user_id: &str) -> Result<Account, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Account>(
        r#"
        UPDATE account
        SET ledger_mode = CASE WHEN ledger_mode = 'ABL' THEN 'TBL' ELSE 'ABL' END, updated_at = $1
        WHERE id = $2 AND COALESCE(user_id, userid, "userId") = $3
        RETURNING 
            id, name, nickname, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        "#,
    )
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

// ==========================================
// Incomings
// ==========================================

pub async fn list_incomings(pool: &PgPool, user_id: &str) -> Result<Vec<Incoming>, sqlx::Error> {
    list_incomings_as_of(pool, user_id, None).await
}

pub async fn list_incomings_as_of(
    pool: &PgPool,
    user_id: &str,
    as_of: Option<chrono::NaiveDate>,
) -> Result<Vec<Incoming>, sqlx::Error> {
    match as_of {
        Some(date) => {
            let cutoff_sec = date.and_hms_opt(23, 59, 59).unwrap().and_utc().timestamp();
            let raw_rows = sqlx::query(
                r#"
                SELECT 
                    i.id, i.name, i.netvalue as net_value, i.grossvalue as gross_value,
                    i.incomingmonthday as incoming_month_day, i.payday, i.type as incoming_type,
                    COALESCE(i.user_id, i.userid, i."userId") as user_id,
                    i.account_id, a.name as account_name, i.archived,
                    i.history_json
                FROM incoming i
                LEFT JOIN account a ON i.account_id = a.id
                WHERE COALESCE(i.user_id, i.userid, i."userId") = $1
                  AND (i.created_at IS NULL OR i.created_at = 0 OR (CASE WHEN i.created_at > 100000000000 THEN i.created_at/1000 ELSE i.created_at END) <= $2)
                  AND (i.archived IS FALSE OR i.archived_at IS NULL OR (CASE WHEN i.archived_at > 100000000000 THEN i.archived_at/1000 ELSE i.archived_at END) > $2)
                ORDER BY i.incomingmonthday ASC, i.id ASC
                "#,
            )
            .bind(user_id)
            .bind(cutoff_sec)
            .fetch_all(pool)
            .await?;

            let mut result = Vec::new();
            for r in raw_rows {
                let id: i64 = r.get("id");
                let mut name: String = r.get("name");
                let mut net_value: f32 = r.get::<Option<f32>, _>("net_value").unwrap_or(0.0);
                let mut gross_value: Option<f32> = r.get("gross_value");
                let mut incoming_month_day: i32 = r.get("incoming_month_day");
                let mut payday: Option<bool> = r.get("payday");
                let incoming_type: Option<String> = r.get("incoming_type");
                let user_id: String = r.get("user_id");
                let account_id: Option<i64> = r.get("account_id");
                let account_name: Option<String> = r.get("account_name");
                let archived: Option<bool> = r.get("archived");
                let history_json: Option<String> = r.get("history_json");

                if let Some(ref json_str) = history_json {
                    if let Ok(entries) = serde_json::from_str::<Vec<serde_json::Value>>(json_str) {
                        for entry in entries {
                            if let Some(ts_val) = entry.get("timestamp").and_then(|v| v.as_i64()) {
                                let ts_sec = if ts_val > 100_000_000_000 { ts_val / 1000 } else { ts_val };
                                if ts_sec <= cutoff_sec {
                                    if let Some(nv) = entry.get("netValue").and_then(|v| v.as_f64()) {
                                        net_value = nv as f32;
                                    }
                                    if let Some(gv) = entry.get("grossValue").and_then(|v| v.as_f64()) {
                                        gross_value = Some(gv as f32);
                                    }
                                    if let Some(n) = entry.get("name").and_then(|v| v.as_str()) {
                                        name = n.to_string();
                                    }
                                    if let Some(imd) = entry.get("incomingMonthDay").and_then(|v| v.as_i64()) {
                                        incoming_month_day = imd as i32;
                                    }
                                    if let Some(pd) = entry.get("payDay").and_then(|v| v.as_bool()) {
                                        payday = Some(pd);
                                    }
                                }
                            }
                        }
                    }
                }

                result.push(Incoming {
                    id,
                    name,
                    net_value,
                    gross_value,
                    incoming_month_day,
                    payday,
                    incoming_type,
                    user_id,
                    account_id,
                    account_name,
                    archived,
                });
            }
            Ok(result)
        }
        None => {
            sqlx::query_as::<_, Incoming>(
                r#"
                SELECT 
                    i.id, i.name, i.netvalue as net_value, i.grossvalue as gross_value,
                    i.incomingmonthday as incoming_month_day, i.payday, i.type as incoming_type,
                    COALESCE(i.user_id, i.userid, i."userId") as user_id,
                    i.account_id, a.name as account_name, i.archived
                FROM incoming i
                LEFT JOIN account a ON i.account_id = a.id
                WHERE COALESCE(i.user_id, i.userid, i."userId") = $1
                  AND (i.archived IS FALSE OR i.archived IS NULL)
                ORDER BY i.incomingmonthday ASC, i.id ASC
                "#,
            )
            .bind(user_id)
            .fetch_all(pool)
            .await
        }
    }
}

pub async fn get_incoming(pool: &PgPool, id: i64, user_id: &str) -> Result<Incoming, sqlx::Error> {
    sqlx::query_as::<_, Incoming>(
        r#"
        SELECT 
            i.id, i.name, i.netvalue as net_value, i.grossvalue as gross_value,
            i.incomingmonthday as incoming_month_day, i.payday, i.type as incoming_type,
            COALESCE(i.user_id, i.userid, i."userId") as user_id,
            i.account_id, a.name as account_name, i.archived
        FROM incoming i
        LEFT JOIN account a ON i.account_id = a.id
        WHERE i.id = $1 AND COALESCE(i.user_id, i.userid, i."userId") = $2
        "#,
    )
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn create_incoming(pool: &PgPool, inc: &NewIncoming) -> Result<Incoming, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Incoming>(
        r#"
        INSERT INTO incoming (
            id, name, netvalue, grossvalue, incomingmonthday, payday, 
            user_id, userid, "userId", account_id, created_at, updated_at, archived
        )
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $5, $6, $6, $6, $7, $8, $8, FALSE)
        RETURNING 
            id, name, netvalue as net_value, grossvalue as gross_value,
            incomingmonthday as incoming_month_day, payday, type as incoming_type,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, archived
        "#,
    )
    .bind(&inc.name)
    .bind(inc.net_value)
    .bind(inc.gross_value)
    .bind(inc.incoming_month_day)
    .bind(inc.payday.unwrap_or(false))
    .bind(&inc.user_id)
    .bind(inc.account_id)
    .bind(now_epoch)
    .fetch_one(pool)
    .await
}

pub async fn update_incoming(pool: &PgPool, id: i64, user_id: &str, inc: &UpdateIncoming) -> Result<Incoming, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Incoming>(
        r#"
        UPDATE incoming
        SET name = $1, netvalue = $2, grossvalue = $3, incomingmonthday = $4, payday = $5, account_id = $6, updated_at = $7
        WHERE id = $8 AND COALESCE(user_id, userid, "userId") = $9
        RETURNING 
            id, name, netvalue as net_value, grossvalue as gross_value,
            incomingmonthday as incoming_month_day, payday, type as incoming_type,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, archived
        "#,
    )
    .bind(&inc.name)
    .bind(inc.net_value)
    .bind(inc.gross_value)
    .bind(inc.incoming_month_day)
    .bind(inc.payday.unwrap_or(false))
    .bind(inc.account_id)
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn archive_incoming(pool: &PgPool, id: i64, user_id: &str) -> Result<Incoming, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Incoming>(
        r#"
        UPDATE incoming
        SET archived = TRUE, archived_at = $1, updated_at = $1
        WHERE id = $2 AND COALESCE(user_id, userid, "userId") = $3
        RETURNING 
            id, name, netvalue as net_value, grossvalue as gross_value,
            incomingmonthday as incoming_month_day, payday, type as incoming_type,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, archived
        "#,
    )
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

// ==========================================
// Outgoings
// ==========================================

pub async fn list_outgoings(pool: &PgPool, user_id: &str) -> Result<Vec<Outgoing>, sqlx::Error> {
    list_outgoings_as_of(pool, user_id, None).await
}

pub async fn list_outgoings_as_of(
    pool: &PgPool,
    user_id: &str,
    as_of: Option<chrono::NaiveDate>,
) -> Result<Vec<Outgoing>, sqlx::Error> {
    match as_of {
        Some(date) => {
            let cutoff_sec = date.and_hms_opt(23, 59, 59).unwrap().and_utc().timestamp();
            let year = date.year();
            let mut rows = sqlx::query_as::<_, Outgoing>(
                r#"
                SELECT 
                    o.id, o.name, o.cost, o.outgoingday as outgoing_day, 
                    o.outgoingmonth as outgoing_month, o.yearinterval as year_interval, 
                    o.startingyear as starting_year, o.bill, o.rent, o.hiddenfromtotal as hidden_from_total,
                    o.paused, COALESCE(o.pause_reason, o.pausereason) as pause_reason,
                    COALESCE(o.paused_at, o.pausedat) as paused_at,
                    COALESCE(o.user_id, o.userid, o."userId") as user_id,
                    o.account_id,
                    a.name as account_name,
                    a.type as account_type,
                    o.archived
                FROM outgoing o
                LEFT JOIN account a ON o.account_id = a.id
                WHERE COALESCE(o.user_id, o.userid, o."userId") = $1
                  AND (o.created_at IS NULL OR (CASE WHEN o.created_at > 100000000000 THEN o.created_at/1000 ELSE o.created_at END) <= $2)
                  AND (o.archived_at IS NULL OR (CASE WHEN o.archived_at > 100000000000 THEN o.archived_at/1000 ELSE o.archived_at END) > $2)
                  AND (o.startingyear IS NULL OR o.startingyear <= $3)
                ORDER BY o.outgoingday ASC, o.id ASC
                "#,
            )
            .bind(user_id)
            .bind(cutoff_sec)
            .bind(year)
            .fetch_all(pool)
            .await?;

            for o in &mut rows {
                if o.paused == Some(true) {
                    let p_at = o.paused_at.map(|p| if p > 100_000_000_000 { p / 1000 } else { p });
                    if let Some(pa) = p_at {
                        if pa > cutoff_sec {
                            o.paused = Some(false);
                            o.pause_reason = None;
                        }
                    }
                }
            }
            Ok(rows)
        }
        None => {
            sqlx::query_as::<_, Outgoing>(
                r#"
                SELECT 
                    o.id, o.name, o.cost, o.outgoingday as outgoing_day, 
                    o.outgoingmonth as outgoing_month, o.yearinterval as year_interval, 
                    o.startingyear as starting_year, o.bill, o.rent, o.hiddenfromtotal as hidden_from_total,
                    o.paused, COALESCE(o.pause_reason, o.pausereason) as pause_reason,
                    COALESCE(o.paused_at, o.pausedat) as paused_at,
                    COALESCE(o.user_id, o.userid, o."userId") as user_id,
                    o.account_id,
                    a.name as account_name,
                    a.type as account_type,
                    o.archived
                FROM outgoing o
                LEFT JOIN account a ON o.account_id = a.id
                WHERE COALESCE(o.user_id, o.userid, o."userId") = $1
                  AND (o.archived IS FALSE OR o.archived IS NULL)
                ORDER BY o.outgoingday ASC, o.id ASC
                "#,
            )
            .bind(user_id)
            .fetch_all(pool)
            .await
        }
    }
}

pub async fn list_rents(pool: &PgPool, user_id: &str) -> Result<Vec<Outgoing>, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        SELECT 
            o.id, o.name, o.cost, o.outgoingday as outgoing_day, 
            o.outgoingmonth as outgoing_month, o.yearinterval as year_interval, 
            o.startingyear as starting_year, o.bill, o.rent, o.hiddenfromtotal as hidden_from_total,
            o.paused, COALESCE(o.pause_reason, o.pausereason) as pause_reason,
            COALESCE(o.paused_at, o.pausedat) as paused_at,
            COALESCE(o.user_id, o.userid, o."userId") as user_id,
            o.account_id,
            a.name as account_name,
            a.type as account_type,
            o.archived
        FROM outgoing o
        LEFT JOIN account a ON o.account_id = a.id
        WHERE COALESCE(o.user_id, o.userid, o."userId") = $1
          AND o.rent IS TRUE
          AND (o.paused IS FALSE OR o.paused IS NULL)
          AND (o.archived IS FALSE OR o.archived IS NULL)
        ORDER BY o.outgoingday ASC, o.id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}

pub async fn list_bills(pool: &PgPool, user_id: &str) -> Result<Vec<Outgoing>, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        SELECT 
            o.id, o.name, o.cost, o.outgoingday as outgoing_day, 
            o.outgoingmonth as outgoing_month, o.yearinterval as year_interval, 
            o.startingyear as starting_year, o.bill, o.rent, o.hiddenfromtotal as hidden_from_total,
            o.paused, COALESCE(o.pause_reason, o.pausereason) as pause_reason,
            COALESCE(o.paused_at, o.pausedat) as paused_at,
            COALESCE(o.user_id, o.userid, o."userId") as user_id,
            o.account_id,
            a.name as account_name,
            a.type as account_type,
            o.archived
        FROM outgoing o
        LEFT JOIN account a ON o.account_id = a.id
        WHERE COALESCE(o.user_id, o.userid, o."userId") = $1
          AND o.bill IS TRUE
          AND (o.paused IS FALSE OR o.paused IS NULL)
          AND (o.archived IS FALSE OR o.archived IS NULL)
        ORDER BY o.outgoingday ASC, o.id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}

pub async fn get_outgoing(pool: &PgPool, id: i64, user_id: &str) -> Result<Outgoing, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        SELECT 
            o.id, o.name, o.cost, o.outgoingday as outgoing_day, 
            o.outgoingmonth as outgoing_month, o.yearinterval as year_interval, 
            o.startingyear as starting_year, o.bill, o.rent, o.hiddenfromtotal as hidden_from_total,
            o.paused, COALESCE(o.pause_reason, o.pausereason) as pause_reason,
            COALESCE(o.paused_at, o.pausedat) as paused_at,
            COALESCE(o.user_id, o.userid, o."userId") as user_id,
            o.account_id,
            a.name as account_name,
            a.type as account_type,
            o.archived
        FROM outgoing o
        LEFT JOIN account a ON o.account_id = a.id
        WHERE o.id = $1 AND COALESCE(o.user_id, o.userid, o."userId") = $2
        "#,
    )
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn create_outgoing(pool: &PgPool, out: &NewOutgoing) -> Result<Outgoing, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Outgoing>(
        r#"
        INSERT INTO outgoing (
            id, name, cost, outgoingday, 
            user_id, userid, "userId", account_id, 
            outgoingmonth, yearinterval, startingyear, bill, rent, hiddenfromtotal, 
            paused, archived, created_at, updated_at
        )
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4, $5, $6, $7, $8, $9, $10, $11, FALSE, FALSE, $12, $12)
        RETURNING 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(paused_at, pausedat) as paused_at,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, NULL::text as account_type, archived
        "#,
    )
    .bind(&out.name)
    .bind(out.cost)
    .bind(out.outgoing_day)
    .bind(&out.user_id)
    .bind(out.account_id)
    .bind(out.outgoing_month.unwrap_or(0))
    .bind(out.year_interval.unwrap_or(1))
    .bind(out.starting_year)
    .bind(out.bill.unwrap_or(false))
    .bind(out.rent.unwrap_or(false))
    .bind(out.hidden_from_total.unwrap_or(false))
    .bind(now_epoch)
    .fetch_one(pool)
    .await
}

pub async fn update_outgoing(pool: &PgPool, id: i64, user_id: &str, out: &UpdateOutgoing) -> Result<Outgoing, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Outgoing>(
        r#"
        UPDATE outgoing
        SET name = $1, cost = $2, outgoingday = $3, outgoingmonth = $4,
            yearinterval = $5, startingyear = $6, bill = $7, rent = $8,
            hiddenfromtotal = $9, account_id = $10, updated_at = $11
        WHERE id = $12 AND COALESCE(user_id, userid, "userId") = $13
        RETURNING 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(paused_at, pausedat) as paused_at,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, NULL::text as account_type, archived
        "#,
    )
    .bind(&out.name)
    .bind(out.cost)
    .bind(out.outgoing_day)
    .bind(out.outgoing_month.unwrap_or(0))
    .bind(out.year_interval.unwrap_or(1))
    .bind(out.starting_year)
    .bind(out.bill.unwrap_or(false))
    .bind(out.rent.unwrap_or(false))
    .bind(out.hidden_from_total.unwrap_or(false))
    .bind(out.account_id)
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn archive_outgoing(pool: &PgPool, id: i64, user_id: &str) -> Result<Outgoing, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Outgoing>(
        r#"
        UPDATE outgoing
        SET archived = TRUE, archived_at = $1, updated_at = $1
        WHERE id = $2 AND COALESCE(user_id, userid, "userId") = $3
        RETURNING 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(paused_at, pausedat) as paused_at,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, NULL::text as account_type, archived
        "#,
    )
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn pause_outgoing(pool: &PgPool, id: i64, user_id: &str, reason: &str) -> Result<Outgoing, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Outgoing>(
        r#"
        UPDATE outgoing
        SET paused = TRUE, 
            pause_reason = $1, pausereason = $1, 
            paused_at = $2, pausedat = $2, updated_at = $2
        WHERE id = $3 AND COALESCE(user_id, userid, "userId") = $4
        RETURNING 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(paused_at, pausedat) as paused_at,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, NULL::text as account_type, archived
        "#,
    )
    .bind(reason)
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn unpause_outgoing(pool: &PgPool, id: i64, user_id: &str) -> Result<Outgoing, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Outgoing>(
        r#"
        UPDATE outgoing
        SET paused = FALSE, 
            pause_reason = NULL, pausereason = NULL, 
            paused_at = NULL, pausedat = NULL, updated_at = $1
        WHERE id = $2 AND COALESCE(user_id, userid, "userId") = $3
        RETURNING 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(paused_at, pausedat) as paused_at,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, NULL::text as account_type, archived
        "#,
    )
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

// ==========================================
// Balances
// ==========================================

pub async fn list_balances(pool: &PgPool, user_id: &str) -> Result<Vec<Balance>, sqlx::Error> {
    sqlx::query_as::<_, Balance>(
        r#"
        SELECT 
            id, COALESCE(value, 0.0)::real AS value, COALESCE(timestamp, 0)::bigint AS timestamp, account_id,
            COALESCE(user_id, userid, "userId") as user_id
        FROM balance
        WHERE COALESCE(user_id, userid, "userId") = $1
        ORDER BY timestamp DESC, id DESC
        "#,
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}

pub async fn create_balance(pool: &PgPool, bal: &NewBalance) -> Result<Balance, sqlx::Error> {
    let ts = bal.timestamp.unwrap_or_else(|| Utc::now().timestamp());
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Balance>(
        r#"
        INSERT INTO balance (id, value, timestamp, account_id, user_id, userid, "userId", created_at, updated_at)
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4, $5, $5)
        RETURNING 
            id, value, timestamp, account_id,
            COALESCE(user_id, userid, "userId") as user_id
        "#,
    )
    .bind(bal.value)
    .bind(ts)
    .bind(bal.account_id)
    .bind(&bal.user_id)
    .bind(now_epoch)
    .fetch_one(pool)
    .await
}

pub async fn delete_balance(pool: &PgPool, id: i64, user_id: &str) -> Result<bool, sqlx::Error> {
    let res = sqlx::query(
        r#"
        DELETE FROM balance
        WHERE id = $1 AND COALESCE(user_id, userid, "userId") = $2
        "#,
    )
    .bind(id)
    .bind(user_id)
    .execute(pool)
    .await?;
    Ok(res.rows_affected() > 0)
}

pub async fn find_erratic_balances(
    pool: &PgPool,
    user_id: &str,
    threshold: f32,
) -> Result<Vec<ErraticBalance>, sqlx::Error> {
    let rows = sqlx::query(
        r#"
        WITH ranked_balances AS (
            SELECT 
                b.id,
                b.account_id,
                a.name as account_name,
                b.value,
                b.timestamp,
                LAG(b.id) OVER (PARTITION BY b.account_id ORDER BY b.timestamp ASC, b.id ASC) as prev_id,
                LAG(b.value) OVER (PARTITION BY b.account_id ORDER BY b.timestamp ASC, b.id ASC) as prev_value
            FROM balance b
            JOIN account a ON b.account_id = a.id
            WHERE COALESCE(b.user_id, b.userid, b."userId") = $1
        )
        SELECT 
            account_id,
            account_name,
            prev_id,
            id as curr_id,
            prev_value,
            value as curr_value,
            ABS(value - prev_value) as jump_amount,
            timestamp
        FROM ranked_balances
        WHERE prev_value IS NOT NULL
          AND ABS(value - prev_value) >= $2
        ORDER BY timestamp DESC
        "#,
    )
    .bind(user_id)
    .bind(threshold)
    .fetch_all(pool)
    .await?;

    let results = rows
        .into_iter()
        .map(|r| ErraticBalance {
            account_id: r.get("account_id"),
            account_name: r.get("account_name"),
            prev_balance_id: r.get("prev_id"),
            curr_balance_id: r.get("curr_id"),
            prev_value: r.get("prev_value"),
            curr_value: r.get("curr_value"),
            jump_amount: r.get("jump_amount"),
            timestamp: r.get("timestamp"),
        })
        .collect();

    Ok(results)
}
