# Plan: Order 89 Prompt v3 and Output Cleaning Pipeline

Implements design doc `19-design-order89-prompt-and-output-cleaning.md`.

## Overview

Two changes to `Order89Executor`:
1. Update the prompt template in `buildPromptFile()` to v3 (role + `<Order89Rules>` block).
2. Replace `stripCodeFences()` with a three-step `cleanOutput()` pipeline:
   `extractCodeBlock()` → `stripLeadingProse()` → `stripTrailingProse()`.

No changes to `Order89Action`, `Order89Dialog`, settings, or plugin.xml.

---

## Step 1: Add `extractCodeBlock()` to `Order89Executor`

**File**: `src/main/kotlin/.../order89/Order89Executor.kt`

Add a new function that replaces `stripCodeFences()`. It finds all markdown fenced code blocks
in the output and concatenates their contents. If no fences are found, returns the input unchanged.

```kotlin
private val FENCED_BLOCK_REGEX = Regex("```[^\\n]*\\n([\\s\\S]*?)\\n\\s*```")

fun extractCodeBlock(output: String): String {
    val matches = FENCED_BLOCK_REGEX.findAll(output).toList()
    if (matches.isEmpty()) return output
    return matches.joinToString("\n\n") { it.groupValues[1] }
}
```

This handles all three cases from the design: whole-output fences, embedded fences with
surrounding prose, and multiple fenced blocks.

**Checkpoint**: Write tests before proceeding (Step 4).

---

## Step 2: Add `stripLeadingProse()` and `stripTrailingProse()`

**File**: `src/main/kotlin/.../order89/Order89Executor.kt`

Add two companion regex patterns and two functions:

```kotlin
private val CODE_LINE_PATTERN = Regex(
    """^\s*([{}\[\]()@$<>]|//|/\*|\*|#|<!--|--|%|\{-|""" +
    """public\b|private\b|protected\b|function\b|class\b|interface\b|""" +
    """def\b|fn\b|let\b|const\b|var\b|val\b|if\b|for\b|while\b|""" +
    """return\b|import\b|use\b|package\b|namespace\b|""" +
    """abstract\b|static\b|final\b|override\b|""" +
    """struct\b|enum\b|trait\b|impl\b|type\b|module\b)"""
)

private val INDENTED_LINE = Regex("""^[\t ]+\S""")

private val IDENTIFIER_CODE_PATTERN = Regex(
    """[a-zA-Z_]\w*\s*[(\[]|[a-zA-Z_]\w*\.[a-zA-Z_]|[a-zA-Z_]\w*\s*[+\-*/]?=\s"""
)

fun looksLikeCode(line: String): Boolean {
    return CODE_LINE_PATTERN.containsMatchIn(line) ||
        INDENTED_LINE.containsMatchIn(line) ||
        IDENTIFIER_CODE_PATTERN.containsMatchIn(line)
}

fun stripLeadingProse(output: String): String {
    val lines = output.split("\n")
    val firstCodeLine = lines.indexOfFirst { it.isNotBlank() && looksLikeCode(it) }
    if (firstCodeLine <= 0) return output
    return lines.drop(firstCodeLine).joinToString("\n")
}

fun stripTrailingProse(output: String): String {
    val lines = output.split("\n")
    val lastCodeLine = lines.indexOfLast { line ->
        line.isNotBlank() && looksLikeCode(line)
    }
    if (lastCodeLine < 0 || lastCodeLine == lines.lastIndex) return output
    return lines.take(lastCodeLine + 1).joinToString("\n")
}
```

**Note on `IDENTIFIER_CODE_PATTERN`**: The `CODE_LINE_PATTERN` and `INDENTED_LINE` regexes miss
function calls (`println(x)`, `assertEquals(...)`), dot-access (`foo.bar()`), and assignments
(`x = 1`). `IDENTIFIER_CODE_PATTERN` catches these, reducing false classification of code as prose.

**Note on blank lines in `stripLeadingProse`**: The scan skips blank lines entirely, looking
for the first non-blank line that `looksLikeCode`. Leading blank lines are dropped along with
prose. This avoids inserting empty lines before the replacement code.

