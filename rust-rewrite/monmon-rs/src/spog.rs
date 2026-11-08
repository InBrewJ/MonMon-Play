use chrono::{Datelike, Local, NaiveDate};
use serde::{Deserialize, Serialize};
use sqlx::{PgPool, Row};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpogMetrics {
    pub incoming_total: f32,
    pub outgoing_total: f32,
    pub surplus: f32,
    pub next_pay_day: i32,
    pub days_between_paydays: i32,
    pub max_per_day: f32,
    pub max_per_week: f32,
    pub max_per_month: f32,
    pub completed_outgoings: f32,
    pub pending_outgoings: f32,
    pub bills_cost: f32,
    pub rent_cost: f32,
    pub monthly_pot_left_per_day: f32,
    pub total_available_debit: f32,
    pub total_available_credit: f32,
    pub credit_balance: f32,
    pub credit_limit: f32,
    pub credit_usage: f32,
    pub liquid_savings_pot: f32,
    pub total_savings_pot: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpendingPaceMetrics {
    pub spent_this_month: f32,
    pub actual_daily_spend: f32,
    pub baseline_diff_total: f32,
    pub is_behind_baseline: bool,
    pub absolute_baseline_diff_total: f32,
}

pub async fn resolve_user_id(pool: &PgPool, user_id_or_name: &str) -> String {
    let row = sqlx::query(
        "SELECT userid FROM user_access WHERE username = $1 OR email = $1 OR userid = $1 LIMIT 1",
    )
    .bind(user_id_or_name)
    .fetch_optional(pool)
    .await
    .unwrap_or(None);

    if let Some(r) = row {
        let uid: String = r.get("userid");
        uid
    } else {
        user_id_or_name.to_string()
    }
}

pub async fn calculate_spog(pool: &PgPool, raw_user_id: &str) -> Result<SpogMetrics, sqlx::Error> {
    let user_id = resolve_user_id(pool, raw_user_id).await;

    // 1. Incomings
    let incoming_row = sqlx::query(
        r#"
        SELECT 
            COALESCE(SUM(netvalue), 0.0)::real AS total,
            COALESCE(
                (SELECT incomingmonthday FROM incoming 
                 WHERE (userid = $1 OR user_id = $1) 
                   AND (archived IS NULL OR archived = false) 
                   AND (payday = true OR type = 'SALARY') 
                 ORDER BY id ASC LIMIT 1),
                25
            ) AS payday
        FROM incoming
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        "#,
    )
    .bind(&user_id)
    .fetch_one(pool)
    .await?;

    let incoming_total: f32 = incoming_row.get::<f32, _>("total");
    let next_pay_day: i32 = incoming_row.get::<i32, _>("payday");

    // 2. Calendar logic
    let today = Local::now().date_naive();
    let today_day = today.day() as i32;
    let days_between_paydays = 31; // Standard monthly cycle representation

    // 3. Outgoings with Rent & Bill Breakdown
    let outgoings_row = sqlx::query(
        r#"
        SELECT 
            COALESCE(SUM(CASE WHEN (hiddenfromtotal IS NULL OR hiddenfromtotal = false) AND (paused IS NULL OR paused = false) THEN cost ELSE 0 END), 0.0)::real AS total_cost,
            COALESCE(SUM(CASE WHEN (hiddenfromtotal IS NULL OR hiddenfromtotal = false) AND (paused IS NULL OR paused = false) AND bill = true THEN cost ELSE 0 END), 0.0)::real AS bills_cost,
            COALESCE(SUM(CASE WHEN (hiddenfromtotal IS NULL OR hiddenfromtotal = false) AND (paused IS NULL OR paused = false) AND rent = true THEN cost ELSE 0 END), 0.0)::real AS rent_cost,
            COALESCE(SUM(CASE WHEN (hiddenfromtotal IS NULL OR hiddenfromtotal = false) AND (paused IS NULL OR paused = false) AND outgoingday <= $2 THEN cost ELSE 0 END), 0.0)::real AS completed_cost,
            COALESCE(SUM(CASE WHEN (hiddenfromtotal IS NULL OR hiddenfromtotal = false) AND (paused IS NULL OR paused = false) AND outgoingday > $2 THEN cost ELSE 0 END), 0.0)::real AS pending_cost
        FROM outgoing
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        "#,
    )
    .bind(&user_id)
    .bind(today_day)
    .fetch_one(pool)
    .await?;

    let outgoing_total: f32 = outgoings_row.get::<f32, _>("total_cost");
    let bills_cost: f32 = outgoings_row.get::<f32, _>("bills_cost");
    let rent_cost: f32 = outgoings_row.get::<f32, _>("rent_cost");
    let completed_outgoings: f32 = outgoings_row.get::<f32, _>("completed_cost");
    let pending_outgoings: f32 = outgoings_row.get::<f32, _>("pending_cost");

    // Surplus & Allowances
    let surplus = (incoming_total - outgoing_total).max(0.0);
    let max_per_day = if days_between_paydays > 0 {
        surplus / (days_between_paydays as f32)
    } else {
        0.0
    };
    let max_per_week = max_per_day * 7.0;
    let max_per_month = surplus;

    // 4. Accounts & Balances
    let accounts_rows = sqlx::query(
        r#"
        WITH latest_balance AS (
            SELECT DISTINCT ON (account_id) account_id, value
            FROM balance
            ORDER BY account_id, timestamp DESC
        )
        SELECT 
            a.id, a.type,
            COALESCE(a.account_limit, 0.0)::real AS limit_val,
            COALESCE(b.value, 0.0)::real AS balance
        FROM account a
        LEFT JOIN latest_balance b ON b.account_id = a.id
        WHERE (a.userid = $1 OR a.user_id = $1)
          AND (a.archived IS NULL OR a.archived = false)
        "#,
    )
    .bind(&user_id)
    .fetch_all(pool)
    .await?;

    let mut total_available_debit = 0.0f32;
    let mut credit_balance = 0.0f32;
    let mut credit_limit = 0.0f32;
    let mut liquid_savings_pot = 0.0f32;
    let mut total_savings_pot = 0.0f32;

    for row in accounts_rows {
        let acc_type: String = row.get("type");
        let balance: f32 = row.get("balance");
        let limit_val: f32 = row.get("limit_val");

        match acc_type.to_uppercase().as_str() {
            "DEBIT" => {
                total_available_debit += balance;
            }
            "CREDIT" => {
                credit_limit += limit_val;
                credit_balance += balance;
            }
            "SAVINGS" => {
                liquid_savings_pot += balance;
                total_savings_pot += balance;
            }
            "PENSION" => {
                total_savings_pot += balance;
            }
            _ => {
                total_available_debit += balance;
            }
        }
    }

    let total_available_credit = (credit_limit - credit_balance).max(0.0);
    let credit_usage = if credit_limit > 0.0 {
        ((credit_balance / credit_limit) * 100.0).clamp(0.0, 100.0)
    } else {
        0.0
    };

    // 5. Monthly Pot Left Per Day
    let pot_row = sqlx::query(
        r#"
        WITH latest_balance AS (
            SELECT DISTINCT ON (account_id) account_id, value
            FROM balance
            ORDER BY account_id, timestamp DESC
        )
        SELECT COALESCE(SUM(b.value), 0.0)::real AS pot_balance
        FROM pot p
        JOIN pot_accounts pa ON pa.pot_id = p.id
        JOIN latest_balance b ON b.account_id = pa.account_id
        WHERE (p.userid = $1 OR p.user_id = $1)
          AND (p.archived IS NULL OR p.archived = false)
          AND p.type = 'MONTHLY'
        "#,
    )
    .bind(&user_id)
    .fetch_optional(pool)
    .await?;

    let pot_balance = pot_row.map(|r| r.get::<f32, _>("pot_balance")).unwrap_or(0.0);
    let days_left = if next_pay_day >= today_day {
        next_pay_day - today_day + 1
    } else {
        days_between_paydays - today_day + next_pay_day
    }
    .max(1);

    let monthly_pot_left_per_day = pot_balance / (days_left as f32);

    Ok(SpogMetrics {
        incoming_total,
        outgoing_total,
        surplus,
        next_pay_day,
        days_between_paydays,
        max_per_day,
        max_per_week,
        max_per_month,
        completed_outgoings,
        pending_outgoings,
        bills_cost,
        rent_cost,
        monthly_pot_left_per_day,
        total_available_debit,
        total_available_credit,
        credit_balance,
        credit_limit,
        credit_usage,
        liquid_savings_pot,
        total_savings_pot,
    })
}

pub async fn calculate_spending_pace(
    pool: &PgPool,
    raw_user_id: &str,
) -> Result<SpendingPaceMetrics, sqlx::Error> {
    let spog = calculate_spog(pool, raw_user_id).await?;
    let today = Local::now().date_naive();
    let today_day = today.day() as i32;

    // Approximate days elapsed in current cycle
    let days_elapsed = today_day.clamp(1, spog.days_between_paydays) as f32;
    let expected_spend = spog.max_per_day * days_elapsed;
    let spent_this_month = 0.0f32; // Ingested bank transactions or balance delta
    let actual_daily_spend = if days_elapsed > 0.0 {
        spent_this_month / days_elapsed
    } else {
        0.0
    };

    let baseline_diff_total = expected_spend - spent_this_month;
    let is_behind_baseline = baseline_diff_total < 0.0;
    let absolute_baseline_diff_total = baseline_diff_total.abs();

    Ok(SpendingPaceMetrics {
        spent_this_month,
        actual_daily_spend,
        baseline_diff_total,
        is_behind_baseline,
        absolute_baseline_diff_total,
    })
}
