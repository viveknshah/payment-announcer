# Android roadmap: from laptop demo to Amma's phone

The Week 3 submission is the Python demo. This document is the plan for the
thing she can actually keep in her pocket: an Android app that hears the
payment, says it out loud in Hindi, and closes the day without a laptop.

Status today: **v0.2 code-complete, release built, device field test still open**.
The parser, config loader, ledger, Room storage, notification listener, Hindi TTS,
template summary, `foss` SMS flavor, cross-source dedupe and watchdog are in
place, with 68 JVM/Robolectric tests. Signed `play` and `foss` release APKs,
checksums, an install guide and a privacy note exist. Nothing has been run on a
physical phone in this repository, so the two-source field test and OEM soak are
still the gate before trusting it at the stall. The optional on-device Gemma
summary is deliberately not included.

## Target architecture

```
      bank app / Messages notification            SMS (sideload flavor only)
                  │                                        │
        NotificationListenerService                 SmsReceiver
                  └──────────────┬─────────────────────────┘
                                 ▼
                     Intake normaliser (source id, time)
                                 ▼
                  cross-source dedupe + Kotlin Parser (:core)
                                 ▼
                     Room ledger (paise, no raw SMS)
                        │                    │
                        ▼                    ▼
              Hindi TTS announcer     End-of-day summary
              (system voice first)    (template, optional Gemma 1B)
```

One Android app module, a Room storage library, and pure-JVM `core`/`ledger`
libraries. No server, no account, and no `INTERNET` permission.

## Decisions to make now, not later

### 1. How the app reads payments

Two paths, and they are not equivalent:

| Path | What it sees | Google Play | Reliability |
|---|---|---|---|
| `NotificationListenerService` | Notifications posted by GPay, PhonePe, Paytm and the Messages app (bank SMS notifications) | Allowed with an in-app disclosure and the system grant | Good, but a dismissed or silenced notification is missed |
| `RECEIVE_SMS` | Incoming SMS broadcasts (no inbox access) | Restricted to default-SMS or backup apps; a normal app will be rejected | Very good on a sideloaded build |

**Recommendation:** build the notification path first because it works on Play
and covers both bank apps and SMS notifications. Add the SMS path behind a
product flavor (`foss`) distributed as a direct APK. Do not make Play approval
the first milestone.

Notification text is not the full SMS. Some banking apps post a short version
("₹250 received"), so the parser must treat a notification as a hint and keep
`needs_review` when a reference is missing. This is already the parser's
behaviour for no-reference messages.

### 2. Voice

Start with Android's `TextToSpeech` and `Locale("hi", "IN")`:

- check `isLanguageAvailable` before promising anything;
- if Hindi is missing, show a setup screen that opens the TTS engine's install
  page instead of silently speaking English;
- speak through `AudioAttributes` `USAGE_NOTIFICATION_EVENT`, request audio
  focus, and keep the existing latest-wins queue (one narrator, not one thread
  per payment).

Piper via sherpa-onnx is the later upgrade if the built-in voice is unacceptable.
It costs ~60 MB and buys a consistent voice, not correctness.

### 3. Model

Do **not** port Gemma 3 4B. On a ₹8,000 phone it will not fit, and the 40-second
CPU summary was already the slowest part of the laptop demo.

- Template summary is the default and must always work.
- Optional: Gemma 3 1B IT or Gemma 3n E2B through MediaPipe LLM Inference /
  LiteRT-LM, downloaded only with consent, run only when the user taps
  "day summary", released afterwards.
- Port the guards before the model: every amount must match the ledger, the
  exact total must appear in digits, no English wrapper text. Otherwise the
  template is spoken and the screen says why.

A summary is a nice-to-have. The announcement is the product.

## Module plan

```
android/
  core/                     done: parser, config loader, record, 32 tests
  ledger/                   done: dedupe, cross-source rule, daily stats, 14 tests
  storage/                  done: Room entity/DAO + RoomLedgerStore, 7 tests
  app/
    AppGraph.kt                     one Room database + config + speaker
    MainActivity.kt                 grants, total, summary, undo, records
    intake/NotificationIntake.kt    package → sender, 7 tests
    intake/NotificationIntakeService.kt
    intake/PaymentIntake.kt         shared notification/SMS parse path
    intake/SmsReceiver.kt           foss flavor
    voice/HindiAnnouncement.kt      per-payment Hindi text, 8 speech tests
    voice/HindiSummary.kt           deterministic day summary
    voice/HindiTtsEngine.kt         system TTS, latest-wins, audio focus
    watchdog/WatchdogService.kt     foreground “listener is on”
    watchdog/BootReceiver.kt        boot/update restart
```

