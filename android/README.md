# Android port (Kotlin)

This is the Android app for the chai-stall payment announcer: a notification
listener, an optional bank-SMS intake, a Room ledger, Hindi speech and a
deterministic day summary. The Python app remains the reference implementation
and the parity oracle.

**Status: v0.2 code-complete.** The release APKs build and are signed locally.
The JVM/Robolectric suite passes, but **nothing here has been run on a physical
phone**. The notification/SMS field test, Hindi voice check and reboot soak are
still the gate before trusting it at the stall. See
[`../docs/android-roadmap.md`](../docs/android-roadmap.md) and
[`../docs/install.md`](../docs/install.md).

## What works

- Notification intake from GPay, PhonePe, Paytm, Messages and a short bank-app
  allowlist.
- `foss` flavor: incoming bank SMS through `RECEIVE_SMS` (no inbox access).
- Kotlin parser with the same rules as `sms_parser.py`.
- Room ledger with the same counted/duplicate/review/refund rules as `ledger.py`.
- 120-second cross-source dedupe: a bank SMS and the matching payment-app
  notification are counted once; ambiguous matches are flagged, not guessed.
- Hindi TTS using the system voice, latest-wins `QUEUE_FLUSH`, audio focus, and
  an explicit “Hindi voice missing” state instead of an English fallback.
- Deterministic Hindi day summary; optional on-device Gemma is **not** included.
- One-screen setup: notification access, SMS grant (`foss`), TTS check, battery
  settings, today's total, summary, undo and recent records.
- Foreground watchdog with a low-importance ongoing notification, plus
  boot/update restart.
- No `INTERNET` permission, no analytics, no account, backup disabled.

## Modules

| Module | Type | What it holds |
|---|---|---|
| `:core` | Kotlin/JVM | parser, config loader, `PaymentRecord` |
| `:ledger` | Kotlin/JVM | dedupe, cross-source rule, undo, daily stats, `LedgerStore` |
| `:storage` | Android library | Room entities, DAO and `RoomLedgerStore` |
| `:app` | Android app | intake, Hindi voice, summary, watchdog, one-screen UI |

Keeping the rules out of the Android module means the parser/ledger tests run on
a laptop, the Python app stays the reference, and the Activity does not grow a
second copy of the rules.

`:core` and `:ledger` need only a JDK. `:storage` and `:app` need Android SDK 35;
their tests still run on the JVM.

## Run the tests

From `android/`:

```bash
# Windows
gradlew.bat test

# macOS / Linux
./gradlew test
```

The suite is **68 unique tests** across the modules:

- `:core:test` — 32 parser/config tests, including field-by-field corpus parity;
- `:ledger:test` — 14 ledger/dedupe/stats tests;
- `:storage:testDebugUnitTest` — 7 Robolectric storage tests;
- `:app:testPlayDebugUnitTest` and `:app:testFossDebugUnitTest` — 15 app tests
  (intake + Hindi text); the same tests run for both flavors.

For a JDK-only run that skips Android:

```bash
gradlew :core:test :ledger:test
```

The Gradle wrapper downloads Gradle 8.10.2, JUnit 4.13.2, Room 2.7.2 and
kotlinx.serialization on first run. A JDK 17 or newer must be on `JAVA_HOME`
(Android Studio ships one). For Android modules, install SDK 35 and point
`ANDROID_HOME` at it:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

The first Robolectric run downloads an Android runtime jar. `local.properties`
with `sdk.dir=...` works too and is git-ignored.

## Build the APKs

There are two product flavors:

| Flavor | Intake | Distribution |
|---|---|---|
| `play` | Notifications only | Play-safe path |
| `foss` | Notifications + bank SMS (`RECEIVE_SMS`) | Sideload only |

Both flavors keep the same application id, so install one at a time.

```bash
# Debug
gradlew :app:assemblePlayDebug :app:assembleFossDebug

# Release (requires keystore.properties, see below)
gradlew :app:assemblePlayRelease :app:assembleFossRelease
```

