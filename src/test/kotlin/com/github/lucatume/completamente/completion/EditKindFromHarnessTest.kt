package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest
import kotlinx.serialization.json.*
import java.io.File

/**
 * Data-driven tests that parse the harness output file (edit-types-harness-output.jsonp)
 * and verify that [extractEdit] produces the correct [EditKind] for each entry.
 */
class EditKindFromHarnessTest : BaseCompletionTest() {

    private data class HarnessEntry(
        val label: String,
        val mode: String,
        val expectedEditKind: String,
        val currentContent: String,
        val response: String,
        val cursorLine: Int,
        val error: String?
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadEntries(): List<HarnessEntry> {
        val file = File("src/test/testData/completion/edit-types-harness-output.jsonp")
        assertTrue("Harness output file must exist: ${file.absolutePath}", file.exists())
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                HarnessEntry(
                    label = obj["label"]!!.jsonPrimitive.content,
                    mode = obj["mode"]!!.jsonPrimitive.content,
                    expectedEditKind = obj["expected_edit_kind"]!!.jsonPrimitive.content,
                    currentContent = obj["current_content"]!!.jsonPrimitive.content,
                    response = obj["response"]!!.jsonPrimitive.content,
                    cursorLine = obj["cursor_line"]!!.jsonPrimitive.int,
                    error = obj["error"]?.jsonPrimitive?.contentOrNull
                )
            }
    }

    private fun applyEdit(content: String, edit: EditRegion): String {
        return content.substring(0, edit.startOffset) + edit.newText + content.substring(edit.endOffset)
    }

    private fun editKindName(kind: EditKind): String = when (kind) {
        is EditKind.Inline -> "Inline"
        is EditKind.Jump -> "Jump"
        is EditKind.Suppress -> "Suppress"
    }

    private fun editRegionOf(kind: EditKind): EditRegion = when (kind) {
        is EditKind.Inline -> kind.editRegion
        is EditKind.Jump -> kind.editRegion
        is EditKind.Suppress -> error("Suppress has no editRegion")
    }

    private fun modelChangedContent(entry: HarnessEntry): Boolean {
        val trimmedResponse = entry.response.trimStart('\n').trimEnd()
        val trimmedCurrent = entry.currentContent.trimEnd()
        return trimmedResponse != trimmedCurrent
    }

    // --- Consistency: applying an Inline/Jump edit produces the model response ---

    fun testApplyingEditProducesModelResponse() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue

            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            if (result is EditKind.Suppress) continue

            val edit = editRegionOf(result)
            val applied = applyEdit(entry.currentContent, edit)
            val expectedContent = entry.response.trimStart('\n').trimEnd()

            if (applied.trimEnd() != expectedContent) {
                failures.add(
                    "[${entry.label}] Applied edit does not produce expected response.\n" +
                        "  Expected: ${expectedContent.take(120)}\n" +
                        "  Got:      ${applied.trimEnd().take(120)}"
                )
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n\n")}")
        }
    }

    // --- Suppress correctness: Suppress only when content is unchanged ---

    fun testSuppressOnlyWhenContentUnchanged() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue

            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            if (result !is EditKind.Suppress) continue

            if (modelChangedContent(entry)) {
                failures.add(
                    "[${entry.label}] extractEdit returned Suppress but response differs from current"
                )
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n")}")
        }
    }

    fun testUnchangedContentProducesSuppressNotInlineOrJump() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue
            if (modelChangedContent(entry)) continue

            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            if (result !is EditKind.Suppress) {
                failures.add(
                    "[${entry.label}] Response identical to current but got ${editKindName(result)} instead of Suppress"
                )
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n")}")
        }
    }

    // --- Kind classification for entries where model produced expected changes ---

    fun testExpectedInlineEntriesAreInline() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue
            if (entry.expectedEditKind != "Inline") continue
            if (!modelChangedContent(entry)) continue

            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            if (result !is EditKind.Inline) {
                failures.add("[${entry.label}] Expected Inline but got ${editKindName(result)}")
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n")}")
        }
    }

    fun testExpectedJumpEntriesAreJump() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue
            if (entry.expectedEditKind != "Jump") continue
            if (!modelChangedContent(entry)) continue

            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            if (result !is EditKind.Jump) {
                failures.add("[${entry.label}] Expected Jump but got ${editKindName(result)}")
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n")}")
        }
    }

    fun testExpectedSuppressEntriesWhereModelAgreesAreSuppress() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue
            if (entry.expectedEditKind != "Suppress") continue
            if (modelChangedContent(entry)) continue // model disagreed, skip

            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            if (result !is EditKind.Suppress) {
                failures.add("[${entry.label}] Expected Suppress but got ${editKindName(result)}")
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n")}")
        }
    }

    fun testExpectedMixedEntriesAreInlineOrJump() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue
            if (entry.expectedEditKind != "Inline+Jump") continue
            if (!modelChangedContent(entry)) continue

            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            if (result is EditKind.Suppress) {
                failures.add("[${entry.label}] Expected Inline or Jump but got Suppress")
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n")}")
        }
    }

    // --- Edit region validity ---

    fun testEditRegionOffsetsAreWithinBounds() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue

            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            if (result is EditKind.Suppress) continue

            val edit = editRegionOf(result)
            val contentLength = entry.currentContent.length

            if (edit.startOffset < 0) {
                failures.add("[${entry.label}] startOffset ${edit.startOffset} is negative")
            }
            if (edit.endOffset < edit.startOffset) {
                failures.add("[${entry.label}] endOffset ${edit.endOffset} < startOffset ${edit.startOffset}")
            }
            if (edit.endOffset > contentLength) {
                failures.add("[${entry.label}] endOffset ${edit.endOffset} > content length $contentLength")
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n")}")
        }
    }

    // --- FIM entries should match their expected edit kind ---

    fun testFimEntriesMatchExpectedKind() {
        val entries = loadEntries()
        val failures = mutableListOf<String>()

        for (entry in entries) {
            if (entry.error != null) continue
            if (entry.mode != "FIM") continue
            if (!modelChangedContent(entry)) continue
            val result = extractEdit(entry.currentContent, entry.response, entry.cursorLine)
            val actual = editKindName(result)
            if (actual != entry.expectedEditKind) {
                failures.add("[${entry.label}] FIM entry expected ${entry.expectedEditKind} but got $actual")
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} failure(s):\n${failures.joinToString("\n")}")
        }
    }

    // --- NEP entries should never have errors ---

    fun testNoHarnessErrors() {
        val entries = loadEntries()
        val errored = entries.filter { it.error != null }
        if (errored.isNotEmpty()) {
            fail(
                "${errored.size} entry(ies) had errors:\n" +
                    errored.joinToString("\n") { "[${it.label}] ${it.error}" }
            )
        }
    }
}
