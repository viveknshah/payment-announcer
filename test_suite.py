#!/usr/bin/env python3
"""Unit tests: standard library unittest, no installs needed.
Run:  python test_suite.py   (or: python -m unittest test_suite -v)
"""
import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

import ledger as lg
import langpack as lp
from sms_parser import (STATUS_CREDITED, STATUS_EXCLUDED, STATUS_REFUND,
                        STATUS_REVIEW, amount_to_paise, format_rupees,
                        parse_sms)
from summarizer import (_template_summary, amounts_ok, bills_due_soon,
                        clean_summary, total_mentioned)
from announcer import sanitize_for_speech

CONFIG = json.loads(Path("config.json").read_text(encoding="utf-8"))
NOW = datetime(2026, 10, 20, 13, 0)

BOB_1 = "Credit Alert! Rs.34.00 credited to Bank of Baroda A/c XX0000 on 20-10-26 from VPA ravi.k@upi (UPI 100000000001)"
BOB_2 = "Rs. 37.00 credited to A/c XX0000 on 20-10-2026 17:13. Avl Bal: Rs.10,000.00"


class TestPaise(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(amount_to_paise("150"), 15000)

    def test_decimals(self):
        self.assertEqual(amount_to_paise("34.00"), 3400)
        self.assertEqual(amount_to_paise("45.05"), 4505)

    def test_indian_commas(self):
        self.assertEqual(amount_to_paise("12,450"), 1245000)
        self.assertEqual(amount_to_paise("1,23,456.50"), 12345650)

    def test_format_rupees(self):
        self.assertEqual(format_rupees(245000), "2,450")
        self.assertEqual(format_rupees(123456700), "12,34,567")
        self.assertEqual(format_rupees(3400), "34")


class TestRealBankFormats(unittest.TestCase):
    def test_bob_credit_alert(self):
        r = parse_sms("VK-BOBSMS", BOB_1, CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_CREDITED)
        self.assertEqual(r.amount_paise, 3400)
        self.assertEqual(r.payer, "ravi.k@upi")
        self.assertEqual(r.txn_id, "100000000001")
        self.assertEqual(r.source, "bank_sms")
        self.assertEqual(r.timestamp[:10], "2026-10-20")
        self.assertFalse(r.needs_review)

    def test_bob_plain_credit(self):
        r = parse_sms("VK-BOBSMS", BOB_2, CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_CREDITED)
        self.assertEqual(r.amount_paise, 3700)
        self.assertIsNone(r.payer)
        self.assertIsNone(r.txn_id)                 # no explicit ref label
        self.assertTrue(r.dedupe_uncertain)          # dedupe by amount+time
        self.assertEqual(r.timestamp, "2026-10-20T17:13:00")

    def test_balance_never_in_record(self):
        r = parse_sms("VK-BOBSMS", BOB_2, CONFIG, now=NOW)
        blob = json.dumps(r.to_dict())
        self.assertNotIn("10,000", blob)
        self.assertNotIn("1000000", blob)            # balance in paise


class TestIllustrativeFormats(unittest.TestCase):
    """The five fictional formats from the design notes - fixtures only."""

    def test_example_a(self):
        r = parse_sms("AX-PAYTMB", "You have received ₹150 from Rahul via UPI. Transaction ID: 123456789012.", CONFIG, now=NOW)
        self.assertEqual((r.status, r.amount_paise, r.payer, r.txn_id),
                         (STATUS_CREDITED, 15000, "Rahul", "123456789012"))
        self.assertEqual(r.source, "paytm_business")

    def test_example_b(self):
        r = parse_sms("VK-HDFCBK", "Payment of Rs. 80 received successfully. UPI Ref No: 987654321012.", CONFIG, now=NOW)
        self.assertEqual((r.status, r.amount_paise, r.txn_id),
                         (STATUS_CREDITED, 8000, "987654321012"))

    def test_example_c_no_payer(self):
        r = parse_sms("AX-ICICIB", "₹250 credited to your account through UPI. Ref: 456789123012.", CONFIG, now=NOW)
        self.assertEqual((r.status, r.amount_paise, r.payer, r.txn_id),
                         (STATUS_CREDITED, 25000, None, "456789123012"))

    def test_example_d_no_txn(self):
        r = parse_sms("AX-PAYTMB", "You received a payment of ₹45.00. Status: Successful.", CONFIG, now=NOW)
        self.assertEqual((r.status, r.amount_paise, r.txn_id), (STATUS_CREDITED, 4500, None))

    def test_example_e_bank(self):
        r = parse_sms("VK-SBISMS", "Your account has been credited with INR 120.00. UPI transaction reference: 223456789012.", CONFIG, now=NOW)
        self.assertEqual((r.status, r.amount_paise, r.txn_id),
                         (STATUS_CREDITED, 12000, "223456789012"))


class TestDecoys(unittest.TestCase):
    def test_payment_request_excluded(self):
        r = parse_sms("AX-PAYTMB", "You have requested ₹500 from Ramesh via UPI. Awaiting approval.", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_EXCLUDED)
        self.assertIn("payment_request", r.reason)

    def test_failed_excluded(self):
        r = parse_sms("AX-PAYTMB", "Payment of Rs. 90 failed. Please retry.", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_EXCLUDED)
        self.assertIn("failed_payment", r.reason)

    def test_outgoing_excluded(self):
        r = parse_sms("VK-BOBSMS", "Rs. 60.00 debited from A/c XX0000 on 20-10-2026. Avl Bal: Rs.9,940.00", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_EXCLUDED)
        self.assertIn("outgoing_money", r.reason)
        self.assertEqual(r.amount_paise, 6000)  # the balance, not 994000

    def test_otp_excluded(self):
        r = parse_sms("VK-BOBSMS", "Your OTP for transaction of Rs. 200 is 123456. Do not share it with anyone.", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_EXCLUDED)
        self.assertIn("otp_message", r.reason)

    def test_link_goes_to_review(self):
        # Even from an allowlisted sender, a link means manual review.
        r = parse_sms("VK-BOBSMS", "Rs. 500 credited to your wallet. Claim now at http://paytm-cashback.example.com", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_REVIEW)
        self.assertIn("contains_link", r.reason)

    def test_unknown_sender_stopped_by_allowlist(self):
        r = parse_sms("9876543210", "Hi! Rs. 500 credited to your wallet. Claim now at http://paytm-cashback.example.com", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_REVIEW)
        self.assertIn("sender_not_in_allowlist", r.reason)

    def test_unknown_sender_flagged_when_allowlist_open(self):
        cfg = dict(CONFIG, sender_allowlist=["*"])
        r = parse_sms("9876543210", "Rs. 500 credited to your account.", cfg, now=NOW)
        self.assertEqual(r.status, STATUS_CREDITED)
        self.assertTrue(r.needs_review)
        self.assertIn("unknown_sender", r.reason)

    def test_refund_recorded_separately(self):
        r = parse_sms("AX-PAYTMB", "Refund of Rs. 45.00 credited to your account. UPI Ref No: 555666777888.", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_REFUND)
        self.assertEqual(r.amount_paise, 4500)

    def test_no_credit_evidence_review(self):
        r = parse_sms("VK-BOBSMS", "Your UPI mandate of Rs. 300 is due tomorrow.", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_REVIEW)
        self.assertIn("no_credit_evidence", r.reason)

    def test_bare_number_not_a_ref(self):
        r = parse_sms("VK-BOBSMS", "Rs. 75 credited to A/c XX1234 on 20-10-2026.", CONFIG, now=NOW)
        self.assertIsNone(r.txn_id)


class TestLargeAndThreshold(unittest.TestCase):
    def test_large_flag(self):
        r = parse_sms("AX-PAYTMB", "You have received ₹5,000 from Suresh via UPI. Transaction ID: 999888777666.", CONFIG, now=NOW)
        self.assertEqual(r.status, STATUS_CREDITED)
        self.assertTrue(r.large)

    def test_below_threshold(self):
        r = parse_sms("AX-PAYTMB", "You have received ₹150 from Rahul via UPI. Transaction ID: 123456789012.", CONFIG, now=NOW)
        self.assertFalse(r.large)


class TestLedgerDedupe(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.NamedTemporaryFile(suffix=".jsonl", delete=False)
        self.store = lg.Ledger(self.tmp.name)

    def test_dedupe_by_ref(self):
        a = self.store.add(parse_sms("VK-BOBSMS", BOB_1, CONFIG, now=NOW))
        b = self.store.add(parse_sms("VK-BOBSMS", BOB_1, CONFIG, now=NOW))
        self.assertFalse(a.duplicate)
        self.assertTrue(b.duplicate)
        self.assertTrue(b.needs_review)

    def test_uncertain_dedupe_amount_time(self):
        a = self.store.add(parse_sms("VK-BOBSMS", BOB_2, CONFIG, now=NOW))
        b = self.store.add(parse_sms("VK-BOBSMS", BOB_2.replace("10,000.00", "10,037.00"), CONFIG, now=NOW))
        self.assertFalse(a.duplicate)
        self.assertTrue(b.duplicate)
        self.assertTrue(b.dedupe_uncertain)

    def test_stats_exclude_decoys_and_duplicates(self):
        self.store.add(parse_sms("VK-BOBSMS", BOB_1, CONFIG, now=NOW))
        self.store.add(parse_sms("VK-BOBSMS", BOB_1, CONFIG, now=NOW))          # dup
        self.store.add(parse_sms("AX-PAYTMB", "Payment of Rs. 90 failed. Please retry.", CONFIG, now=NOW))
        self.store.add(parse_sms("AX-PAYTMB", "Refund of Rs. 45.00 credited to your account. UPI Ref No: 555666777888.", CONFIG, now=NOW))
        stats = lg.daily_stats(self.store.day_records("2026-10-20"))
        self.assertEqual(stats["count"], 1)
        self.assertEqual(stats["total_paise"], 3400)
        self.assertEqual(stats["refund_count"], 1)
        self.assertEqual(stats["duplicate_count"], 1)

    def test_undo_last_sale(self):
        self.store.add(parse_sms("VK-BOBSMS", BOB_1, CONFIG, now=NOW))
        removed = self.store.undo_last_sale()
        self.assertIsNotNone(removed)
        self.assertEqual(removed.amount_paise, 3400)
        self.assertEqual(lg.daily_stats(self.store.day_records("2026-10-20"))["count"], 0)


class TestSummaryAndSpeechSafety(unittest.TestCase):
    def test_template_summary_numbers(self):
        stats = {"count": 3, "total_paise": 245000, "average_paise": 81666,
                 "busiest_hour": 13, "refund_count": 0, "review_count": 0}
        text = _template_summary(stats, lp.load_pack("en"))
        self.assertIn("3 payments", text)
        self.assertIn("2,450", text)
        self.assertIn("13:00", text)

    def test_template_summary_empty(self):
        stats = {"count": 0, "total_paise": 0, "average_paise": 0,
                 "busiest_hour": None, "refund_count": 0, "review_count": 0}
        self.assertTrue(_template_summary(stats, lp.load_pack("en")))

    def test_bills_due_soon_window(self):
        bills = [{"name": "Milk", "due": "2026-10-22"},
                 {"name": "Rent", "due": "2027-05-01"}]
        due = bills_due_soon(bills)
        names = [b["name"] for b in due]
        self.assertIn("Milk", names)
        self.assertNotIn("Rent", names)

    def test_speech_sanitizer(self):
        self.assertEqual(sanitize_for_speech("OTP is 123456"), "")
        self.assertNotIn("http", sanitize_for_speech("paid visit http://x.example.com now"))
        self.assertNotIn("123456789012", sanitize_for_speech("ref 123456789012 done"))
        self.assertEqual(sanitize_for_speech("₹150 received from Rahul"), "₹150 received from Rahul")

    def test_clean_summary_drops_meta(self):
        model_out = (
            "Okay, here's a warm end-of-day summary in Hindi:\n\n"
            "आज कुल ₹150 का पेमेंट मिला।\n\n"
            "**Next Bills:** Milk supplier\n"
            "Would you like me to adjust the tone?"
        )
        cleaned = clean_summary(model_out, lp.load_pack("hi"))
        self.assertIn("आज कुल", cleaned)
        self.assertNotIn("Would you like", cleaned)
        self.assertNotIn("Next Bills", cleaned)
        self.assertNotIn("Okay", cleaned)

    def test_clean_summary_empty_when_only_meta(self):
        self.assertEqual(clean_summary("Here is the summary. Let me know!", lp.load_pack("hi")), "")

    def test_clean_summary_strips_hindi_opener(self):
        cleaned = clean_summary("ज़रूर, यहाँ एक गर्म सारांश है: आज ₹500 मिले।", lp.load_pack("hi"))
        self.assertEqual(cleaned, "आज ₹500 मिले।")

    def test_amounts_guard_accepts_real_numbers(self):
        stats = {"count": 2, "total_paise": 23000, "average_paise": 11500,
                 "busiest_hour": 13, "refund_count": 0, "review_count": 0}
        self.assertTrue(amounts_ok("आज ₹230 मिले, औसत ₹115 रहा।", stats))

    def test_amounts_guard_rejects_invented_total(self):
        stats = {"count": 2, "total_paise": 23000, "average_paise": 11500,
                 "busiest_hour": 13, "refund_count": 0, "review_count": 0}
        self.assertFalse(amounts_ok("आज ₹1,500 मिले।", stats))

    def test_total_guard_accepts_digits(self):
        stats = {"count": 2, "total_paise": 23000, "average_paise": 11500,
                 "busiest_hour": 13, "refund_count": 0, "review_count": 0}
        self.assertTrue(total_mentioned("कुल ₹230 मिले", stats))

    def test_total_guard_rejects_words_and_wrong_digits(self):
        stats = {"count": 2, "total_paise": 23000, "average_paise": 11500,
                 "busiest_hour": 13, "refund_count": 0, "review_count": 0}
        self.assertFalse(total_mentioned("कुल दो सौ तीस रुपये", stats))
        self.assertFalse(total_mentioned("कुल ₹231 मिले", stats))


class TestPacks(unittest.TestCase):
    def test_packs_load(self):
        for code in ("hi", "en", "kn"):
            pack = lp.load_pack(code)
            self.assertIn("announce_payer", pack["templates"])
        self.assertFalse(lp.load_pack("hi")["template_fallback"])
        self.assertTrue(lp.load_pack("kn")["template_fallback"])
        self.assertTrue(lp.load_pack("en")["template_fallback"])

    def test_template_render_hi(self):
        text = lp.tpl(lp.load_pack("hi"), "announce_payer", amount="150", payer="Rahul")
        self.assertIn("150", text)
        self.assertIn("Rahul", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
