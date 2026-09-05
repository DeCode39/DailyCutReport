# Daily Cut Report 0.14.2

Daily Cut Report is a strictly offline Android fitness and nutrition journal. It combines Health Connect activity data with a local food catalog and daily food log, calculates daily energy balance, and exports a structured clipboard report.

## Unreleased development changes

- Food cards have an eye action: hold for a temporary nutrition preview, or tap to keep it open. Preview amounts use one purchase unit with g/ml conversions; Favorite is inside the preview. Tap-to-cart and hold-to-edit remain unchanged.
- Separate optional Health Connect **weight write** permission exports manual readings, with stable client IDs, increasing versions, serialized retries, and deletion reconciliation. Imported readings are not exported, and app-owned exports are excluded from import to prevent double counting.
- Settings → Goal assistant provides reviewed **weight loss with muscle retention** estimates. Apply once or opt into daily adaptation from recent daily-median weight and completed-day burn. Individual targets can be locked; manual target edits lock those fields. Sodium and total-sugar limits stay user-controlled. Deficit mode still uses the live forecast for its calorie allowance.
- An effective-date target ledger preserves historical targets after changes. Dates preceding this feature use the goals that were configured when the ledger began; older per-date settings cannot be reconstructed. Stop adaptation or restore the previous targets in the assistant.
- Room remains schema 9. Backup schema 7 additionally preserves assistant configuration and the target ledger, while accepting schemas 1–6. Internal Health Connect sync markers remain excluded. The tablet preview preserves this configuration during backup round trips and uses historical targets; assistant configuration and Health Connect permissions are Android-only in this pass.

