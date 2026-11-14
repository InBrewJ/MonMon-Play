use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::Json,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, LazyLock, RwLock};
use std::time::{Duration, Instant};

use crate::AppState;
use crate::models::{Account, Outgoing};
use crate::net_worth::NetWorthDto;
use crate::spog::{resolve_user_id, SpogMetrics};

#[derive(Debug, Deserialize)]
pub struct EdQuery {
    #[serde(alias = "userId")]
    pub user_id: Option<String>,
    pub force: Option<bool>,
}

impl EdQuery {
    pub fn get_user_id(&self) -> String {
        self.user_id.clone().unwrap_or_else(|| "demo".to_string())
    }
}

#[derive(Debug, Serialize)]
pub struct EdSaysResponse {
    #[serde(rename = "edSays")]
    pub ed_says: String,
    pub configured: bool,
    #[serde(rename = "lastSpoke", skip_serializing_if = "Option::is_none")]
    pub last_spoke: Option<i64>,
}

#[derive(Clone)]
struct CacheEntry {
    text: String,
    fingerprint: u64,
    timestamp_millis: i64,
    created_at: Instant,
}

static SPOG_CACHE: LazyLock<RwLock<HashMap<String, CacheEntry>>> =
    LazyLock::new(|| RwLock::new(HashMap::new()));
static OUTGOINGS_CACHE: LazyLock<RwLock<HashMap<String, CacheEntry>>> =
    LazyLock::new(|| RwLock::new(HashMap::new()));
static NETWORTH_CACHE: LazyLock<RwLock<HashMap<String, CacheEntry>>> =
    LazyLock::new(|| RwLock::new(HashMap::new()));

/// Use-case specific TTLs for Ed AI advice:
/// - SPOG: 1 day OR if any financial metric / balance changes
/// - Outgoings: 1 week OR if any outgoing / commitment changes
/// - Net Worth: 1 month OR if any asset, debt, or portfolio figure changes
pub const SPOG_CACHE_TTL: Duration = Duration::from_secs(24 * 60 * 60); // 1 day
pub const OUTGOINGS_CACHE_TTL: Duration = Duration::from_secs(7 * 24 * 60 * 60); // 1 week
pub const NETWORTH_CACHE_TTL: Duration = Duration::from_secs(30 * 24 * 60 * 60); // 1 month (30 days)

#[allow(dead_code)]
pub fn compute_fingerprint(data: &str) -> u64 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut hasher = DefaultHasher::new();
    data.hash(&mut hasher);
    hasher.finish()
}

/// Compute a hash fingerprint covering both the system prompt instructions and the user/context prompt.
/// Any change to either the AI prompt wording or the underlying financial data will alter the fingerprint,
/// automatically invalidating cached advice.
pub fn compute_prompt_fingerprint(system_prompt: &str, user_prompt: &str) -> u64 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut hasher = DefaultHasher::new();
    system_prompt.hash(&mut hasher);
    user_prompt.hash(&mut hasher);
    hasher.finish()
}

#[allow(dead_code)]
pub fn clear_ed_cache(user_id: &str) {
    if let Ok(mut cache) = SPOG_CACHE.write() {
        cache.remove(user_id);
    }
    if let Ok(mut cache) = OUTGOINGS_CACHE.write() {
        cache.remove(user_id);
    }
    if let Ok(mut cache) = NETWORTH_CACHE.write() {
        cache.remove(user_id);
    }
}

fn is_mistral_configured() -> bool {
    std::env::var("MISTRAL_API_KEY")
        .map(|k| !k.trim().is_empty())
        .unwrap_or(false)
}

fn get_mistral_model() -> String {
    std::env::var("MISTRAL_MODEL").unwrap_or_else(|_| "mistral-small-latest".to_string())
}

