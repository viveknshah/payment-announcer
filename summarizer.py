"""End-of-day summary: spoken Hindi summary + bills/action list.

Uses a local Gemma model through Ollama (REST on localhost) when the pack
allows it and Ollama is reachable. Otherwise falls back to deterministic
templates - the demo always works, with or without the model.
"""
from __future__ import annotations

import json
import re
import time
import urllib.request
from datetime import datetime, date

from langpack import tpl
from sms_parser import format_rupees

# The web UI asks for state every 1.5s; without this we would hit /api/tags
# on every poll. A short TTL keeps the footer honest without the chatter.
_AVAIL_CACHE: dict[tuple[str, str], tuple[float, bool]] = {}
_AVAIL_TTL = 5.0


def ollama_available(config: dict) -> bool:
    """True only when Ollama is reachable AND the configured model is pulled.

    Checking the tag list keeps the UI honest ("Gemma via Ollama" vs template
    fallback) and skips a doomed generate call when the model is missing.
    """
    ollama = config.get("ollama", {})
    url = ollama.get("tags_url", "http://localhost:11434/api/tags")
    model = ollama.get("model", "gemma3:4b")
    key = (url, model)
    now = time.monotonic()
    cached = _AVAIL_CACHE.get(key)
    if cached and now - cached[0] < _AVAIL_TTL:
        return cached[1]
    available = _probe_ollama(url, model)
    _AVAIL_CACHE[key] = (now, available)
    return available


def _probe_ollama(url: str, model: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=4) as resp:
            if resp.status != 200:
                return False
            data = json.loads(resp.read().decode("utf-8"))
    except Exception:
        return False
    models = data.get("models")
    if not isinstance(models, list):
        return True  # unknown server shape: reachability is all we can prove
    names = set()
    for entry in models:
        if isinstance(entry, dict):
            for key in ("name", "model"):
                if entry.get(key):
                    names.add(entry[key])
    if model in names:
        return True
    if ":" not in model and f"{model}:latest" in names:
        return True
    return False


def build_prompt(stats: dict, pack: dict) -> str:
    total = format_rupees(stats["total_paise"])
    avg = format_rupees(stats["average_paise"])
    busiest = f"{stats['busiest_hour']:02d}:00" if stats["busiest_hour"] is not None else "n/a"
    locale = pack.get("llm_locale", "Hindi")
    return (
        f"Example of the style (spoken {locale}, 2 sentences): "
        f"\"आज 3 भुगतान मिले, कुल ₹240। सबसे व्यस्त समय 17:00 था।\"\n"
        f"Now write the same kind of summary for a tea stall closing its day, "
        f"using these facts. Same style, write the total in digits, start "
        f"directly with the summary, no English, no questions.\n"
        f"Facts: {stats['count']} payments, total Rs {total}, average Rs {avg}, "
        f"busiest hour {busiest}, {stats['refund_count']} refunds, "
        f"{stats['review_count']} messages still to check."
    )


def _ollama_generate(prompt: str, config: dict) -> str | None:
    ollama = config.get("ollama", {})
    url = ollama.get("url", "http://localhost:11434/api/generate")
    options = {"temperature": 0.2, "num_predict": 70}
    if ollama.get("num_gpu") is not None:
        # Reusing the exact option set keeps Ollama from reloading the model
        # (a reload costs 1-3 minutes on this laptop).
        options["num_gpu"] = ollama["num_gpu"]
    payload = json.dumps({
        "model": ollama.get("model", "gemma3:4b"),
        "prompt": prompt,
        "stream": False,
        # Gemma 4 thinks before answering. Without this the token budget goes
        # into the hidden thinking stream and "response" comes back empty.
        "think": False,
        "keep_alive": ollama.get("keep_alive", "30m"),
        "options": options,
    }).encode("utf-8")
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=int(ollama.get("timeout_seconds", 60))) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            text = (data.get("response") or "").strip()
            return text or None
    except Exception:
        return None


_LATIN_RE = re.compile(r"[A-Za-z]")
_AMOUNT_RE = re.compile(
    r"(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)|([\d,]+(?:\.\d+)?)\s*(?:रुपये|रुपया|रु)"
)
_META_HINTS = (
    "here's", "here is", "translation", "pronunciation", "note:", "notes",
    "would you like", "let me know", "in hindi", "in devanagari", "adjust",
    "sure,", "certainly", "as an ai",
)


