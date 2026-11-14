#!/usr/bin/env python3
"""
Accuracy bench for LegMon statement extraction against deterministic ground truths.

Pipelines compared:
  det          : Deterministic parser on pdftotext -layout text
  mistral-chat : pdftotext -layout text -> LegMon prompt -> Mistral chat model (e.g. mistral-small-latest)
  mistral-ocr  : Mistral Document AI OCR (/v1/ocr) markdown -> LegMon prompt -> chat model
  doc-chat     : PDF sent directly to chat completions as a document_url block

Usage:
  python3 statement_accuracy_bench.py [--pdf <pdf>] [--gt <gt.json>] [--models mistral-small-latest] [--pipelines det,mistral-chat] [--all-fixtures]
"""
import argparse
import base64
import collections
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from decimal import Decimal, ROUND_HALF_UP

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
import natwest_ground_truth  # noqa: E402

API = "https://api.mistral.ai/v1"

LEGMON_PROMPT = """You are an expert UK banking document and financial statement parsing assistant.
Analyze the raw OCR text of this bank statement and extract structured data in JSON format.

You MUST return ONLY valid JSON matching this exact structure:
{
  "bank": "Name of the bank or institution (e.g. Lloyds Bank, Barclays, American Express, Nationwide, Chase, HSBC, Santander, NatWest, Monzo)",
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

Extract every valid transaction present in the statement text with the accurate date and amount.
Do not wrap in markdown quotes if possible, output pure JSON.
"""


def find_api_key():
    key = os.environ.get("MISTRAL_API_KEY", "").strip()
    if not key:
        # Search parent dirs for .env
        cur = SCRIPT_DIR
        for _ in range(4):
            env = os.path.join(cur, ".env")
            if os.path.exists(env):
                for line in open(env):
                    if line.startswith("MISTRAL_API_KEY="):
                        key = line.split("=", 1)[1].strip().strip('"').strip("'")
                        if key:
                            return key
            cur = os.path.dirname(cur)
    return key


def post(path, payload, timeout=180, retries=4):
    key = find_api_key()
    if not key:
        raise RuntimeError("MISTRAL_API_KEY not configured")
    data = json.dumps(payload).encode()
    delay = 3
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(
            API + path, data=data,
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            if e.code in (429, 500, 502, 503, 504) and attempt < retries:
                time.sleep(delay)
                delay *= 2
                continue
            raise RuntimeError(f"HTTP {e.code}: {body[:500]}")
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt < retries:
                time.sleep(delay)
                delay *= 2
                continue
            raise


def layout_text(pdf):
    return subprocess.run(["pdftotext", "-layout", pdf, "-"], capture_output=True, text=True, check=True).stdout


def pdf_data_uri(pdf):
    return "data:application/pdf;base64," + base64.b64encode(open(pdf, "rb").read()).decode()


def mistral_ocr_markdown(pdf):
    res = post("/ocr", {
        "model": "mistral-ocr-latest",
        "document": {"type": "document_url", "document_url": pdf_data_uri(pdf)},
        "include_image_base64": False,
    })
    return "\n\n".join(p.get("markdown", "") for p in res.get("pages", []))


def chat_extract_from_text(model, text):
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": LEGMON_PROMPT},
            {"role": "user", "content": "Bank Statement Raw OCR Text:\n\n" + text[:50000]},
        ],
        "response_format": {"type": "json_object"},
        "temperature": 0.1,
    }
    return _content(post("/chat/completions", payload))


def chat_extract_from_document(model, pdf):
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": LEGMON_PROMPT},
            {"role": "user", "content": [
                {"type": "text", "text": "Bank Statement PDF attached. Extract it."},
                {"type": "document_url", "document_url": pdf_data_uri(pdf)},
            ]},
        ],
        "response_format": {"type": "json_object"},
        "temperature": 0.1,
    }
    return _content(post("/chat/completions", payload))


def _content(res):
    c = res["choices"][0]["message"]["content"]
    c = c.strip()
    c = re.sub(r"^```(json)?", "", c).strip()
    c = re.sub(r"```$", "", c).strip()
    return json.loads(c)


