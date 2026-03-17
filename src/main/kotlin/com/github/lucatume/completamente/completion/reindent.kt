package com.github.lucatume.completamente.completion

data class IndentStyle(
    val useTabs: Boolean,
    val indentSize: Int
)

/**
 * Reindents a multi-line suggestion to match the project's indentation style.
 *
 * Line 0 is never modified (it continues from the cursor position).
 * Lines 1+ are reindented: the model's indentation style is detected, relative
 * indent levels are preserved, and the project's indent settings are applied
 * with [cursorLineIndent] as the base level.
 *
 * If the model output uses mixed tabs and spaces, the suggestion is returned
 * unchanged — the heuristic cannot reliably determine indent levels in that case.
 */
fun reindentSuggestion(
    suggestion: String,
    cursorLineIndent: String,
    projectStyle: IndentStyle
): String {
    // Normalize CRLF to LF before processing.
    val normalized = suggestion.replace("\r\n", "\n").replace("\r", "\n")
    if (!normalized.contains('\n')) return normalized

    val lines = normalized.split('\n')

    // Collect leading whitespace info from non-blank lines after the first.
    val tailLines = lines.drop(1)
    val nonBlankTailIndents = tailLines
        .filter { it.isNotBlank() }
        .map { line -> line.takeWhile { it == ' ' || it == '\t' } }

    // If there are no non-blank lines after line 0, nothing to reindent.
    if (nonBlankTailIndents.isEmpty()) return normalized

    val modelUnit = detectModelIndentUnit(nonBlankTailIndents) ?: return normalized

    // coerceAtLeast(1) is defensive: detectModelIndentUnit never returns an empty string,
    // but this guard prevents division by zero if the contract changes.
    val modelUnitLen = modelUnit.length.coerceAtLeast(1)

    // Model base level = minimum indent level among non-blank tail lines.
    val modelBaseLevel = nonBlankTailIndents.minOf { it.length / modelUnitLen }

    val projectUnit = if (projectStyle.useTabs) "\t" else " ".repeat(projectStyle.indentSize.coerceAtLeast(1))

    val reindented = lines.mapIndexed { index, line ->
        if (index == 0) return@mapIndexed line
        if (line.isBlank()) return@mapIndexed line

        val leading = line.takeWhile { it == ' ' || it == '\t' }
        // Integer division: fractional indent levels are truncated. This is intentional —
        // the heuristic cannot distinguish alignment from indentation, so we round down
        // to the nearest whole level rather than guessing.
        val level = leading.length / modelUnitLen
        val relativeLevel = (level - modelBaseLevel).coerceAtLeast(0)
        val newIndent = cursorLineIndent + projectUnit.repeat(relativeLevel)
        newIndent + line.removePrefix(leading)
    }

    return reindented.joinToString("\n")
}

/**
 * Detects the model's indent unit from a list of leading-whitespace strings.
 *
 * Returns `null` if mixed tabs and spaces are detected (some lines use tabs,
 * others use spaces) — the caller should return the suggestion unchanged.
 *
 * If all indentation is tabs, returns "\t".
 * If all indentation is spaces, computes the GCD of all leading-space counts.
 * When the GCD is 1 but the minimum non-zero count is > 1 (e.g., [3, 5]),
 * uses the minimum as a better heuristic — the GCD of coprime widths is
 * mathematically correct but practically useless as an indent unit.
 */
internal fun detectModelIndentUnit(indents: List<String>): String? {
    val hasTabs = indents.any { it.contains('\t') }
    val hasSpaces = indents.any { it.contains(' ') }

    // Mixed tabs and spaces: bail out — cannot reliably determine indent levels.
    if (hasTabs && hasSpaces) return null

    if (hasTabs) return "\t"

    val spaceCounts = indents.map { it.length }.filter { it > 0 }
    // When all indents are zero-length (all lines are flush-left), return a 1-space unit.
    // The actual value does not matter: all indent levels will compute as 0, so the unit
    // is never used to build output indentation.
    if (spaceCounts.isEmpty()) return " "

    val gcd = spaceCounts.reduce { a, b -> gcd(a, b) }
    val unitSize = if (gcd <= 1 && spaceCounts.min() > 1) spaceCounts.min() else gcd

    return " ".repeat(unitSize.coerceAtLeast(1))
}

private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val temp = y
        y = x % y
        x = temp
    }
    return x
}
