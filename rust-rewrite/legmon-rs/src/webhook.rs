use crate::models::Statement;
use serde_json::json;
use tracing::info;

pub async fn dispatch_webhook(
    client: &reqwest::Client,
    callback_url: &str,
    user_id: &str,
    job_id: &str,
    target_account_id: Option<i64>,
    statement: &Statement,
) -> Result<(), String> {
    let payload = json!({
        "userId": user_id,
        "jobId": job_id,
        "bank": statement.bank,
        "startDate": statement.start_date,
        "endDate": statement.end_date,
        "openingBalance": statement.opening_balance,
        "closingBalance": statement.closing_balance,
        "targetAccountId": target_account_id,
        "transactions": statement.transactions
    });

    info!("Dispatching statement webhook to {}", callback_url);
    let res = client
        .post(callback_url)
        .json(&payload)
        .send()
        .await
        .map_err(|e| format!("HTTP request to webhook failed: {}", e))?;

    if !res.status().is_success() {
        return Err(format!("Webhook responded with HTTP status {}", res.status()));
    }

    Ok(())
}
