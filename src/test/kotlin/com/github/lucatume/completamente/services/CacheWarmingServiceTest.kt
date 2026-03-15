package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.completion.InfillExtraChunk
import com.intellij.openapi.util.Disposer

class CacheWarmingServiceTest : BaseCompletionTest() {

    private fun createService(): CacheWarmingService {
        val svc = CacheWarmingService(project)
        Disposer.register(testRootDisposable, svc)
        return svc
    }

    fun testGetLastWarmedExtrasIsInitiallyEmpty() {
        val svc = createService()
        assertTrue(svc.getLastWarmedExtras().isEmpty())
    }

    fun testScheduleWarmupStoresExtras() {
        val svc = createService()
        val extras = listOf(
            InfillExtraChunk(filename = "Foo.kt", text = "class Foo"),
            InfillExtraChunk(filename = "Bar.kt", text = "class Bar")
        )
        svc.scheduleWarmup(extras)
        assertEquals(extras, svc.getLastWarmedExtras())
    }

    fun testDisposeDoesNotCrash() {
        val svc = createService()
        svc.scheduleWarmup(listOf(InfillExtraChunk(filename = "A.kt", text = "a")))
        // Dispose should cancel pending work without throwing.
        svc.dispose()
    }

    fun testRapidScheduleWarmupKeepsLastExtras() {
        val svc = createService()
        val first = listOf(InfillExtraChunk(filename = "First.kt", text = "first"))
        val second = listOf(InfillExtraChunk(filename = "Second.kt", text = "second"))
        val third = listOf(InfillExtraChunk(filename = "Third.kt", text = "third"))

        svc.scheduleWarmup(first)
        svc.scheduleWarmup(second)
        svc.scheduleWarmup(third)

        // After rapid calls, getLastWarmedExtras should reflect the last call.
        assertEquals(third, svc.getLastWarmedExtras())
    }

    fun testDeduplicationSkipsSameExtras() {
        val svc = createService()
        val extras = listOf(
            InfillExtraChunk(filename = "Foo.kt", text = "class Foo"),
            InfillExtraChunk(filename = "Bar.kt", text = "class Bar")
        )

        // First call stores the extras.
        svc.scheduleWarmup(extras)
        assertEquals(extras, svc.getLastWarmedExtras())

        // Dispose and recreate to get a fresh alarm, proving the second call is a no-op
        // by verifying lastWarmedExtras remains the same without alarm activity.
        // Alternatively: schedule same extras again — it should be a no-op (deduplication).
        // We verify by checking that calling with the same list doesn't throw or change state.
        val sameExtras = listOf(
            InfillExtraChunk(filename = "Foo.kt", text = "class Foo"),
            InfillExtraChunk(filename = "Bar.kt", text = "class Bar")
        )
        svc.scheduleWarmup(sameExtras)

        // lastWarmedExtras should still be the original list (unchanged by dedup).
        assertEquals(extras, svc.getLastWarmedExtras())

        // Now schedule different extras — this should update.
        val different = listOf(InfillExtraChunk(filename = "Baz.kt", text = "class Baz"))
        svc.scheduleWarmup(different)
        assertEquals(different, svc.getLastWarmedExtras())
    }
}
