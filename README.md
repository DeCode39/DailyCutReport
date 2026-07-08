# Daily Cut Report 0.8.5

Daily Cut Report is a strictly offline Android fitness and nutrition journal. It combines Health Connect activity data with a local food catalog and daily food log, calculates daily energy balance, and exports a shareable PNG.

## Features

- Material 3 Jetpack Compose UI with Today, Foods, and Settings destinations.
- Shared historical date navigation with a calendar capped at today.
- Health Connect refresh for today on app open, plus manual selected-date refresh from Settings.
- Scan access from Today and Foods, with the daily food log shown on Today.
- Fixed daily nutrition targets: 1850 kcal, 120 g protein, 2000 mg sodium, 150 g carbs, 60 g fat, 50 g sugar, 15 g fiber, and 15 g saturated fat.
- Silent Health Connect nutrition sync after local food-log add/edit/delete, controlled by a separate optional write permission and surfaced in Settings.
- In-app macro threshold snackbars when a food-log change crosses a daily target.
- Dark yellow-on-black Material 3 color scheme.
- Quick Scan home-screen widget showing the current local deficit/surplus above the scanner button.
- On-device CameraX + bundled ML Kit barcode recognition.
- Guided crop/rotation and on-device English, Chinese, and Japanese nutrition-label OCR from up to three camera or gallery images, with local quality warnings, image variants, source-row review, and explicit conflict selection.
- Room product catalog, nutrient snapshots, editable food logs, and non-destructive schema migration.
- Optional product barcodes and 17 additive preloaded foods that never overwrite user edits.
- Password-encrypted full backups compatible with the tablet preview.
- Legacy manual overrides are cleared once for the cleaner 0.8.5 workflow; report values now come from Health Connect and local food logs.
- PNG save through MediaStore on Android 10+, the system document picker on Android 9, and secure cache-backed sharing.
- Browser-based tablet preview with matching manual workflows and versioned localStorage.

## Offline contract

The final APK must not request either of these permissions:

```text
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
```

ML Kit dependencies declare them transitively, so the app manifest explicitly removes them during manifest merging. The `verifyOfflineDebugApk` Gradle task inspects the assembled APK and fails if either permission returns. There is no cloud sync, remote telemetry, background network work, or Android cloud backup. OCR language models are bundled in the APK.

PP-OCRv6 Tiny was prototyped for an optional enhanced retry but did not pass the required Base&U accuracy gate. Its models, native runtime, arm64 restriction, and retry controls are therefore excluded. OCR remains an assistive workflow with manual correction.

Future quality-of-life candidates include a fuller Today summary widget, configurable nutrient targets, food-log copy from previous day, one-tap usual foods, optional ABI splits for smaller release APKs, and Health Connect sync history.

## Build and verify

Use Java 17 and Android SDK/build tools 35:

```bash
gradle --no-daemon testDebugUnitTest lintDebug verifyOfflineDebugApk
node --test tablet-preview/model.test.js
```

From WSL, use the permanent Linux toolchain wrapper:

```bash
cd /mnt/d/Apps/DailyCutReport
/mnt/d/Apps/android-gradle-linux.sh testDebugUnitTest lintDebug verifyOfflineDebugApk assembleDebug
```

The wrapper uses `/mnt/d/Apps/AndroidLinuxToolchain` for Java 17, Linux build-tools 35, and the shared Gradle cache.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and uses the existing stable debug signing identity.

## Data upgrades

Database version 3 migrates barcode-keyed products to stable internal IDs. Numeric retail codes remain barcodes; custom identifiers become barcode-free products. Existing extras and food-log snapshots survive unchanged. Legacy `daily_reports` SharedPreferences are imported once into Room and retained as rollback data.

The bundled catalog is imported transactionally and additively. A product with the same stable ID is never overwritten. Full `.dcrbackup` files use PBKDF2-HMAC-SHA256 and AES-256-GCM; passwords are never stored and cannot be recovered.

Catalog schema 2 uses a stable ID and an optional physical barcode:

```json
{
  "schemaVersion": 2,
  "products": [{
    "id": "CUSTOM-STABLE-ID",
    "barcode": null,
    "name": "Food name",
    "brand": "Brand",
    "servingLabel": "1 serving",
    "calories": 0,
    "proteinG": 0,
    "sodiumMg": 0,
    "carbsG": 0,
    "fatG": 0,
    "sugarG": 0,
    "fiberG": 0,
    "saturatedFatG": 0,
    "notes": "",
    "extras": []
  }]
}
```

## Main components

- `DailyCutApp.kt`: Compose navigation and workflows.
- `ViewModels.kt`: shared date and screen state.
- `DailyCutRepository.kt`: application data boundary.
- `NutritionDatabase.kt`: Room schema, DAO, and v1→v2→v3 migrations.
- `BarcodeScanner.kt`: lifecycle-safe on-device scanner.
- `NutritionImagePreprocessor.kt`, `NutritionLabelOcr.kt`, and `NutritionLabelParser.kt`: guided local image preparation, bundled multilingual OCR, deterministic nutrient extraction, and review candidates.
- `AppBackupManager.kt`: authenticated cross-platform backup format.
- `ReportImageExporter.kt`: dynamic local PNG rendering and storage.
