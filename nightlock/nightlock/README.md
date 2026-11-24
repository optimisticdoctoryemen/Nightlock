# NightLock — Strict Bedtime Lock for Android Tablets

**NightLock** helps you sleep early by locking all apps (and Android Settings) on your Android tablet until your scheduled morning wake time — with **no undo button**, while still allowing your **morning alarms** to ring and be dismissed.

## Features
- **No Undo Once Locked**: Full-screen dim OLED sleep clock with live countdown to morning — no cancel, snooze, or settings access until wake time.
- **Automatic Nightly Schedule + Manual "Lock Now"**: Locks automatically every night on chosen days, plus a one-tap **Lock Now Until Morning** trigger.
- **Anti-Procrastination Freeze**: Freezes schedule edits 30 minutes before bedtime so you can't push bedtime back at the last minute.
- **Morning Alarms Always Allowed**: Automatically discovers your tablet's Clock/Alarm app (Google Clock, Samsung Clock, Xiaomi, Lenovo, AOSP, etc.) so morning alarms ring and can be dismissed normally.
- **Tamper Resistance**:
  - **Accessibility Sleep Guard**: Blocks all apps, Home/Recents, split-screen/pop-up windows, Quick Settings shade, and `Settings` / Package Installer.
  - **Instant Overlay Shield**: Covers blocked screens with zero flicker.
  - **Uninstall Shield (Device Admin)**: Disables uninstalling NightLock while Settings is blocked.
  - **Monotonic Timer + Direct-Boot Survival**: Changing the system clock cannot bypass the timer, and rebooting during sleep hours re-engages the lock immediately.
  - **Safe 60-Second Dry-Run Test**: Test full lockdown safely for 60 seconds before relying on it overnight, plus a 16-hour hard safety cap.

---

## How to Build the APK

### Option A: Build Locally in Android Studio (Easiest on Mac)
1. Open the `nightlock` folder in **Android Studio**.
2. Wait for Gradle sync to finish.
3. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Copy `app/build/outputs/apk/debug/app-debug.apk` to your Android tablet (USB, Google Drive, or Nearby Share) and install it.

### Option B: Build in the Cloud via GitHub Actions (No Local Android Studio Needed)
1. Push this folder to a private GitHub repository.
2. Open the **Actions** tab → **Build NightLock APK** → **Run workflow**.
3. Download the ready-to-install `NightLock-debug-apk` artifact directly onto your tablet.

---

## First-Time Setup on Your Android Tablet

Open **NightLock** during the daytime and grant the permissions shown in the app's checklist:

1. **Draw Over Other Apps** → Enable for NightLock.
2. **Accessibility Sleep Guard**:
   - Tap **Enable** → find **Installed apps / Downloaded apps** → **NightLock Sleep Guard** → turn ON.
   - **Android 13 / 14 / 15 note**: Because this is a sideloaded APK, Android initially blocks Accessibility with *"Restricted setting"*. To unlock it:
     1. Tap **Open NightLock App Info** inside NightLock (or go to **Settings → Apps → NightLock**).
     2. Tap the **⋮ (3 dots)** in the top-right corner → tap **Allow restricted settings** (confirm with PIN/fingerprint).
     3. Return to **Settings → Accessibility → Installed apps → NightLock Sleep Guard** and turn it ON.
3. **Uninstall Shield (Device Admin)** → Tap **Activate**.
4. **Exact Alarms & Unrestricted Battery** (recommended, especially on Samsung/Xiaomi/Lenovo tablets) → Allow both so bedtime lock fires on time and stays alive overnight.
5. Tap **Run Safe 60-Second Dry-Run Test** to verify Home, Recents, Settings, and your Alarm Clock behave cleanly before using it overnight.

---

## How to Uninstall Later (Daytime Only)
1. Outside of sleep hours, open **Settings → Security → Device admin apps** (search "Device admin" in Settings).
2. Deactivate **NightLock Uninstall Shield**.
3. Uninstall **NightLock** normally.
