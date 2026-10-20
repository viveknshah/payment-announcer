# Install Payment Announcer

Payment Announcer is a sideloaded Android app for one chai stall. It reads
payment alerts, speaks the counted sale in Hindi, and keeps the day's ledger on
the phone.

There are two builds:

| Build | Reads | Use it when |
|---|---|---|
| `app-play-release.apk` | Notifications from GPay, PhonePe, Paytm, Messages and a few bank apps | You want the Play-safe path |
| `app-foss-release.apk` | The same notifications **plus incoming bank SMS** | You are sideloading and can grant SMS access |

Both builds have the same application id, so install one or the other. The
`foss` build is not for Play Store distribution.

## 1. Install

1. Copy the APK to the phone.
2. Open it. Android will ask you to allow installs from that app (Files,
   Chrome, etc.). Allow it, then tap **Install**.
3. Open **Payment Announcer**.

Minimum: Android 8.0 (API 26). The release APK is signed; keep the signing key
if you want to publish updates later.

## 2. Grant notification access

1. Tap **Open notification access settings**.
2. Find **Payment Announcer** and turn it on.
3. Return to the app. The status should say **Notification access: ON**.

Android shows a warning because notification access is powerful. The app only
maps a small package allowlist to sender tokens; everything else is ignored.
See [privacy.md](privacy.md).

## 3. Allow the ongoing notification

On Android 13+, allow notifications when asked. The app uses a low-importance
ongoing notification to keep the listener process alive. The notification says
**Payment Announcer is on**.

If you denied it, open Android Settings → Apps → Payment Announcer →
Notifications and allow them.

## 4. Check Hindi TTS

1. Tap **Test Hindi voice**.
2. If the app says the Hindi voice is missing, tap **Open text-to-speech
   settings**, install a Hindi voice, then reopen the app.
3. The app never silently falls back to English. If Hindi is missing it shows a
   setup message and keeps the numbers on screen.

The system TTS voice is used first. A bundled Piper/sherpa voice is a later
option, not part of this build.

## 5. Battery and OEM settings

Tap **Battery optimization settings** and allow Payment Announcer to run without
being optimized.

On Xiaomi/MIUI, Vivo, Oppo/Realme and some Samsung builds, also enable
**Autostart** or **Allow background activity** for Payment Announcer. The exact
menu name varies. The app starts a foreground watchdog after boot, but OEM task
killers can still stop it.

## 6. Test with ₹1

Ask someone to send you ₹1 through UPI (or use the SMS build with a bank alert).
The app should:

- speak a Hindi line;
- add one record to **Latest records**;
- increase **Today** by ₹1.

If it does not, check notification access first, then battery settings. The
app does not need Wi-Fi or mobile data for its ledger; the payment app needs
its own connection, of course.

## 7. SMS build (optional, sideload only)

1. Install `app-foss-release.apk` instead of the play build.
2. Tap **Allow SMS access** and grant it.
3. Incoming bank SMS now goes through the same parser and ledger.

The app requests `RECEIVE_SMS`, not `READ_SMS`; it does not read the SMS inbox.
Google Play restricts SMS permissions to default-SMS and backup apps, so do not
upload this flavor to Play. See [privacy.md](privacy.md).

## Verify the download

The signed local artifacts are in `dist/` (git-ignored):

```
dist/app-play-release.apk
dist/app-foss-release.apk
dist/SHA256SUMS.txt
```

They are also produced under
`android/app/build/outputs/apk/<flavor>/release/` when built locally.

PowerShell:

```powershell
Get-FileHash .\app-foss-release.apk -Algorithm SHA256
```

Linux/macOS:

```bash
sha256sum app-foss-release.apk
```

Compare the value with `SHA256SUMS.txt` from the same source.

Signing certificate SHA-256:

```
A1:82:1C:3A:B7:4A:E0:16:46:2A:A8:76:C0:41:7D:E9:
B2:20:5C:F2:2A:47:A1:3C:AE:A1:87:C0:15:5D:BE:1A
```

## Update and uninstall

- Updates must be signed with the same key.
- Uninstalling removes the Room database and the day's ledger. Export or back
  up the data first if it matters.
- Android backup is disabled for this app; parsed payment data is not uploaded
  to Google.

## Troubleshooting

| Symptom | Check |
|---|---|
| No announcements | Notification access, Hindi TTS, media volume, battery optimization |
| Announcement but no ledger row | Cross-source dedupe may have marked it duplicate; check **Duplicates ignored** |
| Two announcements for one payment | The two sources arrived more than 120 seconds apart or had conflicting references; check **Needs review** |
| SMS build gets nothing | `RECEIVE_SMS` grant, default messaging app behavior, bank sender allowlist in `config.json` |
| Everything stops after a reboot | Open the app once, check notification access, enable OEM autostart |
| Hindi voice missing | Install a Hindi TTS voice, then reopen the app |

This build has **not** been field-tested on a physical phone in this repository.
The JVM tests and release build pass, but a real-phone pass is still the gate
before trusting it at the stall.