**Checkpoint**: Write tests before proceeding (Step 4).

---

## Step 3: Add `cleanOutput()` and wire into `execute()`

**File**: `src/main/kotlin/.../order89/Order89Executor.kt`

Add the composed pipeline function:

```kotlin
fun cleanOutput(raw: String): String {
    return extractCodeBlock(raw)
        .let { stripLeadingProse(it) }
        .let { stripTrailingProse(it) }
}
```

Change `execute()` from:

```kotlin
val output = if (exitCode == 0) stripCodeFences(rawOutput) else rawOutput
```

to:

```kotlin
val output = if (exitCode == 0) cleanOutput(rawOutput) else rawOutput
```

Remove `stripCodeFences()` and `CODE_FENCE_REGEX` (replaced by `extractCodeBlock()`).

---

## Step 4: Write tests

**File**: `src/test/kotlin/.../order89/Order89ExecutorTest.kt`

### 4a. Replace `stripCodeFences` tests with `extractCodeBlock` tests

Remove the existing `stripCodeFences` test section. Add:

| Test | Input | Expected |
|------|-------|----------|
| Whole-output fence with language | `` ```php\n<?php echo 'hello'; ?>\n``` `` | `<?php echo 'hello'; ?>` |
| Whole-output fence without language | `` ```\nsome code\n``` `` | `some code` |
| Multi-line fenced block | `` ```kotlin\nfun main() {\n    println("hello")\n}\n``` `` | `fun main() {\n    println("hello")\n}` |
| No fences (passthrough) | `fun main() {\n    println("hello")\n}` | same |
| Embedded fence with surrounding prose | `Here's the code:\n\n```php\necho 'hi';\n```\n\nThis should work.` | `echo 'hi';` |
| Multiple fenced blocks | `` ```\nblock1\n```\nSome text\n```\nblock2\n``` `` | `block1\n\nblock2` |
| Fence with surrounding whitespace | `\n```js\nconsole.log('hi')\n```\n` | `console.log('hi')` |
| Inline backticks not matched | `` use `val x = 1` here `` | same |

### 4b. Add `stripLeadingProse` tests

| Test | Input | Expected |
|------|-------|----------|
| No prose (code starts immediately) | `public function foo() {}` | same |
| Leading prose then code | `Based on the API, here's the code:\n\npublic function foo() {}` | `public function foo() {}` |
| Leading prose then indented code | `Here it is:\n    $x = 1;` | `    $x = 1;` |
| Leading prose then comment | `I'll write this:\n// comment\ncode()` | `// comment\ncode()` |
| Multiple prose lines | `First line of prose.\nSecond line.\n\nclass Foo {}` | `class Foo {}` |
| All prose (no code detected) | `This is just a sentence.` | same (no stripping if nothing looks like code) |
| Empty input | `` | `` |
| Blank lines before code | `\n\nfunction foo() {}` | `function foo() {}` |

### 4c. Add `stripTrailingProse` tests

| Test | Input | Expected |
|------|-------|----------|
| No trailing prose | `function foo() {\n    return 1;\n}` | same |
| Trailing prose | `function foo() {}\n\nThis should work.` | `function foo() {}` |
| Multiple trailing prose lines | `return x;\n\nHope this helps.\nLet me know.` | `return x;` |
| All prose | `Just some text here.` | same |
| Empty input | `` | `` |
| Code ends with comment | `x = 1\n// done` | same (`//` matches code pattern) |

### 4d. Add `cleanOutput` integration tests

| Test | Input | Expected |
|------|-------|----------|
| Fenced with prose | `Here:\n\n```php\necho 'hi';\n```\n\nDone.` | `echo 'hi';` |
| Unfenced with leading prose | `Based on...\n\nclass Foo {}` | `class Foo {}` |
| Clean code passthrough | `val x = 1\nval y = 2` | same |
| Real-world example from issue | The full model output from the user's example (leading prose paragraph + PHP test methods) | Just the PHP test methods |

### 4e. Update `execute` test

