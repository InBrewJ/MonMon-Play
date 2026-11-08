use crate::spog::resolve_user_id;
use askama::Template;
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::{Html, IntoResponse, Json, Redirect},
    Form,
};
use serde::{Deserialize, Serialize};
use sqlx::{PgPool, Row};
use std::sync::Arc;
use crate::AppState;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsPlanItemDto {
    pub id: i64,
    pub savings_plan_id: i64,
    pub name: String,
    #[serde(rename = "type")]
    pub item_type: String,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
    pub target_amount: f32,
    pub monthly_amount: f32,
    pub priority: i32,
    pub locked: bool,
    pub archived: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsPlanDto {
    pub id: i64,
    pub user_id: String,
    pub name: String,
    pub savings_percentage: i32,
    pub incoming_total: f32,
    pub target_amount: f32,
    pub total_allocated: f32,
    pub unallocated_amount: f32,
    pub max_surplus: f32,
    pub items: Vec<SavingsPlanItemDto>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsPlanQuery {
    #[serde(alias = "user_id")]
    pub user_id: Option<String>,
}

impl SavingsPlanQuery {
    pub fn get_user_id(&self) -> String {
        self.user_id
            .clone()
            .unwrap_or_else(|| "demo".to_string())
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateItemPayload {
    pub name: String,
    #[serde(rename = "type")]
    pub item_type: Option<String>,
    #[serde(alias = "target_amount")]
    pub target_amount: Option<f32>,
    #[serde(alias = "monthly_amount")]
    pub monthly_amount: Option<f32>,
    pub priority: Option<i32>,
    #[serde(alias = "account_id")]
    pub account_id: Option<i64>,
    #[serde(alias = "user_id")]
    pub user_id: Option<String>,
    #[serde(alias = "plan_id")]
    pub plan_id: Option<i64>,
}

#[derive(Template)]
#[template(path = "savings_plan.html")]
pub struct SavingsPlanTemplate {
    pub plan: SavingsPlanDto,
    pub accounts: Vec<AccountSimple>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountSimple {
    pub id: i64,
    pub name: String,
    pub account_type: String,
    pub balance: f32,
    pub limit: f32,
}

// Helper: fetch user's incoming total
pub async fn get_user_incoming_total(pool: &PgPool, user_id: &str) -> f32 {
    let row = sqlx::query(
        r#"
        SELECT COALESCE(SUM(netvalue), 0.0)::real AS total
        FROM incoming
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        "#,
    )
    .bind(user_id)
    .fetch_one(pool)
    .await;

    match row {
        Ok(r) => r.get::<f32, _>("total"),
        Err(_) => 0.0,
    }
}

pub async fn get_or_create_active_plan(pool: &PgPool, user_id: &str) -> Result<SavingsPlanDto, sqlx::Error> {
    let resolved = resolve_user_id(pool, user_id).await;

    let plan_row = sqlx::query(
        r#"
        SELECT id, name, savingspercentage
        FROM savings_plan
        WHERE (userid = $1 OR user_id = $1)
          AND archived = false
        ORDER BY createdat DESC NULLS LAST
        LIMIT 1
        "#,
    )
    .bind(&resolved)
    .fetch_optional(pool)
    .await?;

    let (plan_id, name, savings_percentage) = match plan_row {
        Some(row) => (
            row.get::<i64, _>("id"),
            row.get::<String, _>("name"),
            row.get::<i32, _>("savingspercentage"),
        ),
        None => {
            // Create default active savings plan
            let now = chrono::Utc::now().timestamp_millis();
            let new_row = sqlx::query(
                r#"
                INSERT INTO savings_plan (id, userid, user_id, "userId", name, savingspercentage, archived, createdat, lastupdated)
                VALUES (nextval('hibernate_sequence'), $1, $1, $1, 'Monthly Savings Plan', 0, false, $2, $2)
                RETURNING id
                "#,
            )
            .bind(&resolved)
            .bind(now)
            .fetch_one(pool)
            .await?;

            (new_row.get::<i64, _>("id"), "Monthly Savings Plan".to_string(), 0)
        }
    };

    let items_rows = sqlx::query(
        r#"
        SELECT 
            i.id, i.savings_plan_id, i.name, COALESCE(i.type, 'SAVINGS_TARGET') AS item_type,
            i.account_id, COALESCE(i.targetamount, 0.0)::real AS targetamount,
            COALESCE(i.monthlyamount, 0.0)::real AS monthlyamount,
            COALESCE(i.priority, 1) AS priority,
            COALESCE(i.locked, false) AS locked,
            COALESCE(i.archived, false) AS archived,
            a.name AS account_name
        FROM savings_plan_item i
        LEFT JOIN account a ON a.id = i.account_id
        WHERE i.savings_plan_id = $1
          AND (i.archived IS NULL OR i.archived = false)
        ORDER BY i.priority ASC NULLS LAST, i.id ASC
        "#,
    )
    .bind(plan_id)
    .fetch_all(pool)
    .await?;

    let mut items: Vec<SavingsPlanItemDto> = items_rows
        .into_iter()
        .enumerate()
        .map(|(idx, r)| SavingsPlanItemDto {
            id: r.get("id"),
            savings_plan_id: r.get("savings_plan_id"),
            name: r.get("name"),
            item_type: r.get("item_type"),
            account_id: r.get("account_id"),
            account_name: r.get("account_name"),
            target_amount: r.get("targetamount"),
            monthly_amount: r.get("monthlyamount"),
            priority: (idx + 1) as i32, // Enforce strictly contiguous 1..N order
            locked: r.get("locked"),
            archived: r.get("archived"),
        })
        .collect();

    // Rebalance in DB if priority drifted
    for item in &items {
        let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2")
            .bind(item.priority)
            .bind(item.id)
            .execute(pool)
            .await;
    }

    let incoming_total = get_user_incoming_total(pool, &resolved).await;
    let target_amount = (savings_percentage as f32 / 100.0) * incoming_total;
    let total_allocated: f32 = items.iter().map(|it| it.monthly_amount).sum();
    let unallocated_amount = target_amount - total_allocated;

    Ok(SavingsPlanDto {
        id: plan_id,
        user_id: resolved,
        name,
        savings_percentage,
        incoming_total: (incoming_total * 100.0).round() / 100.0,
        target_amount: (target_amount * 100.0).round() / 100.0,
        total_allocated: (total_allocated * 100.0).round() / 100.0,
        unallocated_amount: (unallocated_amount * 100.0).round() / 100.0,
        max_surplus: incoming_total, // Default representation
        items,
    })
}

// JSON API Handlers
pub async fn get_savings_plan_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<SavingsPlanDto>, (StatusCode, String)> {
    let user_id = q.get_user_id();
    get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map(Json)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn create_savings_plan_item_api(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<CreateItemPayload>,
) -> Result<Json<SavingsPlanItemDto>, (StatusCode, String)> {
    let user_id = payload.user_id.clone().unwrap_or_else(|| "demo".to_string());
    let plan = get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let plan_id = payload.plan_id.unwrap_or(plan.id);
    let target_amount = payload.target_amount.unwrap_or(0.0);
    let monthly_amount = payload.monthly_amount.unwrap_or(0.0);
    let item_type = match payload.item_type.as_deref() {
        Some("SAVINGS") | Some("SAVINGS_TARGET") => "SAVINGS_TARGET".to_string(),
        Some("DEBT_PAYOFF") => "DEBT_PAYOFF".to_string(),
        _ => "SAVINGS_TARGET".to_string(),
    };
    let account_id = match payload.account_id {
        Some(id) if id > 0 => Some(id),
        _ => None,
    };
    let new_priority = payload.priority.unwrap_or_else(|| (plan.items.len() + 1) as i32);

    let row = sqlx::query(
        r#"
        INSERT INTO savings_plan_item (
            id, savings_plan_id, name, type, account_id, targetamount, target_amount,
            monthlyamount, monthly_amount, priority, locked, archived
        )
        VALUES (nextval('hibernate_sequence'), $1, $2, $3, $4, $5, $5, $6, $6, $7, false, false)
        RETURNING id, savings_plan_id, name, type, account_id, targetamount, monthlyamount, priority, locked, archived
        "#,
    )
    .bind(plan_id)
    .bind(&payload.name)
    .bind(&item_type)
    .bind(account_id)
    .bind(target_amount)
    .bind(monthly_amount)
    .bind(new_priority)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(SavingsPlanItemDto {
        id: row.get("id"),
        savings_plan_id: row.get("savings_plan_id"),
        name: row.get("name"),
        item_type: row.get("type"),
        account_id: row.get("account_id"),
        account_name: None,
        target_amount: row.get("targetamount"),
        monthly_amount: row.get("monthlyamount"),
        priority: row.get("priority"),
        locked: row.get("locked"),
        archived: row.get("archived"),
    }))
}

pub async fn move_item_up_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let user_id = q.get_user_id();
    let plan = get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    if let Some(idx) = plan.items.iter().position(|it| it.id == id) {
        if idx > 0 {
            let prev = &plan.items[idx - 1];
            // Swap priorities
            sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2")
                .bind(prev.priority)
                .bind(id)
                .execute(&state.pool)
                .await
                .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

            sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2")
                .bind(plan.items[idx].priority)
                .bind(prev.id)
                .execute(&state.pool)
                .await
                .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
        }
    }

    Ok(Json(serde_json::json!({ "success": true, "moved": id, "direction": "up" })))
}

pub async fn move_item_down_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let user_id = q.get_user_id();
    let plan = get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    if let Some(idx) = plan.items.iter().position(|it| it.id == id) {
        if idx + 1 < plan.items.len() {
            let next = &plan.items[idx + 1];
            // Swap priorities
            sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2")
                .bind(next.priority)
                .bind(id)
                .execute(&state.pool)
                .await
                .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

            sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2")
                .bind(plan.items[idx].priority)
                .bind(next.id)
                .execute(&state.pool)
                .await
                .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
        }
    }

    Ok(Json(serde_json::json!({ "success": true, "moved": id, "direction": "down" })))
}

pub async fn toggle_lock_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Query(_q): Query<SavingsPlanQuery>,
) -> Result<Json<SavingsPlanItemDto>, (StatusCode, String)> {
    let row = sqlx::query(
        r#"
        UPDATE savings_plan_item
        SET locked = NOT COALESCE(locked, false)
        WHERE id = $1
        RETURNING id, savings_plan_id, name, type, account_id, targetamount, monthlyamount, priority, locked, archived
        "#,
    )
    .bind(id)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(SavingsPlanItemDto {
        id: row.get("id"),
        savings_plan_id: row.get("savings_plan_id"),
        name: row.get("name"),
        item_type: row.get("type"),
        account_id: row.get("account_id"),
        account_name: None,
        target_amount: row.get("targetamount"),
        monthly_amount: row.get("monthlyamount"),
        priority: row.get("priority"),
        locked: row.get("locked"),
        archived: row.get("archived"),
    }))
}

pub async fn delete_item_api(
    State(state): State<Arc<AppState>>,
    Path(id): Path<i64>,
    Query(q): Query<SavingsPlanQuery>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let user_id = q.get_user_id();
    let plan = get_or_create_active_plan(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    // Find the item to delete
    let deleted_item = plan.items.iter().find(|it| it.id == id);
    let amount_to_roll_over = deleted_item.map(|it| it.monthly_amount).unwrap_or(0.0);

    // Soft delete
    sqlx::query("UPDATE savings_plan_item SET archived = true, monthlyamount = 0.0, monthly_amount = 0.0 WHERE id = $1")
        .bind(id)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    // Rebalance remaining items
    let remaining_items: Vec<_> = plan.items.into_iter().filter(|it| it.id != id).collect();
    if !remaining_items.is_empty() {
        let top_id = remaining_items[0].id;
        let new_top_amount = remaining_items[0].monthly_amount + amount_to_roll_over;
        let _ = sqlx::query("UPDATE savings_plan_item SET monthlyamount = $1, monthly_amount = $1 WHERE id = $2")
            .bind(new_top_amount)
            .bind(top_id)
            .execute(&state.pool)
            .await;

        for (new_pri, item) in remaining_items.iter().enumerate() {
            let _ = sqlx::query("UPDATE savings_plan_item SET priority = $1 WHERE id = $2")
                .bind((new_pri + 1) as i32)
                .bind(item.id)
                .execute(&state.pool)
                .await;
        }
    }

    Ok(Json(serde_json::json!({ "success": true, "deleted": id })))
}

// HTML Form / Page Handlers
#[derive(Debug, Deserialize)]
pub struct FormAddItem {
    pub name: String,
    pub r#type: Option<String>,
    #[serde(rename = "targetAmount")]
    pub target_amount: Option<f32>,
    #[serde(rename = "monthlyAmount")]
    pub monthly_amount: Option<f32>,
    pub priority: Option<i32>,
    #[serde(rename = "accountId")]
    pub account_id: Option<i64>,
}

pub async fn get_savings_plan_page(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
    let plan = get_or_create_active_plan(&state.pool, "demo")
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let accounts_rows = sqlx::query(
        r#"
        SELECT id, name, type, COALESCE(latestbalancevalue, 0.0)::real AS balance, COALESCE(availablelimit, 0.0)::real AS limit
        FROM account
        WHERE (userid = $1 OR user_id = $1)
          AND (archived IS NULL OR archived = false)
        ORDER BY name ASC
        "#,
    )
    .bind(&plan.user_id)
    .fetch_all(&state.pool)
    .await
    .unwrap_or_default();

    let accounts = accounts_rows
        .into_iter()
        .map(|r| AccountSimple {
            id: r.get("id"),
            name: r.get("name"),
            account_type: r.get("type"),
            balance: r.get("balance"),
            limit: r.get("limit"),
        })
        .collect();

    let tmpl = SavingsPlanTemplate { plan, accounts };
    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn add_item_form_handler(
    State(state): State<Arc<AppState>>,
    Form(form): Form<FormAddItem>,
) -> Result<Redirect, (StatusCode, String)> {
    let payload = CreateItemPayload {
        name: form.name,
        item_type: form.r#type,
        target_amount: form.target_amount,
        monthly_amount: form.monthly_amount,
        priority: form.priority,
        account_id: form.account_id,
        user_id: Some("demo".to_string()),
        plan_id: None,
    };

    let _ = create_savings_plan_item_api(State(state), Json(payload)).await?;
    Ok(Redirect::to("/savingsPlan#sliced-items-card"))
}
