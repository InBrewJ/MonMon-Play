use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatementTransaction {
    pub date: String,
    pub description: String,
    pub amount: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub balance: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Statement {
    pub bank: String,
    #[serde(default)]
    pub account_number: Option<String>,
    #[serde(default)]
    pub sort_code: Option<String>,
    pub start_date: String,
    pub end_date: String,
    pub opening_balance: f64,
    pub closing_balance: f64,
    pub transactions: Vec<StatementTransaction>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogEntry {
    pub time: String,
    pub level: String,
    pub msg: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusResponse {
    pub service: String,
    pub status: String,
    pub error_count: usize,
    pub last_error: String,
    pub has_error: bool,
    pub total_buffered_logs: usize,
    pub uptime_seconds: String,
    pub mistral_key_configured: bool,
    pub mistral_ocr_enabled: bool,
    pub mistral_ocr_model: String,
    pub primary_model: String,
    pub fallback_model: String,
    pub primary_pipeline: String,
    pub fallback_pipeline: String,
    pub deterministic_fallback: String,
    pub deterministic_only: bool,
    pub ocr_tools: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogsResponse {
    pub service: String,
    pub has_error: bool,
    pub error_count: usize,
    pub last_error: String,
    pub logs: Vec<LogEntry>,
}
