use askama::Template;
use axum::{
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Redirect},
    Json,
};
use chrono::{TimeZone, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{PgPool, Row};
use std::sync::Arc;

use crate::AppState;

const THIRTY_DAYS_MS: i64 = 30 * 24 * 60 * 60 * 1000;
const ONE_DAY_MS: i64 = 24 * 60 * 60 * 1000;

#[derive(Debug, Clone, Serialize)]
pub struct WasteBinItem {
    pub id: i64,
    pub entity_type: String,
    pub entity_label: String,
    pub name: String,
    pub details: String,
    pub archived_at_ms: i64,
    pub archived_date: String,
    pub days_remaining: i64,
}

#[derive(Debug, Deserialize)]
pub struct WasteBinQuery {
    pub recovered: Option<String>,
    pub entity_type: Option<String>,
}

#[derive(Template)]
#[template(path = "waste_bin.html")]
pub struct WasteBinTemplate {
    pub items: Vec<WasteBinItem>,
    pub total_count: usize,
    pub accounts_count: usize,
    pub incomings_count: usize,
    pub outgoings_count: usize,
    pub net_worth_count: usize,
    pub plans_count: usize,
    pub recovered_banner: bool,
    pub active_filter: String,
}

fn normalize_ts_ms(ts: i64) -> i64 {
    if ts > 100_000_000_000 {
        ts
    } else {
        ts * 1000
    }
}

fn format_archived_date(ts_ms: i64) -> String {
    Utc.timestamp_millis_opt(ts_ms)
        .single()
        .map(|dt| dt.format("%-d %b %Y, %H:%M UTC").to_string())
        .unwrap_or_else(|| "Recently".to_string())
}

fn compute_days_remaining(archived_at_ms: i64, now_ms: i64) -> i64 {
    let expires_at = archived_at_ms + THIRTY_DAYS_MS;
    let remaining = expires_at - now_ms;
    if remaining <= 0 {
        0
    } else {
        ((remaining + ONE_DAY_MS - 1) / ONE_DAY_MS).clamp(1, 30)
    }
}

pub async fn list_waste_bin_items(pool: &PgPool, user_id: &str) -> Result<Vec<WasteBinItem>, sqlx::Error> {
    let now_ms = Utc::now().timestamp_millis();
    let cutoff_ms = now_ms - THIRTY_DAYS_MS;
    let mut items = Vec::new();

    // 1. Accounts
    let acc_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(name, 'Unnamed Account') AS name, COALESCE(type, '') AS acc_type,
               nickname, archived_at
        FROM account
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND archived = TRUE
          AND archived_at IS NOT NULL
          AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in acc_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        let acc_type: String = r.get("acc_type");
        let nickname: Option<String> = r.get("nickname");
        let details = match nickname.filter(|s| !s.trim().is_empty()) {
            Some(nick) => format!("{} • {}", acc_type, nick),
            None => acc_type,
        };
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "account".to_string(),
            entity_label: "Account".to_string(),
            name: r.get("name"),
            details,
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    // 2. Incomings
    let inc_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(name, 'Unnamed Incoming') AS name,
               COALESCE(netvalue, 0.0)::real AS netvalue,
               incomingmonthday, archived_at
        FROM incoming
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND archived = TRUE
          AND archived_at IS NOT NULL
          AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in inc_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        let net: f32 = r.get("netvalue");
        let day: i32 = r.get("incomingmonthday");
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "incoming".to_string(),
            entity_label: "Incoming".to_string(),
            name: r.get("name"),
            details: format!("£{:.2} • Day {}", net, day),
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    // 3. Outgoings
    let out_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(name, 'Unnamed Outgoing') AS name,
               COALESCE(cost, 0.0)::real AS cost,
               bill, rent, outgoingday, archived_at
        FROM outgoing
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND archived = TRUE
          AND archived_at IS NOT NULL
          AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in out_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        let cost: f32 = r.get("cost");
        let bill: bool = r.get("bill");
        let rent: bool = r.get("rent");
        let kind = if rent { "Rent" } else if bill { "Bill" } else { "Outgoing" };
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "outgoing".to_string(),
            entity_label: "Outgoing".to_string(),
            name: r.get("name"),
            details: format!("{} • £{:.2}", kind, cost),
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    // 4. Net Worth Assets
    let nw_rows = sqlx::query(
        r#"
        SELECT id, name, category, asset_value, archived_at
        FROM net_worth_asset
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND archived = TRUE
          AND archived_at IS NOT NULL
          AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in nw_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        let cat: String = r.get("category");
        let val: f64 = r.get("asset_value");
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "net_worth_asset".to_string(),
            entity_label: "Net Worth Asset".to_string(),
            name: r.get("name"),
            details: format!("{} • £{:.2}", cat, val),
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    // 5. Savings Plans
    let sp_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(name, 'Savings Plan') AS name, savingspercentage, archived_at
        FROM savings_plan
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND archived = TRUE
          AND archived_at IS NOT NULL
          AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in sp_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        let pct: i32 = r.get("savingspercentage");
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "savings_plan".to_string(),
            entity_label: "Savings Plan".to_string(),
            name: r.get("name"),
            details: format!("{}% Savings Target", pct),
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    // 6. Savings Plan Items (ownership via parent savings_plan)
    let spi_rows = sqlx::query(
        r#"
        SELECT spi.id, COALESCE(spi.name, 'Savings Item') AS name,
               COALESCE(spi.type, 'SAVINGS_TARGET') AS item_type,
               COALESCE(spi.targetamount, 0.0)::real AS target_amount,
               spi.archived_at
        FROM savings_plan_item spi
        JOIN savings_plan sp ON spi.savings_plan_id = sp.id
        WHERE COALESCE(sp.user_id, sp.userid, sp."userId") = $1
          AND spi.archived = TRUE
          AND spi.archived_at IS NOT NULL
          AND (CASE WHEN spi.archived_at > 100000000000 THEN spi.archived_at ELSE spi.archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in spi_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        let item_type: String = r.get("item_type");
        let target: f32 = r.get("target_amount");
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "savings_plan_item".to_string(),
            entity_label: "Savings Plan Item".to_string(),
            name: r.get("name"),
            details: format!("{} • Target £{:.2}", item_type, target),
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    // 7. Monthly Pots
    let pot_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(spending_plan_name, 'Monthly Pot') AS name,
               COALESCE(type, 'MONTHLY') AS pot_type, archived_at
        FROM pot
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND archived = TRUE
          AND archived_at IS NOT NULL
          AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in pot_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        let pot_type: String = r.get("pot_type");
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "pot".to_string(),
            entity_label: "Spending Pot".to_string(),
            name: r.get("name"),
            details: format!("{} Pot", pot_type),
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    // 8. Custom Spending Plans
    let csp_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(name, 'Custom Spending Plan') AS name, archived_at
        FROM custom_spending_plan
        WHERE COALESCE(user_id, userid) = $1
          AND archived = TRUE
          AND archived_at IS NOT NULL
          AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in csp_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "custom_spending_plan".to_string(),
            entity_label: "Custom Spending Plan".to_string(),
            name: r.get("name"),
            details: "Saved Preset".to_string(),
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    // 9. Plan Agreements
    let plan_rows = sqlx::query(
        r#"
        SELECT id, COALESCE(type, 'Plan') AS plan_type, split, notes, archived_at
        FROM plan
        WHERE COALESCE(user_id, userid, "userId") = $1
          AND archived = TRUE
          AND archived_at IS NOT NULL
          AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $2
        "#,
    )
    .bind(user_id)
    .bind(cutoff_ms)
    .fetch_all(pool)
    .await?;

    for r in plan_rows {
        let ts = normalize_ts_ms(r.get::<i64, _>("archived_at"));
        let ptype: String = r.get("plan_type");
        let split: Option<f32> = r.get("split");
        let details = match split {
            Some(s) => format!("{:.0}% split", s * 100.0),
            None => "Sharing agreement".to_string(),
        };
        items.push(WasteBinItem {
            id: r.get("id"),
            entity_type: "plan".to_string(),
            entity_label: "Plan Agreement".to_string(),
            name: ptype,
            details,
            archived_at_ms: ts,
            archived_date: format_archived_date(ts),
            days_remaining: compute_days_remaining(ts, now_ms),
        });
    }

    sort_waste_bin_items_desc(&mut items);
    Ok(items)
}

fn sort_waste_bin_items_desc(items: &mut [WasteBinItem]) {
    items.sort_by(|a, b| {
        b.archived_at_ms
            .cmp(&a.archived_at_ms)
            .then_with(|| b.id.cmp(&a.id))
    });
}

pub async fn recover_waste_bin_item(
    pool: &PgPool,
    user_id: &str,
    entity_type: &str,
    id: i64,
) -> Result<(), (StatusCode, String)> {
    let now_ms = Utc::now().timestamp_millis();
    let cutoff_ms = now_ms - THIRTY_DAYS_MS;

    let (entity_label, rows_affected) = match entity_type {
        "account" => {
            let res = sqlx::query(
                r#"
                UPDATE account
                SET archived = FALSE, archived_at = NULL, updated_at = $1
                WHERE id = $2
                  AND COALESCE(user_id, userid, "userId") = $3
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("Account", res.rows_affected())
        }
        "incoming" => {
            let res = sqlx::query(
                r#"
                UPDATE incoming
                SET archived = FALSE, archived_at = NULL, updated_at = $1
                WHERE id = $2
                  AND COALESCE(user_id, userid, "userId") = $3
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("Incoming", res.rows_affected())
        }
        "outgoing" => {
            let res = sqlx::query(
                r#"
                UPDATE outgoing
                SET archived = FALSE, archived_at = NULL, updated_at = $1
                WHERE id = $2
                  AND COALESCE(user_id, userid, "userId") = $3
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("Outgoing", res.rows_affected())
        }
        "net_worth_asset" => {
            let res = sqlx::query(
                r#"
                UPDATE net_worth_asset
                SET archived = FALSE, archived_at = NULL, deleted_at = NULL, updated_at = $1
                WHERE id = $2
                  AND COALESCE(user_id, userid, "userId") = $3
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("Net Worth Asset", res.rows_affected())
        }
        "savings_plan" => {
            let res = sqlx::query(
                r#"
                UPDATE savings_plan
                SET archived = FALSE, archived_at = NULL, updated_at = $1, lastupdated = $1, last_updated = $1
                WHERE id = $2
                  AND COALESCE(user_id, userid, "userId") = $3
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("Savings Plan", res.rows_affected())
        }
        "savings_plan_item" => {
            let res = sqlx::query(
                r#"
                UPDATE savings_plan_item
                SET archived = FALSE, archived_at = NULL, updated_at = $1
                WHERE id = $2
                  AND savings_plan_id IN (
                      SELECT id FROM savings_plan WHERE COALESCE(user_id, userid, "userId") = $3
                  )
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("Savings Plan Item", res.rows_affected())
        }
        "pot" => {
            let res = sqlx::query(
                r#"
                UPDATE pot
                SET archived = FALSE, archived_at = NULL, updated_at = $1
                WHERE id = $2
                  AND COALESCE(user_id, userid, "userId") = $3
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("Pot", res.rows_affected())
        }
        "custom_spending_plan" => {
            let res = sqlx::query(
                r#"
                UPDATE custom_spending_plan
                SET archived = FALSE, archived_at = NULL, updated_at = $1, lastupdated = $1, last_updated = $1
                WHERE id = $2
                  AND COALESCE(user_id, userid) = $3
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("CustomSpendingPlan", res.rows_affected())
        }
        "plan" => {
            let res = sqlx::query(
                r#"
                UPDATE plan
                SET archived = FALSE, archived_at = NULL, updated_at = $1
                WHERE id = $2
                  AND COALESCE(user_id, userid, "userId") = $3
                  AND archived = TRUE
                  AND archived_at IS NOT NULL
                  AND (CASE WHEN archived_at > 100000000000 THEN archived_at ELSE archived_at * 1000 END) >= $4
                "#,
            )
            .bind(now_ms)
            .bind(id)
            .bind(user_id)
            .bind(cutoff_ms)
            .execute(pool)
            .await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
            ("Plan", res.rows_affected())
        }
        _ => {
            return Err((
                StatusCode::BAD_REQUEST,
                format!("Unsupported entity type: {}", entity_type),
            ));
        }
    };

    crate::authz::Authz::verify_affected(rows_affected, entity_label, id)?;
    Ok(())
}

pub async fn get_waste_bin_page(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<WasteBinQuery>,
) -> Result<impl IntoResponse, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;

    let all_items = list_waste_bin_items(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let accounts_count = all_items.iter().filter(|i| i.entity_type == "account").count();
    let incomings_count = all_items.iter().filter(|i| i.entity_type == "incoming").count();
    let outgoings_count = all_items.iter().filter(|i| i.entity_type == "outgoing").count();
    let net_worth_count = all_items.iter().filter(|i| i.entity_type == "net_worth_asset").count();
    let plans_count = all_items
        .iter()
        .filter(|i| {
            matches!(
                i.entity_type.as_str(),
                "savings_plan" | "savings_plan_item" | "pot" | "custom_spending_plan" | "plan"
            )
        })
        .count();

    let active_filter = q.entity_type.unwrap_or_else(|| "all".to_string());
    let filtered_items: Vec<WasteBinItem> = match active_filter.as_str() {
        "all" | "" => all_items.clone(),
        "plans" => all_items
            .iter()
            .filter(|i| {
                matches!(
                    i.entity_type.as_str(),
                    "savings_plan" | "savings_plan_item" | "pot" | "custom_spending_plan" | "plan"
                )
            })
            .cloned()
            .collect(),
        other => all_items
            .iter()
            .filter(|i| i.entity_type == other)
            .cloned()
            .collect(),
    };

    let tmpl = WasteBinTemplate {
        total_count: all_items.len(),
        items: filtered_items,
        accounts_count,
        incomings_count,
        outgoings_count,
        net_worth_count,
        plans_count,
        recovered_banner: q.recovered.is_some(),
        active_filter,
    };

    let html = tmpl
        .render()
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Html(html))
}

pub async fn list_waste_bin_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;

    let items = list_waste_bin_items(&state.pool, &user_id)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    Ok(Json(serde_json::json!({
        "items": items,
        "count": items.len(),
        "retention_days": 30
    })))
}

pub async fn recover_item_api(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path((entity_type, id)): Path<(String, i64)>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;

    recover_waste_bin_item(&state.pool, &user_id, &entity_type, id).await?;

    Ok(Json(serde_json::json!({
        "success": true,
        "recovered": {
            "entity_type": entity_type,
            "id": id
        }
    })))
}

pub async fn recover_item_form(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path((entity_type, id)): Path<(String, i64)>,
) -> Result<Redirect, (StatusCode, String)> {
    let raw_uid = crate::pot::get_user_id_from_headers_or_default(&headers, None);
    let user_id = crate::spog::resolve_user_id(&state.pool, &raw_uid).await;

    recover_waste_bin_item(&state.pool, &user_id, &entity_type, id).await?;

    Ok(Redirect::to("/wasteBin?recovered=1"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_item(id: i64, archived_at_ms: i64) -> WasteBinItem {
        WasteBinItem {
            id,
            entity_type: "account".to_string(),
            entity_label: "Account".to_string(),
            name: format!("Item {}", id),
            details: "CURRENT".to_string(),
            archived_at_ms,
            archived_date: format_archived_date(archived_at_ms),
            days_remaining: 30,
        }
    }

    #[test]
    fn test_sort_waste_bin_items_most_recent_first() {
        let mut items = vec![
            make_item(1, 1_700_000_000_000),
            make_item(2, 1_700_500_000_000),
            make_item(3, 1_700_200_000_000),
            make_item(4, 1_700_500_000_000),
        ];
        sort_waste_bin_items_desc(&mut items);
        let ids: Vec<i64> = items.into_iter().map(|i| i.id).collect();
        assert_eq!(ids, vec![4, 2, 3, 1]);
    }

    #[test]
    fn test_normalize_ts_ms_and_days_remaining() {
        assert_eq!(normalize_ts_ms(1_700_000_000), 1_700_000_000_000);
        assert_eq!(normalize_ts_ms(1_700_000_000_000), 1_700_000_000_000);

        let now_ms = 1_700_000_000_000_i64;
        assert_eq!(compute_days_remaining(now_ms, now_ms), 30);
        assert_eq!(compute_days_remaining(now_ms - 29 * ONE_DAY_MS, now_ms), 1);
        assert_eq!(compute_days_remaining(now_ms - 31 * ONE_DAY_MS, now_ms), 0);
    }
}
