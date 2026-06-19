# Daily Cut Report 0.7

Daily Cut Report is a strictly offline Android fitness and nutrition journal. It combines Health Connect activity data with a local food catalog and daily food log, calculates daily energy balance, and exports a shareable PNG.

## Features

- Material 3 Jetpack Compose UI with Today, Foods, and Settings destinations.
- Shared historical date navigation with a calendar capped at today.
- Foreground-only Health Connect refresh for steps, distance, calories, exercise, and optional nutrition.
- On-device CameraX + bundled ML Kit barcode recognition.
- Room product catalog, nutrient snapshots, editable food logs, and non-destructive schema migration.
- Nullable manual overrides, including an explicit zero value.
- PNG save through MediaStore on Android 10+, the system document picker on Android 9, and secure cache-backed sharing.
- Browser-based tablet preview with matching manual workflows and versioned localStorage.

## Offline contract

The final APK must not request either of these permissions:

```text
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
```

ML Kit dependencies declare them transitively, so the app manifest explicitly removes them during manifest merging. The `verifyOfflineDebugApk` Gradle task inspects the assembled APK and fails if either permission returns. There is no cloud sync, remote telemetry, or background network work.

## Build and verify

Use Java 17 and Android SDK/build tools 35:

```bash
gradle --no-daemon testDebugUnitTest lintDebug verifyOfflineDebugApk
node --test tablet-preview/model.test.js
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and uses the existing stable debug signing identity.

## Data upgrades

Database version 2 migrates existing product and food-log data without deleting history. Legacy `daily_reports` SharedPreferences are imported once into Room and retained as rollback data. Historical food entries are immutable product snapshots, so later product edits cannot alter or delete previous days.

## Main components

- `DailyCutApp.kt`: Compose navigation and workflows.
- `ViewModels.kt`: shared date and screen state.
- `DailyCutRepository.kt`: application data boundary.
- `NutritionDatabase.kt`: Room schema, DAO, and v1→v2 migration.
- `BarcodeScanner.kt`: lifecycle-safe on-device scanner.
- `ReportImageExporter.kt`: dynamic local PNG rendering and storage.
