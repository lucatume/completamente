package com.github.lucatume.completamente.walkthrough

/**
 * Pure navigation logic over a [Walkthrough] tree. No IDE deps — testable as pure stdlib.
 *
 * The navigator tracks the "current path" from the root to the active step. With the linear-
 * chain trees this iteration produces, the path is just a prefix; the data shape is tree-ready
 * so the future forking iteration can extend [goNext]/[goLast] without changing the caller.
 *
 * Resolvability is parameterized — the constructor takes a predicate that decides whether a
 * step can be displayed in the IDE. Unresolvable steps are silently skipped on `goNext` /
 * `goLast`; the runtime path lives in [WalkthroughSession] (the IDE-coupled wrapper).
 */
class WalkthroughNavigator(
    walkthrough: Walkthrough,
    private val isResolvable: (WalkthroughStep) -> Boolean = { true }
) {
    private val path: ArrayDeque<WalkthroughStep> = ArrayDeque<WalkthroughStep>().apply {
        addLast(walkthrough.root)
    }

    /** Steps skipped (unresolvable) by the most recent navigation operation. */
    var lastSkipped: List<WalkthroughStep> = emptyList()
        private set

    /**
     * True iff the most recent `goNext`/`goLast` ran out of resolvable successors and left
     * `current` unchanged. Callers can use this to decide whether to surface a "dead-end"
     * notification.
     */
    var lastNavReachedEnd: Boolean = false
        private set

    /** Current step (tail of the path). */
    val current: WalkthroughStep get() = path.last()

    /**
     * Total number of resolvable steps reachable from the root along the active branch — used
     * by the popup to render `step k/n`.
     */
    val totalReachable: Int
        get() {
            var node: WalkthroughStep? = path.first()
            var count = 0
            while (node != null) {
                if (isResolvable(node)) count++
                node = node.children.firstOrNull()
            }
            return count
        }

    /** 1-based index of the current step within the resolvable subset. */
    val currentIndex: Int
        get() {
            var node: WalkthroughStep? = path.first()
            var idx = 0
            while (node != null) {
                if (isResolvable(node)) idx++
                if (node === current) return idx
                node = node.children.firstOrNull()
            }
            // current is by construction in the tree; if we ever fall through, return the
            // computed count rather than zero.
            return idx
        }

    fun canGoNext(): Boolean {
        var node: WalkthroughStep? = current.children.firstOrNull()
        while (node != null) {
            if (isResolvable(node)) return true
            node = node.children.firstOrNull()
        }
        return false
    }

    fun canGoPrev(): Boolean = path.size > 1

    /** `<<` enablement — false on the root. Mirrors `<` in the linear-only iteration. */
    fun canGoFirst(): Boolean = path.size > 1

    /** `>>` enablement — false when no further resolvable step exists. Mirrors `>` in the linear-only iteration. */
    fun canGoLast(): Boolean = canGoNext()

    /**
     * Advance one step along the active branch, cascading silently through unresolvable steps
     * until landing on a resolvable one. If no further resolvable step exists, [current]
     * stays put and [lastNavReachedEnd] is set to true.
     */
    fun goNext() {
        val skipped = mutableListOf<WalkthroughStep>()
        var node: WalkthroughStep? = current.children.firstOrNull()
        while (node != null) {
            if (isResolvable(node)) {
                path.addLast(node)
                lastSkipped = skipped
                lastNavReachedEnd = false
                return
            }
            skipped += node
            node = node.children.firstOrNull()
        }
        lastSkipped = skipped
        lastNavReachedEnd = true
    }

    /**
     * Move one step back along the path. No-op when already at the root (path size == 1).
     * Going back never cascades — the path was built by past `goNext` calls so every step
     * along it is known to be resolvable.
     */
    fun goPrev() {
        if (path.size <= 1) {
            lastSkipped = emptyList()
            lastNavReachedEnd = false
            return
        }
        path.removeLast()
        lastSkipped = emptyList()
        lastNavReachedEnd = false
    }

    /** Truncate the path back to the root. */
    fun goFirst() {
        while (path.size > 1) path.removeLast()
        lastSkipped = emptyList()
        lastNavReachedEnd = false
    }

    /**
     * Walk forward through the active branch until no further resolvable step exists,
     * collecting any unresolvable steps that were skipped along the way.
     */
    fun goLast() {
        val skipped = mutableListOf<WalkthroughStep>()
        var node: WalkthroughStep? = current.children.firstOrNull()
        while (node != null) {
            if (isResolvable(node)) {
                path.addLast(node)
            } else {
                skipped += node
            }
            node = node.children.firstOrNull()
        }
        lastSkipped = skipped
        lastNavReachedEnd = true
    }

    // -- dispose --

    private val disposeHooks = mutableListOf<() -> Unit>()
    var isDisposed: Boolean = false
        private set

    fun onDispose(hook: () -> Unit) {
        if (isDisposed) {
            // Run immediately if the navigator is already disposed — caller registered too late.
            hook()
            return
        }
        disposeHooks += hook
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        // Run hooks in registration order; swallow individual exceptions so a buggy hook
        // doesn't strand later cleanups.
        disposeHooks.forEach { runCatching { it() } }
        disposeHooks.clear()
    }

    companion object {
        /**
         * Apply the spec's "first-step resolvability" rule: walk the linear chain from the
         * root, find the first resolvable step, and rebuild the tree so that step is the new
         * root with `parentId = null`. Earlier (skipped) steps are dropped entirely. Returns
         * `null` if no step is resolvable.
         *
         * In this iteration the tree is linear, so the rebuild is straightforward — descend
         * via `children.firstOrNull()` and `copy()` each surviving node. When forking lands,
         * this routine will need to recurse into all branches.
         */
        fun rebuildFromFirstResolvable(
            walkthrough: Walkthrough,
            isResolvable: (WalkthroughStep) -> Boolean
        ): Walkthrough? {
            var node: WalkthroughStep? = walkthrough.root
            while (node != null && !isResolvable(node)) {
                node = node.children.firstOrNull()
            }
            if (node == null) return null
            // Reconstruct the surviving spine with parentId fixed up. Walk to the end first
            // and rebuild from the tail so each parent embeds its already-built child.
            val survivors = mutableListOf<WalkthroughStep>()
            var n: WalkthroughStep? = node
            while (n != null) {
                survivors += n
                n = n.children.firstOrNull()
            }
            var child: WalkthroughStep? = null
            for (i in survivors.indices.reversed()) {
                val s = survivors[i]
                val parentId = if (i == 0) null else survivors[i - 1].id
                val children = if (child == null) emptyList() else listOf(child)
                child = s.copy(parentId = parentId, children = children)
            }
            return Walkthrough(child!!)
        }
    }
}
