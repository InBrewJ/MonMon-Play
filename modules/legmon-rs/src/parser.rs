use crate::models::{Statement, StatementTransaction};
use chrono::Datelike;
use regex::Regex;
use std::collections::HashMap;
use std::sync::LazyLock;

static RE_NATWEST_SIGNED: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\s*(\d{2}\s+[A-Za-z]{3})\s{2,}(.+?)\s{2,}(.+?)\s{2,}(-?[£E][\d,]+\.\d{2})\s*$")
        .unwrap()
});

static RE_LEADING_DATE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\s*(\d{1,2}\s+[A-Za-z]{3,9}(?:\s+\d{2,4})?|\d{2}/\d{2}/\d{4})\b(.*)$")
        .unwrap()
});

static RE_AMOUNT_TOKEN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?:([+\-])\s*)?(?:(?:GBP|[£E])\s*)?([\d,]+\.\d{2})\b")
        .unwrap()
});

static RE_YEAR: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"\b(202\d)\b").unwrap()
});

static RE_OPENING_BAL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)(?:opening balance|starting balance|balance from previous statement)\s*[:.]?\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})")
        .unwrap()
});

static RE_CLOSING_BAL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)closing balance\s*[:.]?\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})")
        .unwrap()
});

static RE_BAL_ON: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)balance on \d{1,2} [A-Za-z]+ \d{4}\s*[.:]*\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})")
        .unwrap()
});

static RE_GENERIC_BAL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)(?:balance|total value of your bonds)\s*[:.]?\s*[¿£$€]?\s*(-?[\d,]+(?:\.\d{2})?)")
        .unwrap()
});

static RE_PERIOD_RANGE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)\b(\d{1,2}\s+[A-Za-z]{3,9}(?:\s+\d{4})?)\s+to\s+(\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4})\b")
        .unwrap()
});

static RE_SINGLE_DATE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)\b(\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4})\b").unwrap()
});

static RE_PERIOD_SLASH: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"\b(\d{2}/\d{2}/\d{4})\b").unwrap()
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

pub fn is_valid_date_token(token: &str) -> bool {
    let t = token.trim();
    if t.split('/').count() == 3 {
        return true;
    }
    let parts: Vec<&str> = t.split_whitespace().collect();
    if parts.len() >= 2 {
        if let Ok(d) = parts[0].parse::<u32>() {
            return (1..=31).contains(&d) && month_to_num(parts[1]).is_some();
        }
    }
    false
}

pub fn detect_bank(text: &str) -> String {
    let lower = text.to_lowercase();
    if lower.contains("natwest") || lower.contains("national westminster") {
        "NatWest".to_string()
    } else if lower.contains("first direct") || lower.contains("firstdirect") {
        "First Direct".to_string()
    } else if lower.contains("monzo") {
        "Monzo".to_string()
    } else if lower.contains("lloyds") {
        "Lloyds Bank".to_string()
    } else if lower.contains("barclays") {
        "Barclays".to_string()
    } else if lower.contains("hsbc") {
        "HSBC".to_string()
    } else if lower.contains("santander") {
        "Santander".to_string()
    } else if lower.contains("nationwide") {
        "Nationwide".to_string()
    } else if lower.contains("american express") || lower.contains("amex") {
        "American Express".to_string()
    } else if lower.contains("chase") {
        "Chase".to_string()
    } else if lower.contains("ns&i") || lower.contains("nsandi") || lower.contains("premium bonds") || lower.contains("national savings") {
        "NS&I".to_string()
    } else {
        "Unknown Bank".to_string()
    }
}

pub fn detect_default_year(text: &str) -> i32 {
    let mut counts: HashMap<i32, usize> = HashMap::new();
    for cap in RE_YEAR.captures_iter(text) {
        if let Ok(y) = cap[1].parse::<i32>() {
            *counts.entry(y).or_insert(0) += 1;
        }
    }
    counts
        .into_iter()
        .max_by_key(|&(_, count)| count)
        .map(|(y, _)| y)
        .unwrap_or_else(|| chrono::Utc::now().year())
}

