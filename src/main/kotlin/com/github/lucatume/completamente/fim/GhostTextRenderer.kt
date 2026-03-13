package com.github.lucatume.completamente.fim

import com.github.lucatume.completamente.completion.EditRegion
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints

private val GHOST_COLOR = Color(150, 150, 150)
private val GRADIENT_START = Color(240, 0, 220)   // Neon pink.
private val GRADIENT_END = Color(170, 70, 255)    // Violet.
private val JUMP_NEW_FG = Color.WHITE
private val JUMP_NEW_BG = Color(0, 191, 0)
private val JUMP_OLD_FG = Color.WHITE
private val JUMP_OLD_BG = Color(218, 54, 51)
private const val BG_PAD = 3

private fun drawGradientString(g: Graphics, text: String, x: Int, y: Int, width: Int) {
    val g2d = g as? Graphics2D ?: run { g.color = GRADIENT_START; g.drawString(text, x, y); return }
    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    if (width <= 0) { g2d.color = GRADIENT_START; g2d.drawString(text, x, y); return }
    g2d.paint = LinearGradientPaint(
        x.toFloat(), 0f, (x + width).toFloat(), 0f,
        floatArrayOf(0f, 1f), arrayOf(GRADIENT_START, GRADIENT_END)
    )
    g2d.drawString(text, x, y)
}

private class InlineGhostRenderer(
    private val text: String,
    private val color: Color = GHOST_COLOR,
    private val bgColor: Color? = null
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fontMetrics = editor.contentComponent.getFontMetrics(ghostFont(editor))
        val pad = if (bgColor != null) 2 * BG_PAD else 0
        return fontMetrics.stringWidth(text) + pad
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        if (bgColor != null) {
            g.color = bgColor
            g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
        }
        g.font = ghostFont(editor)
        val textX = if (bgColor != null) targetRegion.x + BG_PAD else targetRegion.x
        if (bgColor == null) {
            drawGradientString(g, text, textX, targetRegion.y + editor.ascent, targetRegion.width)
        } else {
            g.color = color
            g.drawString(text, textX, targetRegion.y + editor.ascent)
        }
    }
}

private class BlockGhostRenderer(
    private val lines: List<String>,
    private val color: Color = GHOST_COLOR,
    private val bgColor: Color? = null
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fontMetrics = editor.contentComponent.getFontMetrics(ghostFont(editor))
        val pad = if (bgColor != null) 2 * BG_PAD else 0
        return (lines.maxOfOrNull { fontMetrics.stringWidth(it) } ?: 0) + pad
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight * lines.size
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val lineHeight = editor.lineHeight
        if (bgColor != null) {
            g.color = bgColor
            g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
        }
        g.font = ghostFont(editor)
        val textX = if (bgColor != null) targetRegion.x + BG_PAD else targetRegion.x
        for ((i, line) in lines.withIndex()) {
            val y = targetRegion.y + editor.ascent + (i * lineHeight)
            if (bgColor == null) {
                val fm = g.fontMetrics
                drawGradientString(g, line, textX, y, fm.stringWidth(line))
            } else {
                g.color = color
                g.drawString(line, textX, y)
            }
        }
    }
}

private fun ghostFont(editor: Editor): Font =
    editor.colorsScheme.getFont(EditorFontType.ITALIC)

fun showGhostText(editor: Editor, offset: Int, text: String, isJump: Boolean = false): Disposable {
    val lines = text.split("\n")
    val firstLine = lines.first()
    val remainingLines = lines.drop(1)
    val fgColor = if (isJump) JUMP_NEW_FG else GHOST_COLOR
    val bgColor = if (isJump) JUMP_NEW_BG else null

    val inlays = mutableListOf<Inlay<*>>()

    // First line: inline element after the cursor
    if (firstLine.isNotEmpty()) {
        val inlineInlay = editor.inlayModel.addInlineElement(offset, true, InlineGhostRenderer(firstLine, fgColor, bgColor))
            ?: throw IllegalStateException("Failed to create inline inlay element")
        inlays.add(inlineInlay)
    }

    // Remaining lines: block element below the cursor line
    if (remainingLines.isNotEmpty()) {
        val blockInlay = editor.inlayModel.addBlockElement(
            offset, true, false, 0, BlockGhostRenderer(remainingLines, fgColor, bgColor)
        ) ?: throw IllegalStateException("Failed to create block inlay element")
        inlays.add(blockInlay)
    }

    if (inlays.isEmpty()) {
        throw IllegalStateException("No inlay elements created")
    }

    return Disposable {
        inlays.forEach { it.dispose() }
    }
}

