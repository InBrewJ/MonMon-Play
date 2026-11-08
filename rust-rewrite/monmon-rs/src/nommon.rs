use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::{Html, Json},
};
use serde::Deserialize;
use serde_json::Value;
use sqlx::Row;
use std::sync::Arc;

#[derive(Debug, Deserialize)]
pub struct NomMonQuery {
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
    pub force: Option<bool>,
    pub budget: Option<f32>,
    pub people: Option<i32>,
    pub postcode: Option<String>,
}

impl NomMonQuery {
    pub fn get_user_id(&self) -> String {
        self.user_id
            .clone()
            .unwrap_or_else(|| "demo".to_string())
    }
}

#[derive(Debug, Deserialize)]
pub struct LockPlanRequest {
    pub plan: Value,
    pub budget: Option<f32>,
    pub people: Option<i32>,
    pub postcode: Option<String>,
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
}

#[derive(Template)]
#[template(path = "nommon.html")]
pub struct NomMonTemplate {
    pub weekly_food_budget: f32,
    pub is_configured: bool,
}

pub async fn get_nommon_page(
    State(state): State<Arc<AppState>>,
) -> Result<Html<String>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, "demo").await;

    // Check active pot for weekly budget if configured
    let row = sqlx::query(
        "SELECT spendingplanfood FROM pot WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false) LIMIT 1",
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    let weekly_food_budget = match row {
        Some(r) => {
            let val: Option<f32> = r.try_get("spendingplanfood").ok().flatten();
            val.map(|f| ((f * 12.0) / 52.0 * 100.0).round() / 100.0).unwrap_or(50.0)
        }
        None => 50.0,
    };

    let tmpl = NomMonTemplate {
        weekly_food_budget,
        is_configured: false,
    };

    tmpl.render()
        .map(Html)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))
}

pub async fn get_nommon_plan_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<NomMonQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;

    // 1. Check for locked meal plan
    let locked_row = sqlx::query(
        r#"
        SELECT planjson, lockedat, weeklybudget, people, postcode
        FROM locked_meal_plan
        WHERE (userid = $1 OR user_id = $1)
        LIMIT 1
        "#,
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    if let Some(r) = locked_row {
        let plan_str: String = r.get("planjson");
        let locked_at: Option<i64> = r.get("lockedat");
        let parsed: Value = serde_json::from_str(&plan_str).unwrap_or(Value::Null);

        return Ok(Json(serde_json::json!({
            "configured": false,
            "locked": true,
            "lockedAt": locked_at,
            "lastGenerated": locked_at,
            "plan": parsed
        })));
    }

    // 2. Default mock / un-configured response matching MistralService contract
    Ok(Json(serde_json::json!({
        "configured": false,
        "locked": false,
        "plan": {
            "title": "NomMon Standard 7-Day Grocery Plan",
            "weeklyBudget": q.budget.unwrap_or(50.0),
            "estimatedCost": 42.50,
            "safetyBuffer": 7.50,
            "shoppingList": [
                { "item": "British Whole Milk 2.27L (4 Pints)", "category": "Dairy & Eggs", "price": 1.65, "checked": false },
                { "item": "Medium Free Range Eggs x12", "category": "Dairy & Eggs", "price": 2.60, "checked": false },
                { "item": "Wholemeal Medium Sliced Bread 800g", "category": "Bakery", "price": 0.85, "checked": false },
                { "item": "Bananas Loose x6", "category": "Fruit & Veg", "price": 0.95, "checked": false },
                { "item": "Broccoli Loose 350g", "category": "Fruit & Veg", "price": 0.79, "checked": false },
                { "item": "Basmati Rice 1kg", "category": "Pantry", "price": 1.85, "checked": false },
                { "item": "Chopped Tomatoes in Tomato Juice 4x400g", "category": "Pantry", "price": 2.00, "checked": false },
                { "item": "Chicken Breast Fillets 640g", "category": "Meat & Fish", "price": 4.50, "checked": false }
            ]
        }
    })))
}

pub async fn lock_nommon_plan_api(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<LockPlanRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = payload.user_id.unwrap_or_else(|| "demo".to_string());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

    let plan_str = serde_json::to_string(&payload.plan).unwrap_or_default();
    let now = chrono::Utc::now().timestamp_millis();
    let budget = payload.budget.unwrap_or(50.0);
    let people = payload.people.unwrap_or(1);
    let postcode = payload.postcode;

    // Delete existing locked plan for this user if any
    let _ = sqlx::query("DELETE FROM locked_meal_plan WHERE userid = $1 OR user_id = $1")
        .bind(&resolved)
        .execute(&state.pool)
        .await;

    // Insert new locked plan
    sqlx::query(
        r#"
        INSERT INTO locked_meal_plan (
            id, userid, user_id, "userId", planjson, "planJson", lockedat, "lockedAt",
            weeklybudget, "weeklyBudget", people, postcode
        )
        VALUES (nextval('hibernate_sequence'), $1, $1, $1, $2, $2, $3, $3, $4, $4, $5, $6)
        "#,
    )
    .bind(&resolved)
    .bind(&plan_str)
    .bind(now)
    .bind(budget)
    .bind(people)
    .bind(postcode)
    .execute(&state.pool)
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(serde_json::json!({
        "success": true,
        "locked": true,
        "lockedAt": now
    })))
}

pub async fn unlock_nommon_plan_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<NomMonQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;

    sqlx::query("DELETE FROM locked_meal_plan WHERE userid = $1 OR user_id = $1")
        .bind(&resolved)
        .execute(&state.pool)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(serde_json::json!({
        "success": true,
        "locked": false
    })))
}
