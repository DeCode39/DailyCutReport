# Internal burn comparison, model 1 (0.16.0)

This is an offline comparison experiment, not a new planner calorie allowance or medical measurement. The Health Connect–based forecast remains authoritative. No estimated calories are written to Health Connect.

## Inputs and accounting

- Reuse the body profile (adult age, height, equation parameter, fallback weight). Prefer the median of the latest day of valid recorded weights within the preceding 28 days, without using future measurements. The source date is displayed. Body-only setup does not apply the weight-loss assistant's BMI eligibility rule or change targets.
- [Mifflin–St Jeor](https://pubmed.ncbi.nlm.nih.gov/2305711/) estimates resting kcal per 24 hours. Scale by the actual calendar-day duration (including 23/25-hour days).
- Read steps/distance only in time intervals outside exercise sessions. Outside-session distance is treated as walking, using 0.5 kcal/kg/km. With missing distance, use 1,300 steps/km as a visibly labeled fallback. Missing movement is not evidence of zero movement.
- Exercise uses a small general-effort selection from the [2024 Adult Compendium](https://pacompendium.com/adult-compendium/): walking 3.8, running 7.5, cycling 6.8, pool swimming 5.8, weightlifting 3.5, yoga 2.3 MET. These represent assumed efforts, not measured intensity. Net exercise kcal = (MET − 1) × 1.05 × kg × hours.
- Partition clipped exercise intervals. For overlapping records, use the maximum supported MET once, never sum duplicate sessions. Unsupported sessions are omitted and disclosed; their steps are not separately added as walking.
- Add an explicit background modeling allowance of 10% of resting expenditure for otherwise unmodeled activity/digestion. This is an app heuristic, not measured thermic effect or an activity multiplier. It may overestimate or underestimate a person's background expenditure.

## Time horizons and uncertainty

Burn so far includes elapsed resting/background energy plus completed movement/exercise. Final burn adds remaining resting/background and the median hourly movement expenditure from up to 28 completed internal model days. Without internal history, remaining movement is zero and the limitation is shown. Provider total/active calories are never used to train this model.

All results remain **low confidence** in this release. The ±25% band is a sensitivity display, **not a validated confidence interval**. The model cannot infer wearable coverage, actual workout intensity, terrain, individual metabolism, or all non-step activity. Unrecorded cycling distance may be mistaken for walking. This version is intended to expose disagreements for evaluation, not establish which engine is correct.

Historical dates use completed-day windows. Both comparison sources show their refresh time. A numeric difference is shown only when the sources were refreshed within one minute of one another. Food intake remains the local log intake.

## Storage and failure isolation

Room schema 10 adds only product expiry; internal estimate snapshots (model version, component totals, source/quality explanation and timestamp) use versioned internal metadata keys. No routes, coordinates, session titles, raw sensor records, or provider calorie inputs are retained by this engine. Calculation caches are excluded from encrypted backups and cleared on restore. Refresh regenerates them. Body profile is included in backup schema 8.

An internal activity-read failure is isolated from the existing Health Connect activity refresh. No new permissions or network capability are introduced. The tablet preview uses manually supplied walking totals and a conservative no-future-movement forecast; native session separation requires Android.
