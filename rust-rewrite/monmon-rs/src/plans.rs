use crate::pot::get_user_id_from_headers_or_default;
use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Form, Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Redirect, Response},
};
use serde::{Deserialize, Serialize};
use sqlx::Row;
use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SharingAgreementDto {
    pub id: i64,
    pub plan_type: String,
    pub split: f32,
    pub human_readable_split: String,
    pub your_percentage: String,
    pub others_percentage: String,
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SavingsPlanSummaryDto {
    pub name: String,
    pub percentage: i32,
    pub items_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PotSummaryDto {
    pub pot_type: String,
    pub spending_plan_name: Option<String>,
    pub accounts_count: usize,
    pub pot_total_balance: f32,
}

#[derive(Template)]
#[template(path = "plans.html")]
pub struct PlansHubTemplate {
    pub active_savings_plan: Option<SavingsPlanSummaryDto>,
    pub active_monthly_pot: Option<PotSummaryDto>,
    pub sharing_agreements: Vec<SharingAgreementDto>,
}

#[derive(Debug, Deserialize)]
pub struct SharingPlanForm {
    pub r#type: String,
    pub mode: Option<String>,
    pub people: Option<f32>,
    pub percent: Option<f32>,
    pub notes: Option<String>,
}

fn format_split(split: f32) -> (String, String, String) {
    if split <= 0.0 {
        return ("0%".to_string(), "100%".to_string(), "You pay 0% · Others pay 100%".to_string());
    }
    let pct = split * 100.0;
    let others = (100.0 - pct).max(0.0);
    let approx_people = 1.0 / split;
    let rounded = approx_people.round() as i32;

    let your_fmt = if (pct.fract()).abs() < 0.05 {
        format!("{:.0}%", pct)
    } else {
        format!("{:.1}%", pct)
    };
    let others_fmt = if (others.fract()).abs() < 0.05 {
        format!("{:.0}%", others)
    } else {
        format!("{:.1}%", others)
    };

    let human = if rounded >= 2 && (approx_people - rounded as f32).abs() < 0.05 {
        format!("You pay {} (1/{} share) · Others pay {}", your_fmt, rounded, others_fmt)
    } else {
        format!("You pay {} · Others pay {}", your_fmt, others_fmt)
    };

    (your_fmt, others_fmt, human)
}

pub async fn get_plans_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<std::collections::HashMap<String, String>>,
) -> Result<Response, (StatusCode, String)> {
    if !crate::management::is_authenticated(&headers)
        && !query.contains_key("userId")
        && !query.contains_key("user_id")
    {
        return Ok(crate::landing::auth_redirect(Some("/myPlans")).await.into_response());
    }

    let user_id = get_user_id_from_headers_or_default(
        &headers,
        query.get("userId").or_else(|| query.get("user_id")).map(|s| s.as_str()),
    );
    let resolved = resolve_user_id(&state.pool, &user_id).await;

    // 1. Active Savings Plan summary
    let sp_row = sqlx::query(
        "SELECT id, name, savingspercentage FROM savings_plan WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false) LIMIT 1",
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    let active_savings_plan = if let Some(r) = sp_row {
        let sp_id: i64 = r.get("id");
        let name: String = r.get("name");
        let pct: i32 = r.get("savingspercentage");
        let items_count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM savings_plan_item WHERE plan_id = $1 AND (archived IS NULL OR archived = false)",
        )
        .bind(sp_id)
        .fetch_one(&state.pool)
        .await
        .unwrap_or(0);

        Some(SavingsPlanSummaryDto {
            name,
            percentage: pct,
            items_count: items_count as usize,
        })
    } else {
        None
    };

    // 2. Active Monthly Pot summary
    let pot_row = sqlx::query(
        "SELECT id, type, spending_plan_name FROM pot WHERE (userid = $1 OR user_id = $1) AND type = 'MONTHLY' AND (archived IS NULL OR archived = false) LIMIT 1",
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    let active_monthly_pot = if let Some(pr) = pot_row {
        let pid: i64 = pr.get("id");
        let ptype: String = pr.get("type");
        let sp_name: Option<String> = pr.get("spending_plan_name");

        let acc_rows = sqlx::query(
            r#"
            WITH latest_b AS (
                SELECT DISTINCT ON (account_id) account_id, value
                FROM balance
                ORDER BY account_id, timestamp DESC
            )
            SELECT COALESCE(SUM(b.value), 0.0)::real AS total_balance, count(pa.account_id) AS acc_count
            FROM pot_accounts pa
            LEFT JOIN latest_b b ON b.account_id = pa.account_id
            WHERE pa.pot_id = $1
            "#,
        )
        .bind(pid)
        .fetch_one(&state.pool)
        .await
        .ok();

        let total_bal: f32 = acc_rows.as_ref().map(|r| r.get("total_balance")).unwrap_or(0.0);
        let acc_count: i64 = acc_rows.as_ref().map(|r| r.get("acc_count")).unwrap_or(0);

        Some(PotSummaryDto {
            pot_type: ptype,
            spending_plan_name: sp_name,
            accounts_count: acc_count as usize,
            pot_total_balance: total_bal,
        })
    } else {
        None
    };

    // 3. Sharing agreements from `plan` table
    let plan_rows = sqlx::query(
        r#"
        SELECT id, type, split, notes
        FROM plan
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
          AND type IN ('BILL_SHARE', 'RENT_SHARE')
        ORDER BY id DESC
        "#,
    )
    .bind(&resolved)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let mut sharing_agreements = Vec::new();
    for pr in plan_rows {
        let id: i64 = pr.get("id");
        let p_type: String = pr.get("type");
        let split: f32 = pr.get("split");
        let notes: Option<String> = pr.get("notes");
        let (your_pct, others_pct, human) = format_split(split);

        sharing_agreements.push(SharingAgreementDto {
            id,
            plan_type: p_type,
            split,
            human_readable_split: human,
            your_percentage: your_pct,
            others_percentage: others_pct,
            notes,
        });
    }

    let tmpl = PlansHubTemplate {
        active_savings_plan,
        active_monthly_pot,
        sharing_agreements,
    };

    tmpl.render()
        .map(|h| Html(h).into_response())
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn post_add_plan(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Form(payload): Form<SharingPlanForm>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &user_id).await;

    let ptype = if payload.r#type.to_uppercase() == "RENT_SHARE" {
        "RENT_SHARE"
    } else {
        "BILL_SHARE"
    };

    let split = if payload.mode.as_deref() == Some("percent") {
        let pct = payload.percent.unwrap_or(50.0).clamp(1.0, 100.0);
        pct / 100.0
    } else {
        let people = payload.people.unwrap_or(2.0).max(1.0);
        1.0 / people
    };

    let notes = payload.notes.filter(|s| !s.trim().is_empty());

    // Archive any previous agreement of the same type
    let _ = sqlx::query("UPDATE plan SET archived = true WHERE (userid = $1 OR user_id = $1) AND type = $2")
        .bind(&resolved)
        .bind(ptype)
        .execute(&state.pool)
        .await;

    // Insert new sharing agreement
    sqlx::query(
        r#"
        INSERT INTO plan (
            id, userid, user_id, "userId", type, split, notes, archived
        )
        VALUES (
            nextval('hibernate_sequence'), $1, $1, $1, $2, $3, $4, false
        )
        "#,
    )
    .bind(&resolved)
    .bind(ptype)
    .bind(split)
    .bind(notes)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Redirect::to("/myPlans"))
}

