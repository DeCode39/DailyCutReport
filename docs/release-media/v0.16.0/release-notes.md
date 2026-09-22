# DailyCutReport 0.16.0

- Add one-time meals through Add. They remain available for seven days; expiry never removes your logged history or unfinished cart.
- Duplicate an existing food as a one-time meal from its nutrition preview. Turn off the one-time toggle to keep it permanently.
- Compare the existing Health Connect–based burn forecast with a separate offline resting/activity calculation in Health. Today shows the internal total too. Planning still uses the existing forecast.
- Body profile & goals allows profile-only setup without changing nutrition targets.
- Internal calculations separate workout intervals from walking and disclose missing data, unsupported activity, modeling assumptions and uncertainty.
- Backup schema 8 imports older backups; Room 9→10 preserves existing products and logs. Report JSON schema 3 includes both burn sources.

The internal estimate is an experimental low-confidence comparison, not a medical measurement. Its displayed range is a model sensitivity band, not a clinical confidence interval. See the model notes in the repository for assumptions.

Download **arm64** for most modern phones, or **universal** for compatibility. Back up before upgrading. No Internet or network-state permission; barcode/OCR remain bundled and offline.