Outputs:

```
app/build/outputs/apk/play/debug/app-play-debug.apk
app/build/outputs/apk/play/release/app-play-release.apk
app/build/outputs/apk/foss/debug/app-foss-debug.apk
app/build/outputs/apk/foss/release/app-foss-release.apk
```

Install a debug build:

```bash
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

### Release signing

The release key is intentionally not in this repository. Create
`android/keystore.properties` and a JKS outside git:

```properties
storeFile=keystore/payment-announcer.jks
storePassword=...
keyAlias=payment-announcer
keyPassword=...
```

`android/keystore/`, `keystore.properties` and `dist/` are git-ignored. Back up
the JKS and its passwords somewhere safe: losing them means existing installs
cannot be updated. The current local certificate SHA-256 is recorded in
[`../docs/install.md`](../docs/install.md).

## Use it

Open the app and work through the sections in order:

1. **Notification access** — grant it in system settings, return to the app.
2. **Notifications** — allow the ongoing “Payment Announcer is on” notification
   on Android 13+. It keeps the watchdog visible.
3. **Hindi voice** — tap **Test Hindi voice**. If Hindi is missing, install a
   system Hindi TTS voice and reopen the app.
4. **Battery** — allow the app to run without optimization; on Xiaomi/Vivo/Oppo
   also enable Autostart.
5. **SMS** — only in `foss`: tap **Allow SMS access** and grant `RECEIVE_SMS`.
6. Send a ₹1 test payment. The app should speak once and add one row.

The source of truth for parser rules is the repository-root `config.json`. A
Gradle task copies it into the APK as `assets/config.json`, so there is no second
copy to drift. Unknown keys are ignored, missing sender lists stay empty (fail
closed), and invalid JSON or a negative threshold throws.

### Ledger API

```kotlin
import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.storage.LedgerDatabase
import dev.viveknshah.payannouncer.storage.RoomLedgerStore

