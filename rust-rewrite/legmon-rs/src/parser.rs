use crate::models::{Statement, StatementTransaction};
use chrono::Datelike;
use regex::Regex;
use std::sync::LazyLock;

static RE_NATWEST_SIGNED: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\s*(\d{2}\s+[A-Za-z]{3})\s{2,}(.+?)\s{2,}(.+?)\s{2,}(-?[£E][\d,]+\.\d{2})\s*$")
        .unwrap()
});

static RE_MARKDOWN_ROW: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\s*\|\s*(\d{1,2}\s+[A-Za-z]{3}(?:\s+\d{2,4})?)\s*\|\s*([^|]+?)\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|.*$")
        .unwrap()
});

static RE_LEADING_DATE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\s*(\d{1,2}\s+[A-Za-z]{3,9}(?:\s+\d{2,4})?|\d{2}/\d{2}/\d{4})\b(.*)$")
        .unwrap()
});

static RE_AMOUNT_TOKEN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?:([+\-])\s*)?(?:(?:GBP|[£E])\s*)?([\d,]+\.\d{2})(?![0-9%])")
        .unwrap()
});

static RE_YEAR: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"\b(20[2-3]\d)\b").unwrap()
});

fn month_to_num(m: &str) -> Option<u32> {
    match m.to_lowercase().as_str() {
        "jan" | "january" => Some(1),
        "feb" | "february" => Some(2),
        "mar" | "march" => Some(3),
        "apr" | "april" => Some(4),
        "may" => Some(5),
        "jun" | "june" => Some(6),
        "jul" | "july" => Some(7),
        "aug" | "august" => Some(8),
        "sep" | "sept" | "september" => Some(9),
        "oct" | "october" => Some(10),
        "nov" | "november" => Some(11),
        "dec" | "december" => Some(12),
        _ => None,
    }
}

pub fn normalize_date(date_str: &str, default_year: i32) -> String {
    let trimmed = date_str.trim();
    if let Some((d, rest)) = trimmed.split_once('/') {
        if let Some((m, y)) = rest.split_once('/') {
            let year = if y.len() == 2 {
                2000 + y.parse::<i32>().unwrap_or(26)
            } else {
                y.parse::<i32>().unwrap_or(default_year)
            };
            let month = m.parse::<u32>().unwrap_or(1);
            let day = d.parse::<u32>().unwrap_or(1);
            return format!("{:04}-{:02}-{:02}", year, month, day);
        }
    }

    let parts: Vec<&str> = trimmed.split_whitespace().collect();
    if parts.len() >= 2 {
        let day = parts[0].parse::<u32>().unwrap_or(1);
        if let Some(m) = month_to_num(parts[1]) {
            let year = if parts.len() >= 3 {
                let y = parts[2];
                if y.len() == 2 {
                    2000 + y.parse::<i32>().unwrap_or(26)
                } else {
                    y.parse::<i32>().unwrap_or(default_year)
                }
            } else {
                default_year
            };
            return format!("{:04}-{:02}-{:02}", year, m, day);
        }
    }

    format!("{}-01-01", default_year)
}

pub fn detect_bank(text: &str) -> String {
    let lower = text.to_lowercase();
    if lower.contains("natwest") || lower.contains("national westminster") {
        "NatWest".to_string()
    } else if lower.contains("monzo") {
        "Monzo".to_string()
    } else if lower.contains("american express") || lower.contains("amex") {
        "American Express".to_string()
    } else if lower.contains("hsbc") {
        "HSBC".to_string()
    } else if lower.contains("santander") {
        "Santander".to_string()
    } else if lower.contains("lloyds") {
        "Lloyds".to_string()
    } else if lower.contains("first direct") {
        "First Direct".to_string()
    } else if lower.contains("barclays") {
        "Barclays".to_string()
    } else if lower.contains("halifax") {
        "Halifax".to_string()
    } else if lower.contains("nationwide") {
        "Nationwide".to_string()
    } else {
        "Unknown Bank".to_string()
    }
}

pub fn detect_default_year(text: &str) -> i32 {
    let mut years = Vec::new();
    for cap in RE_YEAR.captures_iter(text) {
        if let Ok(y) = cap[1].parse::<i32>() {
            if (2020..=2030).contains(&y) {
                years.push(y);
            }
        }
    }
    if years.is_empty() {
        chrono::Utc::now().year()
    } else {
        *years.iter().max().unwrap()
    }
}

pub fn parse(raw_text: &str) -> Statement {
    let bank = detect_bank(raw_text);
    let default_year = detect_default_year(raw_text);

    let mut transactions = Vec::new();

    // 1. NatWest signed format
    for line in raw_text.lines() {
        if let Some(caps) = RE_NATWEST_SIGNED.captures(line) {
            let date_raw = &caps[1];
            let desc_raw = caps[2].trim();
            let amt_raw = caps[4].replace(['£', 'E', ','], "").trim().to_string();
            if let Ok(val) = amt_raw.parse::<f64>() {
                // In NatWest signed export: -£33.00 is Paid Out (+33.0 in MonMon)
                let monmon_amount = -val;
                let date = normalize_date(date_raw, default_year);
                transactions.push(StatementTransaction {
                    date,
                    description: desc_raw.to_string(),
                    amount: monmon_amount,
                    balance: None,
                    category: None,
                });
            }
        }
    }

    // 2. Generic layout lines if NatWest didn't match
    if transactions.is_empty() {
        let mut current_date: Option<String> = None;
        for line in raw_text.lines() {
            let trimmed = line.trim();
            if let Some(caps) = RE_LEADING_DATE.captures(trimmed) {
                let date_token = &caps[1];
                let norm = normalize_date(date_token, default_year);
                current_date = Some(norm);

                let rest = &caps[2];
                if let Some(amt_cap) = RE_AMOUNT_TOKEN.captures(rest) {
                    let sign = amt_cap.get(1).map_or("", |m| m.as_str());
                    let mag: f64 = amt_cap[2].replace(',', "").parse().unwrap_or(0.0);
                    if mag > 0.0 {
                        let amount = if sign == "+" { -mag } else { mag };
                        let desc = rest.replace(&amt_cap[0], "").trim().to_string();
                        transactions.push(StatementTransaction {
                            date: current_date.clone().unwrap(),
                            description: if desc.is_empty() { "Transaction".to_string() } else { desc },
                            amount,
                            balance: None,
                            category: None,
                        });
                    }
                }
            }
        }
    }

    let start_date = transactions
        .first()
        .map(|t| t.date.clone())
        .unwrap_or_else(|| format!("{}-01-01", default_year));
    let end_date = transactions
        .last()
        .map(|t| t.date.clone())
        .unwrap_or_else(|| format!("{}-01-31", default_year));

    Statement {
        bank,
        account_number: None,
        sort_code: None,
        start_date,
        end_date,
        opening_balance: 0.0,
        closing_balance: 0.0,
        transactions,
    }
}
