package com.github.lucatume.completamente.walkthrough

/**
 * Parses the agentic CLI's response into a [Walkthrough] tree.
 *
 * Pure stdlib — no IDE dependencies — so the parser is unit-testable without the IntelliJ
 * test framework. The wire format is XML-flavored tagged blocks:
 *
 * ```
 * <Walkthrough>
 *   <Step id="1" file="src/Foo.kt" range="12:5-19:1">
 *     <Narration>...</Narration>
 *   </Step>
 *   <Step file="src/Bar.kt" range="44:1-61:1"/>   <!-- self-closing, no narration -->
 * </Walkthrough>
 * ```
 *
 * Indexing convention: ranges in the wire format are 1-indexed and end-column-exclusive.
 * The parser converts to 0-indexed when producing [StepRange]; every other module sees
 * only 0-indexed values.
 */
object WalkthroughParser {

    /**
     * Parse [raw] into a walkthrough. Returns:
     * - `Result.success(walkthrough)` for a valid block with ≥1 steps,
     * - `Result.success(null)` for an empty `<Walkthrough></Walkthrough>` block (zero steps
     *   is not a parser error; the action surfaces it as a user-facing notification),
     * - `Result.failure(ParseFailure(message))` for any malformed shape.
     *
     * Any prose before or after the `<Walkthrough>...</Walkthrough>` block is discarded
     * (mirrors `Order89Executor.extractCodeBlock`'s tolerance for surrounding chatter).
     */
    fun parse(raw: String): Result<Walkthrough?> {
        val block = extractWalkthroughBlock(raw)
            ?: return Result.failure(ParseFailure(
                "No <Walkthrough>...</Walkthrough> block found in agent output."
            ))

        val rawStepsResult = parseSteps(block)
        if (rawStepsResult.isFailure) return Result.failure(rawStepsResult.exceptionOrNull()!!)
        val rawSteps = rawStepsResult.getOrThrow()

        if (rawSteps.isEmpty()) return Result.success(null)

        val explicitIds = rawSteps.mapNotNull { it.id }
        val duplicateExplicit = explicitIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateExplicit.isNotEmpty()) {
            return Result.failure(ParseFailure(
                "Duplicate explicit <Step id=\"...\"> values: ${duplicateExplicit.joinToString(", ")}. " +
                    "Each step's id must be unique within a walkthrough."
            ))
        }

