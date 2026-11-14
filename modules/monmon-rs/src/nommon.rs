use crate::spog::resolve_user_id;
use crate::AppState;
use askama::Template;
use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, Json},
};
use serde::Deserialize;
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
    headers: HeaderMap,
    Query(query): Query<NomMonQuery>,
) -> Result<Html<String>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, query.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;

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
    if let Ok(model) = std::env::var("NOMMON_MISTRAL_MODEL") {
        if !model.trim().is_empty() {
            return model;
        }
    }
    if let Ok(model) = std::env::var("MISTRAL_MODEL") {
        if !model.trim().is_empty() && model != "mistral-small-latest" {
            return model;
        }
    }
    "mistral-medium-latest".to_string()
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
   - All recipe 'servings' MUST be at least equal to peopleCount (scaled up for batch cooks).
   - Shopping list retail pack quantities must provide enough food for all people for 7 days.
3. Budget ceiling & scaling: The shopping basket total MUST strictly land between 88% and 96% of weeklyBudget.
4. Nutrition & Dietary Variety: Whole foods, balanced macros, vegetable variety.
5. MEAL PLANNING PHILOSOPHY — PRIMARILY BATCH COOKING WITH SOME INDIVIDUAL RECIPES:
   - The weekly plan MUST be built primarily around 2 to 3 hearty, budget-stretching BATCH COOKS (e.g. big family stews, bolognese, chili con carne, rich curries, pasta bakes) where cooking once yields dinner plus next-day lunches or dinners (e.g. Monday batch cooked dinner, Tuesday lunch/dinner reheat).
   - Complement the batch cooks with 1 to 2 fresh individual recipes for special nights (e.g. fresh pan-seared fish, steak night, or quick stir-fry).
   - In the 'days' array: Clearly indicate when a batch meal is cooked and when leftovers are eaten (e.g. 'Dinner: Hearty Beef & Veg Cottage Pie (Batch Cook)', and the next day 'Lunch: Reheated Cottage Pie (from batch)').
6. FULL RECIPES FOR ALL MEALS:
   - Every single cooked meal on the plan (all 2-3 batch cooks AND the 1-2 individual fresh recipes, covering every day of the week) MUST have a corresponding complete recipe in the 'recipes' array with step-by-step instructions.
   - For batch-cooking recipes: Include '(Batch Cook)' in the title and set 'servings' to at least 2x peopleCount (e.g. if feeding 2 people, a batch cook yields 4+ servings).
   - For individual recipes: Set 'servings' equal to peopleCount.
   - Each recipe must specify 'title', 'servings', 'prepTimeMinutes', 'cookTimeMinutes', 'ingredientsUsed' (array of strings), and 'instructions' (array of clear concise steps).
7. Shopping List Unit Requirements:
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

fn attempt_repair_truncated_json(raw: &str) -> Option<Value> {
    let mut trimmed = raw.trim();
    if let Some(rest) = trimmed.strip_prefix("```json") {
        trimmed = rest;
    } else if let Some(rest) = trimmed.strip_prefix("```") {
        trimmed = rest;
    }
    if let Some(rest) = trimmed.strip_suffix("```") {
        trimmed = rest;
    }
    let trimmed = trimmed.trim();

    // 1. Try parsing directly
    if let Ok(v) = serde_json::from_str::<Value>(trimmed) {
        return Some(v);
    }

    // 2. Scan backwards to find a clean truncation point
    let chars: Vec<char> = trimmed.chars().collect();
    let max_lookback = chars.len().min(4000);
    let start_pos = chars.len();
    let end_pos = chars.len().saturating_sub(max_lookback);

    for i in (end_pos..=start_pos).rev() {
        let candidate: String = chars[..i].iter().collect();
        let mut c_trimmed = candidate.trim_end();
        if c_trimmed.ends_with(',') {
            c_trimmed = c_trimmed[..c_trimmed.len() - 1].trim_end();
        }

        let mut stack = Vec::new();
        let mut in_string = false;
        let mut escaped = false;
        let mut valid = true;

        for ch in c_trimmed.chars() {
            if escaped {
                escaped = false;
                continue;
            }
            if ch == '\\' {
                escaped = true;
                continue;
            }
            if ch == '"' {
                in_string = !in_string;
                continue;
            }
            if !in_string {
                match ch {
                    '{' => stack.push('}'),
                    '[' => stack.push(']'),
                    '}' | ']' => {
                        if stack.last() == Some(&ch) {
                            stack.pop();
                        } else {
                            valid = false;
                            break;
                        }
                    }
                    _ => {}
                }
            }
        }

        if !valid || in_string {
            continue;
        }

        if c_trimmed.ends_with(':') {
            continue;
        }

        let mut repaired = c_trimmed.to_string();
        while let Some(closer) = stack.pop() {
            repaired.push(closer);
        }

        if let Ok(val) = serde_json::from_str::<Value>(&repaired) {
            if val.is_object() && (val.get("shoppingList").is_some() || val.get("title").is_some() || val.get("planTitle").is_some()) {
                return Some(val);
            }
        }
    }

    None
}

