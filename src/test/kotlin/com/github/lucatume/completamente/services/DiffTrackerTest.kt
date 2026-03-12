package com.github.lucatume.completamente.services

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.completion.buildFimPrompt
import com.github.lucatume.completamente.completion.FimRequest
import com.intellij.openapi.editor.impl.DocumentImpl

class DiffTrackerTest : BaseCompletionTest() {

    private fun pendingEdit(
        filePath: String = "test.kt",
        content: String = "content",
        startLine: Int = 0,
        endLine: Int = 0,
        lastEditTime: Long = 0
    ): PendingEdit = PendingEdit(filePath, DocumentImpl(content), content, startLine, endLine, lastEditTime)

    // --- stripDummyIdentifier tests ---

    fun testStripDummyIdentifierRemovesPaddedVersion() {
        val text = "public function mIntellijIdeaRulezzz "
        assertEquals("public function m", stripDummyIdentifier(text))
    }

    fun testStripDummyIdentifierRemovesTrimmedVersion() {
        val text = "public function mIntellijIdeaRulezzz"
        assertEquals("public function m", stripDummyIdentifier(text))
    }

    fun testStripDummyIdentifierLeavesCleanTextUnchanged() {
        val text = "public function makeWith"
        assertEquals("public function makeWith", stripDummyIdentifier(text))
    }

    fun testStripDummyIdentifierHandlesMultipleOccurrences() {
        val text = "line1 IntellijIdeaRulezzz \nline2 IntellijIdeaRulezzz"
        assertEquals("line1 \nline2 ", stripDummyIdentifier(text))
    }

    fun testStripDummyIdentifierHandlesEmptyString() {
        assertEquals("", stripDummyIdentifier(""))
    }

    // --- shouldCoalesce tests ---

    fun testShouldCoalesceNearbyEditWithinSpan() {
        val pending = pendingEdit(startLine = 10, endLine = 12)
        assertTrue(shouldCoalesce(pending, 15, COALESCE_LINE_SPAN))
    }

    fun testShouldCoalesceEditAtExactBoundary() {
        val pending = pendingEdit(startLine = 10, endLine = 10)
        assertTrue(shouldCoalesce(pending, 18, COALESCE_LINE_SPAN))
    }

    fun testShouldCoalesceEditBeyondSpan() {
        val pending = pendingEdit(startLine = 10, endLine = 12)
        assertFalse(shouldCoalesce(pending, 25, COALESCE_LINE_SPAN))
    }

    fun testShouldCoalesceEditBeforeStart() {
        val pending = pendingEdit(startLine = 10, endLine = 12)
        assertTrue(shouldCoalesce(pending, 5, COALESCE_LINE_SPAN))
    }

    fun testShouldCoalesceEditFarBeforeStart() {
        val pending = pendingEdit(startLine = 10, endLine = 12)
        assertFalse(shouldCoalesce(pending, 1, COALESCE_LINE_SPAN))
    }

    fun testShouldCoalesceNearEndLine() {
        val pending = pendingEdit(startLine = 5, endLine = 20)
        assertTrue(shouldCoalesce(pending, 25, COALESCE_LINE_SPAN))
    }

    fun testShouldCoalesceEditOnExactStartLine() {
        val pending = pendingEdit(startLine = 10, endLine = 12)
        assertTrue(shouldCoalesce(pending, 10, COALESCE_LINE_SPAN))
    }

    fun testShouldCoalesceEditOnExactEndLine() {
        val pending = pendingEdit(startLine = 10, endLine = 12)
        assertTrue(shouldCoalesce(pending, 12, COALESCE_LINE_SPAN))
    }

    // --- computeDiffEntries tests ---

    fun testComputeDiffEntriesSingleLineChange() {
        val diffs = listOf(
            StoredDiff(
                filePath = "test.kt",
                timestamp = 1000,
                beforeSnapshot = "line0\nline1\nline2",
                afterSnapshot = "line0\nmodified\nline2",
                startLine = 1,
                endLine = 1
            )
        )
        val entries = computeDiffEntries(diffs)
        assertEquals(1, entries.size)
        assertEquals("test.kt", entries[0].filePath)
        assertEquals("line1", entries[0].original)
        assertEquals("modified", entries[0].updated)
    }

