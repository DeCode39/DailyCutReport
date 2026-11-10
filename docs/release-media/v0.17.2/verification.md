# 0.17.2 verification

- Android unit/Room tests: 158 passed, zero failures.
- Browser tests: 52 passed, zero failures; app JavaScript syntax and landing-page validation passed.
- `lintDebug`, debug app/test assembly, and `verifyOfflineDebugApk` passed. No `INTERNET` or `ACCESS_NETWORK_STATE` permission.
- Final API 34 emulator run: 20 scheduled, zero failures, one pre-existing ignored Base&U OCR fixture. Both new copy-button interaction/accessibility tests passed; eight fresh synthetic screenshots captured.
- Signed universal and arm64 R8 builds passed, including signature verification, ABI inspection, offline permission checks, and the 80 MiB universal / 35 MiB arm64 size gates.

Coverage includes inclusive seven-day boundaries, year/leap transitions, empty days, unavailable burn, per-date goals/logs/spending, unchanged daily JSON, integer calories, barcode exclusion, future-date rejection, mid-week read failures, tap-versus-hold exclusivity, accessibility long-click, busy-state protection, browser drag/cancel, and Shift+Enter.

The connected phone was not used: its test packages remain removed and the release installation/data remain untouched. No database or backup migration, no OCR/barcode changes, no Health Connect query changes. FoodVision remains excluded.

Final application source: `bda7514b7dc5830c4575125eea9d8a42278d1aa0`. The signed APKs from [CI run 42814641294](https://github.com/DeCode39/DailyCutReport/actions/runs/42814641294) are distributed with SHA-256 checksums in the [v0.17.2 prerelease](https://github.com/DeCode39/DailyCutReport/releases/tag/v0.17.2). Subsequent commits change only documentation and screenshots, not the validated application/build sources.
