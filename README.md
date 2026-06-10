# Daily Cut Report — offline Android MVP

First prototype for a local daily fitness report generator.

## What it does

- Reads daily steps, distance, active calories, total calories, and exercise-session count/minutes from Health Connect.
- Lets you manually enter food calories, protein, sodium, notes, and an optional final-burn override.
- Calculates final burn and estimated deficit/surplus.
- Saves daily entries locally using app-private SharedPreferences.
- Exports a PNG into `Pictures/DailyCutReport` and can share that PNG through Android's standard share sheet.

## Offline guarantee

The runtime Android manifest intentionally does **not** request:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

So the installed app cannot use normal Android internet sockets. It only talks to the local Health Connect provider on-device and writes a local image.

## Current limitations

- No FatSecret/FatHealth API integration, because that would require internet access.
- Food data is manual in this MVP.
- Per-source reconciliation is not implemented yet; Health Connect aggregates are used for cumulative data to reduce double counting.
- On Android 9/10 devices, saving directly into shared Pictures may need additional storage handling. On modern Android versions it uses MediaStore.

## Build

1. Open this folder in Android Studio.
2. Let Android Studio sync Gradle dependencies.
3. Run on an Android device with Health Connect available.
4. Connect your source apps to Health Connect first, for example Casio Watches → Health Connect, Google Fit → Health Connect.
5. In the app, grant Health Connect permissions, refresh, enter food values, and export PNG.

## Build command

If you have Gradle and Android SDK configured:

```bash
gradle :app:assembleDebug
```

or add a Gradle wrapper from Android Studio and run:

```bash
./gradlew :app:assembleDebug
```

## Main files

- `MainActivity.kt`: UI and workflow.
- `HealthConnectManager.kt`: Health Connect permission and read logic.
- `LocalStore.kt`: local per-day storage.
- `ReportImageExporter.kt`: local PNG rendering and MediaStore export.
- `PermissionsRationaleActivity.kt`: Health Connect permission rationale screen.