def clean_summary(text: str, pack: dict) -> str:
    """Keep only the spoken sentences, drop English wrapper text.

    Small local models like to answer with "Here's a summary...", a
    pronunciation guide, and a follow-up question. None of that may be
    spoken to a stall owner, so for non-English packs any line containing
    Latin letters is dropped, along with known meta phrases.
    """
    kept = []
    for raw in text.splitlines():
        line = raw.strip().lstrip("*#>-").strip()
        if not line:
            continue
        lowered = line.lower()
        if any(hint in lowered for hint in _META_HINTS):
            continue
        if pack.get("code") != "en" and _LATIN_RE.search(line):
            continue
        kept.append(line)
    text = re.sub(r"\s+", " ", " ".join(kept)).strip()
    # Hindi filler openers ("ज़रूर, यहाँ सारांश है:") survive the line filter
    # because they are Devanagari. Strip them before anyone hears them.
    text = re.sub(r"^(?:ज़रूर|ज़रूर|हाँ|हां|बिल्कुल)\s*[,!।:\-–—]\s*", "", text)
    text = re.sub(r"^(?:यहाँ|यहां)\s+[^:।]{0,40}?(?:सारांश|summary)\s*(?:है)?\s*[:：]\s*", "", text,
                  flags=re.IGNORECASE)
    return text


def total_mentioned(text: str, stats: dict) -> bool:
    """The total must be spoken in digits so it can be verified.

    A small model will happily turn 5,716 into words ("paanch hazaar saat sau
    satrah") and get the last digit wrong, where no digit check can catch it.
    Requiring the digits means a wrong or missing total falls back to the
    deterministic template instead of announcing the wrong takings.
    """
    digits = str(int(round(stats["total_paise"] / 100)))
    return digits in re.sub(r"[\s,]", "", text)


def amounts_ok(text: str, stats: dict) -> bool:
    """Reject a summary that speaks a rupee amount nobody earned.

    The model is told not to invent numbers; this checks it for amounts only
    (counts and clock times are left alone). On a mismatch the deterministic
    template is used instead, so the demo never states a false total.
    """
    allowed = {stats["total_paise"] / 100, stats["average_paise"] / 100}
    for match in _AMOUNT_RE.finditer(text):
        raw = (match.group(1) or match.group(2) or "").replace(",", "")
        try:
            value = float(raw)
        except ValueError:
            continue
        if not any(abs(value - a) < 0.01 for a in allowed):
            return False
    return True


def _template_summary(stats: dict, pack: dict) -> str:
    if stats["count"] == 0:
        return tpl(pack, "summary_empty")
    busiest = (f"{stats['busiest_hour']:02d}:00" if stats["busiest_hour"] is not None
               else tpl(pack, "not_available"))
    return tpl(
        pack, "summary_line",
        count=stats["count"],
        total=format_rupees(stats["total_paise"]),
        average=format_rupees(stats["average_paise"]),
        busiest=busiest,
    )


def load_bills(config: dict) -> list[dict]:
    from pathlib import Path
    path = Path(config.get("bills_file", "bills.json"))
    if not path.exists():
        return []
    try:
        return json.loads(path.read_text(encoding="utf-8")).get("bills", [])
    except (json.JSONDecodeError, OSError):
        return []


def bills_due_soon(bills: list[dict], within_days: int = 7) -> list[dict]:
    today = date.today()
    due = []
    for b in bills:
        try:
            d = datetime.strptime(b["due"], "%Y-%m-%d").date()
        except (KeyError, ValueError):
            continue
        if 0 <= (d - today).days <= within_days:
            due.append(b)
    return sorted(due, key=lambda b: b["due"])


def end_of_day_summary(stats: dict, pack: dict, config: dict) -> dict:
    """Returns {text, bills_text, engine}. Engine: 'gemma' or 'template'."""
    bills = bills_due_soon(load_bills(config))
    if bills:
        bills_text = tpl(pack, "bills_header") + " " + "; ".join(
            tpl(pack, "bills_line", name=b["name"],
                amount=format_rupees(int(b.get("amount_paise", 0))) if b.get("amount_paise") else "-",
                due=b["due"])
            for b in bills)
    else:
        bills_text = tpl(pack, "bills_none")

    text = None
    engine = "template"
    if (stats["count"] > 0
            and not pack.get("template_fallback", True)
            and ollama_available(config)):
        raw = _ollama_generate(build_prompt(stats, pack), config)
        cleaned = clean_summary(raw, pack) if raw else ""
        if cleaned and amounts_ok(cleaned, stats) and total_mentioned(cleaned, stats):
            text = cleaned
            engine = "gemma"
    if not text:
        text = _template_summary(stats, pack)
    return {"text": text, "bills_text": bills_text, "engine": engine}
