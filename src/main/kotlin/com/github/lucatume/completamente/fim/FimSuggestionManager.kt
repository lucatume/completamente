package com.github.lucatume.completamente.fim

import com.github.lucatume.completamente.completion.EditKind
import com.github.lucatume.completamente.completion.EditRegion
import com.github.lucatume.completamente.completion.advanceSubEdit
import com.github.lucatume.completamente.completion.buildFimRequest
import com.github.lucatume.completamente.completion.computeWindowStartOffset
import com.github.lucatume.completamente.completion.extractEdit
import com.github.lucatume.completamente.completion.isFileTooLarge
import com.github.lucatume.completamente.completion.requestFimCompletion
import com.github.lucatume.completamente.completion.resolveDefinitionChunks
import com.github.lucatume.completamente.completion.splitEditIntoSubEdits
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.ChunksRingBuffer
import com.github.lucatume.completamente.services.DiffTracker
import com.github.lucatume.completamente.services.ServerManager
import com.github.lucatume.completamente.services.SettingsState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import java.util.concurrent.Future
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

fun applyEdit(project: Project, editor: Editor, editRegion: EditRegion) {
    WriteCommandAction.runWriteCommandAction(project) {
        try {
            project.service<DiffTracker>().ignoreNextChange = true
        } catch (_: Exception) {
            // DiffTracker may not be available in tests
        }
        val docLength = editor.document.textLength
        val safeStart = editRegion.startOffset.coerceAtMost(docLength)
        val safeEnd = editRegion.endOffset.coerceAtMost(docLength)
        editor.document.replaceString(safeStart, safeEnd, editRegion.newText)
        editor.caretModel.moveToOffset(safeStart + editRegion.newText.length)
    }
}

@Service(Service.Level.PROJECT)
class FimSuggestionManager(private val project: Project) {
    var currentEdits: List<EditRegion> = emptyList()
        private set
    var currentEditIndex: Int = 0
        private set
    var currentInlayDisposable: Disposable? = null
        private set
    var currentEditor: Editor? = null
        private set
    var currentIsJump: Boolean = false
        private set

    val hasSuggestion: Boolean
        get() = currentEditIndex < currentEdits.size

    /** True while cycling through sub-edits (after first TAB accept). */
    var isCyclingSubEdits: Boolean = false
        private set

    private var currentRequestFuture: Future<*>? = null
    private var requestGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var serverChecked: Boolean = false

    private val logger = Logger.getInstance(FimSuggestionManager::class.java)

