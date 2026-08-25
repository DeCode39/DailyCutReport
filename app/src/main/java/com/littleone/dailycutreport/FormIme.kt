package com.littleone.dailycutreport

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class FormImeSpec(val key: String, val enabled: Boolean = true)

@Stable
class FormFocusCoordinator internal constructor(specs: List<FormImeSpec>) {
    private val keys = specs.filter(FormImeSpec::enabled).map(FormImeSpec::key)
    private val requesters = specs.map(FormImeSpec::key).distinct().associateWith { FocusRequester() }

    fun requester(key: String): FocusRequester = requesters.getValue(key)
    fun request(key: String) { requesters[key]?.requestFocus() }
    fun action(key: String): ImeAction = if (keys.indexOf(key) in 0 until keys.lastIndex) ImeAction.Next else ImeAction.Done

    fun keyboardActions(
        key: String,
        focusManager: FocusManager,
        keyboardController: SoftwareKeyboardController?
    ) = KeyboardActions(
        onNext = {
            val next = keys.getOrNull(keys.indexOf(key) + 1)
            if (next == null) finish(focusManager, keyboardController) else requesters.getValue(next).requestFocus()
        },
        onDone = { finish(focusManager, keyboardController) }
    )

    private fun finish(focusManager: FocusManager, keyboardController: SoftwareKeyboardController?) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
}

@Composable
fun rememberFormFocusCoordinator(vararg specs: FormImeSpec): FormFocusCoordinator =
    remember(specs.toList()) { FormFocusCoordinator(specs.toList()) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.formImeField(key: String, coordinator: FormFocusCoordinator): Modifier {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .focusRequester(coordinator.requester(key))
        .bringIntoViewRequester(bringIntoViewRequester)
        .onFocusChanged { state ->
            if (state.isFocused) scope.launch {
                delay(120)
                bringIntoViewRequester.bringIntoView()
            }
        }
}

@Composable
fun FormFocusCoordinator.actions(key: String): KeyboardActions = keyboardActions(
    key,
    LocalFocusManager.current,
    LocalSoftwareKeyboardController.current
)
