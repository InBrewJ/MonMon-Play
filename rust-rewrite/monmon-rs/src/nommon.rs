use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::{Html, Json},
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::Row;
use std::sync::Arc;
use std::time::Duration;

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
        "SELECT spending_plan_food, spendingplanfood FROM pot WHERE (userid = $1 OR user_id = $1) AND (archived IS NULL OR archived = false) LIMIT 1",
    )
    .bind(&resolved)
    .fetch_optional(&state.pool)
    .await
    .unwrap_or(None);

    let weekly_food_budget = match row {
        Some(r) => {
            let val: Option<f32> = r.try_get("spending_plan_food").ok().flatten()
                .or_else(|| r.try_get("spendingplanfood").ok().flatten());
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

fn get_nommon_model() -> String {
    std::env::var("NOMMON_MISTRAL_MODEL")
        .unwrap_or_else(|_| "mistral-medium-latest".to_string())
}

fn build_nommon_system_prompt() -> &'static str {
    r#"You are NomMon, an expert UK grocery planner and adaptive nutritionist for MonMon.
Your mission is to construct a balanced 7-day meal plan, detailed recipes for ALL meals, and an itemized Sainsbury's shopping list that dynamically adapts to the user's weekly food budget and household size (people being fed).

STRICT REQUIREMENTS:
1. Supermarket: Sainsbury's. Adapt product selection to the per-person budget tier (weeklyBudget / peopleCount):
   - FRUGAL (< £55/person/wk): Prioritize Sainsbury's entry-tier budget brand 'Stamford Street Co.' and own-brand value essentials.
   - MODERATE (£55 - £80/person/wk): Sainsbury's own-brand staples with standard fresh chicken, beef mince, eggs, seasonal produce.
   - GENEROUS (£80 - £120/person/wk): Quality fresh meats (chicken fillets, lean beef mince, pork loin), fresh salmon fillets, produce, Taste the Difference items.
   - OPULENT (>= £120/person/wk): Exclusively Sainsbury's 'Taste the Difference' (TTD) range, prime butcher cuts (Ribeye, Sirloin, Fillet Steaks), and luxury seafood (Salmon, Scallops, Jumbo King Prawns).
2. Household Size & Portion Scaling:
   - The plan MUST feed the exact number of people specified ('peopleCount').
   - All recipe 'servings' MUST be at least equal to peopleCount.
   - Shopping list retail pack quantities must provide enough food for all people for 7 days.
3. Budget ceiling & scaling: The shopping basket total MUST strictly land between 88% and 96% of weeklyBudget.
4. Nutrition & Dietary Variety: Whole foods, balanced macros, vegetable variety.
5. RECIPES FOR ALL MEALS:
   - Every single meal across all 7 days (all 7 dinners, plus any cooked lunches) MUST have a corresponding full recipe in the 'recipes' array (at least 7 full recipes), not just batch cook ones!
   - Each recipe must specify 'title', 'servings' (>= peopleCount), 'prepTimeMinutes', 'cookTimeMinutes', 'ingredientsUsed' (array of strings), and 'instructions' (array of clear concise steps).
6. Shopping List Unit Requirements:
   - 'quantity' is STRICTLY integer number of retail packs (1, 2, 3, etc.).
   - 'packageSize' describes retail package (e.g. '500g bag', '640g pack', '400g tin').
   - 'estimatedPrice' is price for ONE pack.

JSON RESPONSE SCHEMA:
{
  "title": "string",
  "weeklyBudget": float,
  "estimatedCost": float,
  "safetyBuffer": float,
  "nutritionHighlight": "string",
  "shoppingList": [
    {"name": "string", "category": "Produce"|"Meat & Poultry"|"Dairy & Eggs"|"Bakery"|"Pantry & Pulses"|"Tins & Cans"|"Frozen"|"Other Staples", "packageSize": "string", "quantity": number, "estimatedPrice": float}
  ],
  "days": [
    {"dayName": "Monday"..."Sunday", "breakfast": "string", "lunch": "string", "dinner": "string", "snack": "string", "prepNotes": "string"}
  ],
  "recipes": [
    {"title": "string", "servings": number, "prepTimeMinutes": number, "cookTimeMinutes": number, "ingredientsUsed": ["string"], "instructions": ["string"]}
  ]
}"#
}

fn build_nommon_user_prompt(budget: f32, people: i32, postcode: Option<&str>) -> String {
    let p = people.max(1).min(12);
    let min_target = budget * 0.88;
    let max_target = budget * 0.96;
    let per_person = budget / (p as f32);

    let tier_desc = if per_person < 55.0 {
        "Frugal / Value (Stamford Street Co. staples, oats, lentils, value mince)"
    } else if per_person < 80.0 {
        "Moderate Everyday (Sainsbury's own-brand essentials, fresh vegetables, chicken)"
    } else if per_person < 120.0 {
        "Generous (Quality fresh cuts, salmon, seasonal vegetables, selected Taste the Difference)"
    } else {
        "Opulent Gourmet Indulgence (Taste the Difference prime steaks, seafood, artisan sourdough, organic produce)"
    };

    let loc_str = postcode
        .filter(|s| !s.trim().is_empty())
        .map(|s| format!(" The user shops near UK postcode '{}'.", s.trim().to_uppercase()))
        .unwrap_or_default();

    format!(
        "Please generate a 7-day Sainsbury's grocery and meal plan for {} person(s) with a total weekly food budget of £{:.2} (£{:.2}/person).\n\
         Target Basket Range: between £{:.2} and £{:.2} (88% - 96% of budget).\n\
         Budget Tier: {}.{}\n\
         CRITICAL: Include FULL RECIPES for ALL 7 days' dinners (at least 7 recipes in the 'recipes' array) with step-by-step instructions and servings equal to {}.\n\
         Respond strictly with valid JSON only.",
        p, budget, per_person, min_target, max_target, tier_desc, loc_str, p
    )
}

fn build_fallback_plan(budget: f32, people: i32) -> Value {
    let p = people.max(1);
    let estimated_cost = ((budget * 0.91) * 100.0).round() / 100.0;
    let safety_buffer = ((budget - estimated_cost) * 100.0).round() / 100.0;

    serde_json::json!({
        "title": format!("NomMon 7-Day Sainsbury's Plan ({} Person{})", p, if p == 1 { "" } else { "s" }),
        "weeklyBudget": budget,
        "estimatedCost": estimated_cost,
        "safetyBuffer": safety_buffer,
        "nutritionHighlight": "High fiber, lean protein, balanced micronutrients & 100% complete recipes for every day",
        "shoppingList": [
            { "name": "Stamford Street Co. British Beef Mince 20% Fat 500g", "category": "Meat & Poultry", "packageSize": "500g pack", "quantity": p, "estimatedPrice": 2.49 },
            { "name": "Sainsbury's British Fresh Chicken Breast Fillets 640g", "category": "Meat & Poultry", "packageSize": "640g pack", "quantity": p, "estimatedPrice": 4.60 },
            { "name": "Sainsbury's Scottish Salmon Fillets x2 240g", "category": "Meat & Poultry", "packageSize": "240g pack", "quantity": p, "estimatedPrice": 4.75 },
            { "name": "Taste the Difference 30-Day Aged British Ribeye Steak 225g", "category": "Meat & Poultry", "packageSize": "225g pack", "quantity": p, "estimatedPrice": 5.50 },
            { "name": "Sainsbury's Whole Milk 2.27L (4 Pints)", "category": "Dairy & Eggs", "packageSize": "4 pints", "quantity": 1, "estimatedPrice": 1.65 },
            { "name": "Sainsbury's Free Range Eggs Medium x12", "category": "Dairy & Eggs", "packageSize": "12 pack", "quantity": 1, "estimatedPrice": 2.60 },
            { "name": "Sainsbury's Mature British Cheddar 400g", "category": "Dairy & Eggs", "packageSize": "400g block", "quantity": 1, "estimatedPrice": 3.00 },
            { "name": "Stamford Street Co. Wholemeal Sliced Bread 800g", "category": "Bakery", "packageSize": "800g loaf", "quantity": p, "estimatedPrice": 0.45 },
            { "name": "Sainsbury's Baking Potatoes Loose x4", "category": "Produce", "packageSize": "4 pack", "quantity": p, "estimatedPrice": 1.40 },
            { "name": "Sainsbury's Carrots Loose 500g", "category": "Produce", "packageSize": "500g bag", "quantity": 1, "estimatedPrice": 0.35 },
            { "name": "Sainsbury's Brown Onions Loose x3", "category": "Produce", "packageSize": "3 pack", "quantity": 1, "estimatedPrice": 0.90 },
            { "name": "Sainsbury's Broccoli Loose 350g", "category": "Produce", "packageSize": "350g head", "quantity": 1, "estimatedPrice": 0.79 },
            { "name": "Sainsbury's Baby Spinach 200g", "category": "Produce", "packageSize": "200g bag", "quantity": 1, "estimatedPrice": 1.10 },
            { "name": "Sainsbury's Bananas Loose x6", "category": "Produce", "packageSize": "6 pack", "quantity": 1, "estimatedPrice": 0.95 },
            { "name": "Stamford Street Co. Chopped Tomatoes 400g", "category": "Tins & Cans", "packageSize": "400g tin", "quantity": 3, "estimatedPrice": 0.35 },
            { "name": "Sainsbury's Chickpeas in Water 400g", "category": "Tins & Cans", "packageSize": "400g tin", "quantity": 2, "estimatedPrice": 0.65 },
            { "name": "Sainsbury's Red Split Lentils 500g", "category": "Pantry & Pulses", "packageSize": "500g bag", "quantity": 1, "estimatedPrice": 1.25 },
            { "name": "Sainsbury's Long Grain Rice 1kg", "category": "Pantry & Pulses", "packageSize": "1kg bag", "quantity": 1, "estimatedPrice": 1.45 },
            { "name": "Sainsbury's Penne Pasta 500g", "category": "Pantry & Pulses", "packageSize": "500g bag", "quantity": 1, "estimatedPrice": 0.75 },
            { "name": "Sainsbury's Rolled Porridge Oats 1kg", "category": "Pantry & Pulses", "packageSize": "1kg bag", "quantity": 1, "estimatedPrice": 0.90 }
        ],
        "days": [
            { "dayName": "Monday", "breakfast": "Warm Porridge with sliced banana & honey", "lunch": "Cheddar & Tomato wholemeal toastie", "dinner": "Hearty Beef & Vegetable Cottage Pie", "snack": "Boiled free range egg", "prepNotes": "Boil potatoes and mash with butter & milk" },
            { "dayName": "Tuesday", "breakfast": "Scrambled free range eggs on wholemeal toast", "lunch": "Leftover Cottage Pie & steamed broccoli", "dinner": "Creamy Garlic Tuscan Chicken with Rice", "snack": "Fresh banana", "prepNotes": "Simmer chicken breasts with spinach and cream" },
            { "dayName": "Wednesday", "breakfast": "Rolled oats with warm milk & cinnamon", "lunch": "Tuscan Chicken rice bowl", "dinner": "Spiced Chickpea & Spinach Curry", "snack": "Cheddar cubes with carrot sticks", "prepNotes": "Toast whole spices for fragrant aromatic curry" },
            { "dayName": "Thursday", "breakfast": "Poached eggs on wholemeal toast with spinach", "lunch": "Warm Chickpea curry with rice", "dinner": "Pan-Seared Salmon Fillets with Crushed Potatoes", "snack": "Banana oat smoothie", "prepNotes": "Crisp salmon skin in hot skillet for 4 mins" },
            { "dayName": "Friday", "breakfast": "Porridge with sliced banana", "lunch": "Egg salad wholemeal sandwich", "dinner": "Baked Stamford Street Beef & Tomato Penne", "snack": "Toasted wholemeal soldiers", "prepNotes": "Top pasta bake with grated cheddar and broil" },
            { "dayName": "Saturday", "breakfast": "Weekend full breakfast: Eggs, grilled tomato, toast", "lunch": "Leftover Baked Beef Penne", "dinner": "30-Day Aged Ribeye Steak & Rosemary Wedges", "snack": "Cheddar cheese on toast", "prepNotes": "Rest steak 5 minutes before slicing" },
            { "dayName": "Sunday", "breakfast": "Soft boiled eggs with buttered wholemeal soldiers", "lunch": "Lentil soup with crusty toast", "dinner": "Slow-Simmered Lentil Dahl & Basmati Rice", "snack": "Warm spiced milk", "prepNotes": "Simmer red lentils with turmeric and cumin" }
        ],
        "recipes": [
            {
                "title": "Hearty Beef & Vegetable Cottage Pie",
                "servings": p,
                "prepTimeMinutes": 20,
                "cookTimeMinutes": 35,
                "ingredientsUsed": ["Stamford Street Co. British Beef Mince 20% Fat 500g", "Sainsbury's Baking Potatoes Loose x4", "Sainsbury's Carrots Loose 500g", "Sainsbury's Brown Onions Loose x3", "Stamford Street Co. Chopped Tomatoes 400g"],
                "instructions": [
                    "Peel and cube potatoes; boil in salted water for 15 minutes until tender, then mash with a splash of milk and seasoning.",
                    "In a deep pan, brown the minced beef with diced onion and sliced carrots until golden.",
                    "Stir in chopped tomatoes, a dash of water and pepper, then simmer on low heat for 15 minutes.",
                    "Transfer meat sauce to an oven dish, spread the mashed potatoes evenly on top, and bake at 200°C for 20 minutes until crisp and bubbling."
                ]
            },
            {
                "title": "Creamy Garlic Tuscan Chicken with Rice",
                "servings": p,
                "prepTimeMinutes": 15,
                "cookTimeMinutes": 25,
                "ingredientsUsed": ["Sainsbury's British Fresh Chicken Breast Fillets 640g", "Sainsbury's Baby Spinach 200g", "Sainsbury's Long Grain Rice 1kg", "Sainsbury's Whole Milk 2.27L (4 Pints)"],
                "instructions": [
                    "Cook long grain rice according to package directions in lightly salted boiling water.",
                    "Season chicken breast fillets with salt and pepper. Sear in a skillet with oil for 6 minutes per side until cooked through.",
                    "Remove chicken; in the same pan, stir in fresh baby spinach, crushed garlic, and milk, simmering until gently thickened.",
                    "Return chicken to the sauce to coat thoroughly and serve warm over fluffy steamed rice."
                ]
            },
            {
                "title": "Spiced Chickpea & Spinach Curry",
                "servings": p,
                "prepTimeMinutes": 10,
                "cookTimeMinutes": 20,
                "ingredientsUsed": ["Sainsbury's Chickpeas in Water 400g", "Stamford Street Co. Chopped Tomatoes 400g", "Sainsbury's Baby Spinach 200g", "Sainsbury's Brown Onions Loose x3", "Sainsbury's Long Grain Rice 1kg"],
                "instructions": [
                    "Finely dice the onion and sauté in a medium pot with oil until softened and translucent.",
                    "Add curry spices (cumin, turmeric, chili) and stir for 1 minute until fragrant.",
                    "Pour in the drained chickpeas and canned chopped tomatoes, simmering gently for 15 minutes.",
                    "Fold in the fresh baby spinach during the final 3 minutes until wilted, and serve alongside steamed rice."
                ]
            },
            {
                "title": "Pan-Seared Salmon Fillets with Crushed Potatoes",
                "servings": p,
                "prepTimeMinutes": 10,
                "cookTimeMinutes": 18,
                "ingredientsUsed": ["Sainsbury's Scottish Salmon Fillets x2 240g", "Sainsbury's Baking Potatoes Loose x4", "Sainsbury's Broccoli Loose 350g"],
                "instructions": [
                    "Boil cubed potatoes in salted water until soft; crush lightly with a fork and season with pepper.",
                    "Steam fresh broccoli florets for 5 minutes until tender-crisp.",
                    "Pat salmon fillets dry with kitchen paper and season the skin generously with salt.",
                    "Sear skin-side down in a hot skillet for 4-5 minutes until golden and crisp, flip gently and cook for 2 more minutes."
                ]
            },
            {
                "title": "Baked Stamford Street Beef & Tomato Penne",
                "servings": p,
                "prepTimeMinutes": 15,
                "cookTimeMinutes": 25,
                "ingredientsUsed": ["Stamford Street Co. British Beef Mince 20% Fat 500g", "Sainsbury's Penne Pasta 500g", "Stamford Street Co. Chopped Tomatoes 400g", "Sainsbury's Mature British Cheddar 400g"],
                "instructions": [
                    "Boil penne pasta in salted water for 9 minutes until al dente; drain.",
                    "Brown remaining minced beef with diced onion and stir in chopped tomatoes with herbs.",
                    "Combine the pasta with the beef tomato sauce and transfer to an ovenproof baking dish.",
                    "Generously top with grated mature cheddar and bake at 200°C for 15 minutes until cheese is melted and golden."
                ]
            },
            {
                "title": "30-Day Aged Gourmet Ribeye Steak & Rosemary Wedges",
                "servings": p,
                "prepTimeMinutes": 10,
                "cookTimeMinutes": 30,
                "ingredientsUsed": ["Taste the Difference 30-Day Aged British Ribeye Steak 225g", "Sainsbury's Baking Potatoes Loose x4", "Sainsbury's Broccoli Loose 350g"],
                "instructions": [
                    "Cut potatoes into thick wedges, toss with vegetable oil, salt, and rosemary, and roast at 210°C for 30 minutes.",
                    "Bring ribeye steak to room temperature for 20 minutes; season both sides generously with sea salt and cracked pepper.",
                    "Heat a heavy cast-iron skillet until smoking hot. Sear steak for 2.5 minutes per side for medium-rare.",
                    "Remove steak and rest on a warm board for 5 minutes before carving; serve with golden wedges and steamed broccoli."
                ]
            },
            {
                "title": "Slow-Simmered Lentil Dahl & Basmati Rice",
                "servings": p,
                "prepTimeMinutes": 10,
                "cookTimeMinutes": 30,
                "ingredientsUsed": ["Sainsbury's Red Split Lentils 500g", "Sainsbury's Long Grain Rice 1kg", "Sainsbury's Brown Onions Loose x3", "Stamford Street Co. Chopped Tomatoes 400g"],
                "instructions": [
                    "Rinse red split lentils in cold water until water runs clear.",
                    "Sauté diced onion with turmeric, cumin, and garlic in a deep saucepan until golden.",
                    "Add red lentils, 2 cups of water, and half a can of chopped tomatoes. Bring to a gentle boil.",
                    "Reduce heat and simmer for 25 minutes, stirring occasionally, until lentils melt into a creamy fragrant dahl. Serve with rice."
                ]
            }
        ]
    })
}

pub async fn get_nommon_plan_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<NomMonQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;
    let budget = q.budget.unwrap_or(50.0);
    let people = q.people.unwrap_or(1);
    let postcode = q.postcode.as_deref();
    let force = q.force.unwrap_or(false);

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

    if !force {
        if let Some(r) = locked_row {
            let plan_str: String = r.get("planjson");
            let locked_at: Option<i64> = r.get("lockedat");
            let parsed: Value = serde_json::from_str(&plan_str).unwrap_or(Value::Null);

            return Ok(Json(serde_json::json!({
                "configured": true,
                "locked": true,
                "lockedAt": locked_at,
                "lastGenerated": locked_at,
                "plan": parsed
            })));
        }
    }

    // 2. Mistral AI generation if API key is provided
    let api_key = std::env::var("MISTRAL_API_KEY").unwrap_or_default();
    if !api_key.trim().is_empty() {
        let model = get_nommon_model();
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(60))
            .build()
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

        let system_prompt = build_nommon_system_prompt();
        let user_prompt = build_nommon_user_prompt(budget, people, postcode);

        let payload = serde_json::json!({
            "model": model,
            "temperature": 0.35,
            "max_tokens": 4000,
            "response_format": { "type": "json_object" },
            "messages": [
                { "role": "system", "content": system_prompt },
                { "role": "user", "content": user_prompt }
            ]
        });

        let resp_result = client
            .post("https://api.mistral.ai/v1/chat/completions")
            .bearer_auth(api_key.trim())
            .header("Content-Type", "application/json")
            .json(&payload)
            .send()
            .await;

        if let Ok(resp) = resp_result {
            if resp.status().is_success() {
                if let Ok(body) = resp.json::<Value>().await {
                    if let Some(content_str) = body
                        .pointer("/choices/0/message/content")
                        .and_then(|v| v.as_str())
                    {
                        if let Ok(parsed_plan) = serde_json::from_str::<Value>(content_str) {
                            return Ok(Json(serde_json::json!({
                                "configured": true,
                                "locked": false,
                                "plan": parsed_plan
                            })));
                        }
                    }
                }
            }
        }
    }

    // 3. Fallback comprehensive plan with all 7 recipes
    let fallback = build_fallback_plan(budget, people);
    Ok(Json(serde_json::json!({
        "configured": !api_key.trim().is_empty(),
        "locked": false,
        "plan": fallback
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