/// Call Mistral chat completions API asynchronously
async fn call_mistral(
    system_prompt: &str,
    user_prompt: &str,
    max_tokens: u32,
) -> Result<String, String> {
    let api_key = match std::env::var("MISTRAL_API_KEY") {
        Ok(k) if !k.trim().is_empty() => k.trim().to_string(),
        _ => return Err("MISTRAL_API_KEY environment variable is not set or empty".to_string()),
    };
    let model = get_mistral_model();

    let payload = serde_json::json!({
        "model": model,
        "temperature": 0.7,
        "max_tokens": max_tokens,
        "messages": [
            { "role": "system", "content": system_prompt },
            { "role": "user", "content": user_prompt }
        ]
    });

    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(25))
        .build()
    {
        Ok(c) => c,
        Err(e) => return Err(format!("Failed to build HTTP client: {e}")),
    };

    tracing::info!(
        "Calling Mistral AI (model: {}) for Ed advice at https://api.mistral.ai/v1/chat/completions",
        model
    );

    let res = client
        .post("https://api.mistral.ai/v1/chat/completions")
        .bearer_auth(api_key)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .json(&payload)
        .send()
        .await
        .map_err(|e| format!("Mistral request failed: {e}"))?;

    let status = res.status();
    if !status.is_success() {
        let err_body = res.text().await.unwrap_or_default();
        tracing::warn!("Mistral API returned error status {}: {}", status, err_body);
        if status.as_u16() == 401 {
            return Err("The Mistral API key provided appears to be invalid or unauthorized. Please verify your MISTRAL_API_KEY.".to_string());
        } else if status.as_u16() == 429 {
            return Err("Mistral rate limit was reached! Give it a minute, then click 'Ask Ed' to try again.".to_string());
        }
        return Err(format!("Mistral returned HTTP {status}: {err_body}"));
    }

    let body: serde_json::Value = res
        .json()
        .await
        .map_err(|e| format!("Failed to parse Mistral response JSON: {e}"))?;

    if let Some(content) = body.pointer("/choices/0/message/content").and_then(|v| v.as_str()) {
        tracing::info!("Received successful response from Mistral AI for Ed advice");
        Ok(content.trim().to_string())
    } else {
        Err("Mistral returned an empty choices array or missing content field".to_string())
    }
}

// ============================================================================
// 1. SPOG Ed Advisor
// ============================================================================

fn build_spog_system_prompt() -> &'static str {
    "You are Ed, the sharp-witted, helpful, and monstrously practical personal finance monster mascot of MonMon. \
Analyze the user's financial dashboard snapshot (Single Pane of Glass - SPOG). Provide:\n\
1. A quick 2-3 sentence overview of their financial health right now (are they on track, ahead, or burning cash?).\n\
2. Exactly 2-4 punchy, high-impact, practical bullet points on what they can do to improve or survive until payday.\n\
Tone: direct, encouraging, clever, with a tiny hint of monstrous wit. No generic fluff. Use Markdown formatting with bolding and bullet points."
}

fn build_spog_context_prompt(m: &SpogMetrics) -> String {
    let mut sb = String::new();
    sb.push_str("Here is the user's current financial snapshot from their MonMon SPOG dashboard:\n\n");
    sb.push_str(&format!(
        "- Days until next payday: {} days (Next payday: {})\n",
        m.days_until_next_payday, m.next_pay_date
    ));
    sb.push_str(&format!(
        "- Total days in this pay cycle: {} days\n",
        m.days_between_paydays
    ));
    sb.push_str(&format!(
        "- Days elapsed since last payday: {} days\n\n",
        m.days_since_last_payday
    ));

    sb.push_str("Income & Allowances:\n");
    sb.push_str(&format!(
        "- Monthly Income / Takehome: £{}\n",
        m.incoming_total.format_pounds()
    ));
    sb.push_str(&format!(
        "- Total Outgoings (bills/rent/subscriptions): £{}\n",
        m.outgoing_total.format_pounds()
    ));
    sb.push_str(&format!(
        "- Max Monthly Spending Allowance: £{}\n",
        m.surplus.format_pounds()
    ));
    sb.push_str(&format!(
        "- Max Daily Allowance: £{}/day\n",
        m.max_per_day.format_pounds()
    ));
    sb.push_str(&format!(
        "- Max Weekly Allowance: £{}/week\n\n",
        m.max_per_week.format_pounds()
    ));

    sb.push_str("Current Spending & Baseline Status:\n");
    sb.push_str(&format!(
        "- Total spent this month so far: £{}\n",
        m.spent_this_month.format_pounds()
    ));
    if m.is_behind_baseline {
        sb.push_str(&format!(
            "- Baseline status: BEHIND baseline by £{} (over budget!)\n",
            m.absolute_baseline_diff_total.format_pounds()
        ));
    } else {
        sb.push_str(&format!(
            "- Baseline status: AHEAD of baseline with £{} surplus\n",
            m.absolute_baseline_diff_total.format_pounds()
        ));
    }
    sb.push_str(&format!(
        "- Outgoings already paid this cycle: £{}\n",
        m.completed_outgoings.format_pounds()
    ));
    sb.push_str(&format!(
        "- Outgoings still pending before payday: £{}\n\n",
        m.pending_outgoings.format_pounds()
    ));

    sb.push_str("Balances & Pot:\n");
    sb.push_str(&format!(
        "- Left per day in designated Monthly Pot: £{}/day\n",
        m.monthly_pot_left_per_day.format_pounds()
    ));
    sb.push_str(&format!(
        "- Total Available Debit: £{}\n",
        m.total_available_debit.format_pounds()
    ));
    sb.push_str(&format!(
        "- Total Available Credit: £{}\n",
        m.total_available_credit.format_pounds()
    ));
    sb.push_str(&format!(
        "- Liquid Savings Pot: £{}\n",
        m.liquid_savings_pot.format_pounds()
    ));
    sb.push_str(&format!(
        "- Total Savings Pot: £{}\n\n",
        m.total_savings_pot.format_pounds()
    ));

    if !m.account_groups.is_empty() {
        sb.push_str("Accounts Breakdown:\n");
        for grp in &m.account_groups {
            for acc in &grp.accounts {
                sb.push_str(&format!(
                    "  • {} ({}): £{}\n",
                    acc.name,
                    acc.account_type,
                    acc.latest_balance.format_pounds()
                ));
            }
        }
        sb.push('\n');
    }

    if let Some(plan) = &m.spending_plan {
        sb.push_str(&format!(
            "Spending Plan ({}):\n",
            plan.name
        ));
        sb.push_str(&format!(
            "  • Max Monthly Allowance: £{} | Spent: £{}\n",
            plan.max_monthly_allowance.format_pounds(),
            plan.spent_this_month.format_pounds()
        ));
        sb.push_str(&format!(
            "  • Remaining Essentials: £{}\n",
            plan.remaining_essentials.format_pounds()
        ));
        sb.push_str(&format!(
            "  • Adjusted Fun Left Per Day: £{}/day\n\n",
            plan.fun_left_per_day.format_pounds()
        ));
    }

    sb.push_str("Please analyze these finances as Ed. Give me an assessment of how I'm doing and clear, practical actions to improve or manage my money for the rest of this month.");
    sb
}