    fun showSuggestion(editor: Editor) {
        dismissSuggestion()

        // First-completion server check: run once per session.
        // Note: The server check runs asynchronously while the completion request below fires
        // immediately. This means the first request may fail with a connection error if the
        // server is down. This is acceptable — subsequent requests will succeed once the server
        // is started (either manually or via the notification action).
        if (!serverChecked) {
            serverChecked = true
            val serverManager = ServerManager.getInstance()
            if (!serverManager.userDeclinedServerStart) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    performServerCheck(serverManager)
                }
            }
        }

        val document = editor.document
        val text = document.text
        if (isFileTooLarge(text)) {
            logger.debug("File too large, skipping FIM request")
            return
        }

        val virtualFile = editor.virtualFile
        val filePath = virtualFile?.path ?: "untitled"
        val cursorLine = document.getLineNumber(editor.caretModel.offset)
        val offset = editor.caretModel.offset
        val settings = SettingsState.getInstance()
        val serverUrl = settings.serverUrl

        val diffTracker = project.service<DiffTracker>()
        val recentDiffs = diffTracker.getRecentDiffs()
        val originalContent = diffTracker.getOriginalContent(filePath)

        val chunks = try {
            project.service<ChunksRingBuffer>().getRingChunks().toList()
        } catch (_: Exception) {
            emptyList()
        }

        val request = buildFimRequest(
            filePath = filePath,
            currentContent = text,
            cursorLine = cursorLine,
            originalContent = originalContent,
            recentDiffs = recentDiffs,
            chunks = chunks
        )

        val windowedContent = request.windowedContent
        val windowStartLine = request.windowStartLine
        val cursorLineInWindow = cursorLine - windowStartLine

        val gen = requestGeneration.incrementAndGet()
        currentRequestFuture?.cancel(true)

        currentRequestFuture = ApplicationManager.getApplication().executeOnPooledThread {
            // Resolve definitions (needs ReadAction for PSI access)
            val definitionChunks = try {
                ReadAction.compute<List<Chunk>, Throwable> {
                    val psiFile = virtualFile?.let { PsiManager.getInstance(project).findFile(it) }
                    if (psiFile != null) {
                        resolveDefinitionChunks(project, psiFile, offset, filePath)
                    } else emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

            val enrichedRequest = request.copy(definitionChunks = definitionChunks)
            val response = requestFimCompletion(serverUrl, enrichedRequest)

            if (gen != requestGeneration.get()) return@executeOnPooledThread

            logger.debug("FIM response content:\n${response.content}")

            if (response.error != null) {
                logger.warn("FIM request failed: ${response.error}")
                return@executeOnPooledThread
            }

            val editKind = extractEdit(windowedContent, response.content, cursorLineInWindow)
            logger.debug("FIM extracted edit: $editKind")

            if (editKind is EditKind.Suppress) {
                logger.debug("Edit suppressed")
                return@executeOnPooledThread
            }

            val isJump = editKind is EditKind.Jump
            val windowEdit = when (editKind) {
                is EditKind.Inline -> editKind.editRegion
                is EditKind.Jump -> editKind.editRegion
                is EditKind.Suppress -> return@executeOnPooledThread
            }

            // Translate window-relative offsets to document offsets
            val windowStartOffset = computeWindowStartOffset(text, windowStartLine)
            val docEdit = EditRegion(
                startOffset = windowStartOffset + windowEdit.startOffset,
                endOffset = windowStartOffset + windowEdit.endOffset,
                newText = windowEdit.newText
            )

            ApplicationManager.getApplication().invokeLater {
                if (gen != requestGeneration.get()) return@invokeLater
                if (editor.isDisposed) return@invokeLater
                // Verify cursor hasn't moved
                if (editor.caretModel.offset != offset) return@invokeLater

                try {
                    // Split into sub-edits for TAB cycling
                    val oldText = document.getText(
                        com.intellij.openapi.util.TextRange(docEdit.startOffset, docEdit.endOffset)
                    )
                    val subEdits = splitEditIntoSubEdits(oldText, docEdit.newText, docEdit.startOffset)

                    if (subEdits.isEmpty()) return@invokeLater

                    val disposable = showMultiEditGhostText(editor, subEdits, isJump)
                    currentEdits = subEdits
                    currentEditIndex = 0
                    currentInlayDisposable = disposable
                    currentEditor = editor
                    currentIsJump = isJump
                } catch (_: IllegalStateException) {
                    // Inlay creation failed, ignore
                }
            }
        }
    }

    fun acceptSuggestion() {
        if (!hasSuggestion) return
        val editor = currentEditor ?: return
        val edit = currentEdits[currentEditIndex]

        clearGhostText(currentInlayDisposable)

        applyEdit(project, editor, edit)

        // Adjust remaining sub-edits for the size delta
        currentEdits = advanceSubEdit(currentEdits, currentEditIndex, edit)
        currentEditIndex++

        if (hasSuggestion) {
            isCyclingSubEdits = true
            // Re-render remaining sub-edits
            try {
                val remaining = currentEdits.subList(currentEditIndex, currentEdits.size)
                val disposable = showMultiEditGhostText(editor, remaining, currentIsJump)
                currentInlayDisposable = disposable
            } catch (_: IllegalStateException) {
                dismissSuggestion()
            }
        } else {
            isCyclingSubEdits = false
            currentIsJump = false
            currentEdits = emptyList()
            currentEditIndex = 0
            currentInlayDisposable = null
            currentEditor = null
        }
    }

    fun dismissSuggestion() {
        currentRequestFuture?.cancel(true)
        currentRequestFuture = null
        clearGhostText(currentInlayDisposable)
        isCyclingSubEdits = false
        currentIsJump = false
        currentEdits = emptyList()
        currentEditIndex = 0
        currentInlayDisposable = null
        currentEditor = null
    }

    /**
     * Runs on a pooled thread the first time a completion is triggered.
     * If the server is down and the URL is local, offers to start it.
     */
    private fun performServerCheck(serverManager: ServerManager) {
        val state = serverManager.checkServerHealth()
        if (state == ServerManager.ServerState.RUNNING) return

        val settings = SettingsState.getInstance()
        val serverUrl = settings.serverUrl
        val isLocal = ServerManager.isLocalUrl(serverUrl)

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup("completamente")

            if (!isLocal) {
                notificationGroup.createNotification(
                    "completamente",
                    "Completion server at $serverUrl is not reachable.",
                    NotificationType.WARNING
                ).notify(project)
                return@invokeLater
            }

            val commandConfigured = settings.serverCommand.isNotBlank()

            if (!commandConfigured) {
                notificationGroup.createNotification(
                    "completamente",
                    "Completion server is not running. Configure the server command in Settings \u2192 Tools \u2192 completamente.",
                    NotificationType.INFORMATION
                ).notify(project)
                return@invokeLater
            }

            val notification = notificationGroup.createNotification(
                "completamente",
                "Completion server is not running.",
                NotificationType.INFORMATION
            )

            notification.addAction(object : AnAction("Start Server") {
                override fun actionPerformed(e: AnActionEvent) {
                    notification.expire()
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val started = serverManager.startServer()
                        if (!started) {
                            ApplicationManager.getApplication().invokeLater {
                                if (project.isDisposed) return@invokeLater
                                notificationGroup.createNotification(
                                    "completamente",
                                    "Failed to start the completion server. Check the binary and model paths in Settings \u2192 Tools \u2192 completamente.",
                                    NotificationType.ERROR
                                ).notify(project)
                            }
                        }
                    }
                }
            })

            notification.addAction(object : AnAction("Not Now") {
                override fun actionPerformed(e: AnActionEvent) {
                    notification.expire()
                    serverManager.userDeclinedServerStart = true
                }
            })

            notification.notify(project)
        }
    }
}
