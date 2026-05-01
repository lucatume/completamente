package com.github.lucatume.completamente.walkthrough

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.font.GlyphVector

/** `Graphics2D` that captures paint submissions; everything else forwards to a delegate. */
internal class RecordingGraphics2D(private val delegate: Graphics2D) : Graphics2D() {

    /** Polygons in submission order. The renderer reuses its `IntArray(3)`s across paints, so
     *  each entry holds a fresh `xs`/`ys` snapshot taken at record time. */
    val polygons: MutableList<Triple<IntArray, IntArray, Int>> = mutableListOf()

    data class CapturedFillRect(val rect: Rectangle, val color: Color)
    data class CapturedDrawRect(val rect: Rectangle, val color: Color)
    data class CapturedDrawLine(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val color: Color)
    data class CapturedDrawString(val text: String, val x: Int, val y: Int, val color: Color)
    data class CapturedDrawGlyphVector(val gv: GlyphVector, val x: Float, val y: Float, val color: Color)

    val fillRects: MutableList<CapturedFillRect> = mutableListOf()
    val drawnRects: MutableList<CapturedDrawRect> = mutableListOf()
    val drawnLines: MutableList<CapturedDrawLine> = mutableListOf()
    val drawnStrings: MutableList<CapturedDrawString> = mutableListOf()
    val drawnGlyphVectors: MutableList<CapturedDrawGlyphVector> = mutableListOf()

    override fun fillPolygon(xs: IntArray, ys: IntArray, npoints: Int) {
        polygons += Triple(xs.copyOf(npoints), ys.copyOf(npoints), npoints)
        delegate.fillPolygon(xs, ys, npoints)
    }

    override fun drawPolygon(xs: IntArray, ys: IntArray, npoints: Int) {
        delegate.drawPolygon(xs, ys, npoints)
    }