pub async fn ed_says_spog_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<EdQuery>,
) -> Result<Json<EdSaysResponse>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, q.user_id.as_deref());
    let resolved = resolve_user_id(&state.pool, &raw_uid).await;
    let metrics = crate::spog::calculate_spog(&state.pool, &resolved)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let configured = is_mistral_configured();
    let force = q.force.unwrap_or(false);

    let system_prompt = build_spog_system_prompt();
    let user_prompt = build_spog_context_prompt(&metrics);
    let current_fp = compute_prompt_fingerprint(system_prompt, &user_prompt);

    // 1. Check cache if not forcing refresh: valid if within TTL AND underlying data unchanged
    if !force {
        if let Ok(cache) = SPOG_CACHE.read() {
            if let Some(entry) = cache.get(&resolved) {
                if entry.created_at.elapsed() < SPOG_CACHE_TTL && entry.fingerprint == current_fp {
                    return Ok(Json(EdSaysResponse {
                        ed_says: entry.text.clone(),
                        configured,
                        last_spoke: Some(entry.timestamp_millis),
                    }));
                }
            }
        }
    }

    // 2. If Mistral is configured, invoke Mistral
    if configured {
        match call_mistral(system_prompt, &user_prompt, 750).await {
            Ok(advice) => {
                let now_ms = chrono::Utc::now().timestamp_millis();
                if let Ok(mut cache) = SPOG_CACHE.write() {
                    cache.insert(
                        resolved.clone(),
                        CacheEntry {
                            text: advice.clone(),
                            fingerprint: current_fp,
                            timestamp_millis: now_ms,
                            created_at: Instant::now(),
                        },
                    );
                }
                return Ok(Json(EdSaysResponse {
                    ed_says: advice,
                    configured: true,
                    last_spoke: Some(now_ms),
                }));
            }
            Err(err) => {
                tracing::warn!("Mistral invocation failed for SPOG Ed advice: {}", err);
            }
        }
    }

    // 3. Fallback rule-based advice when Mistral is unavailable or unconfigured
    let fallback = if metrics.monthly_pot_left_per_day >= metrics.max_per_day {
        format!(
            "🌟 You're doing fantastic! Your daily spending allowance is £{}/day, comfortably above your £{}/day target baseline. With {} days until your next payday, you have a solid buffer. Keep pacing yourself!",
            metrics.monthly_pot_left_per_day.format_pounds(),
            metrics.max_per_day.format_pounds(),
            metrics.days_until_next_payday
        )
    } else {
        format!(
            "⚠️ Heads up! Your daily allowance has dipped to £{}/day (below your £{}/day baseline). With {} days until your next payday, pausing non-essential purchases for a couple of days will help replenish your pot.",
            metrics.monthly_pot_left_per_day.format_pounds(),
            metrics.max_per_day.format_pounds(),
            metrics.days_until_next_payday
        )
    };

    Ok(Json(EdSaysResponse {
        ed_says: fallback,
        configured,
        last_spoke: Some(chrono::Utc::now().timestamp_millis()),
    }))
}

