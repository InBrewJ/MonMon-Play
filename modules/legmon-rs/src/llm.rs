use crate::log_buffer::LogBuffer;
use crate::models::{Statement, StatementTransaction};
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use base64::Engine;
use regex::Regex;
use serde_json::{json, Value};
use std::env;
use std::path::Path;
use std::sync::LazyLock;
use std::time::Duration;
use tokio::time::sleep;
use tracing::warn;

pub const LEGMON_SYSTEM_PROMPT: &str = r#"You are an expert UK banking document and financial statement parsing assistant.
Analyze the raw text or document of this bank statement and extract structured data in JSON format.

You MUST return ONLY valid JSON matching this exact structure:
{
  "bank": "Name of the bank or institution (e.g. Lloyds Bank, Barclays, American Express, Nationwide, Chase, HSBC, Santander, NatWest, Monzo, NS&I, First Direct)",
  "startDate": "YYYY-MM-DD",
  "endDate": "YYYY-MM-DD",
  "openingBalance": 123.45,
  "closingBalance": 456.78,
  "balances": [
    { "date": "YYYY-MM-DD", "value": 456.78 }
  ],
  "transactions": [
    {
      "date": "YYYY-MM-DD",
      "description": "Clean description of the transaction",
      "merchant": "Identified merchant name or clean payee",
      "amount": 12.50,
      "category": "groceries | dining | shopping | bills | transport | entertainment | general"
    }
  ]
}

IMPORTANT AMOUNT CONVENTION RULES:
- In MonMon, spending/expenses MUST be positive numbers (e.g. 15.00 for spending £15.00 at Tesco).
- In MonMon, deposits, salary, refunds, or incoming credits MUST be negative numbers (e.g. -1500.00 for salary).
- For balances: positive number indicates money in bank / credit available.
- If the document is a Certificate of Holdings or balance summary without individual transactions, set transactions to [] and accurately set closingBalance.

Extract every valid transaction present in the statement text with the accurate date and amount.
Output pure JSON with no markdown formatting if possible."#;

static RE_JSON_FENCE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?s)```(?:json)?\s*(.*?)\s*```").unwrap()
});

pub fn get_api_key() -> Option<String> {
    if let Ok(key) = env::var("MISTRAL_API_KEY") {
        let trimmed = key.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }

    // Fallback: check .env files
    if let Ok(val) = dotenvy::var("MISTRAL_API_KEY") {
        let trimmed = val.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }

    None
}

pub fn get_primary_model() -> String {
    env::var("MISTRAL_MODEL").unwrap_or_else(|_| "mistral-small-latest".to_string())
}

pub fn get_fallback_model() -> String {
    "mistral-medium-latest".to_string()
}

pub async fn extract_with_fallback(
    client: &reqwest::Client,
    log_buffer: &LogBuffer,
    job_id: &str,
    pdf_path: &Path,
    extracted_text: &str,
) -> Result<Statement, String> {
    let api_key = match get_api_key() {
        Some(k) => k,
        None => {
            let msg = "Mistral API key not configured (set MISTRAL_API_KEY). Skipping LLM fallback.";
            log_buffer.warn(&format!("[{}] {}", job_id, msg));
            return Err(msg.to_string());
        }
    };

    let primary_model = get_primary_model();
    let fallback_model = get_fallback_model();

    let text_len = extracted_text.trim().len();
    let has_meaningful_text = text_len >= 80;

    // Strategy A: If pdftotext/xberg extracted readable text, run chat extraction on text
    if has_meaningful_text {
        log_buffer.info(&format!(
            "[{}] Fallback Strategy 1: Invoking Mistral ({}) on {} chars of layout text...",
            job_id, primary_model, text_len
        ));

        match call_mistral_chat_text(client, &api_key, &primary_model, extracted_text).await {
            Ok(stmt) => {
                if stmt.has_data() {
                    return Ok(stmt);
                }
                log_buffer.warn(&format!(
                    "[{}] Strategy 1 with {} returned zero data; trying document-level extraction...",
                    job_id, primary_model
                ));
            }
            Err(e) => {
                log_buffer.warn(&format!(
                    "[{}] Strategy 1 with {} failed ({}). Attempting fallback...",
                    job_id, primary_model, e
                ));
            }
        }
    }

    // Strategy B: Document AI / Direct PDF Document input to Mistral
    log_buffer.info(&format!(
        "[{}] Fallback Strategy 2: Submitting raw PDF document directly to Mistral ({})...",
        job_id, primary_model
    ));

    match call_mistral_chat_document(client, &api_key, &primary_model, pdf_path).await {
        Ok(stmt) => {
            if stmt.has_data() {
                return Ok(stmt);
            }
        }
        Err(e) => {
            log_buffer.warn(&format!(
                "[{}] Strategy 2 with {} failed: {}. Trying secondary model {}...",
                job_id, primary_model, e, fallback_model
            ));
        }
    }

    // Strategy C: Secondary model (mistral-medium-latest) with layout text or document
    log_buffer.info(&format!(
        "[{}] Fallback Strategy 3: Invoking secondary model ({}) on layout text...",
        job_id, fallback_model
    ));

    if has_meaningful_text {
        if let Ok(stmt) = call_mistral_chat_text(client, &api_key, &fallback_model, extracted_text).await {
            if stmt.has_data() {
                return Ok(stmt);
            }
        }
    }

    call_mistral_chat_document(client, &api_key, &fallback_model, pdf_path).await
}

