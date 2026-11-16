# 0.17.3 verification

- Android unit/Room tests: 164 passed, no failures or skips.
- Browser tests: 52 passed. JavaScript syntax and landing-page version validation passed.
- Local `testDebugUnitTest lintDebug verifyOfflineDebugApk assembleDebugAndroidTest` passed. No database/entity schema change.
- API 34 emulator: 21 scheduled, zero failures, one pre-existing ignored Base&U OCR fixture. The new refresh-control test passed; eight fresh synthetic screenshots captured.
- Signed universal/arm64 R8 release builds passed, including signature, ABI, offline-permission, and 80 MiB universal / 35 MiB arm64 size gates. Assets are distributed with SHA-256 checksums in the [v0.17.3 prerelease](https://github.com/DeCode39/DailyCutReport/releases/tag/v0.17.3).

New regression coverage verifies exact 28-day inclusive boundaries, bypassing the daily throttle, delayed historical burn corrections and cached forecasts, today's forecast running last, preserving food logs/costs/manual weights and days outside the window, missing permissions/provider failures/incomplete batches, cancellation, serialized daily/window refreshes, retry after a failed today read, unchanged 30-day bootstrap/foreground throttling, rapid-tap protection, and refresh from a historical view without changing the selected date.

The connected phone was not used or modified. No new permissions, background work, database/backup migration, OCR/barcode change, or FoodVision code is included.

Validated application source: `b6d4392`. [Build run 43877568529](https://github.com/DeCode39/DailyCutReport/actions/runs/43877568529). Subsequent source commits only update documentation/website text and screenshots.
