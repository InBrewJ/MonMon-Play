use serde::{Deserialize, Serialize};
use sqlx::FromRow;

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    pub id: i64,
    pub name: String,
    pub nickname: Option<String>,
    #[serde(rename = "type")]
    pub account_type: String,
    pub user_id: String,
    pub account_limit: Option<f32>,
    pub interest_rate: Option<f32>,
    pub ledger_mode: Option<String>,
    pub archived: Option<bool>,
}

impl Account {
    pub fn is_selected(&self, sel: &Option<i64>) -> bool {
        *sel == Some(self.id)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewAccount {
    pub name: String,
    pub nickname: Option<String>,
    #[serde(rename = "type")]
    pub account_type: String,
    pub user_id: String,
    pub account_limit: Option<f32>,
    pub interest_rate: Option<f32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateAccount {
    pub name: String,
    pub nickname: Option<String>,
    #[serde(rename = "type")]
    pub account_type: String,
    pub account_limit: Option<f32>,
    pub interest_rate: Option<f32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct Incoming {
    pub id: i64,
    pub name: String,
    pub net_value: f32,
    pub gross_value: Option<f32>,
    pub incoming_month_day: i32,
    pub payday: Option<bool>,
    pub incoming_type: Option<String>,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
    pub archived: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewIncoming {
    pub name: String,
    pub net_value: f32,
    pub gross_value: Option<f32>,
    pub incoming_month_day: i32,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub payday: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateIncoming {
    pub name: String,
    pub net_value: f32,
    pub gross_value: Option<f32>,
    pub incoming_month_day: i32,
    pub user_id: Option<String>,
    pub account_id: Option<i64>,
    pub payday: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct Outgoing {
    pub id: i64,
    pub name: String,
    pub cost: f32,
    pub outgoing_day: i32,
    pub outgoing_month: Option<i32>,
    pub year_interval: Option<i32>,
    pub starting_year: Option<i32>,
    pub bill: Option<bool>,
    pub rent: Option<bool>,
    pub hidden_from_total: Option<bool>,
    pub paused: Option<bool>,
    pub pause_reason: Option<String>,
    pub paused_at: Option<i64>,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
    pub account_type: Option<String>,
    pub archived: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewOutgoing {
    pub name: String,
    pub cost: f32,
    pub outgoing_day: i32,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub outgoing_month: Option<i32>,
    pub year_interval: Option<i32>,
    pub starting_year: Option<i32>,
    pub bill: Option<bool>,
    pub rent: Option<bool>,
    pub hidden_from_total: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateOutgoing {
    pub name: String,
    pub cost: f32,
    pub outgoing_day: i32,
    pub user_id: Option<String>,
    pub account_id: Option<i64>,
    pub outgoing_month: Option<i32>,
    pub year_interval: Option<i32>,
    pub starting_year: Option<i32>,
    pub bill: Option<bool>,
    pub rent: Option<bool>,
    pub hidden_from_total: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct Balance {
    pub id: i64,
    pub value: f32,
    pub timestamp: i64,
    pub account_id: Option<i64>,
    pub user_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewBalance {
    pub value: f32,
    pub timestamp: Option<i64>,
    pub account_id: i64,
    pub user_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErraticBalance {
    pub account_id: i64,
    pub account_name: String,
    pub prev_balance_id: i64,
    pub curr_balance_id: i64,
    pub prev_value: f32,
    pub curr_value: f32,
    pub jump_amount: f32,
    pub timestamp: i64,
}
