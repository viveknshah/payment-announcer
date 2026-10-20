"""Phone-frame web demo. Pure standard library, no CDN, no cloud.

Run:  python app.py serve              -> http://localhost:8000
      python app.py serve --host 0.0.0.0   -> also reachable from a phone
      on the same Wi-Fi at http://<laptop-ip>:8000
"""
from __future__ import annotations

import json
import queue
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import langpack as lp
import ledger as lg
from announcer import piper_available, speak
from app import (announcement_for, force_utf8_stdio, human_reason, iter_sms_file,
                 load_config)
from sms_parser import STATUS_CREDITED, format_rupees, parse_sms
from summarizer import end_of_day_summary, ollama_available

BASE = Path(__file__).resolve().parent


class DemoState:
    def __init__(self, lang: str, config: dict, no_speak: bool):
        self.config = config
        self.no_speak = no_speak
        self.lock = threading.RLock()
        self.store = lg.Ledger(BASE / config.get("ledger_file", "ledger.jsonl"))
        self.set_lang(lang)
        self.last_announcement = {"text": "", "ts": 0.0, "mode": ""}
        self.last_record = None
        self._speaking = False
        self._seq = 0
        # One narrator only. Rapid fixture clicks used to start one speech
        # thread each, so the voices overlapped and the app fell behind.
        self._speech_q: queue.Queue[tuple[int, str]] = queue.Queue(maxsize=1)
        threading.Thread(target=self._speech_worker, daemon=True).start()

    def set_lang(self, code: str):
        self.pack = lp.load_pack(code)

    def feed(self, sender: str, body: str) -> dict:
        with self.lock:
            record = self.store.add(parse_sms(sender, body, self.config))
            self.last_record = record
            say = announcement_for(record, self.pack)
            if say:
                self.last_announcement = {"text": say, "ts": time.time(), "mode": "pending"}
                self.enqueue_speech(say)
            return self._record_json(record, say)

    def enqueue_speech(self, text: str, spoken: str | None = None):
        """Keep the newest line only; never queue a backlog of stale sales."""
        with self.lock:
            self._seq += 1
            seq = self._seq
        while True:
            try:
                self._speech_q.put_nowait((seq, spoken or text))
                return
            except queue.Full:
                try:
                    self._speech_q.get_nowait()  # drop the older line
                except queue.Empty:
                    pass

    def _speech_worker(self):
        while True:
            seq, spoken = self._speech_q.get()
            self._speaking = True
            try:
                mode = speak(spoken, self.pack, self.config, no_speak=self.no_speak)
            finally:
                self._speaking = False
            with self.lock:
                # Only the newest announcement may finish the "pending" state.
                if seq == self._seq:
                    self.last_announcement["mode"] = mode

    def _record_json(self, r, say):
        return {
            "id": r.id, "status": r.status, "amount": format_rupees(r.amount_paise),
            "payer": r.payer, "ref": r.txn_id, "source": r.source,
            "needs_review": r.needs_review, "large": r.large,
            "duplicate": r.duplicate, "reason": human_reason(r, self.pack),
            "announcement": say,
        }

    def state_json(self) -> dict:
        stats = lg.daily_stats(self.store.day_records())
        last = self._record_json(self.last_record, None) if self.last_record else None
        return {
            "lang": self.pack["code"],
            "languages": [{"code": c, "name": lp.load_pack(c)["name"],
                           "stub": bool(lp.load_pack(c).get("template_fallback"))}
                          for c in lp.available_languages()],
            "total": format_rupees(stats["total_paise"]),
            "count": stats["count"],
            "review_count": stats["review_count"],
            "refund_count": stats["refund_count"],
            "last_payment": last,
            "announcement": self.last_announcement,
            "speaking": self._speaking,
            "engines": {"piper": piper_available(self.pack, self.config),
                        "ollama": ollama_available(self.config)},
        }


STATE: DemoState | None = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _write(self, data: bytes) -> bool:
        """Send the body; a reload can abort a poll mid-flight (WinError 10053)."""
        try:
            self.wfile.write(data)
            return True
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            return False

    def _json(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self._write(data)

    def _body(self) -> dict:
        try:
            n = int(self.headers.get("Content-Length", 0))
            return json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
        except (ValueError, json.JSONDecodeError):
            return {}

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/index.html"):
            data = (BASE / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self._write(data)
        elif self.path == "/api/state":
            self._json(STATE.state_json())
        elif self.path == "/api/fixtures":
            fixtures = [{"sender": s, "body": b} for s, b, _ in iter_sms_file(BASE / "sample_sms.txt")]
            self._json({"fixtures": fixtures})
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        body = self._body()
        if self.path == "/api/feed":
            sender = (body.get("sender") or "PASTED").strip()[:40]
            text = (body.get("body") or "").strip()[:500]
            if not text:
                self._json({"error": "empty body"}, 400)
                return
            self._json(STATE.feed(sender, text))
        elif self.path == "/api/lang":
            try:
                STATE.set_lang(body.get("code", "hi"))
                self._json({"lang": STATE.pack["code"]})
            except FileNotFoundError as e:
                self._json({"error": str(e)}, 400)
        elif self.path == "/api/summary":
            stats = lg.daily_stats(STATE.store.day_records())
            result = end_of_day_summary(stats, STATE.pack, STATE.config)
            STATE.last_announcement = {"text": result["text"], "ts": time.time(), "mode": "pending"}
            STATE.enqueue_speech(result["text"], result["text"] + " " + result["bills_text"])
            self._json(result)
        elif self.path == "/api/undo":
            removed = STATE.store.undo_last_sale()
            if removed:
                line = lp.tpl(STATE.pack, "undo_done",
                              amount=format_rupees(removed.amount_paise))
            else:
                line = lp.tpl(STATE.pack, "undo_none")
            with STATE.lock:
                # The removed sale must stop being shown as "last payment".
                STATE.last_record = None
                STATE.last_announcement = {"text": line, "ts": time.time(), "mode": "pending"}
            STATE.enqueue_speech(line)
            self._json({"removed": format_rupees(removed.amount_paise) if removed else None,
                        "announcement": line})
        else:
            self._json({"error": "not found"}, 404)


def serve(host: str, port: int, lang: str, config: dict, no_speak: bool):
    global STATE
    force_utf8_stdio()
    STATE = DemoState(lang, config, no_speak)
    httpd = ThreadingHTTPServer((host, port), Handler)
    print(f"Phone demo running at http://{host}:{port}  (Ctrl-C to stop)")
    if host == "0.0.0.0":
        print("Open from a phone on the same Wi-Fi: http://<this laptop's IP>:%d" % port)
    httpd.serve_forever()