// ============================================================================
// 2. Outgoings Ed Advisor
// ============================================================================

fn build_outgoings_system_prompt() -> &'static str {
    "You are Ed, financial pragmatist and cold-eyed observer in MonMon. \
Speak strictly in the style of Ernest Hemingway: lean, spare, direct, honest, and unadorned. \
Short sentences. No melodrama, no emojis, no exclamation marks. \
Your mission is to look at the user's outgoings against their wider financial position (income, cash buffer, monthly allowance, debt, and upcoming bills) and actively persuade them to PAUSE specific outgoings that are draining money without adding survival value.\n\n\
Structure:\n\
1. A blunt 2-sentence reality check on their fixed outflow vs incoming cash and breathing room.\n\
2. Specific Outgoing(s) to Pause: Name each candidate outgoing, state its cost, and give the exact, hard-headed reason why they should click 'Pause' on it right now.\n\
3. The Relief: State plainly how much monthly cash they recover by pausing these items and how it shields their position before payday.\n\
If they already have items paused, acknowledge them as good discipline.\n\
Use clean Markdown formatting with simple bold headings and bullet points."
}

fn build_outgoings_context_prompt(
    incoming_total: f32,
    outgoing_total: f32,
    surplus: f32,
    outgoings: &[Outgoing],
    accounts: &[Account],
    latest_balances: &HashMap<i64, f32>,
) -> String {
    let mut sb = String::new();
    sb.push_str("Wider Financial Position:\n");
    sb.push_str(&format!("- Total Monthly Incomings: £{:.2}\n", incoming_total));
    sb.push_str(&format!("- Active Monthly Outgoings: £{:.2}\n", outgoing_total));
    sb.push_str(&format!("- Projected Monthly Surplus / Breathing Room: £{:.2}\n\n", surplus));

    if !accounts.is_empty() {
        sb.push_str("Account Balances & Available Liquidity:\n");
        for a in accounts {
            if !a.archived.unwrap_or(false) {
                let bal = latest_balances.get(&a.id).copied().unwrap_or(0.0);
                sb.push_str(&format!(
                    "  • {} ({}): £{:.2}\n",
                    a.name,
                    a.account_type,
                    bal
                ));
            }
        }
        sb.push('\n');
    }

    let mut active = Vec::new();
    let mut paused = Vec::new();
    for o in outgoings {
        if o.archived.unwrap_or(false) {
            continue;
        }
        if o.paused.unwrap_or(false) {
            paused.push(o);
        } else {
            active.push(o);
        }
    }

    sb.push_str("Active Outgoings (Candidates to Consider Pausing):\n");
    if active.is_empty() {
        sb.push_str("  (No active outgoings recorded)\n");
    } else {
        for o in &active {
            let mut tags = Vec::new();
            if o.bill.unwrap_or(false) {
                tags.push("Bill");
            }
            if o.rent.unwrap_or(false) {
                tags.push("Rent");
            }
            if o.hidden_from_total.unwrap_or(false) {
                tags.push("Savings/Hidden");
            }
            let tag_str = if tags.is_empty() {
                String::new()
            } else {
                format!(" [{}]", tags.join(", "))
            };
            sb.push_str(&format!(
                "  • {} — £{:.2} (due day {}){}\n",
                o.name, o.cost, o.outgoing_day, tag_str
            ));
        }
    }
    sb.push('\n');

    if !paused.is_empty() {
        sb.push_str("Already Paused Outgoings:\n");
        for o in &paused {
            let reason = o.pause_reason.as_deref().unwrap_or("Paused by user");
            sb.push_str(&format!(
                "  • {} — £{:.2} (Paused: \"{}\")\n",
                o.name, o.cost, reason
            ));
        }
        sb.push('\n');
    }

    sb.push_str("Please review these commitments in the Hemingway style. Recommend exact outgoings to pause and show the cash recovered.");
    sb
}