async fn call_mistral_chat_text(
    client: &reqwest::Client,
    api_key: &str,
    model: &str,
    text: &str,
) -> Result<Statement, String> {
    let trimmed = if text.len() > 50000 { &text[..50000] } else { text };

    let payload = json!({
        "model": model,
        "messages": [
            { "role": "system", "content": LEGMON_SYSTEM_PROMPT },
            { "role": "user", "content": format!("Bank Statement Raw OCR Text:\n\n{}", trimmed) }
        ],
        "response_format": { "type": "json_object" },
        "temperature": 0.1
    });

    execute_chat_completions(client, api_key, payload).await
}

async fn call_mistral_chat_document(
    client: &reqwest::Client,
    api_key: &str,
    model: &str,
    pdf_path: &Path,
) -> Result<Statement, String> {
    let pdf_bytes = tokio::fs::read(pdf_path)
        .await
        .map_err(|e| format!("Failed to read PDF file {:?}: {}", pdf_path, e))?;

    let b64_doc = BASE64_STANDARD.encode(&pdf_bytes);
    let data_uri = format!("data:application/pdf;base64,{}", b64_doc);

    let payload = json!({
        "model": model,
        "messages": [
            { "role": "system", "content": LEGMON_SYSTEM_PROMPT },
            {
                "role": "user",
                "content": [
                    { "type": "text", "text": "Bank Statement PDF attached. Please extract all structured statement data in JSON." },
                    { "type": "document_url", "document_url": data_uri }
                ]
            }
        ],
        "response_format": { "type": "json_object" },
        "temperature": 0.1
    });

    execute_chat_completions(client, api_key, payload).await
}

async fn execute_chat_completions(
    client: &reqwest::Client,
    api_key: &str,
    payload: Value,
) -> Result<Statement, String> {
    let max_retries = 3;
    let mut delay = Duration::from_secs(2);

    for attempt in 1..=max_retries {
        let res = client
            .post("https://api.mistral.ai/v1/chat/completions")
            .header("Authorization", format!("Bearer {}", api_key))
            .header("Content-Type", "application/json")
            .json(&payload)
            .timeout(Duration::from_secs(60))
            .send()
            .await;

        match res {
            Ok(resp) => {
                let status = resp.status();
                if status.is_success() {
                    let json_val = resp
                        .json::<Value>()
                        .await
                        .map_err(|e| format!("Failed to parse Mistral response body: {}", e))?;

                    let content = json_val
                        .pointer("/choices/0/message/content")
                        .and_then(|v| v.as_str())
                        .ok_or_else(|| "Missing /choices/0/message/content in response".to_string())?;

                    return parse_extracted_json(content);
                }

                let status_code = status.as_u16();
                let err_body = resp.text().await.unwrap_or_default();
                let is_retryable = (status_code == 429 || status_code >= 500) && attempt < max_retries;

                if is_retryable {
                    warn!(
                        "Mistral API returned HTTP {} (attempt {}/{}). Retrying in {:?}...",
                        status_code, attempt, max_retries, delay
                    );
                    sleep(delay).await;
                    delay *= 2;
                    continue;
                }

                return Err(format!("Mistral API error HTTP {}: {}", status_code, err_body));
            }
            Err(e) => {
                if attempt < max_retries {
                    warn!(
                        "Mistral network error (attempt {}/{}): {}. Retrying in {:?}...",
                        attempt, max_retries, e, delay
                    );
                    sleep(delay).await;
                    delay *= 2;
                    continue;
                }
                return Err(format!("Mistral request failed: {}", e));
            }
        }
    }

    Err("Mistral chat completions exceeded maximum retries".to_string())
}