    fun testComputeDiffEntriesMultiLineChange() {
        val diffs = listOf(
            StoredDiff(
                filePath = "test.kt",
                timestamp = 1000,
                beforeSnapshot = "line0\nline1\nline2\nline3",
                afterSnapshot = "line0\nnewA\nnewB\nline3",
                startLine = 1,
                endLine = 2
            )
        )
        val entries = computeDiffEntries(diffs)
        assertEquals(1, entries.size)
        assertEquals("line1\nline2", entries[0].original)
        assertEquals("newA\nnewB", entries[0].updated)
    }

    fun testComputeDiffEntriesInsertion() {
        val diffs = listOf(
            StoredDiff(
                filePath = "test.kt",
                timestamp = 1000,
                beforeSnapshot = "line0\nline2",
                afterSnapshot = "line0\nline1\nline2",
                startLine = 1,
                endLine = 1
            )
        )
        val entries = computeDiffEntries(diffs)
        assertEquals(1, entries.size)
        // Pure insertion: bottom matching aligns "line2", so only "line1" is the new part
        assertEquals("", entries[0].original)
        assertEquals("line1", entries[0].updated)
    }

    fun testComputeDiffEntriesDeletion() {
        val diffs = listOf(
            StoredDiff(
                filePath = "test.kt",
                timestamp = 1000,
                beforeSnapshot = "line0\nline1\nline2",
                afterSnapshot = "line0\nline2",
                startLine = 1,
                endLine = 1
            )
        )
        val entries = computeDiffEntries(diffs)
        assertEquals(1, entries.size)
        assertEquals("line1", entries[0].original)
        assertEquals("", entries[0].updated)
    }

    fun testComputeDiffEntriesEmptyDiff() {
        val diffs = listOf(
            StoredDiff(
                filePath = "test.kt",
                timestamp = 1000,
                beforeSnapshot = "line0\nline1",
                afterSnapshot = "line0\nline1",
                startLine = 0,
                endLine = 0
            )
        )
        val entries = computeDiffEntries(diffs)
        assertEquals(0, entries.size)
    }

    fun testComputeDiffEntriesTrailingEmptyLineDiffIsFiltered() {
        // When before/after differ only by a trailing empty line, both original
        // and updated resolve to blank strings and should be filtered out.
        val diffs = listOf(
            StoredDiff(
                filePath = "test.kt",
                timestamp = 1000,
                beforeSnapshot = "line0\nline1\n",
                afterSnapshot = "line0\nline1",
                startLine = 0,
                endLine = 0
            )
        )
        val entries = computeDiffEntries(diffs)
        assertEquals(0, entries.size)
    }

    fun testComputeDiffEntriesLeadingEmptyLineDiffIsFiltered() {
        val diffs = listOf(
            StoredDiff(
                filePath = "test.kt",
                timestamp = 1000,
                beforeSnapshot = "\nline0\nline1",
                afterSnapshot = "line0\nline1",
                startLine = 0,
                endLine = 0
            )
        )
        val entries = computeDiffEntries(diffs)
        // The original is just an empty line "" and updated is empty — both blank, filtered.
        assertEquals(0, entries.size)
    }

    fun testComputeDiffEntriesWhitespaceOnlyDiffIsFiltered() {
        val diffs = listOf(
            StoredDiff(
                filePath = "test.kt",
                timestamp = 1000,
                beforeSnapshot = "line0\n  \nline1",
                afterSnapshot = "line0\nline1",
                startLine = 1,
                endLine = 1
            )
        )
        val entries = computeDiffEntries(diffs)
        assertEquals(0, entries.size)
    }

