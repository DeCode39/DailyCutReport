# DailyCutReport 0.15.0

## New

- Food nutrition previews: hold the eye to peek, or tap to keep the preview open. Values reflect one purchase unit and its serving/g/ml conversion. Favorite is inside the preview; tap-to-cart and hold-to-edit are unchanged.
- Optional Health Connect weight export: enable it separately in Settings. Manual readings are synchronized, including deletions, with retries and no duplicate re-imports. Imported readings remain read-only. Unchanged exports are skipped.
- Offline goal assistant for general weight loss with muscle retention: review suggestions, apply once or enable daily adaptation, lock individual targets, and restore previous goals. Historical dates retain their prior targets. Sodium and total-sugar limits remain user-controlled.

## Compatibility and privacy

- Room schema remains 9. Encrypted backups move to schema 7 and still accept schemas 1–6. Back up before upgrading; schema-7 backups cannot be read by older app versions.
- No Internet or network-state permission. Health Connect is a permission-controlled, on-device Android API. Goal calculations are local mathematical estimates, not cloud AI or medical prescriptions.
- Choose the arm64 APK for most modern phones; use universal for compatibility. SHA-256 checksums are provided.

## Verification notes

Local validation passed 113 Android unit tests, 33 browser tests, lint, debug/R8 assembly, and offline-permission checks before release preparation. Publication additionally gates on CI emulator tests, signing verification, ABI checks, and APK-size limits. Physical-device weight-write permission/sync acceptance remains pending; the device-dependent Base&U OCR test remains excluded as in prior releases.

Release screenshots use synthetic data, not personal records.
