package com.littleone.dailycutreport

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

class LiveRefreshDeviceTest {
    @Test fun grantedDeviceCanRefreshWindowAndExportObservedCoverage(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHealth") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as DailyCutApplication).container.repository
        check(repo.healthCorePermissionsGranted()) { "Grant activity reads in the debug app first." }
        val today = LocalDate.now()
        val dao = NutritionDatabase.get(context).nutritionDao()
        val historyCurrent = dao.metadata("health_history_sync_day_v1") == today.toString()
        val yesterdayCovered = dao.metadata("completed_day_burn_checked_v1") == today.minusDays(1).toString()
        android.util.Log.i("DcrDeviceChecks", "Before manual refresh: history current=$historyCurrent; yesterday burn confirmed=$yesterdayCovered")
        repo.refreshRecentHealth().getOrThrow()
        repo.refreshOnForeground().getOrThrow()
        val week = JSONObject(ReportJsonExporter(repo).export(today, true))
        assertEquals(7, week.getJSONArray("days").length())
        assertEquals(today.toString(), week.getString("endDate"))
        assertEquals(today.minusDays(6).toString(), week.getString("startDate"))
        val missing = week.getJSONArray("datesWithoutBurn").length()
        android.util.Log.i("DcrDeviceChecks", "Live manual and foreground refresh succeeded; days with weekly burn: ${7 - missing}/7")
    }
}