pub async fn ed_says_outgoings_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<EdQuery>,
) -> Result<Json<EdSaysResponse>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;
    let outgoings = crate::db::list_outgoings(&state.pool, &resolved)
        .await
        .unwrap_or_default();
    let accounts = crate::db::list_accounts(&state.pool, &resolved)
        .await
        .unwrap_or_default();
    let incomings = crate::db::list_incomings(&state.pool, &resolved)
        .await
        .unwrap_or_default();

    let balances = crate::db::list_balances(&state.pool, &resolved)
        .await
        .unwrap_or_default();
    let mut latest_balances: HashMap<i64, f32> = HashMap::new();
    for b in balances {
        if let Some(acc_id) = b.account_id {
            latest_balances.entry(acc_id).or_insert(b.value);
        }
    }

    let configured = is_mistral_configured();
    let force = q.force.unwrap_or(false);

    let incoming_total: f32 = incomings.iter().map(|i| i.net_value).sum();
    let outgoing_total: f32 = outgoings
        .iter()
        .filter(|o| !o.archived.unwrap_or(false) && !o.paused.unwrap_or(false))
        .map(|o| o.cost)
        .sum();
    let surplus = (incoming_total - outgoing_total).max(0.0);

    let system_prompt = build_outgoings_system_prompt();
    let user_prompt = build_outgoings_context_prompt(
        incoming_total,
        outgoing_total,
        surplus,
        &outgoings,
        &accounts,
        &latest_balances,
    );
    let current_fp = compute_prompt_fingerprint(system_prompt, &user_prompt);

    // 1. Check cache: valid if within 1 week TTL AND outgoings/accounts unchanged
    if !force {
        if let Ok(cache) = OUTGOINGS_CACHE.read() {
            if let Some(entry) = cache.get(&resolved) {
                if entry.created_at.elapsed() < OUTGOINGS_CACHE_TTL && entry.fingerprint == current_fp {
                    return Ok(Json(EdSaysResponse {
                        ed_says: entry.text.clone(),
                        configured,
                        last_spoke: Some(entry.timestamp_millis),
                    }));
                }
            }
        }
    }

    // 2. If Mistral is configured, call Mistral
    if configured {
        match call_mistral(system_prompt, &user_prompt, 750).await {
            Ok(advice) => {
                let now_ms = chrono::Utc::now().timestamp_millis();
                if let Ok(mut cache) = OUTGOINGS_CACHE.write() {
                    cache.insert(
                        resolved.clone(),
                        CacheEntry {
                            text: advice.clone(),
                            fingerprint: current_fp,
                            timestamp_millis: now_ms,
                            created_at: Instant::now(),
                        },
                    );
                }
                return Ok(Json(EdSaysResponse {
                    ed_says: advice,
                    configured: true,
                    last_spoke: Some(now_ms),
                }));
            }
            Err(err) => {
                tracing::warn!("Mistral invocation failed for Outgoings Ed advice: {}", err);
            }
        }
    }

    // 3. Fallback
    let active_count = outgoings.iter().filter(|o| !o.archived.unwrap_or(false) && !o.paused.unwrap_or(false)).count();
    let paused_count = outgoings.iter().filter(|o| o.paused.unwrap_or(false)).count();

    let advice = format!(
        "✂️ Ed's Outgoings Audit: You have {} active recurring commitments and {} paused item(s). Proactively reviewing memberships, duplicate streaming services, or broadband contracts every 3 months is the easiest way to unlock extra monthly cash without feeling pinched!",
        active_count,
        paused_count
    );

    Ok(Json(EdSaysResponse {
        ed_says: advice,
        configured,
        last_spoke: Some(chrono::Utc::now().timestamp_millis()),
    }))
}

// ============================================================================
// 3. Net Worth Ed Advisor
// ============================================================================

fn build_networth_system_prompt() -> &'static str {
    "You are Ed, long-range financial strategist and observer in MonMon. \
Speak strictly in the style of Ernest Hemingway: lean, spare, direct, honest, and unadorned. \
Short sentences. No melodrama, no emojis, no exclamation marks. \
Face the user's total net worth, pensions, physical assets, and debts with cool clarity.\n\n\
Your objective:\n\
1. Make a rough, honest judgement on when the user can retire or achieve financial independence, using sensible rules of thumb (e.g. 4% safe withdrawal rule, compound growth of pensions, and debt drag).\n\
2. OR evaluate how the future value and annual yield of their portfolio could pay for essential living costs, such as food and groceries.\n\
3. Provide exactly 2-3 blunt, practical steps to accelerate their timeline.\n\n\
Use clean Markdown formatting with simple bold headings and bullet points."
}

