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

impl Statement {
    pub fn empty() -> Self {
        let today = chrono::Local::now().date_naive().format("%Y-%m-%d").to_string();
        Self {
            bank: "Unknown Bank".to_string(),
            account_number: None,
            sort_code: None,
            start_date: today.clone(),
            end_date: today,
            opening_balance: 0.0,
            closing_balance: 0.0,
            transactions: Vec::new(),
        }
    }

    pub fn has_data(&self) -> bool {
        !self.transactions.is_empty()
            || self.closing_balance.abs() > 0.001
            || self.opening_balance.abs() > 0.001
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_statement_empty_has_no_data() {
        let stmt = Statement::empty();
        assert!(!stmt.has_data());
    }

    #[test]
    fn test_statement_with_balance_only_has_data() {
        let mut stmt = Statement::empty();
        stmt.closing_balance = 2175.0;
        assert!(stmt.has_data());
    }

    #[test]
    fn test_statement_with_tx_only_has_data() {
        let mut stmt = Statement::empty();
        stmt.transactions.push(StatementTransaction {
            date: "2026-10-10".to_string(),
            description: "Coffee".to_string(),
            amount: 3.50,
            balance: None,
            category: None,
        });
        assert!(stmt.has_data());
    }
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
