package com.littleone.dailycutreport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class CartControlsTest {
    @get:Rule val compose = createComposeRule()
    private val cart = BulkDraft(LocalDate.now(), listOf(BulkDraftItem(ProductEntity("food", name = "Food"))))

    @Test fun copyingFromHistoryExplicitlyDefaultsToToday() {
        var selected: LocalDate? = null
        compose.setContent { DailyCutTheme { CopyDestinationDialog(LocalDate.now().minusDays(8), {}, { selected = it }) } }
        compose.onNodeWithText("Choose date").assertIsDisplayed()
        compose.onNodeWithText("Copy to today").performClick()
        compose.runOnIdle { assertEquals(LocalDate.now(), selected) }
    }

    @Test fun checkoutAndDateControlsStayVisibleAtBottomWithKeyboardOpen() {
        var committed = 0
        compose.setContent {
            DailyCutTheme {
                Column(Modifier.fillMaxSize().imePadding()) {
                    CartSheetHeader(cart, false, {}, {}, { committed++ })
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        repeat(30) { index -> OutlinedTextField("", {}, label = { Text("Quantity $index") }, modifier = Modifier.testTag("field-$index")) }
                    }
                }
            }
        }
        compose.onNodeWithTag("field-29").performScrollTo().performClick().performTextInput("2")
        compose.onNodeWithContentDescription("Change cart date: ${cart.date}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Log cart to ${cart.date}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, committed) }
    }

    @Test fun submittingDisablesClosingDateChangesAndCommit() {
        compose.setContent { DailyCutTheme { CartSheetHeader(cart, true, {}, {}, {}) } }
        compose.onNodeWithText("Close").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Change cart date: ${cart.date}").assertIsNotEnabled()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
    }
}