        return Result.success(Walkthrough(assembleTree(rawSteps)))
    }

    /**
     * Extract the substring between the first `<Walkthrough>` and its matching
     * `</Walkthrough>`. Returns null if either tag is missing.
     */
    internal fun extractWalkthroughBlock(raw: String): String? {
        val openIdx = raw.indexOf("<Walkthrough>")
        if (openIdx < 0) return null
        val closeIdx = raw.indexOf("</Walkthrough>", startIndex = openIdx + "<Walkthrough>".length)
        if (closeIdx < 0) return null
        return raw.substring(openIdx + "<Walkthrough>".length, closeIdx)
    }

    private val STEP_PATTERN = Regex(
        """<Step\b([^>]*?)(?:/\s*>|>([\s\S]*?)</Step>)""",
        RegexOption.MULTILINE
    )
    /**
     * Matches every occurrence of the literal `<Step` token (open or self-closing). We use
     * this as the total-step counter; the precise self-vs-non-self-closing breakdown is then
     * recovered from [STEP_PATTERN] matches via their suffix. This avoids the lookbehind-vs-whitespace
     * pitfall (`<Step ... />` with whitespace between attrs and `/>`).
     */
    private val ANY_STEP_PATTERN = Regex("""<Step\b""")
    /** Accept both single- and double-quoted attribute values. */
    private val ATTR_PATTERN = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val NARRATION_PATTERN = Regex("""<Narration>([\s\S]*?)</Narration>""")
    private val RANGE_PATTERN = Regex("""(\d+):(\d+)-(\d+):(\d+)""")

    /**
     * Scan [block] for `<Step ...>...</Step>` and `<Step ... />` (self-closing).
     * Returns the parsed steps in document order, or a failure for any malformed shape.
     *
     * Detects un-closed `<Step ...>` tags up-front: if the count of `<Step` opens that aren't
     * self-closing exceeds the number of full `<Step>...</Step>` matches, a `</Step>` is missing
     * — surface as a typed failure rather than silently dropping the step.
     */
    internal fun parseSteps(block: String): Result<List<RawStep>> {
        val matches = STEP_PATTERN.findAll(block).toList()
        val totalStepTokens = ANY_STEP_PATTERN.findAll(block).count()
        // Every well-formed <Step> contributes exactly one token (whether self-closing `<.../>`
        // or paired `<...></Step>`); STEP_PATTERN matches both forms. If the token count exceeds
        // the parsed-match count, some `<Step ...>` open lacks its `</Step>` close.
        if (totalStepTokens > matches.size) {
            return Result.failure(ParseFailure(
                "Found <Step ...> open tag with no matching </Step> close. " +
                    "Either close the tag or use the self-closing form <Step ... />."
            ))
        }
        val steps = mutableListOf<RawStep>()
        for (match in matches) {
            val attrs = parseAttrs(match.groupValues[1])
            val body = match.groupValues.getOrNull(2)
            val file = attrs["file"]
                ?: return Result.failure(ParseFailure(
                    "<Step> is missing required 'file' attribute"
                ))
            if (file.isBlank()) return Result.failure(ParseFailure(
                "<Step> 'file' attribute must not be blank"
            ))
            val rangeStr = attrs["range"]
                ?: return Result.failure(ParseFailure(
                    "<Step file=\"$file\"> is missing required 'range' attribute"
                ))
            val rangeMatch = RANGE_PATTERN.matchEntire(rangeStr.trim())
                ?: return Result.failure(ParseFailure(
                    "Malformed range '$rangeStr' on <Step file=\"$file\">; expected L:C-L:C"
                ))
            val (sl, sc, el, ec) = rangeMatch.destructured.toList().map { it.toInt() }
            if (sl < 1 || sc < 1 || el < 1 || ec < 1) {
                return Result.failure(ParseFailure(
                    "Range '$rangeStr' on <Step file=\"$file\"> contains non-positive values; " +
                        "wire format is 1-indexed"
                ))
            }
            // Empty or whitespace-only narration is treated as no narration — the popup hides
            // the panel on null, and an empty string would render an awkward blank box.
            val narration = body
                ?.let { NARRATION_PATTERN.find(it)?.groupValues?.get(1) }
                ?.let(::decodeXmlEntities)
                ?.takeIf { it.isNotBlank() }
            steps += RawStep(
                id = attrs["id"]?.let(::decodeXmlEntities),
                file = decodeXmlEntities(file),
                startLine = sl,
                startCol = sc,
                endLine = el,
                endCol = ec,
                narration = narration
            )
        }
        return Result.success(steps)
    }

    private fun parseAttrs(attrSegment: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (m in ATTR_PATTERN.findAll(attrSegment)) {
            // ATTR_PATTERN has two alternation groups for the value (double or single quotes);
            // exactly one is non-empty per match.
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            out[m.groupValues[1]] = value
        }
        return out
    }

    private fun decodeXmlEntities(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&") // amp last so we don't double-decode `&amp;lt;`

    /**
     * Build a linear-chain walkthrough tree from [rawSteps] (assumed non-empty). Each step
     * becomes the unique child of the previous; the first step is the root with `parentId = null`.
     * Wire-format 1-indexed ranges are converted to internal 0-indexed `StepRange`.
     *
     * IDs absent on the wire are filled in as "1", "2", … in order.
     */
    internal fun assembleTree(rawSteps: List<RawStep>): WalkthroughStep {
        require(rawSteps.isNotEmpty()) { "assembleTree requires at least one step" }
        // Synthetic ids are issued from the first integer that doesn't collide with any
        // explicit id. This prevents `id="2"` on step 0 from clashing with the synthetic `"2"`
        // we'd otherwise hand to step 1.
        val explicit = rawSteps.mapNotNull { it.id }.toSet()
        var nextSynthetic = 1
        val ids = rawSteps.map { raw ->
            raw.id ?: run {
                while (explicit.contains(nextSynthetic.toString())) nextSynthetic++
                (nextSynthetic++).toString()
            }
        }
        // Walk from the tail backwards so each predecessor can embed its successor as a child.
        var child: WalkthroughStep? = null
        for (i in rawSteps.indices.reversed()) {
            val raw = rawSteps[i]
            val parentId = if (i == 0) null else ids[i - 1]
            val children = if (child == null) emptyList() else listOf(child)
            child = WalkthroughStep(
                id = ids[i],
                parentId = parentId,
                range = StepRange(
                    file = raw.file,
                    startLine = raw.startLine - 1,
                    startCol = raw.startCol - 1,
                    endLine = raw.endLine - 1,
                    endCol = raw.endCol - 1
                ),
                narration = raw.narration,
                children = children
            )
        }
        return child!!
    }
}
