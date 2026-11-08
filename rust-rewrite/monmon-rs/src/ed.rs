use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::Json,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use crate::AppState;
use crate::spog::resolve_user_id;

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

fn is_mistral_configured() -> bool {
    std::env::var("MISTRAL_API_KEY")
        .map(|k| !k.trim().is_empty())
        .unwrap_or(false)
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
    let advice = if metrics.monthly_pot_left_per_day >= metrics.max_per_day {
        format!(
            "🌟 You're doing fantastic! Your daily spending allowance is £{:.2}/day, comfortably above your £{:.2}/day target baseline. With {} days until your next payday, you have a solid buffer. Keep pacing yourself!",
            metrics.monthly_pot_left_per_day,
            metrics.max_per_day,
            metrics.days_between_paydays
        )
    } else {
        format!(
            "⚠️ Heads up! Your daily allowance has dipped to £{:.2}/day (below your £{:.2}/day baseline). With {} days until your next payday, pausing non-essential purchases for a couple of days will help replenish your pot.",
            metrics.monthly_pot_left_per_day,
            metrics.max_per_day,
            metrics.days_between_paydays
        )
    };

    Ok(Json(EdSaysResponse {
        ed_says: advice,
        configured,
        last_spoke: Some(chrono::Utc::now().timestamp_millis()),
    }))
}

pub async fn ed_says_outgoings_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<EdQuery>,
) -> Result<Json<EdSaysResponse>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;
    let outgoings = crate::db::list_outgoings(&state.pool, &resolved)
        .await
        .unwrap_or_default();

    let configured = is_mistral_configured();
    let active_count = outgoings.iter().filter(|o| !o.archived.unwrap_or(false)).count();
    let paused_count = outgoings.iter().filter(|o| o.archived.unwrap_or(false)).count();

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

pub async fn ed_says_networth_api(
    State(state): State<Arc<AppState>>,
    Query(q): Query<EdQuery>,
) -> Result<Json<EdSaysResponse>, (StatusCode, String)> {
    let resolved = resolve_user_id(&state.pool, &q.get_user_id()).await;
    let nw = crate::net_worth::calculate_net_worth(&state.pool, &resolved)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let configured = is_mistral_configured();
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
