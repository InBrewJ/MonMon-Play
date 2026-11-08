use crate::models::{
    Account, Balance, ErraticBalance, Incoming, NewAccount, NewBalance, NewIncoming, NewOutgoing,
    Outgoing, UpdateAccount, UpdateIncoming, UpdateOutgoing,
};
use chrono::Utc;
use sqlx::{PgPool, Row};

// ==========================================
// Accounts
// ==========================================

pub async fn list_accounts(pool: &PgPool, user_id: &str) -> Result<Vec<Account>, sqlx::Error> {
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

pub async fn get_account(pool: &PgPool, id: i64) -> Result<Account, sqlx::Error> {
    sqlx::query_as::<_, Account>(
        r#"
        SELECT 
            id, name, nickname, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        FROM account
        WHERE id = $1
        "#,
    )
    .bind(id)
    .fetch_one(pool)
    .await
}

pub async fn create_account(pool: &PgPool, acc: &NewAccount) -> Result<Account, sqlx::Error> {
    sqlx::query_as::<_, Account>(
        r#"
        INSERT INTO account (id, name, nickname, type, user_id, userid, "userId", account_limit, interest_rate, archived, ledger_mode)
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4, $5, $6, FALSE, 'TBL')
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
    .fetch_one(pool)
    .await
}

pub async fn update_account(pool: &PgPool, id: i64, acc: &UpdateAccount) -> Result<Account, sqlx::Error> {
    sqlx::query_as::<_, Account>(
        r#"
        UPDATE account
        SET name = $1, nickname = $2, type = $3, account_limit = $4, interest_rate = $5
        WHERE id = $6
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
    .bind(id)
    .fetch_one(pool)
    .await
}

pub async fn archive_account(pool: &PgPool, id: i64) -> Result<Account, sqlx::Error> {
    sqlx::query_as::<_, Account>(
        r#"
        UPDATE account
        SET archived = TRUE
        WHERE id = $1
        RETURNING 
            id, name, nickname, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        "#,
    )
    .bind(id)
    .fetch_one(pool)
    .await
}

pub async fn toggle_ledger_mode(pool: &PgPool, id: i64) -> Result<Account, sqlx::Error> {
    sqlx::query_as::<_, Account>(
        r#"
        UPDATE account
        SET ledger_mode = CASE WHEN ledger_mode = 'ABL' THEN 'TBL' ELSE 'ABL' END
        WHERE id = $1
        RETURNING 
            id, name, nickname, type as account_type, 
            COALESCE(user_id, userid, "userId") as user_id,
            account_limit, interest_rate, ledger_mode, archived
        "#,
    )
    .bind(id)
    .fetch_one(pool)
    .await
}

// ==========================================
// Incomings
// ==========================================

pub async fn list_incomings(pool: &PgPool, user_id: &str) -> Result<Vec<Incoming>, sqlx::Error> {
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

pub async fn get_incoming(pool: &PgPool, id: i64) -> Result<Incoming, sqlx::Error> {
    sqlx::query_as::<_, Incoming>(
        r#"
        SELECT 
            i.id, i.name, i.netvalue as net_value, i.grossvalue as gross_value,
            i.incomingmonthday as incoming_month_day, i.payday, i.type as incoming_type,
            COALESCE(i.user_id, i.userid, i."userId") as user_id,
            i.account_id, a.name as account_name, i.archived
        FROM incoming i
        LEFT JOIN account a ON i.account_id = a.id
        WHERE i.id = $1
        "#,
    )
    .bind(id)
    .fetch_one(pool)
    .await
}

pub async fn create_incoming(pool: &PgPool, inc: &NewIncoming) -> Result<Incoming, sqlx::Error> {
    let now_epoch = Utc::now().timestamp_millis();
    sqlx::query_as::<_, Incoming>(
        r#"
        INSERT INTO incoming (
            id, name, netvalue, grossvalue, incomingmonthday, payday, 
            user_id, userid, "userId", account_id, created_at, archived
        )
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $5, $6, $6, $6, $7, $8, FALSE)
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

pub async fn update_incoming(pool: &PgPool, id: i64, inc: &UpdateIncoming) -> Result<Incoming, sqlx::Error> {
    sqlx::query_as::<_, Incoming>(
        r#"
        UPDATE incoming
        SET name = $1, netvalue = $2, grossvalue = $3, incomingmonthday = $4, payday = $5, account_id = $6
        WHERE id = $7
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
    .bind(id)
    .fetch_one(pool)
    .await
}

pub async fn archive_incoming(pool: &PgPool, id: i64) -> Result<Incoming, sqlx::Error> {
    sqlx::query_as::<_, Incoming>(
        r#"
        UPDATE incoming
        SET archived = TRUE
        WHERE id = $1
        RETURNING 
            id, name, netvalue as net_value, grossvalue as gross_value,
            incomingmonthday as incoming_month_day, payday, type as incoming_type,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, archived
        "#,
    )
    .bind(id)
    .fetch_one(pool)
    .await
}

// ==========================================
// Outgoings
// ==========================================

pub async fn list_outgoings(pool: &PgPool, user_id: &str) -> Result<Vec<Outgoing>, sqlx::Error> {
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

pub async fn get_outgoing(pool: &PgPool, id: i64) -> Result<Outgoing, sqlx::Error> {
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
        WHERE o.id = $1
        "#,
    )
    .bind(id)
    .fetch_one(pool)
    .await
}

pub async fn create_outgoing(pool: &PgPool, out: &NewOutgoing) -> Result<Outgoing, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        INSERT INTO outgoing (
            id, name, cost, outgoingday, 
            user_id, userid, "userId", account_id, 
            outgoingmonth, yearinterval, startingyear, bill, rent, hiddenfromtotal, 
            paused, archived
        )
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4, $5, $6, $7, $8, $9, $10, $11, FALSE, FALSE)
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
    .fetch_one(pool)
    .await
}

pub async fn update_outgoing(pool: &PgPool, id: i64, out: &UpdateOutgoing) -> Result<Outgoing, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        UPDATE outgoing
        SET name = $1, cost = $2, outgoingday = $3, outgoingmonth = $4,
            yearinterval = $5, startingyear = $6, bill = $7, rent = $8,
            hiddenfromtotal = $9, account_id = $10
        WHERE id = $11
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
    .bind(id)
    .fetch_one(pool)
    .await
}

pub async fn archive_outgoing(pool: &PgPool, id: i64) -> Result<Outgoing, sqlx::Error> {
    sqlx::query_as::<_, Outgoing>(
        r#"
        UPDATE outgoing
        SET archived = TRUE
        WHERE id = $1
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
    .bind(id)
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
            COALESCE(paused_at, pausedat) as paused_at,
            COALESCE(user_id, userid, "userId") as user_id,
            account_id, NULL::text as account_name, NULL::text as account_type, archived
        "#,
    )
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
            id, value, timestamp, account_id,
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
    sqlx::query_as::<_, Balance>(
        r#"
        INSERT INTO balance (id, value, timestamp, account_id, user_id, userid, "userId")
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $4, $4)
        RETURNING 
            id, value, timestamp, account_id,
            COALESCE(user_id, userid, "userId") as user_id
        "#,
    )
    .bind(bal.value)
    .bind(ts)
    .bind(bal.account_id)
    .bind(&bal.user_id)
    .fetch_one(pool)
    .await
}

pub async fn delete_balance(pool: &PgPool, id: i64) -> Result<bool, sqlx::Error> {
    let res = sqlx::query("DELETE FROM balance WHERE id = $1")
        .bind(id)
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
