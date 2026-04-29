package com.github.lucatume.completamente.walkthrough

/**
 * A range in a project file. Lines and columns are 0-indexed and the column range is
 * end-exclusive — matching IntelliJ's `Document` / `LogicalPosition` / `OpenFileDescriptor`
 * APIs. Every module inside the plugin sees only 0-indexed values; the LLM wire format
 * (1-indexed) is converted by the parser at the boundary.
 */
data class StepRange(
    val file: String,           // project-relative path, POSIX separators
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int
)

/**
 * One node of a walkthrough tree. Tree-shaped from day one to keep future forking unblocked.
 * The current iteration only assembles linear chains (each step's `children` is empty or has
 * exactly one element); the data class supports an arbitrary tree without modification.
 */
data class WalkthroughStep(
    val id: String,
    val parentId: String?,
    val range: StepRange,
    val narration: String?,
    val children: List<WalkthroughStep>
)

/** Complete walkthrough tree, rooted at a single step. */
data class Walkthrough(val root: WalkthroughStep)

/**
 * Parser intermediate — a step extracted from the wire format before the linear-chain tree
 * is assembled and 1-indexed values are converted to 0-indexed.
 */
data class RawStep(
    val id: String?,
    val file: String,
    val startLine: Int,         // 1-indexed (wire format)
    val startCol: Int,          // 1-indexed
    val endLine: Int,           // 1-indexed
    val endCol: Int,            // 1-indexed, end-exclusive
    val narration: String?
)

/**
 * Typed parser failure — surfaced to the user as a notification. Thrown by
 * [WalkthroughParser.parse] inside the `Result.failure` so call sites can pattern-match on
 * the exception type when crafting user-facing messages.
 */
class ParseFailure(message: String) : IllegalArgumentException(message)
