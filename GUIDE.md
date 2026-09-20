# Masjid Azan Clock — Native Android App — Build Guide

This turns your existing PWA into a real installable Android app (.apk),
using Capacitor (100% free, open source).

---

## Path A — From your phone only (no computer needed)

**Step 1.** Install **Termux** from the Play Store (free terminal app).

**Step 2.** Open Termux and run, one line at a time:
```
termux-setup-storage
pkg update -y && pkg install git unzip -y
```

**Step 3.** Extract this project (assuming you downloaded the zip to your
phone's Downloads folder):
```
cd ~/storage/downloads
unzip AzanClockApp-Android.zip
cd AzanClockApp
```

**Step 4.** Create a free GitHub account at github.com (skip if you have one),
then create a new **empty** repository (no README) named e.g.
`masjid-azan-clock-app`.

**Step 5.** Create a Personal Access Token (acts as your password for
uploading): GitHub → profile picture → Settings → Developer settings →
Personal access tokens → Tokens (classic) → Generate new token (classic) →
check "repo" → Generate → copy it somewhere safe.

**Step 6.** Push the code, back in Termux:
```
git init
git add .
git commit -m "first upload"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/masjid-azan-clock-app.git
git push -u origin main
```
Username = your GitHub username. Password = the token from Step 5.

**Step 7.** On github.com, open your repo → **Actions** tab → you'll see
"Build Android APK" running automatically (triggered by your push) → wait
for the green checkmark (a few minutes).

**Step 8.** Tap the finished run → scroll to **Artifacts** → download
`masjid-azan-clock-debug-apk` → it downloads as a zip → open with your file
manager → extract → tap the `.apk` inside → Install (allow "install unknown
apps" if your phone asks).

Done — installed entirely from your phone.

---

## Path B — From a computer, with Android Studio

Use this if you want to see logs live, debug interactively, or eventually
publish to the Play Store.

### What you need (all free)
1. **Node.js** (v18+) — https://nodejs.org
2. **Android Studio** — https://developer.android.com/studio (bundles the
   Android SDK and a JDK)
3. A phone (USB) or Android Studio's built-in emulator

### Steps

1. Unzip this project into a folder, e.g. `AzanClockApp`.

2. Open a terminal in that folder and run:
   ```bash
   npm install
   npx cap sync android
   ```
   **This step is required, not optional.** `android/capacitor.settings.gradle`
   and `android/app/capacitor.build.gradle` in this delivery are starting
   versions; `npx cap sync android` regenerates them to exactly match what
   `npm install` downloaded. Skipping this is the #1 cause of "Gradle sync
   failed" errors.

3. Open Android Studio → "Open" → select the `AzanClockApp/android` folder
   (the `android` subfolder specifically, not the root). Let Gradle Sync
   finish (first time takes several minutes — it's downloading build tools).
   If Android Studio offers to regenerate the Gradle wrapper jar, accept —
   that's normal and expected.

4. Enable Developer Options + USB Debugging on your phone (Settings → About
   Phone → tap "Build Number" 7 times → Developer Options → enable USB
   Debugging), connect via USB, allow the prompt.

5. In Android Studio, pick your phone from the device dropdown → click ▶ Run.

6. Inside the app, open Settings and tap, in order:
   - **🔔 ENABLE NOTIFICATIONS**
   - **⏰ EXACT ALARM SETTINGS** (only shows if your Android version needs it)
   - **🔋 ALLOW BACKGROUND**

   These three are what make prayer times fire exactly on time, fully
   offline, with the azan playing automatically.

7. To get a shareable `.apk` file: **Build → Build Bundle(s)/APK(s) → Build
   APK(s)**. When done, click "locate" in the notification to find
   `app-debug.apk`. Copy it to any Android phone and tap to install.

---

## Testing checklist

- Temporarily adjust a prayer's manual offset so one becomes "due" in a
  couple of minutes → confirm the 5-min-before notification appears, then
  at the exact time the azan plays **by itself**, no tap needed.
- Turn on Airplane Mode and repeat — should behave identically offline.
- Force-close the app from Recent Apps, wait for a prayer time to pass —
  it should still fire (AlarmManager runs independently of the app process).
- Restart the phone and confirm alarms still fire afterward (tests the
  boot receiver).

---

## Why this setup meets every requirement you asked for

| Requirement | How it's satisfied |
|---|---|
| Free tools only | Capacitor, Node.js, Android Studio, GitHub, GitHub Actions — all free |
| Exact alarms, no drift, Doze-proof | `AlarmManager.setExactAndAllowWhileIdle` in `AzanAlarmPlugin.java` |
| Works offline | All scheduling is on-device; no network call needed to fire an alarm |
| 5-min-before notifications | `AlarmReceiver.java` → `showNotify5()` |
| Azan plays automatically, no tap | `AzanPlaybackService.java` — a foreground service started directly by the alarm |
| Accurate Qibla | `QiblaCompassPlugin.java` — fused rotation-vector sensor + true-north correction |
| Battery/heat friendly | No polling loop — the OS wakes the app only at 10 exact moments/day (5 prayers × 2 alerts), the same mechanism every alarm-clock app uses |
| Same timing, your native place | `www/index.html` is your unchanged prayer-time table — still fixed to Eraviputhoorkadai (8.2933°N, 77.2586°E), not Chennai |

---

## Troubleshooting

- **"Gradle sync failed: project ':capacitor-android' not found"** → you
  skipped the `npm install && npx cap sync android` step. Run it.
- **Notification permission prompt never appears** → only shows on Android
  13+; older versions work without prompting.
- **Exact alarm settings button doesn't disappear** → make sure you actually
  toggled the switch on the screen Android opened, then reopen the app.
- **Azan doesn't play, even in the foreground** → check that
  `res/raw/azan_fajr.mp3` and `res/raw/azan_normal.mp3` are present and
  roughly 350-380 KB each, not 0 bytes.
- **GitHub Actions build fails** → open the failed run's log, the last
  red-highlighted line usually names the missing/misconfigured piece —
  paste it back to me and I'll fix it.
