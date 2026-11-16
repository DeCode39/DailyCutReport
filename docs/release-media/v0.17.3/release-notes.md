# DailyCutReport 0.17.3

- Refresh now reloads today plus the previous 27 days from Health Connect, picking up delayed burn/activity corrections. Works from Today, Health, and Settings, regardless of the date being viewed.
- Every manual refresh bypasses the daily history throttle. Past-day forecast caches update with their corrected totals; today's live forecast is recalculated last.
- Refresh operations are serialized and rapid repeated taps are guarded. Failed/incomplete history imports retain existing data; a failed final today read retains today's previous values and allows retry.
- Automatic app-open behavior, food logs, prices, barcode scanning, OCR, and offline guarantees are unchanged.

Version code 41. No database or backup migration. Signed arm64 and universal APKs with SHA-256 checksums.
