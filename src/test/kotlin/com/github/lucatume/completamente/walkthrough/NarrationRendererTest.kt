package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest

/**
 * Pure tests for [NarrationRenderer.toHtml] — converts agent-emitted narration (mix of plain
 * text, our markdown subset, and a small allowlist of HTML tags from older agents) to HTML
 * suitable for JEditorPane.
 */
class NarrationRendererTest : BaseCompletionTest() {

    // -- escaping --

    fun testEscapesHtmlMetachars() {
        val out = NarrationRenderer.toHtml("a < b && c > d")
        assertContains("a &lt; b &amp;&amp; c &gt; d", out)
    }

    fun testPlainTextIsPreservedAfterEscape() {
        val out = NarrationRenderer.toHtml("Hello, world.")
        assertContains("Hello, world.", out)
    }

    fun testEmptyInputProducesEmptyOutput() {
        assertEquals("", NarrationRenderer.toHtml(""))
        assertEquals("", NarrationRenderer.toHtml("   \n  "))
    }

    // -- markdown subset --

    fun testInlineCodeMarkdown() {
        val out = NarrationRenderer.toHtml("call `initPlayground` here")
        assertContains("<code>initPlayground</code>", out)
    }

    fun testBoldMarkdown() {
        val out = NarrationRenderer.toHtml("**very** important")
        assertContains("<b>very</b>", out)
    }

    fun testItalicMarkdown() {
        val out = NarrationRenderer.toHtml("this is *emphasised* text")
        assertContains("<i>emphasised</i>", out)
    }

    fun testBoldDoesNotMatchAsItalic() {
        // ** … ** must not be reinterpreted as wrapped italic.
        val out = NarrationRenderer.toHtml("**bold**")
        assertContains("<b>bold</b>", out)
        assertFalse("must not produce <i>*bold*</i>", out.contains("<i>"))
    }

    fun testLinkMarkdown() {
        val out = NarrationRenderer.toHtml("see [docs](https://example.com/page)")
        assertContains("""<a href="https://example.com/page">docs</a>""", out)
    }

    fun testRejectsUnsafeLinkScheme() {
        // `javascript:` and other non-allowlisted schemes are passed through as escaped text.
        val out = NarrationRenderer.toHtml("[bad](javascript:alert(1))")
        assertFalse("must not emit anchor for javascript:", out.contains("<a "))
        assertContains("[bad](javascript:alert(1))", out)
    }

    // -- HTML tag allowlist (legacy agents that emit raw tags) --

    fun testAllowsRawCodeTag() {
        val out = NarrationRenderer.toHtml("Defines the <code>initPlayground</code> entry.")
        assertContains("<code>initPlayground</code>", out)
    }

    fun testAllowsBoldItalicEmStrong() {
        val out = NarrationRenderer.toHtml(
            "<b>Bold</b> <i>italic</i> <em>em</em> <strong>strong</strong>"
        )
        assertContains("<b>Bold</b>", out)
        assertContains("<i>italic</i>", out)
        assertContains("<em>em</em>", out)
        assertContains("<strong>strong</strong>", out)
    }

    fun testStripsUnknownTags() {
        // <script> is not in the allowlist; should appear escaped.
        val out = NarrationRenderer.toHtml("hi <script>evil()</script> there")
        assertFalse("must not emit raw <script>", out.contains("<script>"))
        assertContains("&lt;script&gt;", out)
    }

    fun testRawAnchorTagNotInAllowlist() {
        // We intentionally don't pass <a href> from raw HTML through — the markdown link form
        // is the only sanctioned way to produce a link (so the safe-href check applies).
        val out = NarrationRenderer.toHtml("""click <a href="javascript:alert(1)">here</a>""")
        assertFalse("raw <a> tags must not survive", out.contains("<a "))
    }

    // -- newlines --

    fun testNewlineBecomesBreak() {
        val out = NarrationRenderer.toHtml("line one\nline two")
        assertContains("line one<br>line two", out)
    }

    // -- combined --

    fun testCombinedMarkdownAndAllowlistedHtml() {
        val raw = "Calls `foo()` from <code>module.kt</code> with **strict** mode."
        val out = NarrationRenderer.toHtml(raw)
        assertContains("<code>foo()</code>", out)
        assertContains("<code>module.kt</code>", out)
        assertContains("<b>strict</b>", out)
    }

    fun testHtmlBodyWrapper() {
        val out = NarrationRenderer.toHtml("hello")
        assertTrue("output must be a full HTML document for JEditorPane", out.startsWith("<html>"))
        assertTrue(out.contains("</html>"))
    }

    fun testItalicAcrossInlineCodeSpan() {
        // `*set ` + code + ` here*` must produce a single <i> wrapping a nested <code>.
        val out = NarrationRenderer.toHtml("*set `x` here*")
        assertContains("<i>set <code>x</code> here</i>", out)
    }

    fun testBoldAcrossInlineCodeSpan() {
        val out = NarrationRenderer.toHtml("**call `foo()` now**")
        assertContains("<b>call <code>foo()</code> now</b>", out)
    }

    fun testAllowlistedTagsAreCaseInsensitive() {
        // Older agents sometimes emit uppercase HTML; normalise to lowercase tags.
        val out = NarrationRenderer.toHtml("<CODE>x</CODE> and <B>y</B>")
        assertContains("<code>x</code>", out)
        assertContains("<b>y</b>", out)
    }

    fun testLinkUrlWithBalancedParens() {
        // Doc URLs of the form `Foo_(bar)` must survive the link conversion.
        val out = NarrationRenderer.toHtml("see [Foo](https://example.com/Foo_(bar))")
        assertContains("""<a href="https://example.com/Foo_(bar)">Foo</a>""", out)
    }

    fun testLinkUrlRejectsQuotesInUrl() {
        // A URL containing `"` must NOT pass through (defense against attribute-injection).
        val raw = """[bad](https://x.com/a"onclick=alert)"""
        val out = NarrationRenderer.toHtml(raw)
        assertFalse("must not emit anchor for URL containing quote", out.contains("<a "))
    }

    private fun assertContains(needle: String, haystack: String) {
        assertTrue("expected \"$needle\" inside \"$haystack\"", haystack.contains(needle))
    }
}
