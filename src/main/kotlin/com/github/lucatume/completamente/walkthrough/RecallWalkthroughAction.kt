package com.github.lucatume.completamente.walkthrough

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Component
import java.text.DateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Open a chooser of cached walkthroughs and rehydrate the selected one into a live session.
 * Stale entries (referenced files modified since the walkthrough was created) are evicted by
 * [WalkthroughCache.listFresh] before the chooser is shown.
 */
class RecallWalkthroughAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val vf = psiFile?.virtualFile
        val enabled = if (project != null && editor != null && vf != null) {
            ReadAction.compute<Boolean, RuntimeException> {
                ProjectFileIndex.getInstance(project).isInContent(vf)
            }
        } else false
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val cache = WalkthroughCache.getInstance(project)
        val entries = cache.listFresh()
        if (entries.isEmpty()) {
            notify(project, "No cached walkthroughs to recall.", NotificationType.INFORMATION)
            return
        }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setTitle("Recall Walkthrough")
            .setRenderer(EntryRenderer())
            .setItemChosenCallback { entry -> onChosen(project, editor, cache, entry) }
            .setNamerForFiltering { it.prompt }
            .createPopup()
        popup.showInBestPositionFor(e.dataContext)
    }

    private fun onChosen(project: Project, editor: Editor, cache: WalkthroughCache, entry: EntryDTO) {
        val walkthrough = cache.rehydrate(entry) ?: run {
            cache.remove(entry.id)
            notify(project, "Cached walkthrough is corrupt and was removed.", NotificationType.WARNING)
            return
        }

        val service = WalkthroughService.getInstance(project)
        // Trigger-again semantics: dispose any active session and cancel any in-flight task,
        // mirroring WalkthroughAction.actionPerformed.
        service.cancelInFlight()
        service.setActive(null)

        val session = WalkthroughSession.build(project, walkthrough, editor)
        if (session == null) {
            cache.remove(entry.id)
            notify(
                project,
                "Cached walkthrough is no longer applicable (every step unresolvable). Removed from cache.",
                NotificationType.WARNING
            )
            return
        }
        Disposer.register(service, session)
        service.setActive(session)
        session.start()
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("completamente")
            .createNotification(message, type)
            .notify(project)
    }

    private class EntryRenderer : ListCellRenderer<EntryDTO> {
        private val timeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        override fun getListCellRendererComponent(
            list: JList<out EntryDTO>,
            value: EntryDTO,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(0, 2))
            panel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            val title = JBLabel("${timeFormat.format(Date(value.createdAtMillis))} — ${fileName(value.originFilePath)}")
            val subtitle = JBLabel(truncate(value.prompt, 80)).apply {
                foreground = com.intellij.ui.JBColor.GRAY
            }
            panel.add(title, BorderLayout.NORTH)
            panel.add(subtitle, BorderLayout.CENTER)
            if (isSelected) {
                panel.background = list.selectionBackground
                title.foreground = list.selectionForeground
                subtitle.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                title.foreground = list.foreground
            }
            panel.isOpaque = true
            return panel
        }

        private fun fileName(path: String): String =
            path.substringAfterLast('/').ifEmpty { path }

        private fun truncate(s: String, max: Int): String {
            val flat = s.replace('\n', ' ').replace('\r', ' ').trim()
            return if (flat.length <= max) flat else flat.take(max - 1) + "…"
        }
    }
}