fn build_networth_context_prompt(nw: &NetWorthDto) -> String {
    let mut sb = String::new();
    sb.push_str("Financial Portfolio & Net Worth Snapshot:\n");
    sb.push_str(&format!("- Total Net Worth: £{:.2}\n", nw.net_worth));
    sb.push_str(&format!("- Total Assets: £{:.2}\n", nw.total_assets));
    sb.push_str(&format!("- Total Liabilities / Debts: £{:.2}\n\n", nw.total_liabilities));

    sb.push_str("Liquid & Investable Portfolio:\n");
    sb.push_str(&format!("- Cash & Savings: £{:.2}\n", nw.cash_total));
    sb.push_str(&format!("- Pensions & Retirement: £{:.2}\n", nw.pension_total));
    sb.push_str(&format!("- Combined Investable Portfolio: £{:.2}\n", nw.investable_portfolio));
    sb.push_str(&format!(
        "- 4% Safe Withdrawal Rule: £{:.2}/year (£{:.2}/month)\n\n",
        nw.annual_safe_withdrawal, nw.monthly_safe_withdrawal
    ));

    if !nw.custom_assets.is_empty() {
        sb.push_str("Physical & Capital Assets (Properties, Vehicles, Valuables):\n");
        for a in &nw.custom_assets {
            sb.push_str(&format!(
                "  • {} ({}): £{:.2}",
                a.name, a.category, a.value
            ));
            if let Some(notes) = &a.notes {
                if !notes.trim().is_empty() {
                    sb.push_str(&format!(" [{}]", notes.trim()));
                }
            }
            sb.push('\n');
        }
        sb.push_str(&format!("  • Total Physical Assets: £{:.2}\n\n", nw.custom_assets_total));
    }

    if nw.total_liabilities > 0.0 {
        sb.push_str("Debts & Drag:\n");
        sb.push_str(&format!("- Credit Card Debt: £{:.2}\n", nw.credit_debt_total));
        sb.push_str(&format!("- Loans Remaining: £{:.2}\n\n", nw.loan_debt_total));
    }

    sb.push_str("Living Cost Benchmark:\n");
    sb.push_str(&format!(
        "- Estimated Monthly Food & Essentials Cost: £{:.2}/month\n",
        nw.monthly_food_cost
    ));
    sb.push_str(&format!(
        "- Current Portfolio Food Coverage: {}% of monthly food covered by 4% safe withdrawal\n",
        nw.food_covered_percent
    ));
    sb.push_str(&format!(
        "- Debt to Asset Ratio: {}\n\n",
        nw.debt_to_asset_ratio_formatted
    ));

    sb.push_str("Please evaluate this portfolio as Ed in the Hemingway style. Make a rough judgement on retirement horizon or grocery independence, and outline 2-3 blunt steps to accelerate it.");
    sb
}

