#!/usr/bin/env python3
"""
Accuracy bench for LegMon statement extraction against a deterministic ground truth.

Pipelines compared (all scored to the penny against natwest_ground_truth.py):
  A  "current"   : xberg text (exactly as LegMon runs it, inside legmon:latest) -> LegMon prompt -> chat model
  A2 "layout"    : pdftotext -layout text -> LegMon prompt -> chat model  (control: row-preserving text)
  B  "mistral-ocr": PDF -> Mistral Document AI OCR (/v1/ocr, mistral-ocr-latest) markdown -> LegMon prompt -> chat model
  C  "doc-chat"  : PDF sent directly to chat completions as a document_url content block -> LegMon prompt

Usage:
  MISTRAL_API_KEY=... statement_accuracy_bench.py <pdf> [--models m1,m2] [--pipelines A,A2,B,C] [--out dir]

Amount convention (same as LegMon/MonMon): spend positive, income negative.
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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import natwest_ground_truth  # noqa: E402

API = "https://api.mistral.ai/v1"

# Verbatim copy of the system prompt in legmon/src/main/scala/legmon/MistralExtractionService.scala
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

FIXTURES_DIR_IN_CONTAINER = "/f"


def api_key():
    key = os.environ.get("MISTRAL_API_KEY", "").strip()
    if not key:
        env = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env")
        if os.path.exists(env):
            for line in open(env):
                if line.startswith("MISTRAL_API_KEY="):
                    key = line.split("=", 1)[1].strip()
    if not key:
        sys.exit("MISTRAL_API_KEY not set")
    return key


def post(path, payload, timeout=180, retries=4):
    data = json.dumps(payload).encode()
    delay = 3
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(
            API + path, data=data,
            headers={"Authorization": "Bearer " + api_key(), "Content-Type": "application/json"},
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


# ---------- text sources ----------

def xberg_text(pdf):
    d, name = os.path.split(os.path.abspath(pdf))
    out = subprocess.run(
        ["docker", "run", "--rm", "-v", f"{d}:{FIXTURES_DIR_IN_CONTAINER}:ro", "--entrypoint", "xberg",
         "legmon:latest", "extract", f"{FIXTURES_DIR_IN_CONTAINER}/{name}", "--ocr", "true", "--ocr-backend", "tesseract"],
        capture_output=True, text=True, check=True,
    )
    return out.stdout


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


# ---------- extraction ----------

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
    # Mirror LegMon's extractFloat: strip everything but digits, '.', '-'
    if isinstance(v, (int, float)):
        d = Decimal(str(v))
    else:
        s = re.sub(r"[^0-9.\-]", "", str(v or "0")) or "0"
        d = Decimal(s)
    return d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


# ---------- scoring ----------

def score(extracted, truth_rows):
    txs = extracted.get("transactions", []) or []
    amounts = [to_decimal(t.get("amount", 0)) for t in txs]
    # MonMon convention: positive = paid out, negative = paid in
    out_total = sum((a for a in amounts if a > 0), Decimal("0.00"))
    in_total = sum((-a for a in amounts if a < 0), Decimal("0.00"))

    truth_signed = collections.Counter()
    for r in truth_rows:
        truth_signed[Decimal(r["paidOut"]) if r["paidOut"] else -Decimal(r["paidIn"])] += 1
    got_signed = collections.Counter(amounts)
    matched = sum((truth_signed & got_signed).values())
    # Rows where the magnitude is right but the direction is flipped
    flipped = sum((collections.Counter({-k: v for k, v in truth_signed.items()}) & got_signed).values())

    truth_in = sum((Decimal(r["paidIn"]) for r in truth_rows if r["paidIn"]), Decimal("0.00"))
    truth_out = sum((Decimal(r["paidOut"]) for r in truth_rows if r["paidOut"]), Decimal("0.00"))
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
        "pennyPerfect": in_total == truth_in and out_total == truth_out and matched == len(truth_rows) and len(txs) == len(truth_rows),
        "openingBalance": extracted.get("openingBalance"),
        "closingBalance": extracted.get("closingBalance"),
        "startDate": extracted.get("startDate"),
        "endDate": extracted.get("endDate"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("--models", default="mistral-small-latest")
    ap.add_argument("--pipelines", default="A,A2,B,C")
    ap.add_argument("--out", default=".")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    truth = natwest_ground_truth.parse(args.pdf)
    t_in = sum((Decimal(r["paidIn"]) for r in truth if r["paidIn"]), Decimal("0.00"))
    t_out = sum((Decimal(r["paidOut"]) for r in truth if r["paidOut"]), Decimal("0.00"))
    print(f"GROUND TRUTH: {len(truth)} txs, paid in £{t_in}, paid out £{t_out}, net £{t_in - t_out}\n")

    texts = {}
    pipelines = args.pipelines.split(",")
    if "A" in pipelines:
        texts["A"] = xberg_text(args.pdf)
    if "A2" in pipelines:
        texts["A2"] = layout_text(args.pdf)
    if "B" in pipelines:
        texts["B"] = mistral_ocr_markdown(args.pdf)
    for k, v in texts.items():
        open(os.path.join(args.out, f"text_{k}.txt"), "w").write(v)

    results = []
    for model in args.models.split(","):
        for p in pipelines:
            t0 = time.time()
            try:
                if p == "C":
                    ext = chat_extract_from_document(model, args.pdf)
                else:
                    ext = chat_extract_from_text(model, texts[p])
                s = score(ext, truth)
                json.dump(ext, open(os.path.join(args.out, f"extract_{p}_{model}.json"), "w"), indent=2)
            except Exception as e:  # noqa: BLE001
                s = {"error": str(e)[:300]}
            s.update({"pipeline": p, "model": model, "seconds": round(time.time() - t0, 1)})
            results.append(s)
            print(json.dumps(s))
            sys.stdout.flush()

    json.dump({"truth": {"count": len(truth), "paidIn": str(t_in), "paidOut": str(t_out)}, "results": results},
              open(os.path.join(args.out, "bench_results.json"), "w"), indent=2)


if __name__ == "__main__":
    main()
