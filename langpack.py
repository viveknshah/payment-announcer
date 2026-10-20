"""Language-by-config packs. One JSON file per language.

A pack carries every string the app can say or show, the TTS engine/voice
for that language, and a template_fallback flag. When template_fallback is
true, the app uses only deterministic templates for that language (no LLM),
which is how stub languages ship safely.
"""
from __future__ import annotations

import json
from pathlib import Path

PACK_DIR = Path(__file__).resolve().parent


def load_pack(code: str) -> dict:
    path = PACK_DIR / f"lang_{code}.json"
    if not path.exists():
        raise FileNotFoundError(
            f"No language pack '{code}' ({path.name}). "
            f"Available: {', '.join(available_languages())}"
        )
    pack = json.loads(path.read_text(encoding="utf-8"))
    pack.setdefault("templates", {})
    return pack


def available_languages() -> list[str]:
    return sorted(p.stem.removeprefix("lang_") for p in PACK_DIR.glob("lang_*.json"))


def tpl(pack: dict, key: str, **kwargs) -> str:
    """Render a pack template. Falls back to English, then to the key itself."""
    text = pack["templates"].get(key)
    if text is None and pack.get("code") != "en":
        try:
            text = load_pack("en")["templates"].get(key)
        except FileNotFoundError:
            text = None
    if text is None:
        return key
    try:
        return text.format(**kwargs)
    except (KeyError, IndexError):
        return text
