package com.littleone.dailycutreport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun GoalAssistantScreen(viewModel: SettingsViewModel) {
    val state by viewModel.goalAssistant.collectAsState()
    val suggestion by viewModel.goalSuggestion.collectAsState()
    val applying by viewModel.goalApplying.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    val saved = state?.profile
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Weight loss · muscle retention", style = MaterialTheme.typography.titleLarge)
        Text(state?.status ?: "Choose a profile, review the estimates, then apply once or adapt daily.")
        Text("Estimates for generally healthy adults, not medical prescriptions. Not for pregnancy, breastfeeding, eating disorders, or conditions requiring a clinical diet. Strength training also matters for muscle retention.", style = MaterialTheme.typography.bodySmall)
        if (saved != null && !editing) {
            OutlinedButton(onClick = { editing = true }) { Text("Review profile & locks") }
            if (saved.adaptive) OutlinedButton(onClick = { viewModel.stopGoalAssistant(false) }) { Text("Stop daily adaptation") }
            if (state?.previous != null) TextButton(onClick = { viewModel.stopGoalAssistant(true) }) { Text("Restore previous targets & stop") }
        } else {
            var age by rememberSaveable { mutableStateOf(saved?.age?.toString() ?: "") }
            var height by rememberSaveable { mutableStateOf(saved?.heightCm?.toString() ?: "") }
            var weight by rememberSaveable { mutableStateOf(saved?.weightKg?.toString() ?: "") }
            var sex by rememberSaveable { mutableStateOf(saved?.equationSex ?: GoalEquationSex.FEMALE) }
            var activity by rememberSaveable { mutableStateOf(saved?.activity ?: GoalActivity.LIGHT) }
            var adaptive by rememberSaveable { mutableStateOf(saved?.adaptive ?: false) }
            var eligible by rememberSaveable { mutableStateOf(false) }
            var locks by remember { mutableStateOf(saved?.locks ?: emptySet()) }
            var error by remember { mutableStateOf<String?>(null) }
            val focus = rememberFormFocusCoordinator(FormImeSpec("age"), FormImeSpec("height"), FormImeSpec("weight"))
            val manager = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            fun invalidate() { viewModel.clearGoalSuggestion(); error = null }
            listOf(Triple("age", "Age (years)", age), Triple("height", "Height (cm)", height), Triple("weight", "Fallback weight (kg)", weight)).forEach { (key, label, value) ->
                OutlinedTextField(value, onValueChange = { next ->
                    when (key) { "age" -> age = next; "height" -> height = next; else -> weight = next }
                    invalidate()
                }, label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = focus.action(key)),
                    keyboardActions = focus.keyboardActions(key, manager, keyboard),
                    modifier = Modifier.fillMaxWidth().formImeField(key, focus))
            }
            Text("Equation parameter (not gender identity)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalEquationSex.entries.forEach { option -> FilterChip(sex == option, { sex = option; invalidate() }, label = { Text(option.name.lowercase()) }) }
            }
            Text("Usual activity · used until seven completed burn days are available")
            GoalActivity.entries.forEach { option ->
                FilterChip(activity == option, { activity = option; invalidate() }, label = { Text("${option.name.lowercase()} · ×${option.multiplier}") })
            }
            Text("Lock targets to your current Goals & budget values. Editing a target there also locks it.")
            SuggestedTarget.entries.forEach { key ->
                Row { Checkbox(key in locks, { checked -> locks = if (checked) locks + key else locks - key; invalidate() }); Text(key.name.lowercase().replace('_', ' '), Modifier.padding(top = 12.dp)) }
            }
            Text("Sodium and total sugar stay user-controlled. Free-sugar guidance cannot be inferred from total-sugar labels.", style = MaterialTheme.typography.bodySmall)
            Row { Switch(adaptive, { adaptive = it; invalidate() }); Text("Adapt once daily", Modifier.padding(12.dp)) }
            Text("Recent daily-median weights and completed-day burn inform unlocked targets. Calorie allowance in deficit mode still follows your live forecast. Past dates keep their prior targets.", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(eligible, { eligible = it; invalidate() }); Text("I am an adult and none of the exclusions above apply.", Modifier.padding(top = 8.dp)) }
            fun profile() = GoalAssistantProfile(age.toIntOrNull() ?: 0, height.toDoubleOrNull() ?: 0.0, weight.toDoubleOrNull() ?: 0.0, sex, activity, adaptive, locks).validate()
            Button(enabled = eligible, onClick = {
                runCatching { profile() }.onSuccess { viewModel.previewGoals(it) }.onFailure { error = it.message }
            }) { Text("Preview suggested targets") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            suggestion?.let { result ->
                val g = result.goals
                Text("${formatCalories(g.calories)} kcal planning baseline · Protein ${formatDecimal(g.proteinG)} g\nFat ${formatDecimal(g.fatG)} g · Carbs ${formatDecimal(g.carbsG)} g\nFiber ${formatDecimal(g.fiberG)} g · Saturated fat ${formatDecimal(g.saturatedFatG)} g")
                Text(result.explanation, style = MaterialTheme.typography.bodySmall)
                Button(enabled = eligible && !applying, onClick = { viewModel.applyGoals(profile()) { editing = false } }) {
                    Text(if (applying) "Applying…" else if (adaptive) "Apply & enable daily adaptation" else "Apply once")
                }
            }
        }
        Text("Method: Mifflin–St Jeor (1990); protein review, Leidy et al. (2015); WHO Healthy diet. These defaults and conservative eligibility limits are app design choices, not an individualized clinical assessment.", style = MaterialTheme.typography.bodySmall)
    }
}
