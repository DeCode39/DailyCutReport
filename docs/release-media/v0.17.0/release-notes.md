# DailyCutReport 0.17.0 — quicker entry and safer food data

- Clear search with one tap while retaining keyboard focus.
- Reuse a food's last entered amount, copy yesterday or an order to the review cart, and Undo cart removal.
- Food database replaces Planner in Settings, keeping planning controls alongside Edit/Delete and label-review filtering.
- Catalog deletion preserves detached historical food logs, nutrients, extras, and paid/estimated costs; warnings include affected entries and dates.
- Existing-food edits no longer become unfinished-product recovery cards. Changed edits require explicit discard; new drafts remain recoverable.
- Fix grouped numbers such as `1,140` becoming decimals. Editable nutrition and monetary values retain full precision; JSON accepts real numbers only.
- Offline nutrition verification requests review for improbable label values without guessing corrections.

Room 10 / backup 8 / product JSON 2 / report JSON 3 remain unchanged. Optional deleted-catalog IDs are preserved in new backups; older backups remain importable. Burn calculations and planner ranking are unchanged.

Use the arm64 APK for most modern phones; universal is the compatibility fallback. Existing signed releases upgrade in place. Back up before upgrading.

No Internet or network-state permission is added.
