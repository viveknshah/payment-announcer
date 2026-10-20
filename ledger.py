"""Ledger: append-only JSONL store, dedupe, daily stats, corrections.

The ledger is the shop's notebook. Every parsed SMS is one JSON line.
Dedupe: a record whose dedupe_key already exists (among counted records)
is marked duplicate=True, never counted, and flagged for review.
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from sms_parser import PaymentRecord, STATUS_CREDITED, STATUS_REFUND


class Ledger:
    def __init__(self, path: str | Path = "ledger.jsonl"):
        self.path = Path(path)

    def all_records(self) -> list[PaymentRecord]:
        if not self.path.exists():
            return []
        records = []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                records.append(PaymentRecord.from_dict(json.loads(line)))
        return records

    def _counted_keys(self) -> set[str]:
        keys = set()
        for r in self.all_records():
            if r.status in (STATUS_CREDITED, STATUS_REFUND) and not r.duplicate:
                keys.add(r.dedupe_key)
        return keys

    def add(self, record: PaymentRecord) -> PaymentRecord:
        """Store the record. Flags it as a duplicate if its key was counted before."""
        if record.status in (STATUS_CREDITED, STATUS_REFUND):
            if record.dedupe_key in self._counted_keys():
                record.duplicate = True
                record.needs_review = True
                record.reason = (record.reason + ";" if record.reason else "") + "duplicate"
        with self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(record.to_dict(), ensure_ascii=False) + "\n")
        return record

    def undo_last_sale(self) -> PaymentRecord | None:
        """Correction path: remove the most recent counted credit."""
        records = self.all_records()
        for i in range(len(records) - 1, -1, -1):
            if records[i].status == STATUS_CREDITED and not records[i].duplicate:
                removed = records.pop(i)
                self._rewrite(records)
                return removed
        return None

    def _rewrite(self, records: list[PaymentRecord]) -> None:
        with self.path.open("w", encoding="utf-8") as f:
            for r in records:
                f.write(json.dumps(r.to_dict(), ensure_ascii=False) + "\n")

    def day_records(self, date: str | None = None) -> list[PaymentRecord]:
        """Records for one calendar date (YYYY-MM-DD, default today)."""
        date = date or datetime.now().strftime("%Y-%m-%d")
        return [r for r in self.all_records() if r.timestamp.startswith(date)]


def daily_stats(records: list[PaymentRecord]) -> dict:
    """Stats over counted sales only. Duplicates and excluded SMS never appear."""
    sales = [r for r in records if r.status == STATUS_CREDITED and not r.duplicate]
    refunds = [r for r in records if r.status == STATUS_REFUND and not r.duplicate]
    review = [r for r in records if r.needs_review and not r.duplicate]
    total = sum(r.amount_paise for r in sales)
    hours: dict[int, int] = {}
    for r in sales:
        try:
            h = datetime.fromisoformat(r.timestamp).hour
        except ValueError:
            continue
        hours[h] = hours.get(h, 0) + 1
    busiest = max(hours, key=hours.get) if hours else None
    largest = max((r.amount_paise for r in sales), default=0)
    return {
        "count": len(sales),
        "total_paise": total,
        "average_paise": total // len(sales) if sales else 0,
        "busiest_hour": busiest,          # int 0-23 or None
        "largest_paise": largest,
        "refund_count": len(refunds),
        "refund_total_paise": sum(r.amount_paise for r in refunds),
        "review_count": len(review),
        "duplicate_count": sum(1 for r in records if r.duplicate),
        "sales": sales,
        "refunds": refunds,
        "review": review,
    }