def to_decimal(v):
    if isinstance(v, (int, float)):
        d = Decimal(str(v))
    else:
        s = re.sub(r"[^0-9.\-]", "", str(v or "0")) or "0"
        d = Decimal(s)
    return d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def score(extracted, truth_rows, truth_open=None, truth_close=None):
    raw_txs = extracted.get("transactions", []) or []
    txs = [
        t for t in raw_txs
        if abs(to_decimal(t.get("amount", 0))) >= Decimal("0.01")
        and "balancebroughtforward" not in str(t.get("description", "")).lower().replace(" ", "")
        and "balancecarriedforward" not in str(t.get("description", "")).lower().replace(" ", "")
    ]
    amounts = [to_decimal(t.get("amount", 0)) for t in txs]
    # MonMon convention: positive = paid out, negative = paid in
    out_total = sum((a for a in amounts if a > 0), Decimal("0.00"))
    in_total = sum((-a for a in amounts if a < 0), Decimal("0.00"))

    truth_signed = collections.Counter()
    for r in truth_rows:
        truth_signed[Decimal(r["paidOut"]) if r.get("paidOut") else -Decimal(r["paidIn"])] += 1
    got_signed = collections.Counter(amounts)
    matched = sum((truth_signed & got_signed).values())
    flipped = sum((collections.Counter({-k: v for k, v in truth_signed.items()}) & got_signed).values())

    truth_in = sum((Decimal(r["paidIn"]) for r in truth_rows if r.get("paidIn")), Decimal("0.00"))
    truth_out = sum((Decimal(r["paidOut"]) for r in truth_rows if r.get("paidOut")), Decimal("0.00"))

    ext_open = to_decimal(extracted.get("openingBalance")) if extracted.get("openingBalance") is not None else None
    ext_close = to_decimal(extracted.get("closingBalance")) if extracted.get("closingBalance") is not None else None

    open_match = (ext_open == truth_open) if (truth_open is not None and ext_open is not None) else None
    close_match = (ext_close == truth_close) if (truth_close is not None and ext_close is not None) else None

    penny_perfect = (
        in_total == truth_in
        and out_total == truth_out
        and matched == len(truth_rows)
        and len(txs) == len(truth_rows)
    )

    return {
        "txCount": len(txs),
        "truthTxCount": len(truth_rows),
        "exactSignedMatches": matched,
        "signFlipped": flipped,
        "missingFromTruth": sum((truth_signed - got_signed).values()),
        "extraNotInTruth": sum((got_signed - truth_signed).values()),
        "paidIn": str(in_total),
        "paidOut": str(out_total),
        "paidInError": str(in_total - truth_in),
        "paidOutError": str(out_total - truth_out),
        "pennyPerfect": penny_perfect,
        "openingBalance": extracted.get("openingBalance"),
        "closingBalance": extracted.get("closingBalance"),
        "truthOpening": str(truth_open) if truth_open is not None else None,
        "truthClosing": str(truth_close) if truth_close is not None else None,
        "openingBalanceMatch": open_match,
        "closingBalanceMatch": close_match,
        "startDate": extracted.get("startDate"),
        "endDate": extracted.get("endDate"),
    }


