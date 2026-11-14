#!/usr/bin/env python3
"""
Python reference implementation of LegMon's DeterministicStatementParser.
Mirrors DeterministicStatementParser.scala from commit 0cea0ab.
"""
import collections
import re
from datetime import datetime, date
from decimal import Decimal, ROUND_HALF_UP

MONTH_MAP = {
    "jan": 1, "january": 1, "feb": 2, "february": 2, "mar": 3, "march": 3,
    "apr": 4, "april": 4, "may": 5, "jun": 6, "june": 6, "jul": 7, "july": 7,
    "aug": 8, "august": 8, "sep": 9, "sept": 9, "september": 9, "oct": 10,
    "october": 10, "nov": 11, "november": 11, "dec": 12, "december": 12,
}

NATWEST_SIGNED_ROW = re.compile(
    r"^\s*(\d{2}\s+[A-Za-z]{3})\s{2,}(.+?)\s{2,}(.+?)\s{2,}(-?[£E][\d,]+\.\d{2})\s*$"
)

LEADING_DATE = re.compile(
    r"^\s*(\d{1,2}\s+[A-Za-z]{3,9}(?:\s+\d{2,4})?|\d{2}/\d{2}/\d{4})\b(.*)$"
)

AMOUNT_TOKEN = re.compile(
    r"(?:([+\-])\s*)?(?:(?:GBP|[£E])\s*)?([\d,]+\.\d{2})(?![0-9%])"
)


class ColumnBands:
    def __init__(self, paid_in=None, paid_out=None, balance=None, amount=None):
        self.paid_in = paid_in
        self.paid_out = paid_out
        self.balance = balance
        self.amount = amount

    @property
    def has_any(self):
        return any(x is not None for x in (self.paid_in, self.paid_out, self.amount))


def detect_bank(text):
    lower = text.lower()
    if "natwest" in lower or "national westminster" in lower:
        return "NatWest"
    if "first direct" in lower or "firstdirect" in lower:
        return "First Direct"
    if "monzo" in lower:
        return "Monzo"
    if "lloyds" in lower:
        return "Lloyds Bank"
    if "barclays" in lower:
        return "Barclays"
    if "hsbc" in lower:
        return "HSBC"
    if "santander" in lower:
        return "Santander"
    if "nationwide" in lower:
        return "Nationwide"
    if "american express" in lower or "amex" in lower:
        return "American Express"
    if "chase" in lower:
        return "Chase"
    return "Unknown Bank"


def detect_default_year(text):
    years = [int(y) for y in re.findall(r"\b(202\d)\b", text)]
    if years:
        counts = collections.Counter(years)
        return counts.most_common(1)[0][0]
    return date.today().year


def normalize_date(raw, default_year):
    clean = re.sub(r"\s+", " ", raw.strip())
    if re.match(r"^\d{2}/\d{2}/\d{4}$", clean):
        p = clean.split("/")
        return f"{int(p[2]):04d}-{int(p[1]):02d}-{int(p[0]):02d}"
    parts = clean.split(" ")
    if len(parts) >= 2:
        try:
            day = int(parts[0])
        except ValueError:
            day = 1
        m_str = parts[1].lower()[:3]
        month = MONTH_MAP.get(m_str, 1)
        if len(parts) >= 3:
            try:
                y = int(parts[2])
                year = (2000 + y) if y < 100 else y
            except ValueError:
                year = default_year
        else:
            year = default_year
        return f"{year:04d}-{month:02d}-{day:02d}"
    return date.today().isoformat()


def is_valid_date_token(token):
    t = token.strip()
    if re.match(r"^\d{2}/\d{2}/\d{4}$", t):
        return True
    parts = t.split()
    if len(parts) >= 2:
        try:
            d = int(parts[0])
            return 1 <= d <= 31 and parts[1].lower()[:3] in MONTH_MAP
        except ValueError:
            return False
    return False


def detect_period_dates(text, default_year):
    head = text[:1500]
    # 1. From DD/MM/YYYY To DD/MM/YYYY
    slash = re.findall(r"\b(\d{2}/\d{2}/\d{4})\b", head)
    if len(slash) >= 2:
        return normalize_date(slash[0], default_year), normalize_date(slash[1], default_year)
    # 2. DD Month to DD Month YYYY
    m = re.search(r"(?i)\b(\d{1,2}\s+[A-Za-z]{3,9}(?:\s+\d{4})?)\s+to\s+(\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4})\b", head)
    if m:
        end_iso = normalize_date(m.group(2), default_year)
        end_year = int(end_iso[:4]) if end_iso[:4].isdigit() else default_year
        start_iso = normalize_date(m.group(1), end_year)
        return start_iso, end_iso
    return None, None