pub fn detect_period_dates(text: &str, default_year: i32) -> (Option<String>, Option<String>) {
    let head = if text.len() > 2000 { &text[..2000] } else { text };
    let slashes: Vec<String> = RE_PERIOD_SLASH
        .find_iter(head)
        .map(|m| m.as_str().to_string())
        .collect();
    if slashes.len() >= 2 {
        return (
            Some(normalize_date(&slashes[0], default_year)),
            Some(normalize_date(&slashes[1], default_year)),
        );
    }

    if let Some(caps) = RE_PERIOD_RANGE.captures(head) {
        let end_iso = normalize_date(&caps[2], default_year);
        let end_year = end_iso[..4].parse::<i32>().unwrap_or(default_year);
        let start_iso = normalize_date(&caps[1], end_year);
        return (Some(start_iso), Some(end_iso));
    }

    if let Some(caps) = RE_SINGLE_DATE.captures(head) {
        let iso = normalize_date(&caps[1], default_year);
        return (Some(iso.clone()), Some(iso));
    }

    (None, None)
}

pub fn detect_balances(text: &str) -> (Option<f64>, Option<f64>) {
    let head = if text.len() > 3000 { &text[..3000] } else { text };

    let explicit_open = RE_OPENING_BAL
        .captures(head)
        .and_then(|c| c[1].replace(',', "").parse::<f64>().ok());

    let explicit_close = RE_CLOSING_BAL
        .captures(head)
        .and_then(|c| c[1].replace(',', "").parse::<f64>().ok());

    let bal_on: Vec<f64> = RE_BAL_ON
        .captures_iter(head)
        .filter_map(|c| c[1].replace(',', "").parse::<f64>().ok())
        .collect();

    let open_opt = explicit_open.or_else(|| {
        if bal_on.len() >= 2 {
            Some(bal_on[0])
        } else {
            None
        }
    });

    let close_opt = explicit_close.or_else(|| bal_on.last().copied());

    if open_opt.is_none() && close_opt.is_none() {
        if let Some(caps) = RE_GENERIC_BAL.captures(head) {
            let val = caps[1].replace(',', "").parse::<f64>().ok();
            return (val, val);
        }
    }

    (open_opt, close_opt)
}

#[derive(Default, Clone, Copy)]
struct ColumnBands {
    paid_in: Option<usize>,
    paid_out: Option<usize>,
    balance: Option<usize>,
    amount: Option<usize>,
}

impl ColumnBands {
    fn has_any(&self) -> bool {
        self.paid_in.is_some() || self.paid_out.is_some() || self.amount.is_some()
    }
}

fn index_of_any(line: &str, needles: &[&str]) -> Option<usize> {
    for &n in needles {
        if let Some(idx) = line.find(n) {
            return Some(idx);
        }
    }
    None
}

fn is_noise_line(lower: &str) -> bool {
    let t = lower.trim();
    t.is_empty()
        || t.starts_with("page ")
        || t.starts_with("transactions-0")
        || t.starts_with("your transactions")
        || t.contains("prudential regulation authority")
        || t.contains("national westminster bank plc")
        || t.contains("sort code")
        || t.contains("account number")
}

