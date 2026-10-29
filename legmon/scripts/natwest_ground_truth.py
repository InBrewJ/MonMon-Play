#!/usr/bin/env python3
"""
Deterministic ground-truth parser for NatWest online "Your transactions" PDF exports.

Uses `pdftotext -layout` and a strict row regex. In these exports:
  - Paid out values are printed with a leading minus: -£1,000.00
  - Paid in values are printed without a sign:        £3,111.87

Prints every row, then total paid in / paid out / net, in pence-exact Decimal arithmetic.
Usage: natwest_ground_truth.py <pdf> [--json]
"""
import json
import re
import subprocess
import sys
from decimal import Decimal

ROW = re.compile(
    r"^\s*(?P<date>\d{2} [A-Z][a-z]{2})\s{2,}(?P<desc>.+?)\s{2,}(?P<type>.+?)\s{2,}(?P<amt>-?£[\d,]+\.\d{2})\s*$"
)


def parse(pdf_path):
    text = subprocess.run(
        ["pdftotext", "-layout", pdf_path, "-"], capture_output=True, text=True, check=True
    ).stdout
    rows = []
    for line in text.splitlines():
        m = ROW.match(line)
        if not m:
            continue
        raw = m.group("amt")
        value = Decimal(raw.replace("£", "").replace(",", ""))
        rows.append({
            "date": m.group("date"),
            "description": m.group("desc").strip(),
            "type": m.group("type").strip(),
            "paidIn": str(value) if value > 0 else None,
            "paidOut": str(-value) if value < 0 else None,
        })
    return rows


def main():
    pdf = sys.argv[1]
    rows = parse(pdf)
    paid_in = sum((Decimal(r["paidIn"]) for r in rows if r["paidIn"]), Decimal("0.00"))
    paid_out = sum((Decimal(r["paidOut"]) for r in rows if r["paidOut"]), Decimal("0.00"))
    summary = {
        "transactionCount": len(rows),
        "paidInCount": sum(1 for r in rows if r["paidIn"]),
        "paidOutCount": sum(1 for r in rows if r["paidOut"]),
        "totalPaidIn": str(paid_in),
        "totalPaidOut": str(paid_out),
        "net": str(paid_in - paid_out),
    }
    if "--json" in sys.argv:
        print(json.dumps({"summary": summary, "rows": rows}, indent=2))
    else:
        for r in rows:
            print(f"{r['date']:7} {r['description'][:28]:28} {r['type'][:26]:26} "
                  f"{('+' + r['paidIn']) if r['paidIn'] else '':>10} {('-' + r['paidOut']) if r['paidOut'] else '':>10}")
        print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
