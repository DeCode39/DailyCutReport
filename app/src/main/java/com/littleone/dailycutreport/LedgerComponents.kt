package com.littleone.dailycutreport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
internal fun LedgerLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun LedgerMasthead() {
    Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("◢", color = MaterialTheme.colorScheme.primary)
            Text("DailyCutReport", Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            LedgerLabel("Offline")
        }
        HorizontalDivider()
    }
}

@Composable
internal fun LedgerTitle(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        trailing()
    }
}

@Composable
internal fun LedgerActions(label: String, icon: Int, action: () -> Unit, cart: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        cart()
        Spacer(Modifier.width(8.dp))
        Button(onClick = action, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(painterResource(icon), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

@Composable
internal fun LedgerNumber(number: String?, unit: String) {
    Text(if (number != null) androidx.compose.ui.text.buildAnnotatedString {
        append(number)
        withStyle(androidx.compose.ui.text.SpanStyle(fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("  $unit") }
    } else androidx.compose.ui.text.AnnotatedString("—"),
        style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
}

@Composable
internal fun LedgerStrip(leftLabel: String, left: String, rightLabel: String, right: String) {
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(leftLabel to left, rightLabel to right).forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    LedgerLabel(label)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun LedgerBalance(state: TodayUiState) {
    val report = state.report
    val burn = report.manual.burnCalories ?: report.projectedBurnCalories
    val hasIntake = report.nutrition.entries > 0 || report.health.nutritionRecords > 0 || report.manual.foodCalories != null
    val intake = report.finalFoodCalories.takeIf { hasIntake }
    val balance = if (burn != null && intake != null) burn - intake else null
    val allowanceLeft = intake?.let { state.calorieAllowance?.minus(it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LedgerLabel(if (balance != null && balance < 0) "Projected surplus" else "Projected deficit")
        LedgerNumber(balance?.let { formatCalories(abs(it)) }, "kcal")
        Text(when {
            balance == null -> if (burn == null) "Burn unavailable for this date" else "No intake recorded for this date"
            else -> "${formatCalories(state.goals.desiredDeficitCalories)} kcal deficit goal"
        }, style = MaterialTheme.typography.bodySmall)
        allowanceLeft?.let {
            Text("${formatCalories(abs(it))} kcal " + if (it >= 0) "left in allowance" else "over allowance",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        LedgerStrip("Intake", intake?.let { "${formatCalories(it)} kcal" } ?: "Not recorded",
            if (state.burnForecast?.isEstimate == true) "Est. burn" else "Total burn",
            burn?.let { "${formatCalories(it)} kcal" } ?: "Unavailable")
        burn?.let {
            val refreshed = Instant.ofEpochMilli(report.savedAtEpochMs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd MMM, HH:mm"))
            val confidence = state.burnForecast?.takeIf { it.isEstimate }?.confidence?.name?.lowercase()
            Text("Updated $refreshed" + (confidence?.let { " · $it confidence" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
