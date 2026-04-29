package com.github.lucatume.completamente.walkthrough

/**
 * Pure converter from agent-emitted narration to HTML for `JEditorPane`.
 *
 * Narration may contain a mix of:
 * - plain text (HTML metachars must be escaped),
 * - our markdown subset: `` `code` ``, `**bold**`, `*italic*`, `[label](url)`,
 * - a small allowlist of raw HTML tags that legacy/older agents emit:
 *   `<code>`, `<b>`, `<i>`, `<em>`, `<strong>` (and their close tags).
 *
 * Anything outside the allowlist is escaped to its literal text form (so `<script>` shows up
 * as `&lt;script&gt;` instead of executing). The link form uses an allowlisted scheme check
 * to refuse `javascript:` and similar.
 */
object NarrationRenderer {

    // URL body excludes whitespace, quotes, and angle brackets so a pathological URL like
    // `https://x.com/a"onclick=…` cannot inject through the `href="…"` slot. Allows a single
    // level of paren-balanced segments so doc URLs like `Foo_(bar)` survive.
    private val LINK_PATTERN = Regex(
        """\[([^\[\]\n]+)\]\(((?:[^()\s"'<>]|\([^()\s]*\))+)\)"""
    )
    private val CODE_PATTERN = Regex("""`([^`\n]+)`""")
    private val BOLD_PATTERN = Regex("""\*\*((?:[^*\n]|\*(?!\*))+)\*\*""")
    private val ITALIC_PATTERN = Regex("""(?<![*\w])\*([^*\n]+)\*(?!\*)""")
    private val ALLOWLISTED_TAG_PATTERN = Regex(
        """&lt;(/?)(code|b|i|em|strong)&gt;""",
        RegexOption.IGNORE_CASE
    )

    private val SAFE_LINK_PREFIXES = listOf("http://", "https://", "mailto:", "file:")

    fun toHtml(raw: String): String {
        if (raw.isBlank()) return ""

        var s = escapeHtml(raw)

        // Markdown link first — uses already-escaped URL/label, which is the correct attribute
        // and body encoding (e.g. `&` is `&amp;`).
        s = LINK_PATTERN.replace(s) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (isSafeHref(url)) """<a href="$url">$label</a>""" else m.value
        }
        s = CODE_PATTERN.replace(s, "<code>$1</code>")
        s = BOLD_PATTERN.replace(s, "<b>$1</b>")
        s = ITALIC_PATTERN.replace(s, "<i>$1</i>")

        // Re-allow specific raw HTML tags that survived escaping. Anchor tags are intentionally
        // NOT in the allowlist — the markdown link form (with its safe-scheme check) is the only
        // sanctioned way to produce a link. Tag name is normalised to lowercase so a legacy
        // agent emitting `<CODE>` still produces well-formed HTML.
        s = ALLOWLISTED_TAG_PATTERN.replace(s) { m ->
            "<${m.groupValues[1]}${m.groupValues[2].lowercase()}>"
        }

        // Preserve linebreaks for JEditorPane (HTML collapses whitespace by default).
        s = s.replace("\n", "<br>")

        return "<html><body>$s</body></html>"
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun isSafeHref(url: String): Boolean {
        val lower = url.lowercase()
        return SAFE_LINK_PREFIXES.any { lower.startsWith(it) }
    }
}
