"""SMS parser for the tea-stall payment announcer.

Parser rules (from the project plan):
- Money is stored in paise (integer). Never floats.
- Every message becomes a normalised record.
- A payment counts as a sale ONLY on clear evidence of a successful credit.
  Payment requests, failed payments and outgoing transfers are excluded.
  Refunds are recorded separately, never counted as sales.
- A transaction id is captured only from an explicit reference label
  ("UPI Ref No", "Transaction ID", "Ref:", "(UPI ...)"). A bare number in
  the message is never assumed to be a reference.
- Messages with no reference deduplicate on amount + minute and the match
  is flagged as uncertain (needs_review).
- Balances, OTPs and links are never spoken, shown in announcements, or
  stored in summaries.
- Large payments add a "verify in your bank app" warning.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, asdict
from datetime import datetime
from decimal import Decimal, InvalidOperation

STATUS_CREDITED = "credited"
STATUS_REFUND = "refund"
STATUS_EXCLUDED = "excluded"
STATUS_REVIEW = "review"

AMOUNT_RE = re.compile(r"(?:₹|Rs\.?|INR)\s*([0-9][0-9,]*(?:\.\d{1,2})?)", re.IGNORECASE)
BALANCE_RE = re.compile(
    r"(?:Avl\.?\s*Bal(?:ance)?|available\s+balance)\s*[:\-]?\s*(?:₹|Rs\.?|INR)\s*[0-9][0-9,]*(?:\.\d{1,2})?",
    re.IGNORECASE,
)
OTP_RE = re.compile(r"\b(OTP|O\.T\.P|one[- ]time password)\b", re.IGNORECASE)
LINK_RE = re.compile(r"(https?://\S+|www\.\S+)", re.IGNORECASE)

# Transaction ids are captured ONLY from explicit labels, in this order.
REF_PATTERNS = [
    re.compile(r"Transaction\s*ID\s*[:\-]?\s*([A-Za-z0-9]{6,})", re.IGNORECASE),
    re.compile(r"UPI\s+Ref(?:erence)?\s*No\.?\s*[:\-]?\s*([A-Za-z0-9]{6,})", re.IGNORECASE),
    re.compile(r"UPI\s+transaction\s+reference\s*[:\-]?\s*([A-Za-z0-9]{6,})", re.IGNORECASE),
    re.compile(r"\bRef(?:erence)?\s*(?:No)?\.?\s*[:\-]\s*([A-Za-z0-9]{6,})", re.IGNORECASE),
    re.compile(r"\(UPI\s+([0-9]{9,18})\)", re.IGNORECASE),
    re.compile(r"\bUPI\s+([0-9]{9,18})\b"),
]

PAYER_PATTERNS = [
    re.compile(r"\bfrom\s+VPA\s+([A-Za-z0-9._\-]+@[A-Za-z]+)", re.IGNORECASE),
    re.compile(r"\bfrom\s+([A-Za-z][A-Za-z .]{1,30}?)\s+via\s+UPI\b", re.IGNORECASE),
]

CREDIT_WORDS = ("credited", "received", "deposited")
REQUEST_WORDS = ("requested", "request of", "payment request", "requesting")
FAILED_WORDS = ("failed", "failure", "declined", "unsuccessful", "rejected")
OUTGOING_WORDS = ("debited", "sent to", "paid to", "withdrawn")
REFUND_WORDS = ("refund", "reversed", "reversal", "chargeback")

# Indian SMS dates are day-first. DD-MM-YY / DD-MM-YYYY, optional HH:MM.
DATE_PATTERNS = [
    re.compile(r"\b(\d{2})-(\d{2})-(\d{4})\s+(\d{2}):(\d{2})\b"),
    re.compile(r"\b(\d{2})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2})\b"),
    re.compile(r"\b(\d{2})-(\d{2})-(\d{4})\b"),
    re.compile(r"\b(\d{2})-(\d{2})-(\d{2})\b"),
]


@dataclass
class PaymentRecord:
    id: str
    sender: str
    amount_paise: int
    payer: str | None
    txn_id: str | None
    timestamp: str            # ISO datetime of the payment (from SMS if present)
    status: str               # credited | refund | excluded | review
    source: str               # bank_sms | payment_app | paytm_business | unknown
    needs_review: bool
    large: bool
    duplicate: bool
    dedupe_key: str
    dedupe_uncertain: bool
    reason: str               # why excluded / flagged; "" for clean credits
    ingested_at: str

    def to_dict(self):
        return asdict(self)

    @staticmethod
    def from_dict(d):
        return PaymentRecord(**d)


def amount_to_paise(text: str) -> int | None:
    """'1,234.50' -> 123450. Decimal-based; returns None if not a number."""
    cleaned = text.replace(",", "").strip()
    try:
        return int((Decimal(cleaned) * 100).to_integral_value())
    except InvalidOperation:
        return None


def format_rupees(paise: int) -> str:
    """245000 -> '2,450' (Indian digit grouping)."""
    rupees = paise // 100
    s = str(rupees)
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts) + "," + tail
    return s


def _find_amount_paise(body: str) -> int | None:
    """First amount in the message, ignoring any available-balance clause."""
    without_balance = BALANCE_RE.sub(" ", body)
    m = AMOUNT_RE.search(without_balance)
    if not m:
        return None
    return amount_to_paise(m.group(1))


def _find_txn_id(body: str) -> str | None:
    for pat in REF_PATTERNS:
        m = pat.search(body)
        if m:
            return m.group(1)
    return None


def _find_payer(body: str) -> str | None:
    for pat in PAYER_PATTERNS:
        m = pat.search(body)
        if m:
            return m.group(1).strip()
    return None


def _find_timestamp(body: str, default: datetime) -> datetime:
    for pat in DATE_PATTERNS:
        m = pat.search(body)
        if not m:
            continue
        g = m.groups()
        day, month = int(g[0]), int(g[1])
        year = int(g[2])
        if year < 100:
            year += 2000
        hour = int(g[3]) if len(g) > 3 else 12
        minute = int(g[4]) if len(g) > 4 else 0
        try:
            return datetime(year, month, day, hour, minute)
        except ValueError:
            continue
    return default


def classify_source(sender: str, config: dict) -> str:
    s = (sender or "").upper()
    for token in config.get("paytm_business_senders", []):
        if token.upper() in s:
            return "paytm_business"
    for token in config.get("bank_senders", []):
        if token.upper() in s:
            return "bank_sms"
    for token in config.get("payment_app_senders", []):
        if token.upper() in s:
            return "payment_app"
    return "unknown"


def sender_allowed(sender: str, config: dict) -> bool:
    allow = config.get("sender_allowlist", ["*"])
    if "*" in allow:
        return True
    s = (sender or "").upper()
    return any(token.upper() in s for token in allow)


def parse_sms(sender: str, body: str, config: dict, now: datetime | None = None) -> PaymentRecord:
    """Parse one SMS into a normalised PaymentRecord. Never raises."""
    now = now or datetime.now()
    reasons = []
    status = STATUS_CREDITED
    needs_review = False
    amount_paise = None
    payer = None
    txn_id = None

    body = (body or "").strip()
    sender = (sender or "").strip()
    lower = body.lower()

    # --- hard safety gates first ---
    if OTP_RE.search(body):
        status, reasons = STATUS_EXCLUDED, ["otp_message"]
    elif not sender_allowed(sender, config):
        status, reasons = STATUS_REVIEW, ["sender_not_in_allowlist"]
    else:
        if LINK_RE.search(body):
            # Links in payment SMS are a classic phishing sign. Never auto-count.
            status = STATUS_REVIEW
            reasons.append("contains_link")

        amount_paise = _find_amount_paise(body)
        if amount_paise is None:
            status = STATUS_REVIEW
            reasons.append("no_amount_found")
        else:
            if any(w in lower for w in REQUEST_WORDS):
                status = STATUS_EXCLUDED
                reasons.append("payment_request")
            elif any(w in lower for w in FAILED_WORDS):
                status = STATUS_EXCLUDED
                reasons.append("failed_payment")
            elif any(w in lower for w in OUTGOING_WORDS):
                status = STATUS_EXCLUDED
                reasons.append("outgoing_money")
            elif any(w in lower for w in REFUND_WORDS):
                status = STATUS_REFUND
                reasons.append("refund_recorded_separately")
            elif not any(w in lower for w in CREDIT_WORDS):
                # No clear evidence of a successful credit: do not count it.
                status = STATUS_REVIEW
                reasons.append("no_credit_evidence")

        if status == STATUS_CREDITED:
            txn_id = _find_txn_id(body)
            payer = _find_payer(body)

    if classify_source(sender, config) == "unknown" and status in (STATUS_CREDITED, STATUS_REFUND):
        needs_review = True
        reasons.append("unknown_sender")

    ts = _find_timestamp(body, now)
    large = bool(amount_paise is not None and
                 amount_paise >= int(config.get("large_amount_threshold_paise", 200000)))

    if txn_id:
        dedupe_key, dedupe_uncertain = f"ref:{txn_id}", False
    else:
        dedupe_key = f"amt:{amount_paise}@{ts.strftime('%Y-%m-%d %H:%M')}"
        dedupe_uncertain = True

    rid = hashlib.sha1(f"{sender}|{body}|{now.isoformat()}".encode("utf-8")).hexdigest()[:8]
    return PaymentRecord(
        id=rid,
        sender=sender,
        amount_paise=amount_paise or 0,
        payer=payer,
        txn_id=txn_id,
        timestamp=ts.isoformat(timespec="seconds"),
        status=status,
        source=classify_source(sender, config),
        needs_review=needs_review or status == STATUS_REVIEW,
        large=large,
        duplicate=False,
        dedupe_key=dedupe_key,
        dedupe_uncertain=dedupe_uncertain,
        reason=";".join(reasons),
        ingested_at=now.isoformat(timespec="seconds"),
    )
