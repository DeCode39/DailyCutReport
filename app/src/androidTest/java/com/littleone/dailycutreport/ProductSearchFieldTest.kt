package com.littleone.dailycutreport

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsFocused
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ProductSearchFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resultRecompositionDoesNotResetCursorOrText() {
        var externalQuery by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                ProductSearchField(externalQuery) { externalQuery = it }
            }
        }

        val field = compose.onNodeWithTag("product_search")
        field.performTextInput("milk")
        field.performTextInputSelection(TextRange(2))
        compose.runOnIdle { externalQuery = "database emission" }
        field.performTextInput("X")
        assertEntry("miXlk", TextRange(3))
    }

    @Test fun clearSearchKeepsKeyboardFocusAndAllowsImmediateTyping() {
        var query by mutableStateOf("")
        compose.setContent { MaterialTheme { ProductSearchField(query) { query = it } } }
        val field = compose.onNodeWithTag("product_search")
        field.performClick().performTextInput("milk")
        compose.onNodeWithContentDescription("Clear product search").performClick()
        field.assertIsFocused()
        field.performTextInput("rice")
        assertEntry("rice", TextRange(4))
        field.assertIsFocused()
    }

    private fun assertEntry(text: String, selection: TextRange) {
        // IME commits are asynchronous and can attach composing spans. Check the
        // actual characters and cursor, not equality of their style annotations.
        val characters = SemanticsMatcher("Editable characters = '$text'") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            characters.matches(compose.onNodeWithTag("product_search").fetchSemanticsNode())
        }
        compose.onNodeWithTag("product_search").assert(characters)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
    }
}