The existing `testExecuteWithEchoCommand` test echoes `hello` which has no fences or prose. It
should still pass unchanged. Add one test that verifies `execute()` applies cleaning:

```kotlin
fun testExecuteStripsCodeFencesOnSuccess() {
    val request = makeRequest(
        commandTemplate = "printf '```kotlin\\nval x = 1\\n```'",
        workingDirectory = "/tmp"
    )
    val (_, future) = Order89Executor.execute(request)
    val result = future.get()
    assertTrue(result.success)
    assertEquals("val x = 1", result.output)
}
```

---

## Step 5: Update the prompt template in `buildPromptFile()`

**File**: `src/main/kotlin/.../order89/Order89Executor.kt`

Replace the prompt text in `buildPromptFile()` (lines 33–37) with the v3 template:

**Current** (lines 33–37):
```kotlin
appendLine("Modify the code in the file according to the user instruction below.")
appendLine("Output ONLY the content that should replace the <Order89UserSelection> tags and their contents.")
appendLine("No markdown fences, no explanations, no text before or after the replacement code.")
appendLine("If the selection is empty (<Order89UserSelection></Order89UserSelection>), insert new code at that position.")
```

**New**:
```kotlin
appendLine("You are a code transformation tool. You receive a file with a marked selection and an instruction.")
appendLine("You output ONLY the code that replaces the selection. Nothing else.")
appendLine()
appendLine("<Order89Rules>")
appendLine("- Output raw code only. No markdown fences, no backticks, no explanations.")
appendLine("- Do NOT describe what you are about to do. Do NOT explain your reasoning.")
appendLine("- Do NOT include any text before or after the replacement code.")
appendLine("- Do NOT wrap output in code fences.")
appendLine("- If the instruction asks for comments in the code, include them. But never include")
appendLine("  conversational text — only text that is valid in the target language.")
appendLine("- If the selection is empty (<Order89UserSelection></Order89UserSelection>),")
appendLine("  output code to insert at that position.")
appendLine("- Preserve the indentation style of the surrounding code.")
appendLine("</Order89Rules>")
```

---

## Step 6: Update `buildPromptFile` tests

**File**: `src/test/kotlin/.../order89/Order89ExecutorTest.kt`

The test `testBuildPromptFileContainsInstructionLanguageAndPath` checks for `<Order89Instruction>`
and `Language:` which are unchanged. No modification needed.

The test `testBuildPromptFileSelectionInMiddle` checks for the `<Order89UserSelection>` markers
in the file content section. This is also unchanged.

Add one test to verify the new prompt elements:

```kotlin
fun testBuildPromptFileContainsV3PromptElements() {
    val request = makeRequest(prompt = "test", fileContent = "code", selectionStart = 0, selectionEnd = 4)
    val path = Order89Executor.buildPromptFile(request)
    try {
        val result = File(path).readText()
        assertTrue(result.contains("You are a code transformation tool"))
        assertTrue(result.contains("<Order89Rules>"))
        assertTrue(result.contains("</Order89Rules>"))
        assertTrue(result.contains("Do NOT describe what you are about to do"))
        assertTrue(result.contains("valid in the target language"))
    } finally {
        File(path).delete()
    }
}
```

---

## Step 7: Run tests and verify

```bash
./gradlew test
```

All existing tests should pass (except the `stripCodeFences` tests which are replaced).
All new tests should pass.

---

## Summary of File Changes

| File | Changes |
|------|---------|
| `Order89Executor.kt` | Replace prompt text in `buildPromptFile()`. Remove `stripCodeFences()` + `CODE_FENCE_REGEX`. Add `extractCodeBlock()`, `looksLikeCode()`, `stripLeadingProse()`, `stripTrailingProse()`, `cleanOutput()`. Update `execute()` to call `cleanOutput()`. |
| `Order89ExecutorTest.kt` | Replace `stripCodeFences` test section with `extractCodeBlock` tests. Add test sections for `stripLeadingProse`, `stripTrailingProse`, `cleanOutput`. Add `testBuildPromptFileContainsV3PromptElements`. Add `testExecuteStripsCodeFencesOnSuccess`. |
