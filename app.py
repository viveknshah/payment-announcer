#!/usr/bin/env python3
"""Chai Stall Pay Announcer - CLI entry point.

Commands:
  demo              Feed the bundled sample corpus, then the end-of-day summary.
  ingest FILE       Parse a file of SMS (SENDER | BODY lines), announce sales.
  watch             Paste mode: type or paste SMS lines, one per line.
  summary           End-of-day spoken summary + bills list for today.
  undo              Remove the most recent sale (correction path).
  serve [--port N]  Phone-frame web demo at http://localhost:8000

Flags: --lang hi|en|kn   --config config.json   --no-speak
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path

import langpack as lp
import ledger as lg
from announcer import speak
from sms_parser import (STATUS_CREDITED, STATUS_REFUND, format_rupees,
                        parse_sms)
from summarizer import end_of_day_summary

BASE = Path(__file__).resolve().parent


def force_utf8_stdio() -> None:
    """Windows consoles default to cp1252, which cannot encode Devanagari.

    Reconfigure the streams we print to so the Hindi demo never crashes on
    its first spoken line. Safe to call anywhere; skipped when a stream has
    no reconfigure (e.g. redirected to a plain file-like object).
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            pass


def load_config(path: str | None) -> dict:
    cfg_path = Path(path) if path else BASE / "config.json"
    return json.loads(cfg_path.read_text(encoding="utf-8"))


def load_pack_or_exit(code: str) -> dict:
    try:
        return lp.load_pack(code)
    except FileNotFoundError as e:
        sys.exit(str(e))


def human_reason(record, pack) -> str:
    codes = [c for c in (record.reason or "").split(";") if c]
    out = []
    for c in codes:
        text = pack["templates"].get("reason_" + c, c)
        out.append(text)
    return ", ".join(out) or "?"


def announcement_for(record, pack) -> str | None:
    """The line to speak/show for a freshly parsed record, or None."""
    amount = format_rupees(record.amount_paise)
    if record.status == STATUS_CREDITED:
        if record.duplicate:
            return lp.tpl(pack, "duplicate_line")
        text = lp.tpl(pack, "announce_payer", amount=amount, payer=record.payer) \
            if record.payer else lp.tpl(pack, "announce_no_payer", amount=amount)
        if record.large:
            text += " " + lp.tpl(pack, "verify_large")
        return text
    if record.status == STATUS_REFUND:
        return lp.tpl(pack, "refund_line", amount=amount)
    return lp.tpl(pack, "review_line", reason=human_reason(record, pack))


def describe(record, pack) -> str:
    amount = format_rupees(record.amount_paise)
    flags = []
    if record.duplicate:
        flags.append("DUPLICATE")
    if record.large:
        flags.append("LARGE - verify in bank app")
    if record.needs_review:
        flags.append(f"NEEDS REVIEW ({record.reason or '?'})")
    if record.dedupe_uncertain and record.status == STATUS_CREDITED:
        flags.append("no ref id: dedupe by amount+time")
    flag_s = " [" + "; ".join(flags) + "]" if flags else ""
    return (f"  -> {record.status.upper():8s} Rs {amount:>8s} "
            f"payer={record.payer or '-'} ref={record.txn_id or '-'} "
            f"source={record.source}{flag_s}")


def iter_sms_file(path):
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(" | ")
        sender, body, when = parts[0], parts[-1], None
        if len(parts) == 3:
            try:
                when = datetime.strptime(parts[1], "%Y-%m-%d %H:%M")
            except ValueError:
                pass
        yield sender, body, when


def handle_line(line, store, pack, config, no_speak):
    line = line.strip()
    if not line:
        return None
    if " | " in line:
        sender, body = line.split(" | ", 1)
    else:
        sender, body = "PASTED", line
    record = store.add(parse_sms(sender, body, config))
    print(describe(record, pack))
    say = announcement_for(record, pack)
    if say:
        speak(say, pack, config, no_speak=no_speak)
    return record


def run_summary(store, pack, config, no_speak, date=None):
    stats = lg.daily_stats(store.day_records(date))
    result = end_of_day_summary(stats, pack, config)
    print(f"\n=== {result['text']}")
    print(f"=== {result['bills_text']}")
    print(f"(summary engine: {result['engine']})")
    speak(result["text"] + " " + result["bills_text"], pack, config, no_speak=no_speak)
    return result


def main():
    force_utf8_stdio()
    ap = argparse.ArgumentParser(description="Chai Stall Pay Announcer")
    ap.add_argument("command", choices=["demo", "ingest", "watch", "summary", "undo", "serve"])
    ap.add_argument("file", nargs="?", help="SMS file for 'ingest'")
    ap.add_argument("--lang", default="hi")
    ap.add_argument("--config", default=None)
    ap.add_argument("--no-speak", action="store_true")
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="127.0.0.1",
                    help="use 0.0.0.0 to open the demo from a phone on the same Wi-Fi")
    args = ap.parse_args()

    config = load_config(args.config)
    pack = load_pack_or_exit(args.lang)
    if pack.get("template_fallback") and pack.get("code") != "hi":
        print(f"note: {pack['name']} pack is template mode (no LLM summary).")
    store = lg.Ledger(BASE / config.get("ledger_file", "ledger.jsonl"))

    if args.command == "ingest":
        if not args.file:
            sys.exit("ingest needs a file: python app.py ingest sample_sms.txt")
        for sender, body, when in iter_sms_file(args.file):
            record = store.add(parse_sms(sender, body, config, now=when))
            print(describe(record, pack))
            say = announcement_for(record, pack)
            if say:
                speak(say, pack, config, no_speak=args.no_speak)
        print("\nDone. Try: python app.py summary --lang", args.lang)

    elif args.command == "watch":
        print("Paste an SMS per line (optionally 'SENDER | message'). Ctrl-D to finish.")
        for line in sys.stdin:
            handle_line(line, store, pack, config, args.no_speak)

    elif args.command == "summary":
        run_summary(store, pack, config, args.no_speak)

    elif args.command == "undo":
        removed = store.undo_last_sale()
        if removed:
            print(lp.tpl(pack, "undo_done", amount=format_rupees(removed.amount_paise)))
        else:
            print(lp.tpl(pack, "undo_none"))

    elif args.command == "demo":
        print("Feeding the bundled sample corpus...\n")
        for sender, body, when in iter_sms_file(BASE / "sample_sms.txt"):
            print(f"SMS from {sender}: {body[:70]}{'...' if len(body) > 70 else ''}")
            record = store.add(parse_sms(sender, body, config, now=when))
            print(describe(record, pack))
            say = announcement_for(record, pack)
            if say:
                speak(say, pack, config, no_speak=args.no_speak)
            time.sleep(0.3)
        run_summary(store, pack, config, args.no_speak)

    elif args.command == "serve":
        import server
        server.serve(host=args.host, port=args.port, lang=args.lang,
                     config=config, no_speak=args.no_speak)


if __name__ == "__main__":
    main()