pub async fn ed_says_networth_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<EdQuery>,
) -> Result<Json<EdSaysResponse>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;
    let nw = crate::net_worth::calculate_net_worth(&state.pool, &resolved)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let configured = is_mistral_configured();
    let force = q.force.unwrap_or(false);

    let system_prompt = build_networth_system_prompt();
    let user_prompt = build_networth_context_prompt(&nw);
    let current_fp = compute_prompt_fingerprint(system_prompt, &user_prompt);

    // 1. Check cache: valid if within 1 month TTL AND net worth/assets unchanged
    if !force {
        if let Ok(cache) = NETWORTH_CACHE.read() {
            if let Some(entry) = cache.get(&resolved) {
                if entry.created_at.elapsed() < NETWORTH_CACHE_TTL && entry.fingerprint == current_fp {
                    return Ok(Json(EdSaysResponse {
                        ed_says: entry.text.clone(),
                        configured,
                        last_spoke: Some(entry.timestamp_millis),
                    }));
                }
            }
        }
    }

    // 2. If Mistral is configured, call Mistral
    if configured {
        match call_mistral(system_prompt, &user_prompt, 750).await {
            Ok(advice) => {
                let now_ms = chrono::Utc::now().timestamp_millis();
                if let Ok(mut cache) = NETWORTH_CACHE.write() {
                    cache.insert(
                        resolved.clone(),
                        CacheEntry {
                            text: advice.clone(),
                            fingerprint: current_fp,
                            timestamp_millis: now_ms,
                            created_at: Instant::now(),
                        },
                    );
                }
                return Ok(Json(EdSaysResponse {
                    ed_says: advice,
                    configured: true,
                    last_spoke: Some(now_ms),
                }));
            }
            Err(err) => {
                tracing::warn!("Mistral invocation failed for Net Worth Ed advice: {}", err);
            }
        }
    }

    // 3. Fallback
    let advice = format!(
        "📈 Ed's Wealth Radar: Your net worth stands at £{:.2} with a debt-to-asset ratio of {}. Your liquid cash and pension portfolio generates an estimated £{:.2}/year under the 4% safe withdrawal rule. Accelerating payoff on credit cards and overdrafts yields the highest guaranteed return on your capital!",
        nw.net_worth,
        nw.debt_to_asset_ratio_formatted,
        nw.annual_safe_withdrawal
    );

    Ok(Json(EdSaysResponse {
        ed_says: advice,
        configured,
        last_spoke: Some(chrono::Utc::now().timestamp_millis()),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::money::Pence;
    use crate::net_worth::NetWorthAssetDto;

    #[test]
    fn test_build_spog_context_prompt_contains_critical_figures() {
        let metrics = SpogMetrics {
            incoming_total: Pence::from_pounds_f64(3200.0),
            outgoing_total: Pence::from_pounds_f64(1450.0),
            surplus: Pence::from_pounds_f64(1750.0),
            baseline: Pence::from_pounds_f64(800.0),
            bills_only_baseline: Pence::from_pounds_f64(450.0),
            savings_plan_total: Pence::from_pounds_f64(300.0),
            savings_plan_items: vec![],
            next_pay_day: 28,
            next_pay_date: "2026-11-28".to_string(),
            days_between_paydays: 30,
            max_per_day: Pence::from_pounds_f64(58.33),
            max_per_week: Pence::from_pounds_f64(408.31),
            max_per_month: Pence::from_pounds_f64(1750.0),
            completed_outgoings: Pence::from_pounds_f64(1200.0),
            pending_outgoings: Pence::from_pounds_f64(250.0),
            bills_cost: Pence::from_pounds_f64(850.0),
            rent_cost: Pence::from_pounds_f64(600.0),
            monthly_pot_left_per_day: Pence::from_pounds_f64(62.50),
            baseline_diff_total: Pence::from_pounds_f64(125.0),
            is_behind_baseline: false,
            absolute_baseline_diff_total: Pence::from_pounds_f64(125.0),
            total_available_debit: Pence::from_pounds_f64(1500.0),
            total_available_credit: Pence::from_pounds_f64(4000.0),
            credit_balance: Pence::from_pounds_f64(500.0),
            credit_limit: Pence::from_pounds_f64(4500.0),
            credit_usage: Pence::from_pounds_f64(11.1),
            liquid_savings_pot: Pence::from_pounds_f64(5000.0),
            total_savings_pot: Pence::from_pounds_f64(12000.0),
            account_groups: vec![],
            spending_plan: None,
            spent_this_month: Pence::from_pounds_f64(750.0),
            days_until_next_payday: 14,
            days_since_last_payday: 16,
            is_payday_today: false,
            payday_transfers_count: 0,
            payday_transfers_total: Pence::ZERO,
        };

        let prompt = build_spog_context_prompt(&metrics);
        assert!(prompt.contains("Days until next payday: 14 days"));
        assert!(prompt.contains("Monthly Income / Takehome: £3200.00"));
        assert!(prompt.contains("Total Outgoings (bills/rent/subscriptions): £1450.00"));
        assert!(prompt.contains("Max Daily Allowance: £58.33/day"));
        assert!(prompt.contains("AHEAD of baseline with £125.00 surplus"));
        assert!(prompt.contains("Left per day in designated Monthly Pot: £62.50/day"));
    }

    #[test]
    fn test_build_outgoings_context_prompt_separates_active_and_paused() {
        let active = Outgoing {
            id: 1,
            name: "Broadband".to_string(),
            cost: 35.0,
            outgoing_day: 5,
            outgoing_month: None,
            year_interval: None,
            starting_year: None,
            bill: Some(true),
            rent: Some(false),
            hidden_from_total: Some(false),
            paused: Some(false),
            pause_reason: None,
            paused_at: None,
            user_id: "demo".to_string(),
            account_id: Some(10),
            account_name: Some("Current".to_string()),
            account_type: Some("DEBIT".to_string()),
            archived: Some(false),
        };
        let paused = Outgoing {
            id: 2,
            name: "Gym".to_string(),
            cost: 50.0,
            outgoing_day: 1,
            outgoing_month: None,
            year_interval: None,
            starting_year: None,
            bill: Some(false),
            rent: Some(false),
            hidden_from_total: Some(false),
            paused: Some(true),
            pause_reason: Some("Winter pause".to_string()),
            paused_at: None,
            user_id: "demo".to_string(),
            account_id: Some(10),
            account_name: Some("Current".to_string()),
            account_type: Some("DEBIT".to_string()),
            archived: Some(false),
        };

        let accounts = vec![Account {
            id: 10,
            name: "Current Account".to_string(),
            nickname: None,
            account_type: "DEBIT".to_string(),
            user_id: "demo".to_string(),
            account_limit: None,
            interest_rate: None,
            ledger_mode: None,
            archived: Some(false),
        }];

        let mut balances = HashMap::new();
        balances.insert(10, 850.25);

        let prompt = build_outgoings_context_prompt(
            3000.0,
            35.0,
            2965.0,
            &[active, paused],
            &accounts,
            &balances,
        );

        assert!(prompt.contains("Active Outgoings (Candidates to Consider Pausing):"));
        assert!(prompt.contains("Broadband — £35.00 (due day 5) [Bill]"));
        assert!(prompt.contains("Already Paused Outgoings:"));
        assert!(prompt.contains("Gym — £50.00 (Paused: \"Winter pause\")"));
        assert!(prompt.contains("Current Account (DEBIT): £850.25"));
    }

    #[test]
    fn test_build_networth_context_prompt_includes_assets_and_withdrawal() {
        let nw = NetWorthDto {
            net_worth: 150000.0,
            total_assets: 180000.0,
            total_liabilities: 30000.0,
            cash_total: 20000.0,
            pension_total: 110000.0,
            custom_assets_total: 50000.0,
            credit_debt_total: 5000.0,
            loan_debt_total: 25000.0,
            investable_portfolio: 130000.0,
            annual_safe_withdrawal: 5200.0,
            monthly_safe_withdrawal: 433.33,
            monthly_food_cost: 350.0,
            food_covered_percent: 124,
            asset_to_debt_ratio: Some(6.0),
            debt_to_asset_ratio: Some(0.166),
            debt_to_asset_ratio_formatted: "16.7%".to_string(),
            is_healthy_ratio: true,
            is_debt_free: false,
            custom_assets: vec![NetWorthAssetDto {
                id: 1,
                name: "Campervan".to_string(),
                category: "Vehicle".to_string(),
                category_human_readable: "Vehicle".to_string(),
                category_icon: "fa-car".to_string(),
                value: 50000.0,
                notes: Some("Solar setup".to_string()),
                acquired_at: None,
                acquired_date_formatted: "2024".to_string(),
                acquired_date_html_value: "2024-01-01".to_string(),
                archived: false,
            }],
            pension_accounts: vec![],
            cash_accounts: vec![],
            credit_accounts: vec![],
            loan_accounts: vec![],
        };

        let prompt = build_networth_context_prompt(&nw);
        assert!(prompt.contains("Total Net Worth: £150000.00"));
        assert!(prompt.contains("Combined Investable Portfolio: £130000.00"));
        assert!(prompt.contains("4% Safe Withdrawal Rule: £5200.00/year (£433.33/month)"));
        assert!(prompt.contains("Campervan (Vehicle): £50000.00 [Solar setup]"));
        assert!(prompt.contains("Current Portfolio Food Coverage: 124% of monthly food covered"));
    }

    #[test]
    fn test_cache_ttl_durations() {
        assert_eq!(SPOG_CACHE_TTL, Duration::from_secs(86400)); // 1 day
        assert_eq!(OUTGOINGS_CACHE_TTL, Duration::from_secs(604800)); // 1 week
        assert_eq!(NETWORTH_CACHE_TTL, Duration::from_secs(2592000)); // 30 days
    }

    #[test]
    fn test_fingerprint_changes_when_data_changes() {
        let p1 = "Balance: 1000";
        let p2 = "Balance: 1000";
        let p3 = "Balance: 1050";

        let fp1 = compute_fingerprint(p1);
        let fp2 = compute_fingerprint(p2);
        let fp3 = compute_fingerprint(p3);

        assert_eq!(fp1, fp2);
        assert_ne!(fp1, fp3);
    }

    #[test]
    fn test_fingerprint_changes_when_prompt_changes() {
        let sys1 = "You are Ed, sharp-witted financial advisor.";
        let sys2 = "You are Ed, warm and friendly financial advisor.";
        let user1 = "Income: £3000";
        let user2 = "Income: £3500";

        let fp_base = compute_prompt_fingerprint(sys1, user1);
        let fp_same = compute_prompt_fingerprint(sys1, user1);
        let fp_diff_sys = compute_prompt_fingerprint(sys2, user1);
        let fp_diff_user = compute_prompt_fingerprint(sys1, user2);

        assert_eq!(fp_base, fp_same);
        assert_ne!(fp_base, fp_diff_sys, "Changing system prompt must invalidate cache");
        assert_ne!(fp_base, fp_diff_user, "Changing user data/prompt must invalidate cache");
    }
}

