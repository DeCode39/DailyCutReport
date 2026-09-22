package com.littleone.dailycutreport

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
internal fun BurnComparisonCard(internal: InternalBurnEstimate?, health: BurnForecast?, intake: Double, compact: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Burn comparison", style = MaterialTheme.typography.titleMedium)
            Text("HC-based: ${health?.estimatedFinalCalories?.let(::formatCalories) ?: "Unavailable"} · Internal: ${internal?.finalKcal?.let(::formatCalories) ?: "Unavailable"} kcal")
            Text("Planning continues to use Health Connect–based burn.", style = MaterialTheme.typography.bodySmall)
            if (internal == null) Text("Save your body profile in Settings, then refresh Health. Activity permission is required.", style = MaterialTheme.typography.bodySmall)
            else {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide calculation" else "Details · low confidence") }
                if (expanded) {
                    Text("Internal resting: ${formatCalories(internal.restingDailyKcal)} kcal/day")
                    Text("Movement: ${formatCalories(internal.movementKcal)} · exercise: ${formatCalories(internal.exerciseKcal)} kcal so far")
                    Text("Background assumption: ${formatCalories(internal.backgroundDailyKcal)} kcal/day")
                    Text("Burn so far · HC: ${health?.liveBurnCalories?.let(::formatCalories) ?: "Unavailable"} · internal: ${formatCalories(internal.burnSoFarKcal)} kcal")
                    Text("Internal final minus logged intake: ${formatCalories(internal.finalKcal - intake)} kcal (positive = deficit)")
                    val paired = health?.takeIf { abs(it.refreshedAtEpochMs - internal.refreshedAtEpochMs) < 60_000 }
                    paired?.estimatedFinalCalories?.let { hc ->
                        Text("Internal minus HC-based forecast: ${formatCalories(internal.finalKcal - hc)} kcal")
                    }
                    if (paired == null) Text("Sources were refreshed at different times; refresh before comparing their difference.")
                    Text("Sensitivity band: ${formatCalories(internal.lowerKcal)}–${formatCalories(internal.upperKcal)} kcal")
                    Text("Updated ${Instant.ofEpochMilli(internal.refreshedAtEpochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM HH:mm"))} · ${internal.sampleDays} completed internal days")
                    Text(internal.explanation, style = MaterialTheme.typography.bodySmall)
                    if (!compact) Text("Mifflin–St Jeor resting estimate; general-effort MET activity estimates. Distance outside workouts is treated as walking. No provider calorie totals are used. These are estimates, not medical measurements.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
