package com.littleone.dailycutreport

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProductEditorBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun unchangedExistingEditClosesAndDirtyEditRequiresDiscard() {
        val existing = ProductWithExtras(ProductEntity("saved", name = "Saved"))
        var draft by mutableStateOf(ProductEditorDraft.create("", existing, ProductSaveTarget.CATALOG_ONLY))
        var exits = 0
        compose.setContent {
            MaterialTheme {
                ProductEditorScreen(draft = draft, currencyCode = "TWD", onDraftChange = { draft = it },
                    onScanBarcode = {}, onScanNutrition = {}, onDismiss = { exits++ }, onBack = { exits++ },
                    onSave = { _, _, _ -> error("Back must not save") })
            }
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, exits); draft = draft.copy(name = "Edited") }
        // State writes inside runOnIdle schedule another composition. Wait for the updated
        // BackHandler closure before dispatching the next Android Back event.
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.runOnIdle { assertEquals(1, exits); assertEquals("Edited", draft.name) }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Discard changes", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(2, exits) }
    }
}
