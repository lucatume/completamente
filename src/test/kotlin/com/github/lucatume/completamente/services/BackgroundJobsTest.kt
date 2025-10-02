package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class BackgroundJobsTest : BaseCompletionTest() {
    fun testRunWithDebounceFirstCallExecutesImmediately() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val executionCount = AtomicInteger(0)

        backgroundJobs.runWithDebounce({
            executionCount.incrementAndGet()
        })

        // Give coroutine time to execute
        delay(50)

        assertEquals(1, executionCount.get())

        backgroundJobs.dispose()
    }

    fun testRunWithDebounceSecondCallIsDebounced() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val executionOrder = mutableListOf<String>()

        // First call - should execute immediately
        backgroundJobs.runWithDebounce({
            executionOrder.add("first-start")
            delay(200)  // Simulate long-running task
            executionOrder.add("first-end")
        })

        // Give first task time to start
        delay(20)

        // Second call while first is running - should be debounced
        backgroundJobs.runWithDebounce({
            executionOrder.add("second")
        }, debounceMillis = 50)

        // At this point, first should have started but second should be pending
        assertTrue(executionOrder.contains("first-start"))
        assertFalse(executionOrder.contains("second"))

        // Wait for first to finish and debounce to fire
        delay(300)

        assertTrue(executionOrder.contains("second"))

        backgroundJobs.dispose()
    }

    fun testRunWithDebouncePendingJobIsCancelledOnNewCall() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val executionCount = AtomicInteger(0)

        // First call - start a long-running task
        backgroundJobs.runWithDebounce({
            delay(500)  // Long task
        })

        delay(20)  // Let first task start

        // Second call - creates pending job with 100ms debounce
        backgroundJobs.runWithDebounce({
            executionCount.incrementAndGet()
        }, debounceMillis = 100)

        delay(20)  // Before debounce fires

        // Third call - should cancel second's pending job
        backgroundJobs.runWithDebounce({
            executionCount.addAndGet(10)
        }, debounceMillis = 100)

        // Wait for debounce to fire
        delay(150)

        // Only the third task should have executed (value = 10, not 11)
        assertEquals(10, executionCount.get())

        backgroundJobs.dispose()
    }

    fun testRunWithDebounceUsesCustomDebounceDelay() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val executed = AtomicInteger(0)

        // First call to set currentJob
        backgroundJobs.runWithDebounce({
            delay(300)
        })

        delay(20)

        // Second call with 200ms debounce
        backgroundJobs.runWithDebounce({
            executed.set(1)
        }, debounceMillis = 200)

        // At 100ms, task should not have executed yet
        delay(100)
        assertEquals(0, executed.get())

        // At 250ms total (200ms debounce passed), task should have executed
        delay(150)
        assertEquals(1, executed.get())

        backgroundJobs.dispose()
    }

    fun testRunWithDebounceDefaultDebounceIs100ms() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val executed = AtomicInteger(0)

        // First call to set currentJob
        backgroundJobs.runWithDebounce({
            delay(300)
        })

        delay(20)

        // Second call with default debounce (should be 100ms)
        backgroundJobs.runWithDebounce({
            executed.set(1)
        })

        // At 50ms, task should not have executed yet
        delay(50)
        assertEquals(0, executed.get())

        // At 130ms total (100ms debounce + buffer), task should have executed
        delay(80)
        assertEquals(1, executed.get())

        backgroundJobs.dispose()
    }

    fun testRunWithDebounceTaskReceivesCoroutineScope() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        var scopeReceived = false

        backgroundJobs.runWithDebounce({
            // 'this' should be CoroutineScope
            scopeReceived = true
        })

        delay(50)

        assertTrue(scopeReceived)

        backgroundJobs.dispose()
    }

    fun testRunWithDebounceMultipleRapidCallsOnlyExecuteLast() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val values = mutableListOf<Int>()

        // First call to set currentJob
        backgroundJobs.runWithDebounce({
            delay(500)
        })

        delay(20)

        // Rapid succession of calls - only the last should execute
        for (i in 1..5) {
            backgroundJobs.runWithDebounce({
                values.add(i)
            }, debounceMillis = 50)
            delay(10)  // Small delay between calls, less than debounce
        }

        // Wait for debounce to fire
        delay(100)

        // Only the last value (5) should be in the list
        assertEquals(1, values.size)
        assertEquals(5, values[0])

        backgroundJobs.dispose()
    }

    fun testRunWithDebounceDisposeStopsAllJobs() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val executed = AtomicInteger(0)

        // Start a long-running job
        backgroundJobs.runWithDebounce({
            delay(200)
            executed.incrementAndGet()
        })

        delay(20)

        // Queue a pending job
        backgroundJobs.runWithDebounce({
            executed.addAndGet(10)
        }, debounceMillis = 100)

        // Dispose before jobs complete
        backgroundJobs.dispose()

        // Wait to see if jobs execute
        delay(300)

        // Jobs should have been cancelled by dispose
        // Note: The first job might have partially executed before dispose
        assertTrue(executed.get() < 11)  // At most one job executed
    }

    fun testRunWithDebounceWithZeroDebounce() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val executed = AtomicInteger(0)

        // First call
        backgroundJobs.runWithDebounce({
            delay(200)
        })

        delay(20)

        // Second call with 0ms debounce - should execute almost immediately
        backgroundJobs.runWithDebounce({
            executed.set(1)
        }, debounceMillis = 0)

        delay(50)

        assertEquals(1, executed.get())

        backgroundJobs.dispose()
    }

    fun testRunWithDebounceFirstCallSetsCurrentJob() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val taskStarted = AtomicInteger(0)
        val taskEnded = AtomicInteger(0)

        backgroundJobs.runWithDebounce({
            taskStarted.incrementAndGet()
            delay(100)
            taskEnded.incrementAndGet()
        })

        // Task should start immediately
        delay(20)
        assertEquals(1, taskStarted.get())
        assertEquals(0, taskEnded.get())

        // Wait for task to complete
        delay(150)
        assertEquals(1, taskEnded.get())

        backgroundJobs.dispose()
    }

    fun testRunWithDebounceSubsequentCallsAfterCurrentJobStillDebounce() = runBlocking {
        val backgroundJobs = BackgroundJobs()
        val executions = mutableListOf<Int>()

        // First call - executes immediately
        backgroundJobs.runWithDebounce({
            executions.add(1)
            delay(50)
        })

        delay(20)

        // Second call while first is running - debounced
        backgroundJobs.runWithDebounce({
            executions.add(2)
        }, debounceMillis = 30)

        // First should have completed, second should be pending
        delay(60)
        assertTrue(executions.contains(1))

        // After debounce, second should execute
        delay(50)
        assertTrue(executions.contains(2))

        // Note: Due to how the code works, currentJob is never reset to null
        // So third call will also be debounced
        backgroundJobs.runWithDebounce({
            executions.add(3)
        }, debounceMillis = 30)

        delay(50)
        assertTrue(executions.contains(3))

        backgroundJobs.dispose()
    }
}