fn classify_category(desc: &str, tx_type: &str, amount: f64) -> String {
    let d = desc.to_lowercase();
    let _t = tx_type.to_lowercase();
    if amount < 0.0 {
        return "income".to_string();
    }
    if d.contains("council tax")
        || d.contains("water")
        || d.contains("british gas")
        || d.contains("scottishpower")
        || d.contains("edf")
        || d.contains("eon")
        || d.contains("aviva")
        || d.contains("dvla")
        || d.contains("tv licence")
        || d.contains("loan")
        || d.contains("overdraft")
        || d.contains("rent")
    {
        return "bills".to_string();
    }
    if d.contains("tesco")
        || d.contains("sainsbury")
        || d.contains("asda")
        || d.contains("morrisons")
        || d.contains("waitrose")
        || d.contains("aldi")
        || d.contains("lidl")
        || d.contains("co-op")
        || d.contains("m&s")
        || d.contains("groceries")
        || d.contains("food")
    {
        return "groceries".to_string();
    }
    if d.contains("tfl")
        || d.contains("trainline")
        || d.contains("uber")
        || d.contains("shell")
        || d.contains("bp")
        || d.contains("esso")
        || d.contains("transport")
        || d.contains("railway")
    {
        return "transport".to_string();
    }
    if d.contains("restaurant")
        || d.contains("pub")
        || d.contains("cafe")
        || d.contains("deliveroo")
        || d.contains("just eat")
        || d.contains("mcdonald")
        || d.contains("starbucks")
        || d.contains("costa")
    {
        return "dining".to_string();
    }
    if d.contains("amazon")
        || d.contains("ebay")
        || d.contains("apple")
        || d.contains("currys")
        || d.contains("argos")
        || d.contains("ikea")
        || d.contains("zara")
        || d.contains("h&m")
    {
        return "shopping".to_string();
    }
    if d.contains("netflix")
        || d.contains("spotify")
        || d.contains("cinema")
        || d.contains("disney")
        || d.contains("prime video")
        || d.contains("playstation")
        || d.contains("steam")
    {
        return "entertainment".to_string();
    }
    "general".to_string()
}

fn extract_row_from_line(
    full_line: &str,
    date_token: &str,
    bands: ColumnBands,
    pending_desc: &mut Vec<String>,
) -> Option<(String, String, f64, Option<f64>)> {
    let date_end = if !date_token.is_empty() {
        full_line.find(date_token).map(|i| i + date_token.len()).unwrap_or(0)
    } else {
        0
    };

    let all_matches: Vec<_> = RE_AMOUNT_TOKEN
        .find_iter(full_line)
        .filter(|m| m.start() >= date_end)
        .collect();

    if all_matches.is_empty() {
        return None;
    }

    let first_start = all_matches[0].start();
    let desc_part = full_line[date_end..first_start].trim();
    let mut combined_words = pending_desc.clone();
    if !desc_part.is_empty() {
        combined_words.push(desc_part.to_string());
    }
    let combined_desc = combined_words.join(" ").trim().to_string();
    let compact = combined_desc.to_lowercase().replace(' ', "");

    if compact.contains("balancebroughtforward")
        || compact.contains("balancecarriedforward")
        || combined_desc.is_empty()
    {
        pending_desc.clear();
        return None;
    }

    // Filter out trailing balance column token
    let mut non_bal_matches = Vec::new();
    let mut bal_match = None;

    for m in &all_matches {
        if let Some(b_col) = bands.balance {
            let dist_to_bal = (m.start() as isize - b_col as isize).abs();
            let dist_to_in = bands.paid_in.map(|c| (m.start() as isize - c as isize).abs()).unwrap_or(999);
            let dist_to_out = bands.paid_out.map(|c| (m.start() as isize - c as isize).abs()).unwrap_or(999);
            let dist_to_amt = bands.amount.map(|c| (m.start() as isize - c as isize).abs()).unwrap_or(999);

            if dist_to_bal < dist_to_in && dist_to_bal < dist_to_out && dist_to_amt > dist_to_bal && (all_matches.len() >= 2 || dist_to_bal <= 12) {
                bal_match = Some(*m);
                continue;
            }
        }
        non_bal_matches.push(*m);
    }

    if non_bal_matches.is_empty() {
        return None;
    }

    let tx_m = non_bal_matches[0];
    let caps = RE_AMOUNT_TOKEN.captures(tx_m.as_str())?;
    let sign_str = caps.get(1).map(|m| m.as_str()).unwrap_or("");
    let mag: f64 = caps[2].replace(',', "").parse().ok()?;
    if mag == 0.0 {
        return None;
    }

    let is_paid_in: bool = if sign_str == "+" {
        true
    } else if sign_str == "-" {
        false
    } else if let (Some(p_in), Some(p_out)) = (bands.paid_in, bands.paid_out) {
        let token_center = (tx_m.start() + tx_m.end()) as isize / 2;
        let in_center = p_in as isize + 4;
        let out_center = p_out as isize + 4;
        (token_center - in_center).abs() < (token_center - out_center).abs()
    } else if bands.paid_in.is_some() {
        true
    } else if bands.paid_out.is_some() {
        false
    } else {
        let upper = combined_desc.to_uppercase();
        upper.starts_with("CR ")
            || upper.contains(" BGC")
            || upper.contains(" F/P")
            || upper.contains("AUTOMATED CREDIT")
            || upper.contains("PAYROLL")
    };

    let signed_amt = if is_paid_in { -mag } else { mag };
    let bal_val = bal_match.and_then(|bm| {
        RE_AMOUNT_TOKEN
            .captures(bm.as_str())
            .and_then(|c| c[2].replace(',', "").parse::<f64>().ok())
    });

    Some((combined_desc, "".to_string(), signed_amt, bal_val))
}