pub fn parse_extracted_json(raw_content: &str) -> Result<Statement, String> {
    let trimmed = raw_content.trim();
    let cleaned = if let Some(caps) = RE_JSON_FENCE.captures(trimmed) {
        caps.get(1).map(|m| m.as_str()).unwrap_or(trimmed)
    } else {
        trimmed
    };

    let v: Value = serde_json::from_str(cleaned)
        .map_err(|e| format!("Failed to parse LLM output as JSON ({}): {}", e, &cleaned[..cleaned.len().min(300)]))?;

    let bank = v.get("bank").and_then(|x| x.as_str()).unwrap_or("Unknown Bank").to_string();

    let today = chrono::Local::now().date_naive().format("%Y-%m-%d").to_string();
    let start_date = v.get("startDate").and_then(|x| x.as_str()).unwrap_or(&today).to_string();
    let end_date = v.get("endDate").and_then(|x| x.as_str()).unwrap_or(&today).to_string();

    let opening_balance = v.get("openingBalance")
        .and_then(|x| x.as_f64().or_else(|| x.as_str().and_then(|s| s.replace(',', "").parse().ok())))
        .unwrap_or(0.0);

    let mut closing_balance = v.get("closingBalance")
        .and_then(|x| x.as_f64().or_else(|| x.as_str().and_then(|s| s.replace(',', "").parse().ok())))
        .unwrap_or(0.0);

    // If closing balance was 0.0 but balances list has entries, extract the last balance
    if closing_balance == 0.0 {
        if let Some(arr) = v.get("balances").and_then(|b| b.as_array()) {
            if let Some(last_b) = arr.last() {
                if let Some(val) = last_b.get("value").and_then(|val| val.as_f64()) {
                    closing_balance = val;
                }
            }
        }
    }

    let mut transactions = Vec::new();
    if let Some(arr) = v.get("transactions").and_then(|t| t.as_array()) {
        for t in arr {
            let tx_date = t.get("date").and_then(|x| x.as_str()).unwrap_or(&end_date).to_string();
            let desc = t.get("description").and_then(|x| x.as_str()).unwrap_or("Transaction").to_string();
            let amount = t.get("amount")
                .and_then(|x| x.as_f64().or_else(|| x.as_str().and_then(|s| s.replace(',', "").parse().ok())))
                .unwrap_or(0.0);
            let category = t.get("category").and_then(|x| x.as_str()).map(|s| s.to_string());
            let bal = t.get("balance").and_then(|x| x.as_f64());

            transactions.push(StatementTransaction {
                date: tx_date,
                description: desc,
                amount: (amount * 100.0).round() / 100.0,
                balance: bal,
                category,
            });
        }
    }

    Ok(Statement {
        bank,
        account_number: None,
        sort_code: None,
        start_date,
        end_date,
        opening_balance,
        closing_balance,
        transactions,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_extracted_json_clean() {
        let raw = r#"{
            "bank": "NatWest",
            "startDate": "2026-10-01",
            "endDate": "2026-10-28",
            "openingBalance": 1250.50,
            "closingBalance": 842.10,
            "transactions": [
                {
                    "date": "2026-10-05",
                    "description": "Tesco Superstore",
                    "merchant": "Tesco",
                    "amount": 45.20,
                    "category": "groceries"
                },
                {
                    "date": "2026-10-15",
                    "description": "Salary Employer Ltd",
                    "merchant": "Employer",
                    "amount": -2200.00,
                    "category": "income"
                }
            ]
        }"#;

        let stmt = parse_extracted_json(raw).unwrap();
        assert_eq!(stmt.bank, "NatWest");
        assert_eq!(stmt.opening_balance, 1250.50);
        assert_eq!(stmt.closing_balance, 842.10);
        assert_eq!(stmt.transactions.len(), 2);
        assert_eq!(stmt.transactions[0].amount, 45.20);
        assert_eq!(stmt.transactions[1].amount, -2200.00);
    }

    #[test]
    fn test_parse_extracted_json_with_markdown_fences() {
        let raw = "```json\n{\n  \"bank\": \"NS&I\",\n  \"startDate\": \"2026-11-02\",\n  \"endDate\": \"2026-11-02\",\n  \"openingBalance\": 2175.0,\n  \"closingBalance\": 2175.0,\n  \"transactions\": []\n}\n```";
        let stmt = parse_extracted_json(raw).unwrap();
        assert_eq!(stmt.bank, "NS&I");
        assert_eq!(stmt.closing_balance, 2175.0);
        assert!(stmt.transactions.is_empty());
    }
}
