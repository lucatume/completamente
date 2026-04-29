package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest

/**
 * Pure tests for [WalkthroughCacheLogic] — insertion with cap, stale-by-edit eviction, and
 * referenced-file collection over the walkthrough tree.
 */
class WalkthroughCacheLogicTest : BaseCompletionTest() {

    // -- insert / cap --

    fun testInsertPrependsAndKeepsUnderCap() {
        val existing = listOf(entry("a", createdAt = 1000), entry("b", createdAt = 2000))
        val result = WalkthroughCacheLogic.insert(existing, entry("c", createdAt = 3000), cap = 5)
        assertEquals(listOf("c", "a", "b"), result.map { it.id })
    }

    fun testInsertEvictsOldestAtCap() {
        val existing = (1..5).map { entry("e$it", createdAt = it.toLong() * 1000) }
        // existing has e1 (oldest) … e5 (newest at 5000). Insert one with createdAt=6000.
        val result = WalkthroughCacheLogic.insert(existing, entry("new", createdAt = 6000), cap = 5)
        assertEquals(5, result.size)
        assertEquals("new", result.first().id)
        // e1 (oldest by createdAt) is gone; e5 (newest) survives.
        assertFalse("oldest-by-createdAt should be evicted", result.any { it.id == "e1" })
        assertTrue("newest existing should survive", result.any { it.id == "e5" })
    }

    fun testInsertWhenExistingNotInCreatedAtOrderStillEvictsTrueOldest() {
        // Defensive: callers may pass an arbitrarily-ordered list. Eviction picks the oldest by
        // createdAt, not by list position.
        val existing = listOf(
            entry("middle", createdAt = 3000),
            entry("oldest", createdAt = 1000),
            entry("newer", createdAt = 4000),
            entry("newest", createdAt = 5000),
            entry("older", createdAt = 2000)
        )
        val result = WalkthroughCacheLogic.insert(existing, entry("new", createdAt = 6000), cap = 5)
        assertEquals(5, result.size)
        assertFalse(result.any { it.id == "oldest" })
        assertTrue(result.any { it.id == "newest" })
    }

    fun testInsertUnderCapKeepsAll() {
        val existing = listOf(entry("a", createdAt = 1000))
        val result = WalkthroughCacheLogic.insert(existing, entry("b", createdAt = 2000), cap = 5)
        assertEquals(2, result.size)
    }

    // -- stale eviction --

    fun testEvictStaleKeepsEntriesWithMatchingStamps() {
        val e = entry("a", fileStamps = mapOf("foo.kt" to 100L, "bar.kt" to 200L))
        val current = mapOf("foo.kt" to 100L, "bar.kt" to 200L)
        val result = WalkthroughCacheLogic.evictStale(listOf(e)) { current[it] }
        assertEquals(listOf("a"), result.map { it.id })
    }

    fun testEvictStaleDropsEntryWithChangedStamp() {
        val e = entry("a", fileStamps = mapOf("foo.kt" to 100L))
        val current = mapOf("foo.kt" to 101L)
        val result = WalkthroughCacheLogic.evictStale(listOf(e)) { current[it] }
        assertTrue(result.isEmpty())
    }

    fun testEvictStaleDropsEntryWithMissingFile() {
        val e = entry("a", fileStamps = mapOf("foo.kt" to 100L))
        val result = WalkthroughCacheLogic.evictStale(listOf(e)) { null }
        assertTrue(result.isEmpty())
    }

    fun testEvictStalePartialDrop() {
        val keep = entry("keep", fileStamps = mapOf("a.kt" to 1L))
        val drop = entry("drop", fileStamps = mapOf("b.kt" to 2L))
        val current = mapOf("a.kt" to 1L, "b.kt" to 999L)
        val result = WalkthroughCacheLogic.evictStale(listOf(keep, drop)) { current[it] }
        assertEquals(listOf("keep"), result.map { it.id })
    }

    fun testEvictStaleEntryWithNoFilesIsKept() {
        // Defensive: an entry that somehow has no file stamps shouldn't be evicted as a side
        // effect of "no fingerprints to compare".
        val e = entry("a", fileStamps = emptyMap())
        val result = WalkthroughCacheLogic.evictStale(listOf(e)) { null }
        assertEquals(listOf("a"), result.map { it.id })
    }

    // -- referenced files --

    fun testCollectReferencedFilesSingleStep() {
        val w = Walkthrough(step("root", file = "a.kt"))
        assertEquals(setOf("a.kt"), WalkthroughCacheLogic.collectReferencedFiles(w))
    }

    fun testCollectReferencedFilesLinearChain() {
        val leaf = step("leaf", file = "c.kt")
        val mid = step("mid", file = "b.kt", children = listOf(leaf))
        val root = step("root", file = "a.kt", children = listOf(mid))
        val w = Walkthrough(root)
        assertEquals(setOf("a.kt", "b.kt", "c.kt"), WalkthroughCacheLogic.collectReferencedFiles(w))
    }

    fun testCollectReferencedFilesDeduplicates() {
        val leaf = step("leaf", file = "a.kt")
        val mid = step("mid", file = "a.kt", children = listOf(leaf))
        val root = step("root", file = "a.kt", children = listOf(mid))
        assertEquals(setOf("a.kt"), WalkthroughCacheLogic.collectReferencedFiles(Walkthrough(root)))
    }

    fun testCollectReferencedFilesTreeWithBranches() {
        val l1 = step("l1", file = "left.kt")
        val l2 = step("l2", file = "right.kt")
        val root = step("root", file = "root.kt", children = listOf(l1, l2))
        assertEquals(
            setOf("root.kt", "left.kt", "right.kt"),
            WalkthroughCacheLogic.collectReferencedFiles(Walkthrough(root))
        )
    }

    // -- helpers --

    private fun entry(
        id: String,
        createdAt: Long = 0L,
        fileStamps: Map<String, Long> = emptyMap()
    ): EntryDTO = EntryDTO().also {
        it.id = id
        it.createdAtMillis = createdAt
        it.fileStamps = fileStamps.toMutableMap()
    }

    private fun step(
        id: String,
        file: String,
        children: List<WalkthroughStep> = emptyList()
    ): WalkthroughStep = WalkthroughStep(
        id = id,
        parentId = null,
        range = StepRange(file = file, startLine = 0, startCol = 0, endLine = 0, endCol = 1),
        narration = null,
        children = children
    )
}
