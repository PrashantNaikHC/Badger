<p align="center">
  <img src="assets/badger-logo.png" alt="Badger Logo" width="180" />
</p>

<h1 align="center">Badger</h1>

<p align="center">
  <strong>Offline, privacy-first NFC ID badge kiosk for workplace events, gift distribution, and attendance tracking.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-1A237E.svg" alt="License: MIT" /></a>
  <img src="https://img.shields.io/badge/Android-API%2026%2B-5C6BC0.svg" alt="Android API 26+" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF.svg" alt="Kotlin & Jetpack Compose" />
  <img src="https://img.shields.io/badge/Offline-100%25%20On--Device-2E7D32.svg" alt="100% On-Device" />
</p>

---

## Overview

**Badger** turns any NFC-enabled Android phone or tablet into a watchful, self-service event kiosk. Employees tap their physical workplace ID badge on the back of the device to claim gifts, collect welcome kits, or check in and out of events.

The first time an unknown card is tapped, Badger automatically activates the camera and uses **on-device ML Kit OCR** to read the employee's printed name directly from their badge—linking the card's hardware UID once and reusing it across all future events without ever requiring an internet connection or backend server.

---

## Key Features

- **Two Event Modes**
  - **One-Time Check-In (`ONE_TIME`)**: Ideal for gift distributions, festive hampers, welcome kits, or meal tokens. Prevents duplicate claims and shows the original claim timestamp on repeat taps.
  - **Entry & Exit (`ENTRY_EXIT`)**: Tracks both check-in and check-out timestamps for town halls, workshops, and training sessions.
- **Zero-Type Badge Registration (On-Device OCR)**
  - Uses CameraX and Google ML Kit's bundled Latin text recognition model to extract the cardholder's name offline in real time.
  - Camera frames are processed strictly in memory—no photos are ever saved.
- **Customizable Templates & Prompts**
  - Built-in templates for **Gift Collection**, **Welcome Kit**, **Attendance**, and **Custom** events, with fully editable idle, success, duplicate, and exit messages.
- **Public Kiosk Lock & Admin NFC Cards**
  - Exiting the public scanner screen is protected by **Device Screen Lock** (PIN, pattern, or biometric) or by tapping a designated **Admin NFC Card**.
  - Survives app restarts by automatically resuming the active scanner session.
- **Rich Excel (.xlsx) Export**
  - Exports styled spreadsheets featuring an event summary banner, real-time KPI metric blocks (Total Attendees, Currently Inside, Completed/Exited), duration calculations, status pills, and Excel auto-filtering.
  - Zero third-party dependencies—generates native OpenXML (.xlsx) on-device with zero APK bloat.
  - Raw NFC hardware UIDs are sensitive and intentionally excluded from exports.

---

## Project Structure

Package namespace: `app.badger.kiosk`

| File | Responsibility |
| :--- | :--- |
| [`MainActivity.kt`](app/src/main/java/app/badger/kiosk/MainActivity.kt) | NFC foreground `ReaderCallback`, device credential authentication, Excel (.xlsx) document export, and root navigation host. |
| [`AppViewModel.kt`](app/src/main/java/app/badger/kiosk/AppViewModel.kt) | State management for events, scanner state machine (`Idle`, `Registering`, `Result`), audio/haptic feedback, and admin card enrollment. |
| [`Logic.kt`](app/src/main/java/app/badger/kiosk/Logic.kt) | Pure decision engine (`TapRules`) for entry/exit tap validation and event templates. |
| [`XlsxExporter.kt`](app/src/main/java/app/badger/kiosk/XlsxExporter.kt) | Zero-dependency OpenXML (.xlsx) builder generating styled spreadsheets with KPI cards and attendee tables. |
| [`Data.kt`](app/src/main/java/app/badger/kiosk/Data.kt) | Room database schema (`EmployeeEntity`, `EventEntity`, `AttendanceEntity`, `AdminCardEntity`) and DAO queries. |
| [`NameExtractor.kt`](app/src/main/java/app/badger/kiosk/NameExtractor.kt) | Parses OCR text tokens from the ID badge layout into a normalized full name. |
| [`Screens.kt`](app/src/main/java/app/badger/kiosk/Screens.kt) | Jetpack Compose screens for Active/Past Events, Device Data & Governance, Event Creation, Event Details, and the Public Scanner. |
| [`ScannerParts.kt`](app/src/main/java/app/badger/kiosk/ScannerParts.kt) | CameraX + ML Kit OCR preview analyzer and frame-clock driven NFC tap animation. |
| [`Theme.kt`](app/src/main/java/app/badger/kiosk/Theme.kt) | Material 3 color palette and typography setup. |

---

## Getting Started

### Prerequisites

- **Android Studio** Ladybug (or newer) / JDK 17
- An Android device running **Android 8.0 (API 26)+** with **NFC hardware** and a front or rear camera

### Build & Run

1. Clone the repository and open the project in Android Studio, or build from the command line:
   ```bash
   ./gradlew assembleDebug
   ```
2. Run unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```
3. Install on a connected NFC-enabled Android device:
   ```bash
   ./gradlew installDebug
   ```

### In-App ID Card OCR Template Creator

Every organization formats its employee ID badges differently (different layouts, employee ID prefixes, department labels, etc.). Rather than hardcoding separators or patterns, **Badger includes a built-in visual template creator**:

1. Go to the **Data** tab on the home screen and tap **New ID Card Template**.
2. Hold an employee ID card in front of the device's **back camera** and tap **Freeze / Capture ID Card**.
3. Badger runs on-device OCR on the snapshot and renders all recognized text lines as selectable interactive chips.
4. Select the **Start (Top)** and/or **End (Bottom)** anchors:
   - **Start Anchor**: The static header or label printed right before the employee's name (e.g. `EMPLOYEE` or `NAME`).
   - **End Anchor**: The static code or label printed right after the employee's name (e.g. `ID`, `BADGE NO`, or department code).
   - *Note*: If the name starts directly at the top or extends to the bottom, you only need to select one anchor.
5. Watch the **Live Extraction Preview** card update in real time as you tap anchors. Once satisfied, name your template and tap **Save Template**.
6. When creating any event, select your saved template from the **Badge Template** dropdown. During live kiosk check-in, the scanner automatically uses the chosen template's anchors to extract the employee's name.

---

## App Icon & Branding

The project logo is available at:
- [`assets/badger-logo.png`](assets/badger-logo.png)

To set it as the launcher icon in Android Studio:
1. Right-click `app/src/main/res` → **New** → **Image Asset**.
2. Choose **Launcher Icons (Adaptive and Legacy)**.
3. Select [`assets/badger-logo.png`](assets/badger-logo.png) as the Source Asset path and click **Next** → **Finish**.

---

## License

**Badger** is open-source software licensed under the **[MIT License](LICENSE)**.  
You are free to use, copy, modify, merge, publish, distribute, sublicense, and use it commercially without restriction.