    fun testComputeDiffEntriesMultipleDiffs() {
        val diffs = listOf(
            StoredDiff(
                filePath = "a.kt",
                timestamp = 1000,
                beforeSnapshot = "hello",
                afterSnapshot = "world",
                startLine = 0,
                endLine = 0
            ),
            StoredDiff(
                filePath = "b.kt",
                timestamp = 2000,
                beforeSnapshot = "foo\nbar",
                afterSnapshot = "foo\nbaz",
                startLine = 1,
                endLine = 1
            )
        )
        val entries = computeDiffEntries(diffs)
        assertEquals(2, entries.size)
        assertEquals("a.kt", entries[0].filePath)
        assertEquals("hello", entries[0].original)
        assertEquals("world", entries[0].updated)
        assertEquals("b.kt", entries[1].filePath)
        assertEquals("bar", entries[1].original)
        assertEquals("baz", entries[1].updated)
    }

    // --- buildFimPrompt with recentDiffs tests ---

    fun testBuildFimPromptWithNoDiffs() {
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            recentDiffs = emptyList()
        )
        val prompt = buildFimPrompt(request)
        assertFalse(prompt.contains(".diff"))
        assertTrue(prompt.contains("<|file_sep|>original/test.kt"))
        assertTrue(prompt.contains("<|file_sep|>current/test.kt"))
        assertTrue(prompt.contains("<|file_sep|>updated/test.kt"))
    }

    fun testBuildFimPromptWithDiffs() {
        val diffs = listOf(
            DiffEntry(
                filePath = "other.kt",
                original = "val result = a + b",
                updated = "val total = a + b"
            )
        )
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            recentDiffs = diffs
        )
        val prompt = buildFimPrompt(request)
        assertTrue(prompt.contains("<|file_sep|>other.kt.diff"))
        assertTrue(prompt.contains("original:\nval result = a + b"))
        assertTrue(prompt.contains("updated:\nval total = a + b"))
    }

    fun testBuildFimPromptDiffsSectionComesBeforeOriginal() {
        val diffs = listOf(
            DiffEntry(filePath = "a.kt", original = "old", updated = "new")
        )
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            recentDiffs = diffs
        )
        val prompt = buildFimPrompt(request)
        val diffIndex = prompt.indexOf("<|file_sep|>a.kt.diff")
        val originalIndex = prompt.indexOf("<|file_sep|>original/test.kt")
        assertTrue("Diffs should appear before original section", diffIndex < originalIndex)
    }

    fun testBuildFimPromptMultipleDiffs() {
        val diffs = listOf(
            DiffEntry(filePath = "a.kt", original = "oldA", updated = "newA"),
            DiffEntry(filePath = "b.kt", original = "oldB", updated = "newB")
        )
        val request = FimRequest(
            filePath = "test.kt",
            currentContent = "content",
            originalContent = "original",
            cursorLine = 0,
            windowedContent = "content",
            windowStartLine = 0,
            recentDiffs = diffs
        )
        val prompt = buildFimPrompt(request)
        assertTrue(prompt.contains("<|file_sep|>a.kt.diff"))
        assertTrue(prompt.contains("<|file_sep|>b.kt.diff"))
        val aIndex = prompt.indexOf("<|file_sep|>a.kt.diff")
        val bIndex = prompt.indexOf("<|file_sep|>b.kt.diff")
        assertTrue("First diff should appear before second", aIndex < bIndex)
    }

    // --- finalizePendingEdit tests ---

    fun testFinalizePendingEditCapturesDocumentChanges() {
        val doc = DocumentImpl("line0\nline1\nline2")
        val pending = PendingEdit("test.kt", doc, doc.immutableCharSequence, startLine = 1, endLine = 1, lastEditTime = 0)
        val diffTracker = project.getService(DiffTracker::class.java)

        // Simulate the pending edit being tracked
        val pendingEditsField = DiffTracker::class.java.getDeclaredField("pendingEdits")
        pendingEditsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pendingEdits = pendingEditsField.get(diffTracker) as MutableMap<String, PendingEdit>
        pendingEdits["test.kt"] = pending

        // Mutate the document (simulates user typing)
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            doc.setText("line0\nmodified\nline2")
        }

        diffTracker.finalizePendingEdit("test.kt")

        val diffs = diffTracker.getRecentDiffs()
        assertEquals(1, diffs.size)
        assertEquals("test.kt", diffs[0].filePath)
        assertEquals("line1", diffs[0].original)
        assertEquals("modified", diffs[0].updated)
    }

    fun testFinalizePendingEditNoChangeProducesNoDiff() {
        val doc = DocumentImpl("line0\nline1")
        val pending = PendingEdit("test.kt", doc, doc.immutableCharSequence, startLine = 0, endLine = 0, lastEditTime = 0)
        val diffTracker = project.getService(DiffTracker::class.java)

        val diffsBefore = diffTracker.getRecentDiffs().size

        val pendingEditsField = DiffTracker::class.java.getDeclaredField("pendingEdits")
        pendingEditsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pendingEdits = pendingEditsField.get(diffTracker) as MutableMap<String, PendingEdit>
        pendingEdits["test.kt"] = pending

        diffTracker.finalizePendingEdit("test.kt")

        val diffsAfter = diffTracker.getRecentDiffs().size
        assertEquals(diffsBefore, diffsAfter)
    }

    fun testFinalizePendingEditForUnknownPathIsNoOp() {
        val diffTracker = project.getService(DiffTracker::class.java)
        diffTracker.finalizePendingEdit("nonexistent.kt")
        val diffs = diffTracker.getRecentDiffs()
        assertEquals(0, diffs.size)
    }

    // --- snapshotOriginalContent / getOriginalContent tests ---

    fun testSnapshotOriginalContentStoresOnFirstCall() {
        val diffTracker = project.getService(DiffTracker::class.java)
        diffTracker.snapshotOriginalContent("snapshot_first.kt", "original content")
        assertEquals("original content", diffTracker.getOriginalContent("snapshot_first.kt"))
    }

    fun testSnapshotOriginalContentDoesNotOverwriteOnSubsequentCalls() {
        val diffTracker = project.getService(DiffTracker::class.java)
        diffTracker.snapshotOriginalContent("snapshot_overwrite.kt", "first version")
        diffTracker.snapshotOriginalContent("snapshot_overwrite.kt", "second version")
        assertEquals("first version", diffTracker.getOriginalContent("snapshot_overwrite.kt"))
    }

    fun testGetOriginalContentReturnsEmptyStringWhenNoSnapshot() {
        val diffTracker = project.getService(DiffTracker::class.java)
        assertEquals("", diffTracker.getOriginalContent("nonexistent.kt"))
    }

    fun testGetOriginalContentReturnsStoredContentAfterSnapshot() {
        val diffTracker = project.getService(DiffTracker::class.java)
        val content = "line1\nline2\nline3"
        diffTracker.snapshotOriginalContent("snapshot_retrieve.kt", content)
        assertEquals(content, diffTracker.getOriginalContent("snapshot_retrieve.kt"))
    }

    fun testDisposesClearsOriginalSnapshots() {
        val diffTracker = project.getService(DiffTracker::class.java)
        diffTracker.snapshotOriginalContent("snapshot_dispose.kt", "content")
        diffTracker.dispose()
        assertEquals("", diffTracker.getOriginalContent("snapshot_dispose.kt"))
    }

    // --- DocumentListener integration tests ---

    fun testEditingUntrackedDocumentProducesNoDiffs() {
        // configureByText creates a file backed by a LightVirtualFile which
        // won't resolve via LocalFileSystem. Edits to such documents should
        // not produce diffs or crash.
        myFixture.configureByText("test.txt", "hello world")
        val diffTracker = project.getService(DiffTracker::class.java)
        val doc = myFixture.editor.document

        val diffsBefore = diffTracker.getRecentDiffs().size

        // Perform a real document edit
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            doc.setText("hello changed")
        }

        val diffsAfter = diffTracker.getRecentDiffs().size
        assertEquals(diffsBefore, diffsAfter)
    }

}
