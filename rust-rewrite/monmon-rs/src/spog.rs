use chrono::{Datelike, Local};
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

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct SpendingPaceChunkDto {
    pub label: String,
    pub date_range: String,
    pub days_in_chunk: i32,
    pub days_elapsed: i32,
    pub total_spent: f32,
    pub pace: f32,
    pub cumulative_spent: f32,
    pub cumulative_pace: f32,
    pub is_current: bool,
    pub is_future: bool,
    pub is_over_target: bool,
}

#[derive(askama::Template)]
#[template(path = "spending_pace.html")]
pub struct SpendingPaceTemplate {
    pub days_elapsed: i32,
    pub days_in_cycle: i32,
    pub max_per_day: f32,
    pub monthly_budget: f32,
    pub actual_daily_spend: f32,
    pub spent_this_month: f32,
    pub absolute_baseline_diff_total: f32,
    pub is_behind_baseline: bool,
    pub chunks: Vec<SpendingPaceChunkDto>,
    pub chart_json: String,
}

pub async fn get_spending_pace_page(
    state: &std::sync::Arc<crate::AppState>,
) -> Result<axum::response::Html<String>, (axum::http::StatusCode, String)> {
    let spog = calculate_spog(&state.pool, "demo")
        .await
        .map_err(|e| (axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let pace = calculate_spending_pace(&state.pool, "demo")
        .await
        .map_err(|e| (axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let today = Local::now().date_naive();
    let days_in_cycle = spog.days_between_paydays.max(28);
    let days_elapsed = (today.day() as i32).clamp(1, days_in_cycle);

    // Build 5-day chunks
    let mut chunks = Vec::new();
    let mut cumulative_spent = 0.0f32;
    let mut cumulative_days = 0i32;

    let month_str = today.format("%b").to_string();
    let mut start_day = 1;
    let mut chunk_idx = 1;

    while start_day <= days_in_cycle {
        let end_day = (start_day + 4).min(days_in_cycle);
        let days_in_chunk = end_day - start_day + 1;

        let is_current = days_elapsed >= start_day && days_elapsed <= end_day;
        let is_future = days_elapsed < start_day;
        let chunk_elapsed = if is_future {
            0
        } else if is_current {
            days_elapsed - start_day + 1
        } else {
            days_in_chunk
        };

        // Calculate chunk spent based on pace and elapsed
        let chunk_spent = if chunk_elapsed > 0 {
            let chunk_ratio = chunk_elapsed as f32 / days_elapsed as f32;
            ((pace.spent_this_month * chunk_ratio * 100.0).round() / 100.0).max(0.0)
        } else {
            0.0
        };

        let chunk_pace = if chunk_elapsed > 0 {
            ((chunk_spent / chunk_elapsed as f32) * 100.0).round() / 100.0
        } else {
            0.0
        };

        cumulative_spent += chunk_spent;
        cumulative_days += chunk_elapsed;

        let cumulative_pace = if cumulative_days > 0 {
            ((cumulative_spent / cumulative_days as f32) * 100.0).round() / 100.0
        } else {
            0.0
        };

        let is_over_target = chunk_pace > spog.max_per_day;

        chunks.push(SpendingPaceChunkDto {
            label: format!("Chunk {}", chunk_idx),
            date_range: format!("{} {} – {} {}", start_day, month_str, end_day, month_str),
            days_in_chunk,
            days_elapsed: chunk_elapsed,
            total_spent: chunk_spent,
            pace: chunk_pace,
            cumulative_spent,
            cumulative_pace,
            is_current,
            is_future,
            is_over_target,
        });

        start_day += 5;
        chunk_idx += 1;
    }

    let chart_data = serde_json::json!({
        "maxPerDay": spog.max_per_day,
        "monthlyBudget": spog.surplus,
        "chunks": chunks.iter().map(|c| {
            serde_json::json!({
                "label": c.label,
                "dateRange": c.date_range,
                "daysInChunk": c.days_in_chunk,
                "daysElapsed": c.days_elapsed,
                "totalSpent": c.total_spent,
                "pace": c.pace,
                "cumulativeSpent": c.cumulative_spent,
                "cumulativePace": c.cumulative_pace,
                "isCurrent": c.is_current,
                "isFuture": c.is_future,
                "isOverTarget": c.is_over_target,
            })
        }).collect::<Vec<_>>()
    });

    let chart_json = serde_json::to_string(&chart_data).unwrap_or_else(|_| "{}".to_string());

    let tmpl = SpendingPaceTemplate {
        days_elapsed,
        days_in_cycle,
        max_per_day: spog.max_per_day,
        monthly_budget: spog.surplus,
        actual_daily_spend: pace.actual_daily_spend,
        spent_this_month: pace.spent_this_month,
        absolute_baseline_diff_total: pace.absolute_baseline_diff_total,
        is_behind_baseline: pace.is_behind_baseline,
        chunks,
        chart_json,
    };

    use askama::Template;
    tmpl.render()
        .map(axum::response::Html)
        .map_err(|e| (axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}
