package com.littleone.dailycutreport

import android.content.ClipboardManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class WeeklyExportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun clipboardAndDocumentContainTheSameSevenDays() {
        val context = instrumentation.targetContext
        val dao = NutritionDatabase.get(context).nutritionDao()
        val end = LocalDate.of(2002, 2, 10)
        val dates = WeeklyReportJson.datesEndingOn(end)
        val ids = mutableListOf<Long>()
        val count = if (InstrumentationRegistry.getArguments().getString("denseWeek") == "true") 80 else 1
        val fileName = "DailyCutTest-${UUID.randomUUID()}.json"
        val path = "/sdcard/Download/$fileName"
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        try {
            runBlocking {
                check(dates.all { dao.foodLogsForDate(it.toString()).isEmpty() && dao.dailyReport(it.toString()) == null })
                for (i in listOf(0, 3, 6)) {
                    repeat(count) { n ->
                        ids += dao.insertFoodLog(DailyFoodLogEntity(date = dates[i].toString(), productName = "測試-$i-$n",
                            quantity = 2.0, caloriesPerServing = 50.0 + i))
                    }
                }
            }
            compose.runOnUiThread { ViewModelProvider(compose.activity)[ReportDateViewModel::class.java].select(end) }
            val copy = compose.onNodeWithContentDescription("Copy daily report JSON")
            copy.performClick()
            fun clip() = compose.runOnUiThread {
                context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            compose.waitUntil(10_000) { runCatching { JSONObject(clip()).optString("date") == end.toString() }.getOrDefault(false) }
            copy.performTouchInput { longClick() }
            val save = "Save weekly JSON · $end"
            compose.waitUntil(10_000) { compose.onAllNodesWithText(save).fetchSemanticsNodes().isNotEmpty() }
            val payload = clip()
            val week = JSONObject(payload)
            val days = week.getJSONArray("days")
            assertEquals(7, days.length())
            assertEquals(3, week.getInt("daysWithFoodLogs"))
            for ((i, d) in dates.withIndex()) {
                val day = days.getJSONObject(i)
                assertEquals(d.toString(), day.getString("date"))
                val groups = day.getJSONArray("foodGroups")
                if (i in listOf(0, 3, 6)) {
                    assertEquals(count, groups.length())
                    assertEquals((0 until count).map { "測試-$i-$it" }.toSet(), (0 until groups.length()).map {
                        groups.getJSONObject(it).getJSONArray("items").getJSONObject(0).getString("name")
                    }.toSet())
                    assertEquals(count * (100 + 2 * i), day.getJSONObject("effectiveIntake").getInt("caloriesKcal"))
                } else {
                    assertEquals(0, groups.length())
                    assertTrue(day.getJSONObject("effectiveIntake").isNull("caloriesKcal"))
                }
            }
            // The unattended emulator may not have an unlocked DocumentsUI in Downloads.
            if (InstrumentationRegistry.getArguments().getString("verifyDownloads") != "true") return
            compose.onNodeWithText(save).performClick()
            fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (predicate(n)) return n
                    for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it) }?.let { return it }
                    return null
                }
                return automation.rootInActiveWindow?.let { visit(it) }
            }
            fun picker(n: AccessibilityNodeInfo) = n.packageName?.toString()?.endsWith("documentsui") == true
            compose.waitUntil(15_000) { node { picker(it) && it.isEditable } != null }
            val field = checkNotNull(node { picker(it) && it.isEditable })
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fileName)
            }))
            val confirm = checkNotNull(node { picker(it) && it.viewIdResourceName == "android:id/button1" })
            check(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(15_000) { (shell("stat -c %s $path").trim().toLongOrNull() ?: 0L) > 0L }
            assertEquals(payload, shell("cat $path"))
        } finally {
            runBlocking { ids.forEach { dao.deleteLog(it) } }
            shell("rm -f $path")
        }
    }
}