`core` and `ledger` stay free of Android imports. `storage` owns Room; keep the
duplicate rules in one place. The app has one screen rather than separate
onboarding/home activities: grants, TTS check, battery link, total, summary,
undo and recent records are all in a scroll view.

## Cross-source dedupe (the new bug)

Today's ledger dedupes inside one stream. On a phone the same ₹250 can arrive
twice: once as a GPay notification, once as a bank SMS notification. Counting
both is worse than missing one.

Implemented rule (`Ledger.add` with a 120-second window):

1. If the message has an explicit reference, key on `ref:<id>`.
2. Otherwise key on amount + minute (the existing uncertain path).
3. For cross-source matching, look back 120 seconds for the same amount from a
   *different* source. If exactly one candidate exists and neither has a
   conflicting reference, the second record is kept but marked
   `duplicate=true`, reason `cross_source_duplicate`. It is not counted twice.
4. If more than one candidate matches, do not merge: mark the new record and
   every candidate `needs_review`, reason `cross_source_ambiguous`.
5. The second message is never deleted; the audit trail is the point.

The app sets the window to 120 seconds; the Python-parity tests leave it off so
the corpus stays identical. Two genuine ₹10 teas two minutes apart are still
plausible, which is why conflicting references or multiple candidates never
merge.

Difference from the original design: the Room schema has no `sourceRefs` or
`duplicateOf` column. The duplicate flag plus the reason code carries the
decision, and the second row still has its own source. The UI shows “Duplicates
ignored” and a review warning; a literal “seen in 2 apps” badge would need a
schema v2 and is not in this build.

## Permissions and platform constraints

| Capability | Declaration / grant | Notes |
|---|---|---|
| Notification access | `BIND_NOTIFICATION_LISTENER_SERVICE`, user enables in Settings | In-app disclosure text explains the package allowlist |
| SMS (foss flavor) | `RECEIVE_SMS` runtime grant | `READ_SMS` is deliberately not requested; Play-restricted flavor |
| Foreground service | `FOREGROUND_SERVICE` + `specialUse` type | Visible ongoing “Payment Announcer is on” notification |
| Post notifications | `POST_NOTIFICATIONS` (Android 13+) | Needed for the ongoing watchdog notification |
| Battery optimisation | Guide the user to Settings; no direct request | Xiaomi/Vivo/Oppo also need Autostart enabled |
| Boot | `RECEIVE_BOOT_COMPLETED` + `BootReceiver` | Restarts the foreground watchdog; system rebinds the listener |

No `INTERNET` permission. That is a product promise and it removes a whole class
of privacy questions. The optional model is not in this build; if added later it
must use a separate explicit download screen or run entirely on-device.

## Build order

| Phase | Work | Acceptance |
|---|---|---|
| 0 (done) | Kotlin parser, config loader, record, 32 tests | `gradlew :core:test` green; 8 sales / ₹5,716 on the corpus |
| 1 (done) | `:ledger` + `:storage` Room adapter, same dedupe and daily stats; config loader from assets | 16-message corpus produces the same stats as `ledger.py`; duplicate + undo tests; Room close/reopen test |
| 2 (code done) | Notification listener, Hindi TTS queue, template day summary, single-screen UI | 20 real notifications on a phone: correct announcement in under 2 seconds, no overlapping voices |
| 3 (code done) | `foss` SMS flavor, 120-second cross-source dedupe, boot watchdog, OEM battery guide | 72-hour soak on two phones; totals match the bank SMS at end of day; survives reboot |
| 4 (done) | Onboarding for the two grants, TTS check, battery link, undo, day summary | A non-technical person completes setup with one helper in five minutes |
| 5 (optional, not built) | Gemma 1B behind a flag, guards, download screen | 20 summaries, zero wrong totals; model off still gives a full demo |
| 6 (artifacts done) | Signed release APK, checksum, install guide, privacy note | APK installs on a clean phone and runs with Wi-Fi and mobile data off |

The remaining work is not more code: it is physical-device testing (phase 2-3
acceptance), OEM soak testing, and optionally the on-device model. The template
summary always works without the model.

Part-time estimate: **5-6 weeks**. Full-time: about **3 weeks**. Phase 0 took a
day, storage another, and the intake spike a morning; the device testing in
phases 2-3 is what usually slips.

## Testing

