CHAI STALL PAY ANNOUNCER - laptop demo
======================================
Reads payment SMS, speaks the amount aloud in Hindi, and closes the day
with a spoken summary and a bills list. Built for the DEV Hacktoberfest
Week 3 entry. All sample data is fictional.

The app never counts payment requests, failed payments, or money going
out as sales. Refunds are recorded separately. It never speaks OTPs,
links, or account balances. Large payments get a "verify in your bank
app" warning.


WHAT YOU NEED
-------------
- Python 3.10 or newer. Nothing to install: standard library only.
- Optional, for the Hindi voice: Piper (offline TTS) + one voice file.
- Optional, for the Gemma end-of-day summary: Ollama + a Gemma model.
  The demo uses gemma3:4b (4.3B, about 2.9 GB) because it fits an 8 GB
  laptop; gemma4:latest (7.5B, 6.6 GB) needs more free RAM than a 16 GB
  machine usually has and fails to load with an out-of-memory error.
- Works the same on Windows, macOS and Linux. Tested on Python 3.10.


FILE MAP - one flat folder, rename after download
-------------------------------------------------
Put ALL files in a single new folder, e.g. payannouncer/
Files were sent with .txt added so they download cleanly. Strip the
trailing .txt from each name (sample_sms.txt and README.txt stay as-is):

  app.py.txt          -> app.py           (CLI entry point)
  server.py.txt       -> server.py        (phone-frame web demo)
  index.html.txt      -> index.html       (web demo page)
  sms_parser.py.txt   -> sms_parser.py    (the parser)
  ledger.py.txt       -> ledger.py        (store, dedupe, daily stats)
  announcer.py.txt    -> announcer.py     (Piper / OS voice / text)
  summarizer.py.txt   -> summarizer.py    (Gemma via Ollama + fallback)
  langpack.py.txt     -> langpack.py      (language pack loader)
  test_suite.py.txt   -> test_suite.py    (41 unit tests)
  config.json.txt     -> config.json      (senders, threshold, models)
  lang_hi.json.txt    -> lang_hi.json     (Hindi pack, complete)
  lang_en.json.txt    -> lang_en.json     (English pack, template stub)
  lang_kn.json.txt    -> lang_kn.json     (Kannada pack, template stub)
  bills.json.txt      -> bills.json       (fictional bills - edit me)
  sample_sms.txt                          (fictional SMS corpus)
  README.txt                              (this file)

On Windows, rename in File Explorer (enable "File name extensions" in
the View menu first) or run this in the folder:
  ren *.py.txt *.py & ren *.json.txt *.json & ren index.html.txt index.html


QUICK START (5 minutes, no downloads)
-------------------------------------
  cd payannouncer
  python test_suite.py              -> "Ran 41 tests ... OK"
  python app.py demo --no-speak     -> full pipeline, printed not spoken
  python app.py serve               -> phone demo at http://localhost:8000

(If "python" is not found on Windows, use "py" or "python3".)

The web demo shows a phone-sized frame: SMS arrive like notifications,
each becomes a card (Rs X received), the spoken line shows with a
speaking indicator, the running total stays on top, and "End of day"
produces the summary + bills. Feed decoys from the list to watch the
safeguards reject them. To open it on a real phone on the same Wi-Fi:
  python app.py serve --host 0.0.0.0
then browse to http://<your laptop's IP>:8000


VERIFICATION CHECKLIST
----------------------
1. python test_suite.py
   Expect: Ran 41 tests, OK.
