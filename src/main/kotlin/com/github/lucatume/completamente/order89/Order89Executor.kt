package com.github.lucatume.completamente.order89

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class Order89Request(
    val commandTemplate: String,
    val prompt: String,
    val selectedText: String,
    val filePath: String,
    val fileContent: String,
    val language: String,
    val referencedFiles: List<String>,
    val workingDirectory: String
)

data class Order89Result(
    val success: Boolean,
    val output: String,
    val exitCode: Int
)

object Order89Executor {

    fun shellEscape(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun buildCommand(request: Order89Request): String {
        val replacements = mapOf(
            "{{prompt}}" to shellEscape(request.prompt),
            "{{selected_text}}" to shellEscape(request.selectedText),
            "{{file_path}}" to shellEscape(request.filePath),
            "{{file_content}}" to shellEscape(request.fileContent),
            "{{language}}" to shellEscape(request.language),
            "{{referenced_files}}" to shellEscape(request.referencedFiles.joinToString("\n"))
        )
        val pattern = Regex(replacements.keys.joinToString("|") { Regex.escape(it) })
        return pattern.replace(request.commandTemplate) { match ->
            replacements[match.value] ?: match.value
        }
    }

    fun execute(request: Order89Request): Pair<Process, Future<Order89Result>> {
        val command = buildCommand(request)
        val process = ProcessBuilder(listOf("/bin/sh", "-c", command))
            .directory(File(request.workingDirectory))
            .redirectErrorStream(true)
            .start()

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            try {
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                Order89Result(exitCode == 0, output, exitCode)
            } finally {
                executor.shutdown()
            }
        })

        return Pair(process, future)
    }
}