val db = LedgerDatabase.open(context) // once per process, off the main thread
val ledger = Ledger(RoomLedgerStore(db.paymentDao()), crossSourceWindowSeconds = 120)
val stats = ledger.dailyStats()
check(stats.totalPaise == 571_600L) // the 16-message corpus
```

`LedgerStore` is the storage seam. Dedupe, cross-source matching, undo and stats
stay in `:ledger`; Room only keeps rows. `PaymentDao.hasCountedKey` uses the same
counted predicate as `ledger.py`, and undo rewrites the table in a transaction.

Cross-source dedupe is represented without a schema migration: the second record
is kept, marked `duplicate=true`, `needsReview=true`, with reason
`cross_source_duplicate`. If more than one candidate matches, nothing is merged
and every candidate gets `cross_source_ambiguous`. The app enables a 120-second
window; the Python parity tests leave it off.

## Notification intake

`NotificationIntakeService` maps a short package allowlist to the parser's sender
tokens:

| Package | Sender |
|---|---|
| `com.google.android.apps.nbu.paisa.user` | `GPAY` |
| `com.phonepe.app` | `PHONEPE` |
| `net.one97.paytm` | `PAYTM` |
| `com.paytm.business` | `PAYTMB` |
| HDFC / ICICI / SBI / Axis / Bank of Baroda apps | `HDFC`, `ICICI`, `SBI`, `AXIS`, `BOB` |
| Google / AOSP / Samsung Messages | notification title (often `BOB`, `HDFC`) |

Everything else is ignored. Group summaries are dropped. Expanded big text is
preferred over collapsed text, and the post time is the fallback timestamp. The
package ids still need confirming on a real phone; an unmapped bank app is missed
rather than guessed.

`SmsReceiver` is in `src/foss`. It concatenates multipart messages, uses the
originating address and timestamp, joins the same `PaymentIntake` path, and calls
`goAsync()` so the ledger write finishes after the broadcast returns. It requests
`RECEIVE_SMS`, not `READ_SMS`.

## Hindi voice and summary

`HindiAnnouncement` speaks only counted sales and refunds. Duplicates and
excluded messages are silent. A payer is spoken only when it looks like a name,
not a VPA. Large payments get a “बड़ा भुगतान” prefix.

`HindiTtsEngine` initializes the system `TextToSpeech`, checks
`Locale.forLanguageTag("hi-IN")`, and reports `HINDI_MISSING` instead of falling
back to English. Speech uses `QUEUE_FLUSH`, so the newest payment wins, and a
short audio-focus request is released when the utterance finishes.

`HindiSummary` is a pure Kotlin template over `DailyStats`; every number comes
from the ledger. The optional 1B model from the roadmap is deliberately omitted:
it would add a large download and cannot be tested on a phone here. The template
is the default and always works.

## Watchdog and boot

`WatchdogService` is a `specialUse` foreground service with a low-importance
ongoing notification. It does not read notifications; it keeps the process
resident on OEM builds that kill background processes. `BootReceiver` starts it
after `BOOT_COMPLETED` or an app update if notification or SMS access is granted.
Android rebinds the notification listener itself.

The service starts only when at least one intake path is granted. If a boot-time
foreground-service start is refused by a newer Android/OEM policy, the exception
is caught and the notification listener still works while the process survives.

## Parity contract

- `PaymentRecord` field names map 1:1 to `sms_parser.PaymentRecord` (snake_case
  in Python, camelCase in Kotlin).
- Status and source strings are identical.
- The id is the same SHA-1 prefix when both sides receive the same `now`.
- `DailyStats` mirrors `daily_stats`: count, total, average, busiest hour,
  largest, refunds, review count and duplicate count.
- The Python suite (`python test_suite.py`) and this suite must be changed in
  the same commit. The corpus totals are the shared contract.

The corpus test reads `../../sample_sms.txt`, the same 16 messages as
`python app.py demo`, and asserts 8 counted sales, **₹5,716**, 4 excluded, 1
refund, 1 review, 2 duplicate keys, and the ₹5,000 large flag. The golden file
`src/test/resources/sample_expected.tsv` is generated by the Python parser:

```bash
python android/tools/export_parser_golden.py
```

## Layout

```
android/
  core/       parser, config loader, record, corpus parity tests
  ledger/     Ledger, cross-source dedupe, stats, in-memory store
  storage/    Room entity/DAO/database, RoomLedgerStore, schema v1
  app/
    src/main/kotlin/dev/viveknshah/payannouncer/
      AppGraph.kt
      MainActivity.kt
      intake/NotificationIntake.kt
      intake/NotificationIntakeService.kt
      intake/PaymentIntake.kt
      voice/HindiAnnouncement.kt
      voice/HindiSummary.kt
      voice/HindiTtsEngine.kt
      watchdog/WatchdogService.kt
      watchdog/BootReceiver.kt
    src/foss/AndroidManifest.xml
    src/foss/kotlin/.../intake/SmsReceiver.kt
    src/test/kotlin/.../intake/NotificationIntakeTest.kt
    src/test/kotlin/.../voice/HindiSpeechTest.kt
  tools/export_parser_golden.py
```

## Limitations

- **No physical-device test yet.** The service, TTS, foreground watchdog, boot
  receiver and SMS receiver have not been exercised on a phone or emulator.
- Bank app package ids and Google/Samsung Messages titles need field
  confirmation.
- The notification path can miss dismissed, silenced or truncated notifications.
- Cross-source matching uses payment timestamps. A bank SMS without a time may
  fall outside the 120-second window and produce two counted rows; the review
  counter will show a flagged record, not a silent correction.
- Two genuine same-amount payments from different sources can be ambiguous; the
  app flags them instead of merging.
- The optional on-device summary model is not built.
- AOSP notification-listener behavior varies by OEM; test the target phone.