The assistant is an optional adult wellness estimate, not a clinical diet. Its defaults use [Mifflin–St Jeor](https://pubmed.ncbi.nlm.nih.gov/2305711/) for fallback energy, [the 2015 protein/weight-management review](https://pubmed.ncbi.nlm.nih.gov/25926512/) for the 1.6 g/kg protein starting point, and [WHO healthy-diet guidance](https://www.who.int/news-room/fact-sheets/detail/healthy-diet) for fiber/fat context. WHO free-sugar guidance is not applied to total-sugar labels. The eligibility checks and allocation choices are conservative app heuristics, not guarantees of safety or muscle retention. No source is fetched by the app: all calculations and Health Connect operations are local.

This development commit does not publish a new APK release. The website continues to identify the latest published version.

## Features

- Material 3 Jetpack Compose UI with Today, Foods, Health, and Settings destinations.
- Shared historical date navigation with a calendar capped at today.
- One process-level Health Connect refresh on app foreground, plus a serialized 30-day bootstrap after installation, upgrade, or newly granted optional permissions; rotation does not refresh again.
- A deficit-focused Health dashboard with optional Health Connect weight import, multiple timestamped daily readings, quick floating manual-weight entry, labeled deficit/weight trend plots, robust 28-day weight-change ranges, and capped walking guidance derived from personal sessions or body weight.
- A floating Scan action on Today and the home-screen widget; both feed the same persistent cart used by Foods, while Foods uses its floating Add action for a full-screen product editor.
- Custom fixed-calorie or dynamic-deficit goals, editable macro targets, one ISO currency, and a daily food budget. Deficit mode requires only the desired deficit and derives its allowance from recorded burn plus a robust 28-day remaining-burn forecast.
- The idle Foods catalog shows up to five recently used favorites followed by five recently logged products that do not duplicate them; typing in search queries the complete catalog.
- Optional catalog pricing by minimum purchase unit, historical cost snapshots, and per-entry actual-paid totals for discounts and free items.
- A deterministic offline remaining-day planner that ranks up to three strict purchase-unit suggestions. Already-logged fixed items count toward their requirement, and pre-existing target violations produce one baseline-aware balanced recovery option instead of a minimums-at-any-cost fallback.
- A dedicated searchable Planner settings page controls inclusion, food/drink classification, fixed status, and one-to-six required purchase units per product. Fixed items are exempt from category caps, so two additional non-fixed drink units remain available.
- Catalog cards use left-side line-art Add/Edit controls, tap-to-cart, and hold-to-edit. Every catalog add defaults to one complete purchase unit while keeping quantities editable.
- Per-log price exclusion keeps the recorded paid/estimated amount visible while omitting it from daily budget and planner calculations.
- Every catalog or barcode addition enters one persistent, date-locked cart. Duplicate products merge, date conflicts require an explicit choice, press-and-hold quantity controls accelerate from one to ten purchase units, and checkout atomically logs several products with one final paid total. A one-item checkout remains an ordinary entry.
- Bulk orders appear as one expandable Today card with aggregate nutrition and checkout cost. Individual rows or the complete order can be deleted and restored transactionally.
- Stored goal and currency values are sanitized on startup, and zero targets render safely.
- Serialized, retryable Health Connect nutrition upserts after local food-log add/edit/delete, controlled by a separate optional write permission and surfaced in Settings.
- In-app macro threshold snackbars when a food-log change crosses a daily target.
- Dark yellow-on-black Material 3 color scheme.
- Responsive Today home-screen widget showing local balance, intake, protein, spending, and a direct Scan action.
- On-device CameraX + bundled ML Kit barcode recognition, with an optional per-session Multi-scan queue for ordinary logging or the Bulk Cart.
- Guided crop/rotation and on-device English, Chinese, and Japanese nutrition-label OCR from up to three camera or gallery images, with local quality warnings, image variants, source-row review, and explicit conflict selection.
- Unsaved product drafts survive the complete OCR workflow; OCR only replaces explicitly accepted nutrient fields and never discards identity, pricing, extras, or planner settings.
- Manual product creation accepts a validated versioned JSON document for fast AI-assisted photo estimates; Settings provides a copyable schema.
- Locale-aware numeric presentation rounds to at most two decimal places and removes floating-point display noise without rewriting active editor text.
- Room product catalog with linked-log nutrient corrections: editing a saved source updates its linked nutrition history while preserving quantity, time, and paid cost.
- Optional product barcodes and 17 additive preloaded foods that never overwrite user edits.
- Password-encrypted full backups compatible with the tablet preview.
- Legacy manual overrides are cleared during initialization and every restore; report values now come from Health Connect and local food logs.
- Missing burn data is shown as unavailable, never as a calorie surplus.
- Deleted food entries can be restored from one-shot, finite-duration snackbar Undo actions; newer messages replace stale ones instead of queueing.
- Today provides a selected-date Health Connect refresh and a privacy-marked clipboard action that copies versioned, barcode-free daily-report JSON.
- Forms use keyboard Next/Done/Search actions with focus-aware scrolling. Unfinished product drafts can be resumed after process death, and the shared cart keeps its checkout action pinned above the scrollable contents.
- Browser-based tablet preview with matching manual workflows and versioned localStorage.

## Offline contract

The final APK must not request either of these permissions:

```text
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
```

ML Kit dependencies declare them transitively, so the app manifest explicitly removes them during manifest merging. The `verifyOfflineDebugApk` and `verifyOfflineReleaseApk` Gradle tasks inspect assembled APKs and fail if either permission returns. There is no cloud sync, remote telemetry, background network work, or Android cloud backup. OCR language models are bundled in the APK.

OCR remains an assistive workflow with manual correction. Captured images and recognized text are transient and discarded after use or cancellation.

Future quality-of-life candidates include sending a planner result directly into the bulk cart, food-log copy from a previous day, reusable meals, receipt capture, and richer Health Connect diagnostics.

## Build and verify

Use Java 17, Android platform 36, and Android build tools 35:

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

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`, uses the existing stable debug signing identity, and installs as `com.littleone.dailycutreport.debug` so it can coexist with a release build.

Release builds use R8 and resource shrinking. Signing values are supplied only through `DCR_RELEASE_*` environment variables. Build the universal APK normally, or the smaller modern-phone build with:

```bash
/mnt/d/Apps/android-gradle-linux.sh verifyOfflineReleaseApk -PdcrAbi=arm64-v8a
```

GitHub tagged prereleases publish signed arm64 and universal APKs with SHA-256 checksums. The release key lives outside the repository and is mirrored only in encrypted GitHub Actions secrets.

### 0.8.5 signing transition

Public 0.8.5 APKs used the old debug certificate, so Android cannot install a privately signed release over them. Export a `.dcrbackup` in 0.8.5, uninstall it, install the current release, then restore the backup. This is a one-time transition; future release-signed versions upgrade normally.

## Data upgrades

Database version 9 adds flexible serving, gram, and milliliter quantity metadata while preserving how historical entries were logged. Version 8 added configurable fixed purchase-unit amounts and defaults existing fixed products to one unit. Version 7 added product favorites. Version 6 added the health profile, manual/imported weight entries, and privacy-limited walking-session summaries; multiple readings per day use the existing timestamped entries. Version 5 added optional one-time bulk-log grouping fields. Existing products, reports, extras, and food logs survive; old logs intentionally retain unknown cost. Legacy `daily_reports` SharedPreferences remain as rollback data.

The bundled catalog is imported transactionally and additively. A product with the same stable ID is never overwritten. Products may be logged by servings, grams, or milliliters when their measurement basis is configured; historical logs retain the unit actually entered. Backup schema 6 includes quantity metadata, fixed purchase-unit amounts, favorites, and every health entry while accepting schemas 1–5. Full `.dcrbackup` files use PBKDF2-HMAC-SHA256 and AES-256-GCM; passwords are never stored and cannot be recovered. The browser preview stores equivalent state in `dcr_v8`.

Catalog schema 3 uses a stable ID, an optional physical barcode, and optional quantity-basis fields:

```json
{
  "schemaVersion": 3,
  "products": [{
    "id": "CUSTOM-STABLE-ID",
    "barcode": null,
    "name": "Food name",
    "brand": "Brand",
    "servingLabel": "1 serving",
    "quantityMode": "SERVING_AND_WEIGHT",
    "measurePerServing": 40,
    "preferredLogUnit": "GRAMS",
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

- `AppShell.kt`: Compose navigation, shared workflow host, and app-level snackbars.
- `DailyCutApp.kt`: Today, Foods, Health, Settings, OCR, and shared dialog components.
- `FoodsComponents.kt`: product catalog, persistent Bulk Cart sheet, search editor, and planner-result UI.
- `ViewModels.kt`: shared date and screen state.
- `DailyCutRepository.kt`: application data boundary.
- `NutritionDatabase.kt`: Room schema, DAO, grouped mutations, and v1→v2→v3→v4→v5→v6→v7→v8→v9 migrations.
- `HealthAnalytics.kt`: robust deficit, weight projection, and walking-estimate calculations.
- `BarcodeScanner.kt`: lifecycle-safe on-device scanner.
- `NutritionImagePreprocessor.kt`, `NutritionLabelOcr.kt`, and `NutritionLabelParser.kt`: guided local image preparation, bundled multilingual OCR, deterministic nutrient extraction, and review candidates.
- `AppBackupManager.kt`: authenticated cross-platform backup format.
- `DailyReportJson.kt`: stable, rounded, barcode-free clipboard report serialization.
- `NutritionSyncCoordinator.kt`: serialized, versioned Health Connect nutrition synchronization and retries.
- `OfflineMealPlanner.kt`: bounded deterministic nutrition-and-budget recommendation search.
