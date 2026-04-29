package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest

/**
 * Tests for the pure navigation logic of [WalkthroughNavigator]. Has no IDE deps.
 */
class WalkthroughSessionLogicTest : BaseCompletionTest() {

    private fun step(id: String, file: String = "$id.kt", child: WalkthroughStep? = null): WalkthroughStep =
        WalkthroughStep(
            id = id,
            parentId = null,
            range = StepRange(file, 0, 0, 0, 1),
            narration = "step $id",
            children = if (child != null) listOf(child) else emptyList()
        )

    private fun chain(vararg ids: String): Walkthrough {
        require(ids.isNotEmpty())
        var node: WalkthroughStep? = null
        for (i in ids.indices.reversed()) {
            node = WalkthroughStep(
                id = ids[i],
                parentId = if (i == 0) null else ids[i - 1],
                range = StepRange("${ids[i]}.kt", 0, 0, 0, 1),
                narration = "step ${ids[i]}",
                children = if (node == null) emptyList() else listOf(node)
            )
        }
        return Walkthrough(node!!)
    }

    fun testSinglePathStartsAtRoot() {
        val nav = WalkthroughNavigator(chain("a"))
        assertEquals("a", nav.current.id)
        assertEquals(1, nav.totalReachable)
        assertEquals(1, nav.currentIndex)
        assertFalse(nav.canGoNext())
        assertFalse(nav.canGoPrev())
    }

    fun testGoNextWalksLinearChain() {
        val nav = WalkthroughNavigator(chain("a", "b", "c"))
        assertEquals("a", nav.current.id)
        assertEquals(3, nav.totalReachable)
        assertEquals(1, nav.currentIndex)
        assertTrue(nav.canGoNext())
        assertFalse(nav.canGoPrev())

        nav.goNext()
        assertEquals("b", nav.current.id)
        assertEquals(2, nav.currentIndex)
        assertTrue(nav.canGoNext())
        assertTrue(nav.canGoPrev())

        nav.goNext()
        assertEquals("c", nav.current.id)
        assertEquals(3, nav.currentIndex)
        assertFalse(nav.canGoNext())
        assertTrue(nav.canGoPrev())
    }

    fun testGoPrevWalksBack() {
        val nav = WalkthroughNavigator(chain("a", "b", "c"))
        nav.goNext(); nav.goNext()
        assertEquals("c", nav.current.id)
        nav.goPrev()
        assertEquals("b", nav.current.id)
        nav.goPrev()
        assertEquals("a", nav.current.id)
        assertFalse(nav.canGoPrev())
    }

    fun testGoFirstAndGoLast() {
        val nav = WalkthroughNavigator(chain("a", "b", "c", "d"))
        nav.goLast()
        assertEquals("d", nav.current.id)
        assertEquals(4, nav.currentIndex)
        nav.goFirst()
        assertEquals("a", nav.current.id)
        assertEquals(1, nav.currentIndex)
    }

    fun testGoNextOnLastStepIsNoop() {
        val nav = WalkthroughNavigator(chain("a"))
        nav.goNext()
        assertEquals("a", nav.current.id)
    }

    fun testGoPrevOnRootIsNoop() {
        val nav = WalkthroughNavigator(chain("a", "b"))
        nav.goPrev()
        assertEquals("a", nav.current.id)
    }

    // -- initial unresolvable cascade (tree-rebuild) --

    fun testRebuildDropsLeadingUnresolvableSteps() {
        // Original chain: a -> b -> c -> d.  a, b are unresolvable.  After rebuild: c -> d.
        val original = chain("a", "b", "c", "d")
        val resolvable: (WalkthroughStep) -> Boolean = { it.id == "c" || it.id == "d" }
        val rebuilt = WalkthroughNavigator.rebuildFromFirstResolvable(original, resolvable)
        assertNotNull(rebuilt)
        assertEquals("c", rebuilt!!.root.id)
        assertNull("New root must have parentId=null", rebuilt.root.parentId)
        assertEquals(1, rebuilt.root.children.size)
        assertEquals("d", rebuilt.root.children[0].id)
        // The dropped steps are gone from the tree entirely.
        assertEquals(2, walkSteps(rebuilt.root).size)
    }

    fun testRebuildAllUnresolvableReturnsNull() {
        val original = chain("a", "b", "c")
        val rebuilt = WalkthroughNavigator.rebuildFromFirstResolvable(original) { false }
        assertNull("All-unresolvable walkthrough should rebuild to null", rebuilt)
    }

    fun testRebuildAllResolvableReturnsOriginalShape() {
        val original = chain("a", "b", "c")
        val rebuilt = WalkthroughNavigator.rebuildFromFirstResolvable(original) { true }
        assertNotNull(rebuilt)
        assertEquals("a", rebuilt!!.root.id)
        assertEquals(3, walkSteps(rebuilt.root).size)
    }

    fun testRebuildPreservesTrailingUnresolvableSteps() {
        // a (unresolvable) -> b (resolvable) -> c (unresolvable) -> d (resolvable)
        // After rebuild: starts at b. The cascade of unresolvable trailing/middle steps is
        // handled by goNext at runtime — rebuild only handles the LEADING run.
        val original = chain("a", "b", "c", "d")
        val rebuilt = WalkthroughNavigator.rebuildFromFirstResolvable(original) { it.id == "b" || it.id == "d" }
        assertNotNull(rebuilt)
        assertEquals("b", rebuilt!!.root.id)
        assertEquals("c", rebuilt.root.children[0].id)
        assertEquals("d", rebuilt.root.children[0].children[0].id)
    }

    // -- runtime cascade (skip unresolvable on goNext) --

    fun testGoNextCascadesThroughUnresolvableSteps() {
        // chain: a -> b -> c -> d.  b and c are unresolvable.  goNext from a lands on d.
        val nav = WalkthroughNavigator(chain("a", "b", "c", "d")) { it.id == "a" || it.id == "d" }
        assertEquals("a", nav.current.id)
        nav.goNext()
        assertEquals("d", nav.current.id)
        assertEquals(listOf("b", "c"), nav.lastSkipped.map { it.id })
    }

    fun testGoNextWithNoResolvableNextLeavesCurrentInPlace() {
        // chain: a -> b -> c, b and c are unresolvable, no further resolvable step to land on.
        val nav = WalkthroughNavigator(chain("a", "b", "c")) { it.id == "a" }
        nav.goNext()
        assertEquals("a", nav.current.id)
        assertEquals(listOf("b", "c"), nav.lastSkipped.map { it.id })
        assertTrue(nav.lastNavReachedEnd)
    }

    fun testGoLastCascadesAndLandsOnLastResolvableStep() {
        // chain: a -> b -> c -> d.  c is unresolvable.  goLast from a lands on d.
        val nav = WalkthroughNavigator(chain("a", "b", "c", "d")) { it.id != "c" }
        nav.goLast()
        assertEquals("d", nav.current.id)
    }

    // -- dispose idempotency --

    fun testDisposeIsIdempotent() {
        val nav = WalkthroughNavigator(chain("a", "b"))
        var disposeCount = 0
        nav.onDispose { disposeCount++ }
        nav.dispose()
        nav.dispose()
        nav.dispose()
        assertEquals("dispose hook must run exactly once", 1, disposeCount)
        assertTrue(nav.isDisposed)
    }

    private fun walkSteps(root: WalkthroughStep): List<WalkthroughStep> {
        val out = mutableListOf<WalkthroughStep>()
        var node: WalkthroughStep? = root
        while (node != null) {
            out += node
            node = node.children.firstOrNull()
        }
        return out
    }
}
