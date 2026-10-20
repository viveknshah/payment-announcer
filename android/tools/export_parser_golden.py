#!/usr/bin/env python3
"""Regenerate the Kotlin parser's field-by-field golden file.

The Python parser is the reference implementation. Run this after changing a
rule, then commit both the Python tests and the updated TSV:

    python android/tools/export_parser_golden.py

The Kotlin corpus test compares every field, including the short id, so a
silent divergence between the two implementations fails the build.
"""
from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

BASE = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(BASE))

from sms_parser import parse_sms  # noqa: E402

CONFIG = json.loads((BASE / "config.json").read_text(encoding="utf-8"))
CORPUS = BASE / "sample_sms.txt"
OUT = BASE / "android" / "core" / "src" / "test" / "resources" / "sample_expected.tsv"

HEADER = [
    "sender", "status", "amount_paise", "payer", "txn_id", "timestamp",
    "source", "needs_review", "large", "dedupe_key", "dedupe_uncertain",
    "reason", "id",
]


def rows() -> list[list[str]]:
    out: list[list[str]] = []
    for line in CORPUS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(" | ")
        sender, body = parts[0], parts[-1]
        when = datetime.strptime(parts[1], "%Y-%m-%d %H:%M") if len(parts) == 3 else None
        r = parse_sms(sender, body, CONFIG, now=when).to_dict()
        out.append([
            r["sender"], r["status"], str(r["amount_paise"]), r["payer"] or "",
            r["txn_id"] or "", r["timestamp"], r["source"],
            str(r["needs_review"]).lower(), str(r["large"]).lower(),
            r["dedupe_key"], str(r["dedupe_uncertain"]).lower(), r["reason"], r["id"],
        ])
    return out


def main() -> None:
    data = rows()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    text = "\t".join(HEADER) + "\n"
    text += "\n".join("\t".join(row) for row in data) + "\n"
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(BASE)} with {len(data)} rows")


if __name__ == "__main__":
    main()
