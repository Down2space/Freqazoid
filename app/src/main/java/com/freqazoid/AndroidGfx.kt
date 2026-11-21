package com.freqazoid

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/** [Gfx] on an Android Canvas. Paints and paths are reused; nothing is allocated per point. */
class AndroidGfx : Gfx {
    var canvas: Canvas? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private var plus = false
    private val sans = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL); private val sansB = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); private val monoB = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private fun c(): Canvas = canvas!!
    private fun stroke(color: Int, width: Float, round: Boolean = true) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = width; p.color = color
        p.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT; p.strokeJoin = Paint.Join.ROUND; if (plus) p.blendMode = BlendMode.PLUS }
    private fun fillP(color: Int) { p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = color; if (plus) p.blendMode = BlendMode.PLUS }
    override fun fill(color: Int) { c().drawColor(color) }
    override fun circle(cx: Float, cy: Float, r: Float, color: Int, stroke: Float) { if (stroke > 0) stroke(color, stroke) else fillP(color); c().drawCircle(cx, cy, r, p) }
    override fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int, width: Float, round: Boolean) {
        stroke(color, width, round); oval.set(cx - r, cy - r, cx + r, cy + r)
        c().drawArc(oval, Math.toDegrees(start.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), false, p) }
    override fun quad(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, color: Int, width: Float) {
        stroke(color, width); path.rewind(); path.moveTo(x1, y1); path.quadTo(cx, cy, x2, y2); c().drawPath(path, p) }
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) { stroke(color, width, false); c().drawLine(x1, y1, x2, y2, p) }
    override fun poly(xs: FloatArray, ys: FloatArray, n: Int, color: Int, width: Float, fill: Int) {
        path.rewind(); path.fillType = Path.FillType.EVEN_ODD; path.moveTo(xs[0], ys[0]); for (i in 1 until n) path.lineTo(xs[i], ys[i])
        if (fill != 0) { fillP(fill); c().drawPath(path, p) }
        stroke(color, width); c().drawPath(path, p) }
    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { fillP(color); c().drawRect(x, y, x + w, y + h, p) }
    private fun font(size: Float, bold: Boolean, m: Boolean) { p.textSize = size; p.typeface = if (m) (if (bold) monoB else mono) else (if (bold) sansB else sans) }
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, mono: Boolean, align: Gfx.Align) {
        fillP(color); font(size, bold, mono)
        p.textAlign = when (align) { Gfx.Align.LEFT -> Paint.Align.LEFT; Gfx.Align.CENTER -> Paint.Align.CENTER; Gfx.Align.RIGHT -> Paint.Align.RIGHT }
        val fm = p.fontMetrics; c().drawText(s, x, y - (fm.ascent + fm.descent) / 2, p) }
    override fun textWidth(s: String, size: Float, bold: Boolean, mono: Boolean): Float { font(size, bold, mono); return p.measureText(s) }
    override fun additive(on: Boolean) { plus = on }
}