def detect_balances(text):
    head = text[:3000]
    explicit_open = None
    m_open = re.search(r"(?i)(?:opening balance|starting balance|balance from previous statement)\s*[:.]?\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})", head)
    if m_open:
        explicit_open = float(m_open.group(1).replace(",", ""))

    explicit_close = None
    m_close = re.search(r"(?i)closing balance\s*[:.]?\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})", head)
    if m_close:
        explicit_close = float(m_close.group(1).replace(",", ""))

    bal_on = [
        float(m.group(1).replace(",", ""))
        for m in re.finditer(r"(?i)balance on \d{1,2} [A-Za-z]+ \d{4}\s*[.:]*\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})", head)
    ]
    open_opt = explicit_open if explicit_open is not None else (bal_on[0] if len(bal_on) >= 2 else None)
    close_opt = explicit_close if explicit_close is not None else (bal_on[-1] if bal_on else None)
    return open_opt, close_opt


def index_of_any(line, needles):
    for n in needles:
        idx = line.find(n)
        if idx >= 0:
            return idx
    return None


def is_noise_line(lower):
    t = lower.strip()
    return (
        not t
        or t.startswith("page ")
        or t.startswith("transactions-0")
        or t.startswith("your transactions")
        or "prudential regulation authority" in t
        or "national westminster bank plc" in t
        or "sort code" in t
        or "account number" in t
    )


def classify_category(desc, tx_type, amount):
    d = desc.lower()
    t = tx_type.lower()
    if amount < 0:
        return "income"
    if any(k in d for k in ("council tax", "water", "british gas", "scottishpower", "edf", "eon", "aviva", "dvla", "tv licence", "loan", "overdraft", "rent")):
        return "bills"
    if any(k in d for k in ("tesco", "sainsbury", "asda", "morrisons", "waitrose", "aldi", "lidl", "co-op", "m&s", "groceries", "food")):
        return "groceries"
    if any(k in d for k in ("tfl", "trainline", "uber", "shell", "bp", "esso", "transport", "railway")):
        return "transport"
    if any(k in d for k in ("restaurant", "pub", "cafe", "deliveroo", "just eat", "mcdonald", "starbucks", "costa")):
        return "dining"
    if any(k in d for k in ("amazon", "ebay", "apple", "currys", "argos", "ikea", "zara", "h&m")):
        return "shopping"
    if any(k in d for k in ("netflix", "spotify", "cinema", "disney", "prime video", "playstation", "steam")):
        return "entertainment"
    return "general"


def parse(raw_text):
    import collections  # noqa
    bank = detect_bank(raw_text)
    default_year = detect_default_year(raw_text)
    header_start, header_end = detect_period_dates(raw_text, default_year)
    open_bal, close_bal = detect_balances(raw_text)

    # 1. Try NatWest signed export rows first
    transactions = []
    idx = 0
    for line in raw_text.splitlines():
        m = NATWEST_SIGNED_ROW.match(line)
        if m:
            date_raw, desc_raw, type_raw, amt_raw = m.groups()
            clean_amt = amt_raw.replace("£", "").replace("E", "").replace(",", "").strip()
            try:
                raw_val = float(clean_amt)
                # MonMon convention: spend positive, income negative
                # NatWest: -£33.00 is spend -> +33.00; £3,111.87 is income -> -3111.87
                monmon_amount = -raw_val
                iso_date = normalize_date(date_raw.strip(), default_year)
                transactions.append({
                    "id": f"legmon_det_tx_{idx}",
                    "date": iso_date,
                    "description": desc_raw.strip(),
                    "merchant": desc_raw.strip(),
                    "amount": round(monmon_amount, 2),
                    "category": classify_category(desc_raw, type_raw, monmon_amount),
                })
                idx += 1
            except ValueError:
                pass

    # 2. If no signed rows, parse layout columns (First Direct, Lloyds, etc.)
    if not transactions:
        bands = ColumnBands()
        current_date = None
        pending_desc = []

        for line in raw_text.splitlines():
            lower = line.lower()
            compact = lower.replace(" ", "")

            if ("information about the financial services" in lower or
                lower.startswith("transaction types") or
                "about your statement:" in lower):
                current_date = None
                pending_desc = []
            elif (("date" in lower or "paymenttype" in compact) and
                  any(w in lower for w in ("paid in", "paid out", "money in", "money out", "payments in", "payments out", "amount"))):
                p_in = index_of_any(lower, ["paid in", "money in", "credit", "deposits", "payments in"])
                p_out = index_of_any(lower, ["paid out", "money out", "debit", "withdrawals", "payments out"])
                bal = index_of_any(lower, ["balance"])
                amt = index_of_any(lower, ["amount"])
                bands = ColumnBands(p_in, p_out, bal, amt)
                pending_desc = []
            elif bands.has_any and not is_noise_line(lower):
                m_date = LEADING_DATE.match(line)
                if m_date and is_valid_date_token(m_date.group(1)):
                    date_tok = m_date.group(1)
                    current_date = normalize_date(date_tok.strip(), default_year)
                    pending_desc = []
                    res = extract_row_from_line(line, date_tok, current_date, bands, pending_desc)
                    if res:
                        desc, tx_type, signed_amt, bal_val = res
                        transactions.append({
                            "id": f"legmon_det_tx_{idx}",
                            "date": current_date,
                            "description": desc,
                            "merchant": desc,
                            "amount": float(signed_amt),
                            "balance": float(bal_val) if bal_val is not None else None,
                            "category": classify_category(desc, tx_type, signed_amt),
                        })
                        idx += 1
                        pending_desc = []
                    else:
                        rest = m_date.group(2).strip()
                        if rest:
                            pending_desc.append(rest)
                elif current_date and line.strip():
                    res = extract_row_from_line(line, "", current_date, bands, pending_desc)
                    if res:
                        desc, tx_type, signed_amt, bal_val = res
                        transactions.append({
                            "id": f"legmon_det_tx_{idx}",
                            "date": current_date,
                            "description": desc,
                            "merchant": desc,
                            "amount": float(signed_amt),
                            "balance": float(bal_val) if bal_val is not None else None,
                            "category": classify_category(desc, tx_type, signed_amt),
                        })
                        idx += 1
                        pending_desc = []
                    else:
                        tr = line.strip()
                        if tr and tr.lower() != "blank.":
                            pending_desc.append(tr)

    dates = [t["date"] for t in transactions if re.match(r"^\d{4}-\d{2}-\d{2}$", t["date"])]
    start_date = header_start or (min(dates) if dates else f"{default_year}-01-01")
    end_date = header_end or (max(dates) if dates else f"{default_year}-01-31")

    # Invert chronological sort if newest first, so transactions are oldest-to-newest
    # But preserve date order
    transactions.sort(key=lambda t: t["date"])

    total_in = sum((Decimal(str(-t["amount"])) for t in transactions if t["amount"] < 0), Decimal("0.00"))
    total_out = sum((Decimal(str(t["amount"])) for t in transactions if t["amount"] > 0), Decimal("0.00"))

    balances = []
    if close_bal is not None:
        balances.append({"date": end_date, "value": close_bal})

    return {
        "bank": bank,
        "startDate": start_date,
        "endDate": end_date,
        "openingBalance": open_bal if open_bal is not None else 0.0,
        "closingBalance": close_bal if close_bal is not None else 0.0,
        "totalPaidIn": float(total_in),
        "totalPaidOut": float(total_out),
        "balances": balances,
        "transactions": transactions,
    }


