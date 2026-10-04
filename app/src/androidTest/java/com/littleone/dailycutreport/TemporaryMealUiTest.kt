package com.littleone.dailycutreport

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TemporaryMealUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun togglePersistsThroughNutritionReturnAndPinnedSaveCreatesTemporaryProduct() {
        var draft by mutableStateOf(ProductEditorDraft(name = "Test lunch", brand = "Test cafe", saveTarget = ProductSaveTarget.BULK_CART))
        var saved: ProductEntity? = null
        compose.setContent {
            MaterialTheme { ProductEditorScreen(draft, "TWD", { draft = it }, {}, {}, {}, onSave = { product, _, _ -> saved = product }) }
        }
        compose.onNodeWithTag("one-time-meal").performScrollTo().performClick()
        compose.runOnIdle { draft = draft.copy(calories = "450", protein = "25") }
        compose.waitForIdle()
        compose.onNodeWithText("Save & continue").assertIsDisplayed().performClick()
        compose.onNodeWithText("Review nutrition").assertIsDisplayed()
        compose.onNodeWithText("Save anyway").performClick()
        compose.runOnIdle {
            assertEquals("Test lunch", saved?.name)
            assertEquals("Test cafe", saved?.brand)
            assertNotNull(saved?.expiresAtEpochMs)
            assertFalse(requireNotNull(saved).includeInPlanner)
        }
    }
}
