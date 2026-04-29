package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest

class WalkthroughParserTest : BaseCompletionTest() {

    // -- happy paths --

    fun testParseSingleStepWithNarration() {
        val raw = """
            <Walkthrough>
              <Step id="a" file="src/Foo.kt" range="12:5-19:1">
                <Narration>This is the entry point.</Narration>
              </Step>
            </Walkthrough>
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue("expected success, got: $result", result.isSuccess)
        val tree = result.getOrThrow()!!
        assertEquals("a", tree.root.id)
        assertNull("root has no parent", tree.root.parentId)
        assertEquals("src/Foo.kt", tree.root.range.file)
        // 1-indexed wire format → 0-indexed internal
        assertEquals(11, tree.root.range.startLine)
        assertEquals(4, tree.root.range.startCol)
        assertEquals(18, tree.root.range.endLine)
        assertEquals(0, tree.root.range.endCol)
        assertEquals("This is the entry point.", tree.root.narration)
        assertEquals(0, tree.root.children.size)
    }

    fun testParseThreeStepsLinearChain() {
        val raw = """
            <Walkthrough>
              <Step id="1" file="a.kt" range="1:1-1:2"><Narration>first</Narration></Step>
              <Step id="2" file="b.kt" range="2:1-2:2"><Narration>second</Narration></Step>
              <Step id="3" file="c.kt" range="3:1-3:2"><Narration>third</Narration></Step>
            </Walkthrough>
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isSuccess)
        val tree = result.getOrThrow()!!
        assertEquals("1", tree.root.id)
        assertNull(tree.root.parentId)
        assertEquals(1, tree.root.children.size)
        assertEquals("2", tree.root.children[0].id)
        assertEquals("1", tree.root.children[0].parentId)
        assertEquals(1, tree.root.children[0].children.size)
        assertEquals("3", tree.root.children[0].children[0].id)
        assertEquals("2", tree.root.children[0].children[0].parentId)
        assertEquals(0, tree.root.children[0].children[0].children.size)
    }

    fun testParseSelfClosingStepHasNullNarration() {
        val raw = """
            <Walkthrough>
              <Step id="x" file="src/Foo.kt" range="63:1-63:31"/>
            </Walkthrough>
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow()!!.root.narration)
    }

    fun testParseProseAroundWalkthroughBlockIsDiscarded() {
        val raw = """
            Sure thing! Here's the walkthrough:

            <Walkthrough>
              <Step file="x.kt" range="1:1-1:2"><Narration>only step</Narration></Step>
            </Walkthrough>

            Let me know if you'd like to refine it.
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue("expected success, got: $result", result.isSuccess)
        assertEquals("only step", result.getOrThrow()!!.root.narration)
    }

    fun testParseAssignsSequentialIdsWhenAbsent() {
        val raw = """
            <Walkthrough>
              <Step file="a.kt" range="1:1-1:2"/>
              <Step file="b.kt" range="2:1-2:2"/>
            </Walkthrough>
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isSuccess)
        val tree = result.getOrThrow()!!
        assertEquals("1", tree.root.id)
        assertEquals("2", tree.root.children[0].id)
    }

    // -- entity decoding --

    fun testParseDecodesXmlEntitiesInAttributesAndNarration() {
        val raw = """
            <Walkthrough>
              <Step file="a&amp;b/c.kt" range="1:1-1:2"><Narration>uses &lt;Foo&gt; &amp; &quot;Bar&quot;</Narration></Step>
            </Walkthrough>
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isSuccess)
        val step = result.getOrThrow()!!.root
        assertEquals("a&b/c.kt", step.range.file)
        assertEquals("""uses <Foo> & "Bar"""", step.narration)
    }

    // -- empty walkthrough is legal --

    fun testParseEmptyWalkthroughBlockSucceedsWithNullRoot() {
        // A successfully-parsed but empty walkthrough returns a Walkthrough with a sentinel
        // empty list — the action then surfaces the "no steps" notification. The parser does
        // NOT fail on `<Walkthrough></Walkthrough>` because that's a valid (if unhelpful)
        // shape; the rule "at least one step" is enforced at the action level.
        val raw = "<Walkthrough></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue("Empty walkthrough is not a parser error: $result", result.isSuccess)
        // Sentinel: an empty Walkthrough has no root step; the result wraps it as null.
        // getOrNull() collapses success(null) and failure to null, so disambiguate via getOrThrow()
        assertNull("Empty walkthrough has no root", result.getOrThrow())
    }

    // -- failures --

    fun testParseMissingClosingTagFails() {
        val raw = "<Walkthrough><Step file=\"x.kt\" range=\"1:1-1:2\"/>"
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message ?: ""
        assertTrue("expected close-tag error, got: $msg", msg.contains("Walkthrough"))
    }

    fun testParseMissingRangeAttributeFails() {
        val raw = "<Walkthrough><Step file=\"x.kt\"/></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message ?: ""
        assertTrue("expected range error, got: $msg", msg.contains("range"))
    }

    fun testParseMissingFileAttributeFails() {
        val raw = "<Walkthrough><Step range=\"1:1-1:2\"/></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message ?: ""
        assertTrue("expected file error, got: $msg", msg.contains("file"))
    }

    fun testParseMalformedRangeFails() {
        val raw = "<Walkthrough><Step file=\"x.kt\" range=\"12-18\"/></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isFailure)
    }

    fun testParseRangeWithNonNumericGarbageFails() {
        val raw = "<Walkthrough><Step file=\"x.kt\" range=\"a:b-c:d\"/></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue("Non-numeric range must fail: $result", result.isFailure)
    }

    fun testParseSelfClosingStepWithSpaceBeforeSlash() {
        // Common LLM emission: a space before the `/>`. Earlier the unclosed-step detector
        // misclassified this as an open tag because the negative-lookbehind didn't see `/`.
        val raw = "<Walkthrough><Step file=\"x.kt\" range=\"1:1-1:2\" /></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue("expected success for self-closing with space, got: $result", result.isSuccess)
        assertEquals("x.kt", result.getOrThrow()!!.root.range.file)
    }

    fun testParseDuplicateExplicitIdsFails() {
        val raw = """
            <Walkthrough>
              <Step id="a" file="x.kt" range="1:1-1:2"/>
              <Step id="a" file="y.kt" range="2:1-2:2"/>
            </Walkthrough>
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue("Duplicate explicit ids must fail: $result", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Duplicate"))
    }

    fun testParseSyntheticIdsSkipExplicitIds() {
        // Step 0 has explicit id="2"; step 1 has no id. A naive index-based synthetic would
        // assign "2" to step 1, colliding with step 0. Synthetic generator must skip occupied
        // ids.
        val raw = """
            <Walkthrough>
              <Step id="2" file="x.kt" range="1:1-1:2"/>
              <Step file="y.kt" range="2:1-2:2"/>
            </Walkthrough>
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue("Expected success, got: $result", result.isSuccess)
        val tree = result.getOrThrow()!!
        assertEquals("2", tree.root.id)
        // Synthetic must skip "2" and pick "1" (or "3" — anything but "2").
        assertFalse("Synthetic id must not collide with explicit id", tree.root.children[0].id == "2")
    }

    fun testParseFailuresAreTypedParseFailure() {
        val result = WalkthroughParser.parse("not a walkthrough")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()!!
        assertTrue(
            "Parser failures must be typed as ParseFailure for downstream pattern-matching, got: ${ex::class.simpleName}",
            ex is ParseFailure
        )
    }

    fun testParseNonPositiveNumericValueFails() {
        val raw = "<Walkthrough><Step file=\"x.kt\" range=\"0:1-2:3\"/></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue("0 is not a valid 1-indexed value: $result", result.isFailure)
    }

    fun testParseNoWalkthroughBlockFails() {
        val raw = "Just some prose. No walkthrough here."
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isFailure)
    }

    fun testParseUnclosedStepTagFails() {
        // A bare <Step ...> with no </Step> close should NOT silently produce zero steps —
        // surface a typed failure instead.
        val raw = "<Walkthrough><Step file=\"x.kt\" range=\"1:1-1:2\"></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue("Expected failure for missing </Step>: $result", result.isFailure)
        val msg = result.exceptionOrNull()!!.message ?: ""
        assertTrue("expected close-step error, got: $msg", msg.contains("</Step>") || msg.contains("close"))
    }

    fun testParseAcceptsSingleQuotedAttributes() {
        // A "lenient" parser shouldn't fail just because the model picked single quotes.
        val raw = "<Walkthrough><Step file='x.kt' range='1:1-1:2'/></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue("Expected success for single-quoted attrs: $result", result.isSuccess)
        assertEquals("x.kt", result.getOrThrow()!!.root.range.file)
    }

    fun testParseEmptyNarrationIsTreatedAsNull() {
        // <Narration></Narration> and <Narration>   </Narration> render an empty panel; cleaner
        // to treat them as "no narration".
        val raw = """
            <Walkthrough>
              <Step file="x.kt" range="1:1-1:2"><Narration></Narration></Step>
              <Step file="y.kt" range="2:1-2:2"><Narration>   </Narration></Step>
            </Walkthrough>
        """.trimIndent()
        val result = WalkthroughParser.parse(raw)
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow()!!.root.narration)
        assertNull(result.getOrThrow()!!.root.children[0].narration)
    }

    fun testParseNegativeNumericValueFails() {
        // \d+ won't match `-1` so this falls into the "malformed range" path; either way it must
        // fail rather than be silently accepted.
        val raw = "<Walkthrough><Step file=\"x.kt\" range=\"-1:1-2:3\"/></Walkthrough>"
        val result = WalkthroughParser.parse(raw)
        assertTrue("Negative line value must fail: $result", result.isFailure)
    }
}
