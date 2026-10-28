# DailyCutReport 0.17.1 — safer meal copying and cart checkout

- Copy a previous meal/day to **today** or an explicitly chosen date, rather than silently reusing the historical screen date.
- Tap the date in the pinned cart header to retarget the entire cart. Quantities, label, checkout total, and budget exclusion stay intact.
- The checkout action displays its destination. Existing carts require an explicit conflict decision before copying to another date.
- Copy review names foods skipped because they were deleted/detached or their quantity units are incompatible.
- Checkout Undo returns items and checkout details to the cart. It never deletes older history or overwrites a new cart; changed checkout entries are protected.
- Logging and clearing the saved cart are transactional. Rapid taps, restart, incomplete paired quantities, failed writes, and late cart restoration are guarded against duplicate or lost entries.
- Browser parity includes editable dates, explicit copy destination, checkout Undo, and atomic log/cart storage.

Version code 39. No Room, backup, product JSON, report JSON, or browser storage-version migration. Barcode recognition, OCR, planning, burn calculations, and network-permission exclusions are unchanged.

Signed arm64 and universal builds upgrade existing signed releases in place. Back up before upgrading. Use arm64 on most modern phones; universal is the compatibility fallback.