2. python app.py demo --no-speak
   Expect: 8 payments counted, total Rs 5,716; the request / failed /
   debit lines EXCLUDED; the refund recorded separately; the OTP SMS
   excluded; the spoof (sender 9876543210) flagged for review; the
   Rs 5,000 payment counted with a LARGE flag; the two repeats flagged
   DUPLICATE; summary + bills printed. Engine is "template" with Ollama
   off, "gemma" when Ollama is running with the model pulled (add ~40s
   on a laptop CPU for the model's summary).
3. python app.py ingest sample_sms.txt --lang en --no-speak
   Expect: same classifications, English announcements.
   (Delete ledger.jsonl first if you want a clean count.)
4. python app.py serve, open http://localhost:8000
   Expect: phone frame, total updates when you tap a sample SMS,
   decoys show amber/red cards and never raise the total.
5. python app.py watch --no-speak
   Paste: AX-PAYTMB | You have received ₹99 from Asha via UPI. Transaction ID: 111222333444.
   Expect: CREDITED Rs 99. Ctrl-D to exit.
6. python app.py undo
   Expect: "Removed last sale" message; total drops by the last sale.
7. Voice (after Piper setup below): python app.py demo
   Expect: Hindi spoken aloud for each payment.
8. Gemma (after Ollama setup below): python app.py summary
   Expect: "(summary engine: gemma)" and a free-form Hindi summary.


HINDI VOICE - Piper setup (one-time, free, offline after download)
------------------------------------------------------------------
1. Download the Piper Windows release from
   https://github.com/rhasspy/piper/releases (piper_windows_amd64.zip)
   and extract it into a folder named piper next to app.py, so that
   piper\piper.exe and its DLLs sit there. config.json already points
   at piper\piper.exe; change "binary" to "piper" only if you instead
   put piper.exe on your PATH.
2. Create a folder named voices next to app.py and download these
   two files into it:
   https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx
   https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx.json
   (priyamvada is a female voice in the same folder if you prefer;
   update lang_hi.json "model_file" to match.)
3. If Piper or the voice file is missing, the app falls back to the
   Windows system voice (if a Hindi voice is installed), then to
   printed text. The demo never breaks because of voice.
   Windows usually ships only English voices, so without Piper the
   Hindi lines will be read by an English voice (or skipped) - install
   Piper before recording a demo.


GEMMA SUMMARY - Ollama setup (one-time, free, local)
----------------------------------------------------
1. Install Ollama: https://ollama.com/download
2. ollama pull gemma3:4b   (4.3B, about 2.9 GB, one-time)
3. Leave Ollama running; the app talks to it on localhost:11434.
   config.json sets num_gpu to 0 and keep_alive to 30m. On a laptop with
   a small GPU, forcing CPU avoids a partial offload that is no faster,
   and the long keep_alive stops the model unloading between takes.
   Set num_gpu to null to let Ollama decide.
4. Gemma 4 models "think" before answering. The app sends think:false so
   the token budget goes into the spoken summary instead of hidden
   reasoning; it is harmless for Gemma 3.
5. If Ollama is off or the model is missing, the summary falls back
   to deterministic templates - the demo still works. The English and
   Kannada packs always use templates (they are marked stubs).
6. Small models drift: gemma3:4b once wrote "5,717" for a 5,716 total and
   once ignored the facts completely. The prompt now includes a one-line
   Hindi example, and the answer must contain the exact total in digits;
   otherwise the template is used.


WHAT THE GEMMA SUMMARY IS ALLOWED TO SAY
----------------------------------------
- The model writes only the payment summary. English wrapper text
  ("Here's a summary...", pronunciation notes, follow-up questions) is
  stripped before anything is spoken.
- If it speaks a rupee amount that does not match the ledger, the
  deterministic template is used instead. A wrong total is never read out.
- The total must also appear in digits, exactly as computed (Rs 5,716).
  If the model spells it out or misses it, the template is used, because
  a one-rupee slip in words is invisible to the amount check.
- The bills line always comes from bills.json, never from the model.
- The web footer says "Gemma via Ollama" only when the configured model
  is actually pulled; otherwise it says "template fallback".


HOW THE PARSER DECIDES (the honest version)
-------------------------------------------
- Counts a sale only on clear evidence of a successful credit
  ("credited", "received", "deposited") with an amount.
- Excludes: payment requests, failed payments, debits/money out, OTPs.
- Refunds are recorded as refunds, never added to sales.
- Transaction ids come only from explicit labels (UPI Ref No,
  Transaction ID, Ref:, (UPI ...)). Bare numbers are never refs.
- Dedupe: by reference id; without a ref, by amount + minute, and the
  match is flagged uncertain (needs_review).
- Sender allowlist in config.json: unknown senders go to review.
- Links in a payment SMS: straight to review (phishing pattern).
- Balances ("Avl Bal") are parsed around, never stored or spoken.
- Amounts >= Rs 2,000 (configurable) add "verify in your bank app".
- Dates in SMS are read as Indian day-first (DD-MM-YY / DD-MM-YYYY).


KNOWN LIMITATIONS (say these out loud in the post - judges respect it)
----------------------------------------------------------------------
- Keyword matching is English-word based; Hindi/regional SMS wording
  needs new keyword lists per bank. That is real future work.
- Amount+minute dedupe can merge two genuinely different sales of the
  same amount in the same minute; they are flagged for review, not
  silently dropped.
- The parser is template-driven, not ML. New bank formats mean new
  fixtures first, then new patterns.
- Piper has no Kannada voice; Kannada ships as a template stub. The
  APK stage uses Android's built-in voices, which cover Kannada and
  8+ more Indian languages for free.
- Windows system voices may not include Hindi; install Piper for the
  real demo sound.
- The web demo stores the day's ledger in ledger.jsonl next to app.py.
  Delete that file to reset the day.
- All dates and amounts in sample_sms.txt and bills.json are fictional.
  Never paste a real SMS containing account numbers, balances, OTPs or
  a payer's UPI id into the repo, the video, or the post.
