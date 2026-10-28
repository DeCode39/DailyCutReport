# 0.17.1 verification

- Android local unit/Room tests: 153 passed, zero failures.
- Browser model/cart tests: 48 passed, zero failures; JavaScript syntax and landing-page version validated.
- `lintDebug`, `verifyOfflineDebugApk`, debug app and test APK assembly passed.
- API 34 emulator instrumentation: 18 scheduled, zero failures, one pre-existing ignored Base&U OCR fixture. Eight fresh synthetic-data screenshots captured.
- Connected Android 16 / API 36 device: separate debug package installed without changing the release package or its data. Instrumentation completed successfully; screenshot capture was intentionally skipped on-device, and the pre-existing device-dependent Base&U fixture remains ignored. Monster OCR, preprocessing, migration, search, editor, and cart-control checks passed.
- Scanner smoke check: cold launch with camera granted opened the preview; permission-denied launch exposed Allow camera/Cancel without crashing. Test-only camera flags were cleared afterwards.

Regression coverage includes historical source versus destination dates, explicit cart retargeting, keep/replace/cancel conflicts, future dates, partial numeric inputs, corrected measurement conversions without density guessing, missing/deleted products, atomic checkout and restart recovery, exact checkout payment, extras cleanup, protection of unrelated/edited history, one-shot Undo, checkout failure retention, rapid repeated submits, late cart restoration, and destination-specific macro goals.

Limitations: real barcode acquisition speed and live Health Connect provider/permission behavior were not re-measured. No OCR accuracy changes are included; the existing Base&U manual-correction limitation remains. The installed release and personal data were not used as test fixtures. FoodVision remains excluded.

Signed release/R8, signature, ABI, size and offline-permission gates are handled by the linked CI run; publication occurs only after those gates pass.
