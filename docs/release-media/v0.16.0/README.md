# 0.16.0 release media

Fresh Today, Foods, Health, and Settings screenshots were captured on an API-34 Pixel 2 emulator in [release CI run 35764057811](https://github.com/DeCode39/DailyCutReport/actions/runs/35764057811). All food, weight, and burn values shown are synthetic demo data, not device or backup data.

- `today.png`
- `foods.png`
- `health.png`
- `settings.png`

Updated landing-page HTML can be placed in this folder for a later website revision. The public page remains `docs/index.html`.

## Verification

- Local: 124 Android unit tests, lint, debug assembly, instrumentation APK assembly, and offline-permission verification passed.
- Browser: 36 model tests and an isolated Chromium UI smoke test passed with external HTTP requests blocked.
- Attached device: 0.15.0-debug → 0.16.0-debug in-place upgrade and startup succeeded without uninstalling or clearing data. Instrumentation reported `OK (11 tests)`, including the new editor toggle and Room 9→10 coverage. Synthetic screenshot seeding was deliberately disabled on the device; the existing ignored Base&U OCR fixture remains ignored.
- Release CI additionally runs emulator instrumentation, R8 signed universal/arm64 builds, signature/ABI/size checks, and offline APK verification before publication. The tag retains the original application source; the subsequent CI-only repair stops requesting the retired Android SDK `tools` package.

The internal burn model is a low-confidence comparison experiment. Real-world calibration against measured expenditure and broader activity coverage are not claimed by these software tests.
