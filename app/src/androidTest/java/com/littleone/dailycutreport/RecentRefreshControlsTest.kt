package com.littleone.dailycutreport

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class RecentRefreshControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun todayRefreshHasClearWindowLabelAndHandlesMissingPermissionsWithoutCrashing() {
        compose.onNodeWithContentDescription("Refresh last 28 days").assertIsDisplayed().assertHasClickAction().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Refresh last 28 days").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Refresh last 28 days").assertIsDisplayed()
    }
}