def run_single(pdf_path, gt_path, pipelines, models, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    truth_open = None
    truth_close = None
    if gt_path and os.path.exists(gt_path):
        gt_data = json.load(open(gt_path))
        truth = gt_data.get("rows", [])
        if "openingBalance" in gt_data:
            truth_open = to_decimal(gt_data["openingBalance"])
        if "closingBalance" in gt_data:
            truth_close = to_decimal(gt_data["closingBalance"])
    else:
        truth = natwest_ground_truth.parse(pdf_path)

    t_in = sum((Decimal(r["paidIn"]) for r in truth if r.get("paidIn")), Decimal("0.00"))
    t_out = sum((Decimal(r["paidOut"]) for r in truth if r.get("paidOut")), Decimal("0.00"))

    print(f"\nEvaluating: {os.path.basename(pdf_path)}")
    print(f"GROUND TRUTH: {len(truth)} txs, paid in £{t_in}, paid out £{t_out}, net £{t_in - t_out}")
    if truth_open is not None or truth_close is not None:
        print(f"GROUND TRUTH BALANCES: Opening: £{truth_open}, Closing: £{truth_close}")

    texts = {}
    if any(p in ("mistral-chat", "det") for p in pipelines):
        texts["layout"] = layout_text(pdf_path)
    if "mistral-ocr" in pipelines:
        try:
            texts["ocr"] = mistral_ocr_markdown(pdf_path)
        except Exception as e:
            print(f"Mistral OCR markdown extraction failed: {e}")

    results = []
    for p in pipelines:
        if p == "det":
            # Run deterministic parser via legmon-rs CLI or direct script
            t0 = time.time()
            try:
                # We can call cargo run / bin or our python port
                ext = run_deterministic_parser(pdf_path, texts.get("layout", ""))
                s = score(ext, truth, truth_open, truth_close)
            except Exception as e:
                s = {"error": str(e)[:300]}
            s.update({"pipeline": "det", "model": "none", "seconds": round(time.time() - t0, 1)})
            results.append(s)
            print(f"[{s['pipeline']}] Txs: {s.get('txCount')}/{s.get('truthTxCount')}, Matched: {s.get('exactSignedMatches')}, Perfect: {s.get('pennyPerfect')}, InErr: {s.get('paidInError')}, OutErr: {s.get('paidOutError')}, In: £{s.get('paidIn')}, Out: £{s.get('paidOut')}")
            continue

        for model in models:
            t0 = time.time()
            try:
                if p == "doc-chat":
                    ext = chat_extract_from_document(model, pdf_path)
                elif p == "mistral-ocr":
                    ext = chat_extract_from_text(model, texts["ocr"])
                elif p == "mistral-chat":
                    ext = chat_extract_from_text(model, texts["layout"])
                else:
                    ext = {}
                s = score(ext, truth, truth_open, truth_close)
                base = os.path.basename(pdf_path).replace(".pdf", "").replace(" ", "_")
                json.dump(ext, open(os.path.join(out_dir, f"extract_{base}_{p}_{model}.json"), "w"), indent=2)
            except Exception as e:
                s = {"error": str(e)[:300]}
            s.update({"pipeline": p, "model": model, "seconds": round(time.time() - t0, 1)})
            results.append(s)
            print(f"[{s['pipeline']}:{s['model']}] Txs: {s.get('txCount')}/{s.get('truthTxCount')}, Matched: {s.get('exactSignedMatches')}, Perfect: {s.get('pennyPerfect')}, InErr: {s.get('paidInError')}, OutErr: {s.get('paidOutError')}")

    return {"pdf": os.path.basename(pdf_path), "results": results}


def run_deterministic_parser(pdf_path, text):
    """Invokes legmon-rs parser directly via cargo or python reference implementation."""
    # Check if legmon-rs has a parse CLI or test runner
    legmon_dir = os.path.join(SCRIPT_DIR, "..")
    cmd = ["cargo", "run", "--quiet", "--manifest-path", os.path.join(legmon_dir, "Cargo.toml"), "--bin", "parse_cli", "--", pdf_path]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0 and res.stdout.strip().startswith("{"):
        return json.loads(res.stdout)
    # Fallback to python reference implementation of DeterministicStatementParser
    import deterministic_parser
    return deterministic_parser.parse(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdf", default="")
    ap.add_argument("--gt", default="")
    ap.add_argument("--models", default="mistral-small-latest")
    ap.add_argument("--pipelines", default="det,mistral-chat")
    ap.add_argument("--out", default=os.path.join(SCRIPT_DIR, "output"))
    ap.add_argument("--all-fixtures", action="store_true")
    args = ap.parse_args()

    fixtures_dir = os.path.join(SCRIPT_DIR, "..", "test_fixtures")
    pipelines = [p.strip() for p in args.pipelines.split(",") if p.strip()]
    models = [m.strip() for m in args.models.split(",") if m.strip()]

    # If Mistral key is not set, exclude LLM pipelines unless explicitly requested
    if not find_api_key():
        print("Note: MISTRAL_API_KEY not set. Running 'det' (deterministic) pipeline only.")
        pipelines = [p for p in pipelines if p == "det"]

    if args.all_fixtures or not args.pdf:
        fixtures = [
            (
                os.path.join(fixtures_dir, "Statement_2026_10.pdf"),
                os.path.join(fixtures_dir, "Statement_2026_10_ground_truth.json"),
            ),
            (
                os.path.join(fixtures_dir, "fd statement 9363 26102026.pdf"),
                os.path.join(fixtures_dir, "fd_statement_9363_26102026_ground_truth.json"),
            ),
            (
                os.path.join(fixtures_dir, "TXN_22102026_29072026_29102026_NatWest.pdf"),
                os.path.join(fixtures_dir, "TXN_22102026_29072026_29102026_NatWest_ground_truth.json"),
            ),
        ]
        all_results = []
        for pdf, gt in fixtures:
            if os.path.exists(pdf):
                res = run_single(pdf, gt, pipelines, models, args.out)
                all_results.append(res)
        summary_path = os.path.join(args.out, "bench_summary.json")
        json.dump(all_results, open(summary_path, "w"), indent=2)
        print(f"\nAll benchmark results saved to {summary_path}")
    else:
        run_single(args.pdf, args.gt, pipelines, models, args.out)


if __name__ == "__main__":
    main()
