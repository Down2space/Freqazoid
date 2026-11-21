package com.freqazoid
import java.awt.*
import java.awt.geom.*
import java.awt.image.BufferedImage
/** Java2D implementation of [Gfx] used only to render preview images off-device. */
class Java2dGfx(val img: BufferedImage) : Gfx {
    private val g = img.createGraphics().apply { setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE) }
    private fun c(argb: Int) = Color(argb, true)
    private fun font(size: Float, bold: Boolean, mono: Boolean) = Font(if (mono) "DejaVu Sans Mono" else "DejaVu Sans", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
    override fun fill(color: Int) { g.color = c(color); g.fillRect(0, 0, img.width, img.height) }
    override fun circle(cx: Float, cy: Float, r: Float, color: Int, stroke: Float) { g.color = c(color); val e = Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r); if (stroke > 0) { g.stroke = BasicStroke(stroke); g.draw(e) } else g.fill(e) }
    override fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int, width: Float, round: Boolean) {
        g.color = c(color); g.stroke = BasicStroke(width, if (round) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        g.draw(Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, -Math.toDegrees(start.toDouble()).toFloat(), -Math.toDegrees(sweep.toDouble()).toFloat(), Arc2D.OPEN)) }
    override fun quad(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, color: Int, width: Float) { g.color = c(color); g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(QuadCurve2D.Float(x1, y1, cx, cy, x2, y2)) }
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) { g.color = c(color); g.stroke = BasicStroke(width); g.draw(Line2D.Float(x1, y1, x2, y2)) }
    override fun poly(xs: FloatArray, ys: FloatArray, n: Int, color: Int, width: Float, fill: Int) {
        val p = Path2D.Float(Path2D.WIND_EVEN_ODD); p.moveTo(xs[0], ys[0]); for (i in 1 until n) p.lineTo(xs[i], ys[i])
        if (fill != 0) { g.color = c(fill); g.fill(p) }
        g.color = c(color); g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(p) }
    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { g.color = c(color); g.fill(Rectangle2D.Float(x, y, w, h)) }
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, mono: Boolean, align: Gfx.Align) {
        g.font = font(size, bold, mono); val fm = g.fontMetrics; val tw = fm.stringWidth(s)
        val dx = when (align) { Gfx.Align.LEFT -> 0f; Gfx.Align.CENTER -> -tw / 2f; Gfx.Align.RIGHT -> -tw.toFloat() }
        g.color = c(color); g.drawString(s, x + dx, y + (fm.ascent - fm.descent) / 2f) }
    override fun textWidth(s: String, size: Float, bold: Boolean, mono: Boolean): Float { g.font = font(size, bold, mono); return g.fontMetrics.stringWidth(s).toFloat() }
    override fun additive(on: Boolean) { /* Java2D has no additive blend; previews draw normally */ }
    fun clipCircle() { g.clip = Ellipse2D.Float(0f, 0f, img.width.toFloat(), img.height.toFloat()) }
}
