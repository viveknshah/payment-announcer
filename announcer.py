"""Spoken announcements.

Preference order:
1. Piper (offline neural TTS) with the pack's voice, e.g. hi_IN-pratham-medium.
2. OS text-to-speech (Windows SAPI / macOS say / Linux espeak or spd-say).
3. Text only: the line is printed and shown in the UI, nothing is spoken.

Safety: text is sanitised before speech - URLs, OTPs and long digit runs
(account numbers, references) are stripped even if a bug upstream let one
through.
"""
from __future__ import annotations

import platform
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

_URL_RE = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
_LONG_DIGITS_RE = re.compile(r"\b\d{6,}\b")
_OTP_RE = re.compile(r"\b(OTP|one[- ]time password)\b", re.IGNORECASE)


def sanitize_for_speech(text: str) -> str:
    text = _URL_RE.sub(" ", text)
    if _OTP_RE.search(text):
        return ""  # never speak anything that mentions an OTP
    text = _LONG_DIGITS_RE.sub(" ", text)
    return re.sub(r"\s+", " ", text).strip()


def _play_wav(path: str) -> bool:
    system = platform.system()
    try:
        if system == "Windows":
            ps = shutil.which("powershell") or shutil.which("pwsh")
            if not ps:
                return False
            subprocess.run(
                [ps, "-NoProfile", "-Command",
                 f"(New-Object Media.SoundPlayer '{path}').PlaySync()"],
                check=True, capture_output=True, timeout=60)
            return True
        if system == "Darwin":
            subprocess.run(["afplay", path], check=True, capture_output=True, timeout=60)
            return True
        for player in ("aplay", "paplay", "ffplay"):
            if shutil.which(player):
                args = [player, path] if player != "ffplay" else [player, "-nodisp", "-autoexit", path]
                subprocess.run(args, check=True, capture_output=True, timeout=60)
                return True
    except (subprocess.SubprocessError, OSError):
        return False
    return False


def _speak_piper(text: str, pack: dict, config: dict) -> bool:
    piper_cfg = config.get("piper", {})
    binary = shutil.which(piper_cfg.get("binary", "piper"))
    if not binary:
        return False
    model = Path(piper_cfg.get("voices_dir", "voices")) / pack.get("tts", {}).get("model_file", "")
    if not model.exists():
        return False
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            out = tmp.name
        proc = subprocess.run(
            [binary, "--model", str(model), "--output_file", out],
            input=text.encode("utf-8"), capture_output=True, timeout=120)
        if proc.returncode != 0:
            return False
        return _play_wav(out)
    except (subprocess.SubprocessError, OSError):
        return False


def _speak_os_tts(text: str) -> bool:
    system = platform.system()
    try:
        if system == "Windows":
            ps = shutil.which("powershell") or shutil.which("pwsh")
            if not ps:
                return False
            safe = text.replace("'", "''")
            subprocess.run(
                [ps, "-NoProfile", "-Command",
                 "Add-Type -AssemblyName System.Speech; "
                 f"(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('{safe}')"],
                check=True, capture_output=True, timeout=60)
            return True
        if system == "Darwin" and shutil.which("say"):
            subprocess.run(["say", text], check=True, capture_output=True, timeout=60)
            return True
        for tts in ("spd-say", "espeak"):
            if shutil.which(tts):
                subprocess.run([tts, text], check=True, capture_output=True, timeout=60)
                return True
    except (subprocess.SubprocessError, OSError):
        return False
    return False


def speak(text: str, pack: dict, config: dict, no_speak: bool = False) -> str:
    """Speak text if possible. Returns the mode used: piper | ostts | text | muted."""
    clean = sanitize_for_speech(text)
    if not clean:
        return "muted"
    if no_speak:
        print(f"[SPEAK:{pack.get('code', '?')}] {clean}")
        return "text"
    if _speak_piper(clean, pack, config):
        return "piper"
    if _speak_os_tts(clean):
        return "ostts"
    print(f"[SPEAK:{pack.get('code', '?')}] {clean}")
    return "text"


def piper_available(pack: dict, config: dict) -> bool:
    piper_cfg = config.get("piper", {})
    if not shutil.which(piper_cfg.get("binary", "piper")):
        return False
    model = Path(piper_cfg.get("voices_dir", "voices")) / pack.get("tts", {}).get("model_file", "")
    return model.exists()
