package com.github.lucatume.completamente.order89

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class Order89Request(
    val commandTemplate: String,
    val prompt: String,
    val filePath: String,
    val fileContent: String,
    val language: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val workingDirectory: String
)

data class Order89Result(
    val success: Boolean,
    val output: String,
    val exitCode: Int
)

object Order89Executor {

    fun buildPromptFile(request: Order89Request): String {
        val before = request.fileContent.substring(0, request.selectionStart)
        val selection = request.fileContent.substring(request.selectionStart, request.selectionEnd)
        val after = request.fileContent.substring(request.selectionEnd)

        val prompt = buildString {
            appendLine("<Order89Prompt>")
            appendLine("You are a code transformation tool. You receive a file with a marked selection and an instruction.")
            appendLine("You output ONLY the code that replaces the selection. Nothing else.")
            appendLine()
            appendLine("<Order89Rules>")
            appendLine("- Output raw code only. No markdown fences, no backticks, no explanations.")
            appendLine("- Do NOT describe what you are about to do. Do NOT explain your reasoning.")
            appendLine("- Do NOT include any text before or after the replacement code.")
            appendLine("- Do NOT wrap output in ```code fences```.")
            appendLine("- If the instruction asks for comments in the code, include them. But never include")
            appendLine("  conversational text — only text that is valid in the target language.")
            appendLine("- If the selection is empty (<Order89UserSelection></Order89UserSelection>),")
            appendLine("  output code to insert at that position.")
            appendLine("- Preserve the indentation style of the surrounding code.")
            appendLine("</Order89Rules>")
            appendLine()
            appendLine("Language: ${request.language}")
            appendLine("File: ${request.filePath}")
            appendLine()
            appendLine("<Order89Instruction>")
            appendLine(request.prompt)
            appendLine("</Order89Instruction>")
            appendLine()
            appendLine("<Order89FileContent>")
            append(before)
            append("<Order89UserSelection>")
            append(selection)
            append("</Order89UserSelection>")
            append(after)
            appendLine()
            appendLine("</Order89FileContent>")
            appendLine("</Order89Prompt>")
        }

        val tempFile = File.createTempFile("order89-", ".txt")
        tempFile.writeText(prompt)
        return tempFile.absolutePath
    }

    fun buildCommand(request: Order89Request, promptFilePath: String): String {
        return request.commandTemplate.replace("{{prompt_file}}", promptFilePath)
    }

    fun detectBaseIndent(text: String): String {
        return text.split("\n")
            .filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it == ' ' || it == '\t' } }
            .minByOrNull { it.length } ?: ""
    }

    private val FENCED_BLOCK = Regex("```[^\\n]*\\n([\\s\\S]*?)\\n\\s*```")

    fun extractCodeBlock(output: String): String {
        val matches = FENCED_BLOCK.findAll(output).toList()
        if (matches.isEmpty()) return output
        return matches.joinToString("\n\n") { it.groupValues[1] }
    }

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
        val lastCodeLine = lines.indexOfLast { it.isNotBlank() && looksLikeCode(it) }
        if (lastCodeLine < 0 || lastCodeLine == lines.lastIndex) return output
        return lines.take(lastCodeLine + 1).joinToString("\n")
    }

    fun cleanOutput(raw: String): String {
        return extractCodeBlock(raw)
            .let { stripLeadingProse(it) }
            .let { stripTrailingProse(it) }
    }

    fun reindentOutput(output: String, selectionIndent: String): String {
        val lines = output.split("\n")
        if (lines.size <= 1) return output

        val baseIndent = detectBaseIndent(output)
        return lines.mapIndexed { index, line ->
            when {
                line.isBlank() -> line
                index == 0 -> line.removePrefix(baseIndent)
                else -> selectionIndent + line.removePrefix(baseIndent)
            }
        }.joinToString("\n")
    }

    fun execute(request: Order89Request): Pair<Process, Future<Order89Result>> {
        val promptFilePath = buildPromptFile(request)
        val command = buildCommand(request, promptFilePath)
        val process = ProcessBuilder(listOf("/bin/sh", "-c", command))
            .directory(File(request.workingDirectory))
            .redirectErrorStream(true)
            .start()

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            try {
                val rawOutput = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                val output = if (exitCode == 0) cleanOutput(rawOutput) else rawOutput
                Order89Result(exitCode == 0, output, exitCode)
            } finally {
                File(promptFilePath).delete()
                executor.shutdown()
            }
        })

        return Pair(process, future)
    }
}