fun showEditGhostText(editor: Editor, editRegion: EditRegion, isJump: Boolean = false): Disposable {
    val isInsertion = editRegion.startOffset == editRegion.endOffset

    val inlays = mutableListOf<Inlay<*>>()
    val highlighters = mutableListOf<RangeHighlighter>()

    if (isInsertion) {
        // Pure insertion: show ghost text at insertion point
        return showGhostText(editor, editRegion.startOffset, editRegion.newText, isJump)
    }

    val docLength = editor.document.textLength
    val safeEnd = editRegion.endOffset.coerceAtMost(docLength)
    val safeStart = editRegion.startOffset.coerceAtMost(docLength)

    // Find common prefix between old text and new text to avoid
    // striking through and re-rendering text the user already typed.
    val oldText = editor.document.getText(com.intellij.openapi.util.TextRange(safeStart, safeEnd))
    val commonPrefixLen = oldText.zip(editRegion.newText).takeWhile { (a, b) -> a == b }.size
    val adjustedStart = safeStart + commonPrefixLen
    val adjustedNewText = editRegion.newText.substring(commonPrefixLen)

    val adjustedIsDeletion = adjustedNewText.isEmpty()

    // Old text styling: strikethrough for inline, red bg + white fg for jump
    if (adjustedStart < safeEnd) {
        val oldTextAttrs = if (isJump) {
            TextAttributes().apply {
                effectType = com.intellij.openapi.editor.markup.EffectType.STRIKEOUT
                effectColor = JUMP_OLD_FG
                foregroundColor = JUMP_OLD_FG
                backgroundColor = JUMP_OLD_BG
            }
        } else {
            TextAttributes().apply {
                effectType = com.intellij.openapi.editor.markup.EffectType.STRIKEOUT
                effectColor = Color(255, 100, 100)
                foregroundColor = Color(255, 100, 100)
            }
        }
        val highlighter = editor.markupModel.addRangeHighlighter(
            adjustedStart, safeEnd,
            HighlighterLayer.LAST + 1,
            oldTextAttrs,
            HighlighterTargetArea.EXACT_RANGE
        )
        highlighters.add(highlighter)
    }

    // New text: gray ghost for inline, green bg + white fg for jump
    if (!adjustedIsDeletion) {
        val ghostText = adjustedNewText.trimEnd('\n')
        if (ghostText.isNotEmpty()) {
            val lines = ghostText.split("\n")
            val firstLine = lines.first()
            val remainingLines = lines.drop(1)
            val fgColor = if (isJump) JUMP_NEW_FG else GHOST_COLOR
            val bgColor = if (isJump) JUMP_NEW_BG else null

            // For same-line replacements, place inlays at safeEnd so ghost text
            // renders after the struck-through old text: ~old~new.
            // For cross-line replacements, place at adjustedStart so the first
            // ghost line stays inline on the cursor line instead of jumping to
            // the next line.
            val oldTextSpansLines = oldText.contains('\n')
            val inlayOffset = if (oldTextSpansLines) adjustedStart else safeEnd

            if (firstLine.isNotEmpty()) {
                val inlineInlay = editor.inlayModel.addInlineElement(
                    inlayOffset, true, InlineGhostRenderer(firstLine, fgColor, bgColor)
                )
                if (inlineInlay != null) inlays.add(inlineInlay)
            }

            if (remainingLines.isNotEmpty()) {
                val blockInlay = editor.inlayModel.addBlockElement(
                    inlayOffset, true, false, 0, BlockGhostRenderer(remainingLines, fgColor, bgColor)
                )
                if (blockInlay != null) inlays.add(blockInlay)
            }
        }
    }

    return Disposable {
        inlays.forEach { it.dispose() }
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
    }
}

fun showMultiEditGhostText(editor: Editor, edits: List<EditRegion>, isJump: Boolean = false): Disposable {
    val disposables = edits.map { showEditGhostText(editor, it, isJump) }
    return Disposable {
        disposables.forEach { it.dispose() }
    }
}

fun clearGhostText(disposable: Disposable?) {
    disposable?.dispose()
}