def extract_row_from_line(full_line, date_token, iso_date, bands, pending_desc):
    date_end = full_line.find(date_token) + len(date_token) if date_token else 0
    matches = [m for m in AMOUNT_TOKEN.finditer(full_line) if m.start() >= date_end]
    if not matches:
        return None

    first_start = matches[0].start()
    desc_part = full_line[date_end:first_start].strip()
    combined_desc = " ".join(pending_desc + ([desc_part] if desc_part else [])).strip()
    combined_desc = re.sub(r"\s{2,}", " ", combined_desc)
    compact = combined_desc.lower().replace(" ", "")

    if "balancebroughtforward" in compact or "balancecarriedforward" in compact or not combined_desc:
        pending_desc.clear()
        return None

    # Filter out trailing balance column token
    non_balance_matches = []
    bal_match = None
    for m in matches:
        if bands.balance is not None:
            dist_to_bal = abs(m.start() - bands.balance)
            dist_to_in = abs(m.start() - bands.paid_in) if bands.paid_in is not None else 999
            dist_to_out = abs(m.start() - bands.paid_out) if bands.paid_out is not None else 999
            dist_to_amt = abs(m.start() - bands.amount) if bands.amount is not None else 999
            if dist_to_bal < dist_to_in and dist_to_bal < dist_to_out and dist_to_amt > dist_to_bal and (len(matches) >= 2 or dist_to_bal <= 12):
                bal_match = m
                continue
        non_balance_matches.append(m)

    if not non_balance_matches:
        return None

    tx_match = non_balance_matches[0]
    sign_str = tx_match.group(1) or ""
    mag = Decimal(tx_match.group(2).replace(",", ""))
    if mag == Decimal(0):
        return None

    is_paid_in = False
    if sign_str == "+":
        is_paid_in = True
    elif sign_str == "-":
        is_paid_in = False
    elif bands.paid_in is not None and bands.paid_out is not None:
        token_center = (tx_match.start() + tx_match.end()) / 2
        in_center = bands.paid_in + 4
        out_center = bands.paid_out + 4
        is_paid_in = abs(token_center - in_center) < abs(token_center - out_center)
    elif bands.paid_in is not None:
        is_paid_in = True
    elif bands.paid_out is not None:
        is_paid_in = False
    else:
        upper = combined_desc.upper()
        is_paid_in = (
            upper.startswith("CR ")
            or " BGC" in upper
            or " F/P" in upper
            or "AUTOMATED CREDIT" in upper
            or "PAYROLL" in upper
        )

    signed_amt = -mag if is_paid_in else mag
    bal_val = Decimal(bal_match.group(2).replace(",", "")) if bal_match else None
    return combined_desc, "", signed_amt, bal_val