- **JVM:** parser, config loader, ledger, cross-source dedupe, intake mapping and
  Hindi text. The Python suite remains the oracle; change both in one commit.
- **Golden corpus:** `sample_sms.txt` is the shared fixture. Keep the expected
  8 / ₹5,716 assertion in both languages.
- **Room under Robolectric:** field round-trip, dedupe across store instances,
  undo rewrite, and a file database opened again after close. No emulator.
- **App intake (JVM):** package mapping, expanded text, group summaries, bank
  title, duplicate repost and cross-source rules. The Android service itself is
  thin and untested here.
- **Hindi text (JVM):** payer/no-payer, VPA suppression, large payment, refund,
  review, duplicate silence, corpus summary and empty day.
- **Release checks:** both flavors build, `apksigner verify` passes, `aapt2 dump
  permissions` shows no `INTERNET`, and `lintVital` passes.
- **Instrumented / device:** real GPay, PhonePe and Paytm notifications, SMS
  broadcast, TTS voice, Room migration and service restart after reboot. **Not
  run yet**; this is the next gate.
- **Device matrix:** Android 8 Go edition, 11, 13, 14; at least one Xiaomi or
  Vivo plus one Samsung. Cheap phones are the target, not a flagship.
- **Field test:** three days at the stall, with the day's total compared against
  the phone's SMS list every evening. Log every miss with the notification that
  caused it. Do not call it done from emulator tests.

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Play rejects SMS access | No store distribution for the reliable path | Ship notification path on Play, SMS flavor as a direct APK |
| OEM kills the service | Silent missed payments | Foreground service, boot receiver, battery/autostart onboarding, watchdog notification |
| Notification is dismissed or truncated | Missed sale | Review counter visible; SMS flavor covers it; never claim 100% capture |
| Cross-source double count | Wrong total, lost trust | 120-second amount match, duplicate reason, review when ambiguous |
| Two genuine same-amount payments | Wrong merge | Only merge across different sources; flag when more than one candidate |
| Hindi TTS missing | No voice | Detect early, send user to install, keep on-screen text |
| Model download/RAM/heat | Bad experience on cheap phones | Default off, 1B class only, run on demand, template fallback |
| Battery drain | She turns it off | Measure; batch nothing in the background; the listener is event-driven |
| Phishing link counted | Fake sale | Existing link gate + allowlist; keep the review path |
| Data loss / phone change | Lost day book | CSV export, no cloud backup of SMS, documented transfer |

## Distribution and privacy

- Direct APK from GitHub Releases, signed, with a SHA-256 checksum and a
  one-page install guide. See [install.md](install.md) for install steps and
  [privacy.md](privacy.md) for the data note.
- `android:allowBackup="false"` plus data-extraction rules so parsed payment
  data is not swept into a Google backup without consent.
- Store parsed fields, not raw SMS bodies. The Python app already does this;
  keep it that way.
- No analytics, no crash upload, no account. If a crash log is needed, export it
  manually.
- Play Store later only if the notification-only flavor passes review; the
  `foss` SMS flavor stays a direct APK.

## Cost

| Item | Cost |
|---|---|
| Software, libraries, voice | ₹0 |
| Test phone (if no spare) | ₹7,000-10,000 |
| Play Store account (optional) | $25 once |
| Amma's phone | assumed existing; check Android version and RAM first |

## Open questions

1. What phone does she actually have: Android version, RAM, is Hindi TTS
   installed, is the bootloader locked?
2. Is she willing to grant notification access, or does she want the SMS
   flavor sideloaded?
3. Does she want the day summary at all, or only the per-payment voice?
4. Who updates the app when a bank changes its SMS wording?
5. Is a second source (GPay notification) present on her phone, or only bank SMS?

Answer 1 and 5 on the target phone before trusting the field result; they change
which intake path is the primary one.

## Definition of done for the MVP

- Three consecutive stall days where the app's total matches the bank SMS total.
- Every counted payment is announced once, in Hindi, within two seconds.
- No double announcements from two sources.
- Survives a reboot and a full day with the screen off.
- Runs with mobile data and Wi-Fi off, with no `INTERNET` permission.
- She can undo the last sale without help.
- Battery use is acceptable to her, measured over a full day.
- If the optional model is removed, everything except the prose summary still
  works.

Status: every code path above exists, but the evidence is JVM/Robolectric and
signed-build checks, not a stall. The next entry in the log should be a physical
phone, a ₹1 transfer, a reboot and a full day with the screen off.
