package com.littleone.dailycutreport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class GoalAssistantStoreTest {
    private lateinit var db: NutritionDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NutritionDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()
    @Test fun applicationIsHistoricalAndSameDayAdaptationIsNoOp() = runBlocking {
        val dao = db.nutritionDao()
        val store = GoalAssistantStore(dao)
        val before = UserGoals(proteinG = 95.0)
        dao.upsertUserGoals(before.toEntity())
        store.apply(GoalAssistantProfile(30, 175.0, 80.0, GoalEquationSex.MALE, GoalActivity.LIGHT, adaptive = true))
        val snapshot = store.state()
        store.adapt()
        assertEquals(snapshot, store.state())
        assertEquals(95.0, snapshot.goalsFor(LocalDate.now().minusDays(1), before).proteinG, 0.0)
        store.stop(true)
        assertEquals(before, dao.userGoals()!!.toDomain())
        assertFalse(store.state().profile!!.adaptive)
    }
    @Test fun manualTargetChangeLocksItAndRestoreClearsAssistantOnOldBackups() = runBlocking {
        val dao = db.nutritionDao()
        val store = GoalAssistantStore(dao)
        store.apply(GoalAssistantProfile(30, 175.0, 80.0, GoalEquationSex.MALE, GoalActivity.LIGHT, adaptive = true))
        store.manual(dao.userGoals()!!.toDomain().copy(proteinG = 111.0))
        assertTrue(SuggestedTarget.PROTEIN in store.state().profile!!.locks)
        dao.replaceUserData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), UserGoalsEntity())
        assertNull(dao.metadata(GoalAssistantState.KEY))
    }
}
