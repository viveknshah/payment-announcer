# Android roadmap: from laptop demo to Amma's phone

The Week 3 submission is the Python demo. This document is the plan for the
thing she can actually keep in her pocket: an Android app that hears the
payment, says it out loud in Hindi, and closes the day without a laptop.

Status today: **step 0 is done**. The parser is ported to Kotlin and pinned by
25 JVM tests that read the same 16-message corpus as the Python demo
(8 counted, ₹5,716). Everything below is still to build.

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

One Kotlin app module, one pure-JVM core module. No server, no account, and no
`INTERNET` permission.

## Decisions to make now, not later

### 1. How the app reads payments

Two paths, and they are not equivalent:

| Path | What it sees | Google Play | Reliability |
|---|---|---|---|
| `NotificationListenerService` | Notifications posted by GPay, PhonePe, Paytm and the Messages app (bank SMS notifications) | Allowed with an in-app disclosure and the system grant | Good, but a dismissed or silenced notification is missed |
| `READ_SMS` / `RECEIVE_SMS` | The SMS database and incoming SMS broadcasts | Restricted to default-SMS or backup apps; a normal app will be rejected | Very good on a sideloaded build |

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
  core/                     done: parser, config, record, 25 tests
  ledger/                   Room entities, DAO, dedupe, daily stats
  app/
    intake/NotificationIntakeService.kt
    intake/SmsReceiver.kt            (foss flavor)
    data/RoomLedger.kt
    voice/HindiAnnouncer.kt
    summary/SummaryEngine.kt         template + optional model
    ui/onboarding/                   notification access, battery, TTS check
    ui/home/                         total, last payment, day summary
```

`core` stays free of Android imports. `ledger` may be JVM for tests and Android
for Room; keep the duplicate rules in one place.

## Cross-source dedupe (the new bug)

Today's ledger dedupes inside one stream. On a phone the same ₹250 can arrive
twice: once as a GPay notification, once as a bank SMS notification. Counting
both is worse than missing one.

Proposed rule:

1. If the message has an explicit reference, key on `ref:<id>`.
2. Otherwise key on amount + minute (the existing uncertain path).
3. For cross-source matching, look back 120 seconds for the same amount from a
   *different* source. If exactly one candidate exists and neither has a
   conflicting reference, attach the second source to the first record instead
   of creating a new sale.
4. If more than one candidate matches, do not merge: flag both for review.
   Two genuine ₹10 teas two minutes apart are plausible at a stall.
5. Store `sourceRefs` and a `duplicateOf` pointer. Never delete the second
   message; the audit trail is the point.

Show "seen in 2 apps" in the UI so a wrong merge is visible, not silent.

## Permissions and platform constraints

| Capability | Declaration / grant | Notes |
|---|---|---|
| Notification access | `BIND_NOTIFICATION_LISTENER_SERVICE`, user enables in Settings | Play requires an in-app disclosure of what is read and why |
| SMS (foss flavor) | `READ_SMS`, `RECEIVE_SMS` runtime grant | Play-restricted; do not ship this flavor to Play |
| Foreground service | `FOREGROUND_SERVICE` + a declared type | Android 14+ requires a type; `specialUse` needs a Play justification |
| Post notifications | `POST_NOTIFICATIONS` (Android 13+) | Needed for the ongoing "announcer is on" notification |
| Battery optimisation | Guide the user to Settings; avoid the restricted direct request on Play | Xiaomi/Vivo/Oppo also need Autostart enabled |
| Boot | `RECEIVE_BOOT_COMPLETED` | Restart the foreground service after reboot |

No `INTERNET` permission. That is a product promise and it removes a whole class
of privacy questions. If the optional model needs a download, use a separate
explicit downloader screen; do not add `INTERNET` to the main process without a
review.

## Build order

| Phase | Work | Acceptance |
|---|---|---|
| 0 (done) | Kotlin parser, config, record, 25 tests | `gradlew :core:test` green; 8 sales / ₹5,716 on the corpus |
| 1 | `:ledger` with Room (or in-memory first), same dedupe and daily stats; config loader from assets | 16-message corpus produces the same stats as `ledger.py`; duplicate + undo tests |
| 2 | Notification listener, foreground service, Hindi TTS queue, home screen | 20 real notifications on a phone: correct announcement in under 2 seconds, no overlapping voices |
| 3 | `foss` SMS flavor, cross-source dedupe, boot restart, OEM battery setup | 72-hour soak on two phones; totals match the bank SMS at end of day; survives reboot |
| 4 | Onboarding for the two grants, TTS check, undo, day summary (template) | A non-technical person completes setup with one helper in five minutes |
| 5 | Optional Gemma 1B behind a flag, guards, download screen | 20 summaries, zero wrong totals; model off still gives a full demo |
| 6 | Signed release APK, checksum, install guide, privacy note | APK installs on a clean phone and runs with Wi-Fi and mobile data off |

Part-time estimate: **5-6 weeks**. Full-time: about **3 weeks**. Phase 0 took a
day; the device testing in phases 2-3 is what usually slips.

## Testing

- **JVM:** parser, ledger, summary guards. The Python suite remains the oracle;
  change both in one commit.
- **Golden corpus:** `sample_sms.txt` is the shared fixture. Keep the expected
  8 / ₹5,716 assertion in both languages.
- **Instrumented:** Room migrations, notification parsing from saved
  `StatusBarNotification` fixtures, service restart after reboot.
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
| Cross-source double count | Wrong total, lost trust | 120-second amount match, source refs, visible merge, review when ambiguous |
| Two genuine same-amount payments | Wrong merge | Only merge across different sources; flag when more than one candidate |
| Hindi TTS missing | No voice | Detect early, send user to install, keep on-screen text |
| Model download/RAM/heat | Bad experience on cheap phones | Default off, 1B class only, run on demand, template fallback |
| Battery drain | She turns it off | Measure; batch nothing in the background; the listener is event-driven |
| Phishing link counted | Fake sale | Existing link gate + allowlist; keep the review path |
| Data loss / phone change | Lost day book | CSV export, no cloud backup of SMS, documented transfer |

## Distribution and privacy

- Direct APK from GitHub Releases, signed, with a SHA-256 checksum and a
  one-page install guide. This is the realistic path for one stall.
- `android:allowBackup="false"` so parsed payment data is not swept into a
  Google backup without consent.
- Store parsed fields, not raw SMS bodies. The Python app already does this;
  keep it that way.
- No analytics, no crash upload, no account. If a crash log is needed, export it
  manually.
- Play Store later only if the notification-only flavor passes review; the
  $25 fee is optional.

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

Answer 1 and 5 before writing the listener; they change the design.

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