pub fn parse(raw_text: &str) -> Statement {
    let bank = detect_bank(raw_text);
    let default_year = detect_default_year(raw_text);
    let (header_start, header_end) = detect_period_dates(raw_text, default_year);
    let (open_bal, close_bal) = detect_balances(raw_text);

    let mut transactions = Vec::new();

    // 1. Try NatWest signed export format first
    for line in raw_text.lines() {
        if let Some(caps) = RE_NATWEST_SIGNED.captures(line) {
            let date_raw = &caps[1];
            let desc_raw = caps[2].trim();
            let type_raw = caps[3].trim();
            let amt_raw = caps[4].replace(['£', 'E', ','], "").trim().to_string();
            if let Ok(val) = amt_raw.parse::<f64>() {
                // In NatWest signed export: -£33.00 is Paid Out (+33.00 in MonMon)
                let monmon_amount = -val;
                let date = normalize_date(date_raw, default_year);
                transactions.push(StatementTransaction {
                    date,
                    description: desc_raw.to_string(),
                    amount: (monmon_amount * 100.0).round() / 100.0,
                    balance: None,
                    category: Some(classify_category(desc_raw, type_raw, monmon_amount)),
                });
            }
        }
    }

    // 2. Generic layout lines if NatWest signed fast-path didn't match
    if transactions.is_empty() {
        let mut bands = ColumnBands::default();
        let mut current_date: Option<String> = None;
        let mut pending_desc = Vec::new();

        for line in raw_text.lines() {
            let lower = line.to_lowercase();
            let compact = lower.replace(' ', "");

            if lower.contains("information about the financial services")
                || lower.starts_with("transaction types")
                || lower.contains("about your statement:")
            {
                current_date = None;
                pending_desc.clear();
            } else if (lower.contains("date") || compact.contains("paymenttype"))
                && (lower.contains("paid in")
                    || lower.contains("paid out")
                    || lower.contains("money in")
                    || lower.contains("money out")
                    || lower.contains("payments in")
                    || lower.contains("payments out")
                    || lower.contains("amount"))
            {
                let p_in = index_of_any(&lower, &["paid in", "money in", "credit", "deposits", "payments in"]);
                let p_out = index_of_any(&lower, &["paid out", "money out", "debit", "withdrawals", "payments out"]);
                let bal = index_of_any(&lower, &["balance"]);
                let amt = index_of_any(&lower, &["amount"]);
                bands = ColumnBands {
                    paid_in: p_in,
                    paid_out: p_out,
                    balance: bal,
                    amount: amt,
                };
                pending_desc.clear();
            } else if bands.has_any() && !is_noise_line(&lower) {
                if let Some(m_date) = RE_LEADING_DATE.captures(line) {
                    let date_tok = &m_date[1];
                    if is_valid_date_token(date_tok) {
                        current_date = Some(normalize_date(date_tok.trim(), default_year));
                        pending_desc.clear();
                        if let Some((desc, tx_type, signed_amt, bal_val)) =
                            extract_row_from_line(line, date_tok, bands, &mut pending_desc)
                        {
                            transactions.push(StatementTransaction {
                                date: current_date.clone().unwrap(),
                                description: desc.clone(),
                                amount: (signed_amt * 100.0).round() / 100.0,
                                balance: bal_val,
                                category: Some(classify_category(&desc, &tx_type, signed_amt)),
                            });
                            pending_desc.clear();
                        } else {
                            let rest = m_date[2].trim();
                            if !rest.is_empty() {
                                pending_desc.push(rest.to_string());
                            }
                        }
                        continue;
                    }
                }

                if let Some(ref c_date) = current_date {
                    if !line.trim().is_empty() {
                        if let Some((desc, tx_type, signed_amt, bal_val)) =
                            extract_row_from_line(line, "", bands, &mut pending_desc)
                        {
                            transactions.push(StatementTransaction {
                                date: c_date.clone(),
                                description: desc.clone(),
                                amount: (signed_amt * 100.0).round() / 100.0,
                                balance: bal_val,
                                category: Some(classify_category(&desc, &tx_type, signed_amt)),
                            });
                            pending_desc.clear();
                        } else {
                            let tr = line.trim();
                            if !tr.is_empty() && !tr.eq_ignore_ascii_case("blank.") {
                                pending_desc.push(tr.to_string());
                            }
                        }
                    }
                }
            }
        }
    }

    let dates: Vec<String> = transactions.iter().map(|t| t.date.clone()).collect();
    let start_date = header_start
        .or_else(|| dates.iter().min().cloned())
        .unwrap_or_else(|| format!("{}-01-01", default_year));
    let end_date = header_end
        .or_else(|| dates.iter().max().cloned())
        .unwrap_or_else(|| format!("{}-01-31", default_year));

    // Sort transactions oldest-to-newest
    transactions.sort_by(|a, b| a.date.cmp(&b.date));

    Statement {
        bank,
        account_number: None,
        sort_code: None,
        start_date,
        end_date,
        opening_balance: open_bal.unwrap_or(0.0),
        closing_balance: close_bal.unwrap_or(0.0),
        transactions,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_detect_bank() {
        assert_eq!(detect_bank("Lloyds Bank plc"), "Lloyds Bank");
        assert_eq!(detect_bank("first direct 03 456 100 100"), "First Direct");
        assert_eq!(detect_bank("NatWest Current Account"), "NatWest");
    }

    #[test]
    fn test_natwest_signed_parsing() {
        let text = "NatWest\n22 Oct   ACC-NWESTMSTR   Mobile/Online Transaction   -£33.00\n14 Oct   J122E00A0099NSPB   BGC   £500.00";
        let stmt = parse(text);
        assert_eq!(stmt.bank, "NatWest");
        assert_eq!(stmt.transactions.len(), 2);
        // -£33.00 paid out is +33.00 in MonMon
        assert_eq!(stmt.transactions[1].amount, 33.0);
        // £500.00 paid in is -500.00 in MonMon
        assert_eq!(stmt.transactions[0].amount, -500.0);
    }

    #[test]
    fn test_column_layout_parsing_with_balances() {
        let sample = r#"
Mr J Brewer
Opening Balance £14.50
Closing Balance £61.89
27 September to 26 October 2026

Date          Payment type and details          £ Paid out                    £ Paid in                   £ Balance
26 Sep 26     Balance brought forward                                                                         14.50
29 Sep 26     LINK.COM* OCCASIO                       6.00                                                     8.50
30 Sep 26     CR BREWER JMD FIRSTDIRECTYO                                        600.00                      608.50
02 Oct 26     DD SCOTTISHPOWER                      123.01
              DD FIRST DIRECT LOAN                  246.64
              DD PAYPAL PAYMENT                      12.99                                                   225.86
"#;
        let stmt = parse(sample);
        assert_eq!(stmt.opening_balance, 14.50);
        assert_eq!(stmt.closing_balance, 61.89);
        assert_eq!(stmt.start_date, "2026-09-27");
        assert_eq!(stmt.end_date, "2026-10-26");
        assert_eq!(stmt.transactions.len(), 5);
        assert_eq!(stmt.transactions[0].amount, 6.00);
        assert_eq!(stmt.transactions[1].amount, -600.00);
        assert_eq!(stmt.transactions[2].amount, 123.01);
        assert_eq!(stmt.transactions[3].amount, 246.64);
        assert_eq!(stmt.transactions[4].amount, 12.99);
    }

    #[test]
    fn test_fixtures_end_to_end_accuracy() {
        use crate::ocr::extract_text_pdftotext;
        use std::path::Path;

        let fd_pdf = Path::new("test_fixtures/fd statement 9363 26102026.pdf");
        if fd_pdf.exists() {
            let text = extract_text_pdftotext(fd_pdf).expect("pdftotext failed");
            let stmt = parse(&text);
            assert_eq!(stmt.bank, "First Direct");
            assert_eq!(stmt.opening_balance, 14.50);
            assert_eq!(stmt.closing_balance, 61.89);
            assert_eq!(stmt.transactions.len(), 18);
            let in_total: f64 = stmt.transactions.iter().filter(|t| t.amount < 0.0).map(|t| -t.amount).sum();
            let out_total: f64 = stmt.transactions.iter().filter(|t| t.amount > 0.0).map(|t| t.amount).sum();
            assert!((in_total - 700.0).abs() < 0.01);
            assert!((out_total - 652.61).abs() < 0.01);
        }

        let lloyds_pdf = Path::new("test_fixtures/Statement_2026_10.pdf");
        if lloyds_pdf.exists() {
            let text = extract_text_pdftotext(lloyds_pdf).expect("pdftotext failed");
            let stmt = parse(&text);
            assert_eq!(stmt.bank, "Lloyds Bank");
            assert_eq!(stmt.opening_balance, 0.0);
            assert_eq!(stmt.closing_balance, 0.02);
            assert_eq!(stmt.transactions.len(), 5);
            let in_total: f64 = stmt.transactions.iter().filter(|t| t.amount < 0.0).map(|t| -t.amount).sum();
            let out_total: f64 = stmt.transactions.iter().filter(|t| t.amount > 0.0).map(|t| t.amount).sum();
            assert!((in_total - 500.0).abs() < 0.01);
            assert!((out_total - 499.98).abs() < 0.01);
        }

        let natwest_pdf = Path::new("test_fixtures/TXN_22102026_29072026_29102026_NatWest.pdf");
        if natwest_pdf.exists() {
            let text = extract_text_pdftotext(natwest_pdf).expect("pdftotext failed");
            let stmt = parse(&text);
            assert_eq!(stmt.bank, "NatWest");
            assert_eq!(stmt.transactions.len(), 78);
            let in_total: f64 = stmt.transactions.iter().filter(|t| t.amount < 0.0).map(|t| -t.amount).sum();
            let out_total: f64 = stmt.transactions.iter().filter(|t| t.amount > 0.0).map(|t| t.amount).sum();
            assert!((in_total - 10432.05).abs() < 0.01);
            assert!((out_total - 10399.08).abs() < 0.01);
        }

        let nsandi_pdf = Path::new("test_fixtures/Download_19235814_1793579663115.pdf");
        if nsandi_pdf.exists() {
            let text = extract_text_pdftotext(nsandi_pdf).expect("pdftotext failed");
            let stmt = parse(&text);
            assert_eq!(stmt.bank, "NS&I");
            assert_eq!(stmt.opening_balance, 2175.0);
            assert_eq!(stmt.closing_balance, 2175.0);
            assert_eq!(stmt.start_date, "2026-11-02");
            assert_eq!(stmt.end_date, "2026-11-02");
            assert_eq!(stmt.transactions.len(), 0);
        }
    }
}