pub async fn get_bin_plan(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    headers: HeaderMap,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = get_user_id_from_headers_or_default(&headers, None);
    let user_id = resolve_user_id(&state.pool, &raw_uid).await;
    let res = sqlx::query("UPDATE plan SET archived = true WHERE id = $1 AND (userid = $2 OR user_id = $2)")
        .bind(id)
        .bind(&user_id)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    crate::authz::Authz::verify_affected(res.rows_affected(), "Plan", id)?;

    Ok(Redirect::to("/myPlans"))
}

#[derive(Deserialize)]
pub struct UpdateSavingsPercentageQuery {
    pub percent: Option<i32>,
}

pub async fn get_update_savings_payoff_percentage(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(query): Query<UpdateSavingsPercentageQuery>,
) -> Result<Redirect, (StatusCode, String)> {
    let user_id = get_user_id_from_headers_or_default(&headers, None);
    let resolved = resolve_user_id(&state.pool, &user_id).await;
    let pct = query.percent.unwrap_or(20).clamp(0, 100);

    let active_plan_id: Option<i64> = sqlx::query_scalar(
        "SELECT id FROM savings_plan WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false) LIMIT 1",
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    if let Some(pid) = active_plan_id {
        let _ = sqlx::query("UPDATE savings_plan SET savingspercentage = $1, lastupdated = $2 WHERE id = $3")
            .bind(pct)
            .bind(chrono::Utc::now().timestamp())
            .bind(pid)
            .execute(&state.pool)
            .await;
    } else {
        let now = chrono::Utc::now().timestamp();
        let _ = sqlx::query(
            r#"
            INSERT INTO savings_plan (id, userid, user_id, "userId", name, savingspercentage, createdat, lastupdated, archived)
            VALUES (nextval('hibernate_sequence'), $1, $1, $1, 'Primary Plan', $2, $3, $3, false)
            "#,
        )
        .bind(&resolved)
        .bind(pct)
        .bind(now)
        .execute(&state.pool)
        .await;
    }

    Ok(Redirect::to("/spog"))
}
