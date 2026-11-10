package com.littleone.dailycutreport

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReportCopyButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tapCopiesDayAndHoldCopiesOnlyWeek() {
        var days = 0
        var weeks = 0
        compose.setContent { DailyCutTheme { ReportCopyButton(false, { days++ }, { weeks++ }) } }
        val button = compose.onNodeWithContentDescription("Copy daily report JSON")
        button.performClick()
        button.performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, days); assertEquals(1, weeks) }
        button.assertHasClickAction()
        button.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        compose.runOnIdle { assertEquals(1, days); assertEquals(2, weeks) }
    }

    @Test fun busyCopyButtonCannotStartAnotherExport() {
        compose.setContent { DailyCutTheme { ReportCopyButton(true, { error("Unexpected daily copy") }, { error("Unexpected weekly copy") }) } }
        compose.onNode(hasClickAction()).assertIsNotEnabled()
    }
}
