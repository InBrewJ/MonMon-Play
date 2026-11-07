use crate::models::{Account, Balance, ErraticBalance, Incoming, NewAccount, NewBalance, NewIncoming, NewOutgoing, Outgoing};
use chrono::Utc;
use sqlx::{PgPool, Row};

pub async fn list_accounts(pool: &PgPool, user_id: &str) -> Result<Vec<Account>, sqlx::Error> {
    sqlx::query_as::<_, Account>(
        r#"
        SELECT 
            id, name, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        FROM account
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND (archived IS FALSE OR archived IS NULL)
        ORDER BY id ASC
        "#
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}

pub async fn create_account(pool: &PgPool, acc: &NewAccount) -> Result<Account, sqlx::Error> {
    sqlx::query_as::<_, Account>(
        r#"
        INSERT INTO account (id, name, type, user_id, userid, "userId", account_limit, interest_rate, archived)
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $3, $3, $4, $5, FALSE)
        RETURNING 
            id, name, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        "#
    )
    .bind(&acc.name)
    .bind(&acc.account_type)
    .bind(&acc.user_id)
    .bind(acc.account_limit)
    .bind(acc.interest_rate)
    .fetch_one(pool)
    .await
}

pub async fn list_incomings(pool: &PgPool, user_id: &str) -> Result<Vec<Incoming>, sqlx::Error> {
    sqlx::query_as::<_, Incoming>(
        r#"
        SELECT 
            id, name, netvalue as net_value, grossvalue as gross_value,
            incomingmonthday as incoming_month_day, payday, type as incoming_type,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, archived
        FROM incoming
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND (archived IS FALSE OR archived IS NULL)
        ORDER BY incomingmonthday ASC, id ASC
        "#
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}

pub async fn create_incoming(pool: &PgPool, inc: &NewIncoming) -> Result<Incoming, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Incoming>(
        r#"
        INSERT INTO incoming (
            id, name, netvalue, incomingmonthday, payday, 
            user_id, userid, "userId", account_id, created_at, archived
        )
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $5, $5, $5, $6, $7, FALSE)
        RETURNING 
            id, name, netvalue as net_value, grossvalue as gross_value,
            incomingmonthday as incoming_month_day, payday, type as incoming_type,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, archived
        "#
    )
    .bind(&inc.name)
    .bind(inc.net_value)
    .bind(inc.incoming_month_day)
    .bind(inc.payday.unwrap_or(false))
    .bind(&inc.user_id)
    .bind(inc.account_id)
    .bind(now_epoch)
    .fetch_one(pool)
    .await
}

pub async fn list_outgoings(pool: &PgPool, user_id: &str) -> Result<Vec<Outgoing>, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        SELECT 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, archived
        FROM outgoing
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND (archived IS FALSE OR archived IS NULL)
        ORDER BY outgoingday ASC, id ASC
        "#
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}

pub async fn create_outgoing(pool: &PgPool, out: &NewOutgoing) -> Result<Outgoing, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        INSERT INTO outgoing (
            id, name, cost, outgoingday, 
            user_id, userid, "userId", account_id, 
            outgoingmonth, bill, rent, hiddenfromtotal, 
            paused, archived
        )
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4, $5, $6, $7, $8, $9, FALSE, FALSE)
        RETURNING 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, archived
        "#
    )
    .bind(&out.name)
    .bind(out.cost)
    .bind(out.outgoing_day)
    .bind(&out.user_id)
    .bind(out.account_id)
    .bind(out.outgoing_month.unwrap_or(0))
    .bind(out.bill.unwrap_or(false))
    .bind(out.rent.unwrap_or(false))
    .bind(out.hidden_from_total.unwrap_or(false))
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
            paused_at = $2, pausedat = $2
        WHERE id = $3 AND COALESCE(user_id, userid, "userId") = $4
        RETURNING 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, archived
        "#
    )
    .bind(reason)
    .bind(now_epoch)
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn unpause_outgoing(pool: &PgPool, id: i64, user_id: &str) -> Result<Outgoing, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        UPDATE outgoing
        SET paused = FALSE, 
            pause_reason = NULL, pausereason = NULL, 
            paused_at = NULL, pausedat = NULL
        WHERE id = $1 AND COALESCE(user_id, userid, "userId") = $2
        RETURNING 
            id, name, cost, outgoingday as outgoing_day, 
            outgoingmonth as outgoing_month, yearinterval as year_interval, 
            startingyear as starting_year, bill, rent, hiddenfromtotal as hidden_from_total,
            paused, COALESCE(pause_reason, pausereason) as pause_reason,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, archived
        "#
    )
    .bind(id)
    .bind(user_id)
    .fetch_one(pool)
    .await
}

pub async fn list_balances(pool: &PgPool, user_id: &str) -> Result<Vec<Balance>, sqlx::Error> {
    sqlx::query_as::<_, Balance>(
        r#"
        SELECT 
            id, value, timestamp, account_id,
            COALESCE(user_id, userid, "userId") as user_id
        FROM balance
        WHERE COALESCE(user_id, userid, "userId") = $1
        ORDER BY timestamp DESC, id DESC
        "#
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
}

pub async fn create_balance(pool: &PgPool, b: &NewBalance) -> Result<Balance, sqlx::Error> {
    let ts = b.timestamp.unwrap_or_else(|| Utc::now().timestamp());
    sqlx::query_as::<_, Balance>(
        r#"
        INSERT INTO balance (id, value, timestamp, user_id, userid, "userId", account_id)
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $3, $3, $4)
        RETURNING 
            id, value, timestamp, account_id,
            COALESCE(user_id, userid, "userId") as user_id
        "#
    )
    .bind(b.value)
    .bind(ts)
    .bind(&b.user_id)
    .bind(b.account_id)
    .fetch_one(pool)
    .await
}

pub async fn detect_erratic_balances(
    pool: &PgPool,
    user_id: &str,
    threshold: f32,
) -> Result<Vec<ErraticBalance>, sqlx::Error> {
    // Favor heavy aggregation in PostgreSQL per AGENTS.md!
    let rows = sqlx::query(
        r#"
        WITH ranked_balances AS (
            SELECT 
                b.id, b.value, b.timestamp, b.account_id,
                a.name as account_name,
                LAG(b.id) OVER (PARTITION BY b.account_id ORDER BY b.timestamp ASC, b.id ASC) as prev_id,
                LAG(b.value) OVER (PARTITION BY b.account_id ORDER BY b.timestamp ASC, b.id ASC) as prev_value
            FROM balance b
            JOIN account a ON a.id = b.account_id
            WHERE COALESCE(b.user_id, b.userid, b."userId") = $1
        )
        SELECT 
            account_id, account_name, prev_id, id as curr_id,
            prev_value, value as curr_value,
            ABS(value - prev_value) as jump_amount,
            timestamp
        FROM ranked_balances
        WHERE prev_value IS NOT NULL 
          AND ABS(value - prev_value) >= $2
        ORDER BY timestamp DESC
        "#
    )
    .bind(user_id)
    .bind(threshold)
    .fetch_all(pool)
    .await?;

    let mut result = Vec::new();
    for row in rows {
        result.push(ErraticBalance {
            account_id: row.get("account_id"),
            account_name: row.get("account_name"),
            prev_balance_id: row.get("prev_id"),
            curr_balance_id: row.get("curr_id"),
            prev_value: row.get("prev_value"),
            curr_value: row.get("curr_value"),
            jump_amount: row.get("jump_amount"),
            timestamp: row.get("timestamp"),
        });
    }

    Ok(result)
}