fn build_nommon_user_prompt(budget: f32, people: i32, postcode: Option<&str>) -> String {
    let p = people.max(1).min(12);
    let min_target = budget * 0.88;
    let max_target = budget * 0.96;
    let per_person = budget / (p as f32);

    let tier_desc = if per_person < 55.0 {
        "Frugal / Value (Stamford Street Co. staples, oats, lentils, value mince, bulk batch cooking)"
    } else if per_person < 80.0 {
        "Moderate Everyday (Sainsbury's own-brand essentials, fresh vegetables, chicken, family batch cooks)"
    } else if per_person < 120.0 {
        "Generous (Quality fresh cuts, salmon, seasonal vegetables, selected Taste the Difference, weekend steak)"
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
         CORE MEAL STRUCTURE: Primarily BATCH COOKING (2-3 large batch cooks like cottage pie, rich curry, or pasta bake that provide dinner plus next-day lunches/dinners) paired with 1-2 fresh individual recipes for other nights.\n\
         RECIPES REQUIREMENT: Every meal in the plan must have a full recipe in the 'recipes' array (both batch cooks with scaled servings AND individual meals).\n\
         Respond strictly with valid JSON only.",
        p, budget, per_person, min_target, max_target, tier_desc, loc_str
    )
}

fn build_fallback_plan(budget: f32, people: i32) -> Value {
    let p = people.max(1);
    let estimated_cost = ((budget * 0.91) * 100.0).round() / 100.0;
    let safety_buffer = ((budget - estimated_cost) * 100.0).round() / 100.0;
    let batch_servings = p * 2;

    serde_json::json!({
        "title": format!("NomMon 7-Day Sainsbury's Batch Cooking Plan ({} Person{})", p, if p == 1 { "" } else { "s" }),
        "weeklyBudget": budget,
        "estimatedCost": estimated_cost,
        "safetyBuffer": safety_buffer,
        "nutritionHighlight": "Batch cooking efficiency with high fiber, lean protein, balanced micronutrients & 100% complete recipes for every meal",
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
            { "dayName": "Monday", "breakfast": "Warm Porridge with sliced banana & honey", "lunch": "Cheddar & Tomato wholemeal toastie", "dinner": "Hearty Beef & Vegetable Cottage Pie (Batch Cook Day 1)", "snack": "Boiled free range egg", "prepNotes": "Batch cook double batch tonight; portion half into fridge for Tuesday's lunch & dinner" },
            { "dayName": "Tuesday", "breakfast": "Scrambled free range eggs on wholemeal toast", "lunch": "Reheated Cottage Pie (from Monday's batch) & steamed broccoli", "dinner": "Creamy Garlic Tuscan Chicken with Rice (Batch Cook Day 1)", "snack": "Fresh banana", "prepNotes": "Simmer double chicken portion in rich garlic spinach sauce for Wednesday's lunch" },
            { "dayName": "Wednesday", "breakfast": "Rolled oats with warm milk & cinnamon", "lunch": "Reheated Tuscan Chicken Rice Bowl (from Tuesday's batch)", "dinner": "Spiced Chickpea & Spinach Curry (Batch Cook Day 1)", "snack": "Cheddar cubes with carrot sticks", "prepNotes": "Toast whole cumin and coriander seeds for deep fragrant batch curry" },
            { "dayName": "Thursday", "breakfast": "Poached eggs on wholemeal toast with spinach", "lunch": "Warm Chickpea Curry with Rice (from Wednesday's batch)", "dinner": "Pan-Seared Salmon Fillets with Crushed Potatoes (Fresh Meal)", "snack": "Banana oat smoothie", "prepNotes": "Crisp salmon skin in hot skillet for 4 mins for a quick fresh Thursday dinner" },
            { "dayName": "Friday", "breakfast": "Porridge with sliced banana", "lunch": "Egg salad wholemeal sandwich", "dinner": "Baked Stamford Street Beef & Tomato Penne (Batch Cook Day 1)", "snack": "Toasted wholemeal soldiers", "prepNotes": "Bake in large casserole dish; save half for Saturday lunch" },
            { "dayName": "Saturday", "breakfast": "Weekend full breakfast: Eggs, grilled tomato, toast", "lunch": "Reheated Baked Beef Penne (from Friday's batch)", "dinner": "30-Day Aged Ribeye Steak & Rosemary Wedges (Fresh Meal)", "snack": "Cheddar cheese on toast", "prepNotes": "Sear steak to medium-rare; rest 5 minutes with garlic butter" },
            { "dayName": "Sunday", "breakfast": "Soft boiled eggs with buttered wholemeal soldiers", "lunch": "Leftover steak slices on warm toast with mustard", "dinner": "Slow-Simmered Lentil Dahl & Basmati Rice (Fresh Meal)", "snack": "Warm spiced milk", "prepNotes": "Simmer red lentils with turmeric and cumin for a soothing end to the week" }
        ],
        "recipes": [
            {
                "title": "Hearty Beef & Vegetable Cottage Pie (Batch Cook)",
                "servings": batch_servings,
                "prepTimeMinutes": 20,
                "cookTimeMinutes": 35,
                "ingredientsUsed": ["Stamford Street Co. British Beef Mince 20% Fat 500g", "Sainsbury's Baking Potatoes Loose x4", "Sainsbury's Carrots Loose 500g", "Sainsbury's Brown Onions Loose x3", "Stamford Street Co. Chopped Tomatoes 400g"],
                "instructions": [
                    "Peel and cube potatoes; boil in salted water for 15 minutes until tender, then mash with a splash of milk and seasoning.",
                    "In a deep pan, brown the minced beef with diced onion and sliced carrots until golden.",
                    "Stir in chopped tomatoes, a dash of water and pepper, then simmer on low heat for 15 minutes.",
                    "Transfer meat sauce to an oven dish, spread the mashed potatoes evenly on top, and bake at 200°C for 20 minutes until crisp and bubbling. Portion half for Tuesday."
                ]
            },
            {
                "title": "Creamy Garlic Tuscan Chicken with Rice (Batch Cook)",
                "servings": batch_servings,
                "prepTimeMinutes": 15,
                "cookTimeMinutes": 25,
                "ingredientsUsed": ["Sainsbury's British Fresh Chicken Breast Fillets 640g", "Sainsbury's Long Grain Rice 1kg", "Sainsbury's Baby Spinach 200g", "Sainsbury's Whole Milk 2.27L (4 Pints)"],
                "instructions": [
                    "Rinse long grain rice and simmer in double volume of salted water for 12 minutes until fluffy.",
                    "Dice chicken breast fillets and sear in a hot skillet with olive oil until golden brown on all sides.",
                    "Add garlic, splash of milk, and fresh baby spinach; simmer gently for 8 minutes until sauce is glossy and chicken is cooked through.",
                    "Serve over steamed rice. Package the remaining portions for Wednesday's lunch."
                ]
            },
            {
                "title": "Spiced Chickpea & Spinach Curry (Batch Cook)",
                "servings": batch_servings,
                "prepTimeMinutes": 10,
                "cookTimeMinutes": 20,
                "ingredientsUsed": ["Sainsbury's Chickpeas in Water 400g", "Sainsbury's Baby Spinach 200g", "Sainsbury's Brown Onions Loose x3", "Stamford Street Co. Chopped Tomatoes 400g"],
                "instructions": [
                    "Finely dice onion and sauté in a saucepan with oil and curry spices until softened and fragrant.",
                    "Pour in chopped tomatoes and drained chickpeas; simmer on medium heat for 15 minutes.",
                    "Fold in baby spinach leaves until wilted, seasoning with salt and pepper to taste.",
                    "Serve hot with steamed rice or bread. Chill the second half for Thursday's lunch."
                ]
            },
            {
                "title": "Baked Stamford Street Beef & Tomato Penne (Batch Cook)",
                "servings": batch_servings,
                "prepTimeMinutes": 15,
                "cookTimeMinutes": 25,
                "ingredientsUsed": ["Sainsbury's Penne Pasta 500g", "Stamford Street Co. British Beef Mince 20% Fat 500g", "Stamford Street Co. Chopped Tomatoes 400g", "Sainsbury's Mature British Cheddar 400g"],
                "instructions": [
                    "Boil penne pasta in salted water for 9 minutes until al dente; drain.",
                    "Brown the minced beef with chopped onion, stir in chopped tomatoes, and simmer for 10 minutes.",
                    "Toss pasta with meat sauce in an ovenproof baking dish and top generously with grated cheddar.",
                    "Bake at 190°C for 15 minutes until cheese is golden and bubbling. Save remaining portions for Saturday lunch."
                ]
            },
            {
                "title": "Pan-Seared Salmon Fillets with Crushed Potatoes (Fresh Meal)",
                "servings": p,
                "prepTimeMinutes": 10,
                "cookTimeMinutes": 15,
                "ingredientsUsed": ["Sainsbury's Scottish Salmon Fillets x2 240g", "Sainsbury's Baking Potatoes Loose x4", "Sainsbury's Broccoli Loose 350g"],
                "instructions": [
                    "Boil potatoes until tender, drain and lightly crush with a fork, olive oil, and sea salt.",
                    "Steam broccoli florets for 5 minutes until vibrant green.",
                    "Heat oil in a skillet over high heat; place salmon skin-side down and press gently for 4 minutes until skin is crisp, flip and cook 2 minutes.",
                    "Serve salmon immediately over crushed potatoes with steamed broccoli."
                ]
            },
            {
                "title": "30-Day Aged Ribeye Steak & Rosemary Wedges (Fresh Meal)",
                "servings": p,
                "prepTimeMinutes": 10,
                "cookTimeMinutes": 20,
                "ingredientsUsed": ["Taste the Difference 30-Day Aged British Ribeye Steak 225g", "Sainsbury's Baking Potatoes Loose x4"],
                "instructions": [
                    "Cut potatoes into wedges, toss with olive oil, salt and pepper; roast at 210°C for 25 minutes until crisp.",
                    "Bring ribeye steak to room temperature; pat dry and season generously with salt.",
                    "Sear in a smoking hot pan with oil and butter for 2.5 minutes per side for medium-rare.",
                    "Rest steak on a warm cutting board for 5 minutes before serving with hot wedges."
                ]
            },
            {
                "title": "Slow-Simmered Lentil Dahl & Basmati Rice (Fresh Meal)",
                "servings": p,
                "prepTimeMinutes": 10,
                "cookTimeMinutes": 25,
                "ingredientsUsed": ["Sainsbury's Red Split Lentils 500g", "Sainsbury's Long Grain Rice 1kg", "Sainsbury's Brown Onions Loose x3"],
                "instructions": [
                    "Rinse red split lentils until water runs clear.",
                    "In a pot, sauté diced onion with spices; add lentils and 3 cups of water.",
                    "Simmer on low heat for 20 minutes stirring occasionally until lentils are creamy and tender.",
                    "Serve hot over steamed long grain rice with fresh chopped herbs or a dollop of yogurt."
                ]
            }
        ]
    })
}

