# Privacy note

Payment Announcer is built for one stall and one phone. It has no account, no
analytics, and no `INTERNET` permission. Payment parsing and the ledger happen
on the device.

## What the app reads

### Notification build (`play` and `foss`)

Android notification access technically exposes all notifications. The app
passes a notification to the parser only when its package id is in a short
allowlist:

- GPay, PhonePe, Paytm;
- Google, AOSP and Samsung Messages;
- a small set of known bank apps.

Everything else is dropped without parsing. The service reads the title, text,
expanded text and post time; it does not read contacts, photos, files or the
notification history.

### SMS build (`foss` only)

The `foss` flavor declares `RECEIVE_SMS` and receives incoming SMS broadcasts.
It does **not** declare `READ_SMS` and does not read the SMS inbox or sent
messages. The receiver joins multipart messages, passes the sender and body to
the parser, and stores the parsed fields. Messages from senders outside the
allowlist are parsed as review/unknown, never as money.

## What is stored

Room stores one row per parsed message:

- amount in integer paise;
- sender token and payment source;
- payer name when the parser finds one;
- transaction reference when the message has one;
- payment timestamp and ingestion timestamp;
- status (`credited`, `refund`, `excluded`, `review`);
- review/duplicate flags and short reason codes.

Raw SMS bodies and raw notification text are **not** stored. The database is
local to the app. Android backup and device-transfer backup are disabled by the
manifest and data-extraction rules.

## What leaves the phone

Nothing. The app has no `INTERNET` permission. It does not upload crash logs,
analytics or ledger rows. If the optional on-device summary model is added
later, it must run locally or use a separate, explicit download screen; this
build uses a deterministic template only.

The text-to-speech engine is a system service. Android may use its own
on-device or network voice depending on the engine installed; the app only hands
it the Hindi announcement text. No ledger data is sent to the TTS engine beyond
the spoken amount/name.

## Permissions

| Permission | Build | Why |
|---|---|---|
| Notification listener binding | both | Read payment notifications after the user grants access |
| `POST_NOTIFICATIONS` | both | Show the ongoing “listener is on” notification on Android 13+ |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | both | Keep the listener process alive with a visible notification |
| `RECEIVE_BOOT_COMPLETED` | both | Restore the watchdog after reboot |
| `RECEIVE_SMS` | `foss` only | Read incoming bank SMS after the user grants access |

The app does not request `INTERNET`, `READ_SMS`, contacts, location, camera,
microphone, or storage access.

## Delete the data

Uninstall the app to remove its Room database. Android backup is off, so there
is no cloud copy made by this app. If you need the day's records, export them
before uninstalling; export is not part of this build yet.

## Limits

- Notification access is broad at the platform level even though the app uses a
  narrow allowlist. Grant it only on a phone you control.
- A dismissed or truncated notification can be missed.
- The SMS flavor is for sideloading; Play policy restricts SMS permissions.
- Parsed payer names and transaction references can still be personal data. The
  database is not encrypted beyond Android's app sandbox.