    override fun setColor(c: java.awt.Color?) { delegate.color = c }
    override fun getColor(): java.awt.Color = delegate.color
    override fun setFont(font: java.awt.Font?) { delegate.font = font }
    override fun getFont(): java.awt.Font = delegate.font
    override fun setPaintMode() = delegate.setPaintMode()
    override fun setXORMode(c: java.awt.Color) = delegate.setXORMode(c)
    override fun getFontMetrics(font: java.awt.Font): java.awt.FontMetrics = delegate.getFontMetrics(font)
    override fun getClipBounds(): java.awt.Rectangle? = delegate.clipBounds
    override fun clipRect(x: Int, y: Int, w: Int, h: Int) = delegate.clipRect(x, y, w, h)
    override fun setClip(x: Int, y: Int, w: Int, h: Int) = delegate.setClip(x, y, w, h)
    override fun getClip(): java.awt.Shape? = delegate.clip
    override fun setClip(clip: java.awt.Shape?) { delegate.clip = clip }
    override fun copyArea(x: Int, y: Int, w: Int, h: Int, dx: Int, dy: Int) = delegate.copyArea(x, y, w, h, dx, dy)
    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        drawnLines += CapturedDrawLine(x1, y1, x2, y2, delegate.color)
        delegate.drawLine(x1, y1, x2, y2)
    }
    override fun fillRect(x: Int, y: Int, w: Int, h: Int) {
        fillRects += CapturedFillRect(Rectangle(x, y, w, h), delegate.color)
        delegate.fillRect(x, y, w, h)
    }
    // Override drawRect so the JDK abstract Graphics fallback (which would cascade through
    // this.drawLine 4 times) cannot pollute drawnLines. delegate.drawRect is self-contained.
    override fun drawRect(x: Int, y: Int, w: Int, h: Int) {
        drawnRects += CapturedDrawRect(Rectangle(x, y, w, h), delegate.color)
        delegate.drawRect(x, y, w, h)
    }
    override fun clearRect(x: Int, y: Int, w: Int, h: Int) = delegate.clearRect(x, y, w, h)
    override fun drawRoundRect(x: Int, y: Int, w: Int, h: Int, ax: Int, ay: Int) =
        delegate.drawRoundRect(x, y, w, h, ax, ay)
    override fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, ax: Int, ay: Int) =
        delegate.fillRoundRect(x, y, w, h, ax, ay)
    override fun drawOval(x: Int, y: Int, w: Int, h: Int) = delegate.drawOval(x, y, w, h)
    override fun fillOval(x: Int, y: Int, w: Int, h: Int) = delegate.fillOval(x, y, w, h)
    override fun drawArc(x: Int, y: Int, w: Int, h: Int, sa: Int, ae: Int) = delegate.drawArc(x, y, w, h, sa, ae)
    override fun fillArc(x: Int, y: Int, w: Int, h: Int, sa: Int, ae: Int) = delegate.fillArc(x, y, w, h, sa, ae)
    override fun drawPolyline(xs: IntArray, ys: IntArray, n: Int) = delegate.drawPolyline(xs, ys, n)
    override fun drawImage(img: java.awt.Image?, x: Int, y: Int, observer: java.awt.image.ImageObserver?) =
        delegate.drawImage(img, x, y, observer)
    override fun drawImage(img: java.awt.Image?, x: Int, y: Int, w: Int, h: Int, observer: java.awt.image.ImageObserver?) =
        delegate.drawImage(img, x, y, w, h, observer)
    override fun drawImage(img: java.awt.Image?, x: Int, y: Int, bg: java.awt.Color?, observer: java.awt.image.ImageObserver?) =
        delegate.drawImage(img, x, y, bg, observer)
    override fun drawImage(img: java.awt.Image?, x: Int, y: Int, w: Int, h: Int, bg: java.awt.Color?, observer: java.awt.image.ImageObserver?) =
        delegate.drawImage(img, x, y, w, h, bg, observer)
    override fun drawImage(
        img: java.awt.Image?,
        dx1: Int, dy1: Int, dx2: Int, dy2: Int,
        sx1: Int, sy1: Int, sx2: Int, sy2: Int,
        observer: java.awt.image.ImageObserver?,
    ): Boolean = delegate.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer)
    override fun drawImage(
        img: java.awt.Image?,
        dx1: Int, dy1: Int, dx2: Int, dy2: Int,
        sx1: Int, sy1: Int, sx2: Int, sy2: Int,
        bg: java.awt.Color?,
        observer: java.awt.image.ImageObserver?,
    ): Boolean = delegate.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, bg, observer)
    override fun create(): Graphics = delegate.create()
    override fun translate(x: Int, y: Int) = delegate.translate(x, y)
    override fun dispose() = delegate.dispose()

    // -- Graphics2D abstract members — forward to delegate.
    override fun draw(s: java.awt.Shape?) = delegate.draw(s)
    override fun drawImage(img: java.awt.Image?, xform: java.awt.geom.AffineTransform?, obs: java.awt.image.ImageObserver?): Boolean =
        delegate.drawImage(img, xform, obs)
    override fun drawImage(img: java.awt.image.BufferedImage?, op: java.awt.image.BufferedImageOp?, x: Int, y: Int) =
        delegate.drawImage(img, op, x, y)
    override fun drawRenderedImage(img: java.awt.image.RenderedImage?, xform: java.awt.geom.AffineTransform?) =
        delegate.drawRenderedImage(img, xform)
    override fun drawRenderableImage(img: java.awt.image.renderable.RenderableImage?, xform: java.awt.geom.AffineTransform?) =
        delegate.drawRenderableImage(img, xform)
    override fun drawString(str: String, x: Int, y: Int) {
        drawnStrings += CapturedDrawString(str, x, y, delegate.color)
        delegate.drawString(str, x, y)
    }
    override fun drawString(str: String, x: Float, y: Float) = delegate.drawString(str, x, y)
    override fun drawString(it: java.text.AttributedCharacterIterator?, x: Int, y: Int) = delegate.drawString(it, x, y)
    override fun drawString(it: java.text.AttributedCharacterIterator?, x: Float, y: Float) = delegate.drawString(it, x, y)
    override fun drawGlyphVector(g: GlyphVector?, x: Float, y: Float) {
        if (g != null) drawnGlyphVectors += CapturedDrawGlyphVector(g, x, y, delegate.color)
        delegate.drawGlyphVector(g, x, y)
    }
    override fun fill(s: java.awt.Shape?) = delegate.fill(s)
    override fun hit(rect: java.awt.Rectangle?, s: java.awt.Shape?, onStroke: Boolean): Boolean = delegate.hit(rect, s, onStroke)
    override fun getDeviceConfiguration(): java.awt.GraphicsConfiguration = delegate.deviceConfiguration
    override fun setComposite(comp: java.awt.Composite?) { delegate.composite = comp }
    override fun setPaint(paint: java.awt.Paint?) { delegate.paint = paint }
    override fun setStroke(s: java.awt.Stroke?) { delegate.stroke = s }
    override fun setRenderingHint(key: java.awt.RenderingHints.Key?, value: Any?) = delegate.setRenderingHint(key, value)
    override fun getRenderingHint(key: java.awt.RenderingHints.Key?): Any? = delegate.getRenderingHint(key)
    override fun setRenderingHints(hints: MutableMap<*, *>?) = delegate.setRenderingHints(hints)
    override fun addRenderingHints(hints: MutableMap<*, *>?) = delegate.addRenderingHints(hints)
    override fun getRenderingHints(): java.awt.RenderingHints = delegate.renderingHints
    override fun translate(tx: Double, ty: Double) = delegate.translate(tx, ty)
    override fun rotate(theta: Double) = delegate.rotate(theta)
    override fun rotate(theta: Double, x: Double, y: Double) = delegate.rotate(theta, x, y)
    override fun scale(sx: Double, sy: Double) = delegate.scale(sx, sy)
    override fun shear(shx: Double, shy: Double) = delegate.shear(shx, shy)
    override fun transform(xform: java.awt.geom.AffineTransform?) = delegate.transform(xform)
    override fun setTransform(xform: java.awt.geom.AffineTransform?) { delegate.transform = xform }
    override fun getTransform(): java.awt.geom.AffineTransform = delegate.transform
    override fun getPaint(): java.awt.Paint = delegate.paint
    override fun getComposite(): java.awt.Composite = delegate.composite
    override fun setBackground(color: java.awt.Color?) { delegate.background = color }
    override fun getBackground(): java.awt.Color = delegate.background
    override fun getStroke(): java.awt.Stroke = delegate.stroke
    override fun clip(s: java.awt.Shape?) = delegate.clip(s)
    override fun getFontRenderContext(): java.awt.font.FontRenderContext = delegate.fontRenderContext
}