pub async fn get_nommon_plan_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<NomMonQuery>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
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
        tracing::info!("NomMon calling Mistral API (model: {}) for budget £{:.2}, people: {}", model, budget, people);

        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(60))
            .build()
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

        let system_prompt = build_nommon_system_prompt();
        let user_prompt = build_nommon_user_prompt(budget, people, postcode);

        let payload = serde_json::json!({
            "model": model,
            "temperature": 0.35,
            "max_tokens": 8192,
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

        match resp_result {
            Ok(resp) => {
                let status = resp.status();
                if status.is_success() {
                    match resp.json::<Value>().await {
                        Ok(body) => {
                            if let Some(content_str) = body
                                .pointer("/choices/0/message/content")
                                .and_then(|v| v.as_str())
                            {
                                if let Some(parsed_plan) = attempt_repair_truncated_json(content_str) {
                                    tracing::info!("NomMon successfully generated fresh meal plan from Mistral ({})", model);
                                    return Ok(Json(serde_json::json!({
                                        "configured": true,
                                        "locked": false,
                                        "plan": parsed_plan
                                    })));
                                } else {
                                    tracing::warn!("Failed to parse or repair Mistral JSON content. Raw snippet: {}", &content_str[..content_str.len().min(300)]);
                                }
                            }
                        }
                        Err(err) => {
                            tracing::warn!("Failed to deserialize Mistral response JSON: {:?}", err);
                        }
                    }
                } else {
                    let err_text = resp.text().await.unwrap_or_default();
                    tracing::warn!("Mistral API returned error status {}: {}", status, err_text);
                }
            }
            Err(err) => {
                tracing::warn!("Mistral API HTTP request error: {:?}", err);
            }
        }
    } else {
        tracing::warn!("MISTRAL_API_KEY environment variable is missing or empty; NomMon serving fallback batch plan");
    }

    // 3. Fallback comprehensive batch cooking plan with all recipes
    let fallback = build_fallback_plan(budget, people);
    Ok(Json(serde_json::json!({
        "configured": !api_key.trim().is_empty(),
        "locked": false,
        "plan": fallback
    })))
}

pub async fn lock_nommon_plan_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(payload): Json<LockPlanRequest>,
) -> Result<Json<Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, payload.user_id.as_deref());
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
