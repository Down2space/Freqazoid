package com.freqazoid.membrane

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.freakazoid.watch.ColorMath
import com.freqazoid.analysis.MonitorModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Inspection cursor: [column] −1 = none; [age] −1 = live spectrum, otherwise waterfall row (0 newest). */
data class Inspect(val column: Int = -1, val age: Int = -1)

/** Plot rectangles for one monitor mode inside the circular viewport (pixels, absolute). */
private class MonitorLayout(val spectrum: Rect?, val history: Rect, val labelsY: Float)

private fun layoutFor(mode: DisplayMode, size: Size, plotTop: Float, plotBottom: Float, density: Float): MonitorLayout {
    val r = size.minDimension / 2; val cx = size.width / 2; val cy = size.height / 2
    val labelH = 13 * density
    val top = cy + plotTop; val bottom = cy + plotBottom
    val rDp = r / density
    // Width limited by the circle at the plot's top and bottom edges (corners inside the round screen).
    val half = min(RoundSafeArea.maxWidth(rDp, plotTop / density, 0f, 6f), RoundSafeArea.maxWidth(rDp, plotBottom / density, 0f, 6f)) * density / 2
    val left = cx - half; val right = cx + half
    val dataBottom = bottom - labelH
    return if (mode == DisplayMode.SPECTRUM) {
        val split = top + (dataBottom - top) * .70f
        MonitorLayout(Rect(left, top, right, split), Rect(left, split + 2 * density, right, dataBottom), bottom - 2 * density)
    } else MonitorLayout(null, Rect(left, top, right, dataBottom), bottom - 2 * density)
}

private fun DrawScope.text(paint: Paint, value: String, x: Float, y: Float, sizePx: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
    paint.textSize = sizePx; paint.color = color; paint.textAlign = align
    drawIntoCanvas { it.nativeCanvas.drawText(value, x, y, paint) }
}

@Composable
fun MonitorCanvas(
    model: MonitorModel,
    version: Long,
    mode: DisplayMode,
    plotTop: Float,      // px, relative to viewport centre
    plotBottom: Float,
    inspect: Inspect,
    onInspect: (Inspect) -> Unit,
    onView: (MonitorModel.View) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = remember(model) { MonitorModel.View(model.columns, model.rows) }
    val levelBitmap = remember(model) { Bitmap.createBitmap(model.columns, model.rows, Bitmap.Config.ARGB_8888) }
    val deltaBitmap = remember(model) { Bitmap.createBitmap(model.columns, model.rows, Bitmap.Config.ARGB_8888) }
    val levelImage = remember(levelBitmap) { levelBitmap.asImageBitmap() }
    val deltaImage = remember(deltaBitmap) { deltaBitmap.asImageBitmap() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT } }
    val callback by rememberUpdatedState(onView)
    // Copy shared state once per NEW analysis frame (not per redraw), on the main thread.
    val shownVersion = remember(version) {
        model.copyTo(view)
        levelBitmap.setPixels(view.levelPixels, 0, model.columns, 0, 0, model.columns, model.rows)
        deltaBitmap.setPixels(view.deltaPixels, 0, model.columns, 0, 0, model.columns, model.rows)
        view.version
    }
    LaunchedEffect(shownVersion) { callback(view) }
    val hostView = LocalView.current
    val currentInspect by rememberUpdatedState(inspect)
    val inspectCallback by rememberUpdatedState(onInspect)
    // Plain holder (not Compose state): written during draw, read only by gesture handlers.
    val lastLayout = remember { arrayOfNulls<MonitorLayout>(1) }

    fun hit(p: Offset, keepRowKind: Boolean): Inspect? {
        val l = lastLayout[0] ?: return null
        val all = l.spectrum?.let { Rect(it.left, it.top, it.right, l.history.bottom) } ?: l.history
        if (!keepRowKind && !all.inflate(12f).contains(p)) return null
        val column = ((p.x - all.left) / all.width * model.columns).toInt().coerceIn(0, model.columns - 1)
        val inHistory = p.y >= l.history.top
        val age = if (inHistory) ((p.y - l.history.top) / l.history.height * model.rows).toInt().coerceIn(0, model.rows - 1) else -1
        return Inspect(column, age)
    }

    Canvas(
        modifier
            .semantics { contentDescription = "${mode.label} plot. Tap or drag to inspect frequency and level. ${Readouts.spectrumLine(view)}" }
            .pointerInput(mode) {
                // Inspection drags inside the plot must not trigger Wear OS swipe-to-dismiss.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val inPlot = hit(down.position, false) != null
                    if (inPlot) hostView.parent?.requestDisallowInterceptTouchEvent(true)
                    do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                    if (inPlot) hostView.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            .pointerInput(mode) {
                detectTapGestures(onTap = { p ->
                    val h = hit(p, false)
                    inspectCallback(if (h == null || (currentInspect.column >= 0 && currentInspect.column == h.column && currentInspect.age == h.age)) Inspect() else h)
                })
            }
            .pointerInput(mode) {
                detectDragGestures(onDragStart = { p -> hit(p, false)?.let { inspectCallback(it) } }) { change, _ ->
                    hit(change.position, true)?.let { inspectCallback(it) }
                }
            }
    ) {
        @Suppress("UNUSED_VARIABLE") val tick = shownVersion   // redraw when a new frame was copied
        val radius = size.minDimension / 2
        val circle = Path().apply { addOval(Rect(center - Offset(radius, radius), center + Offset(radius, radius))) }
        val layout = layoutFor(mode, size, plotTop, plotBottom, density)
        lastLayout[0] = layout
        clipPath(circle) {
            drawRect(BackgroundColor)
            val cols = model.columns
            layout.spectrum?.let { r ->
                fun y(db: Double) = (r.bottom - ((db - MonitorModel.FLOOR_DB) / -MonitorModel.FLOOR_DB).coerceIn(0.0, 1.0) * r.height).toFloat()
                val step = if (r.height < 40 * density) 40 else 20
                var db = -100
                while (db <= 0) {
                    drawLine(Color(0xff3a3350), Offset(r.left, y(db.toDouble())), Offset(r.right, y(db.toDouble())), 1f)
                    if (r.height >= 28 * density) text(paint, db.toString(), r.left + 2 * density, y(db.toDouble()) - 1.5f * density, 10f * density * fontScale, 0xffd6cce4.toInt(), Paint.Align.LEFT)
                    db += step
                }
                // Live bars; a run of columns sharing one coarse FFT bin is drawn as ONE wide bar.
                val w = r.width / cols
                var x = 0
                while (x < cols) {
                    var end = x + 1
                    while (end < cols && view.repeated[end] && view.repeated[x] && view.bands[end] == view.bands[x]) end++
                    val level = view.bands[x]
                    if (level > MonitorModel.NO_DATA) {
                        val top = y(level)
                        drawRect(Color(ColorMath.fromDb(level)), Offset(r.left + x * w, top), Size((end - x) * w, r.bottom - top))
                    }
                    x = end
                }
                val peak = Path(); var started = false
                for (i in 0 until cols) {
                    val p = view.peaks[i]; if (p <= MonitorModel.NO_DATA) { started = false; continue }
                    val px = r.left + (i + .5f) * w; val py = y(p)
                    if (!started) { peak.moveTo(px, py); started = true } else peak.lineTo(px, py)
                }
                drawPath(peak, Color(0xffeeeeee), style = Stroke(max(1f, density * .8f)))
            }
            val h = layout.history
            val image = if (mode == DisplayMode.DELTA) deltaImage else levelImage
            drawRect(Color.Black, h.topLeft, h.size)
            drawImage(image, IntOffset.Zero, IntSize(cols, model.rows), IntOffset(h.left.roundToInt(), h.top.roundToInt()),
                IntSize(h.width.roundToInt().coerceAtLeast(1), h.height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.None)
            // Frequency labels + palette legend line.
            val ly = layout.labelsY; val ts = 10f * density * fontScale
            val all = layout.spectrum?.let { Rect(it.left, it.top, it.right, h.bottom) } ?: h
            text(paint, Readouts.hzShort(view.lowHz) + " Hz", all.left, ly, ts, 0xffe6e0f0.toInt(), Paint.Align.LEFT)
            text(paint, Readouts.hzShort(view.highHz) + " Hz", all.right, ly, ts, 0xffe6e0f0.toInt(), Paint.Align.RIGHT)
            if (mode == DisplayMode.SPECTRUM) text(paint, "log · dBFS", center.x, ly, ts, 0xffc7bfd6.toInt())
            else {
                val lw = min(all.width * .32f, 64 * density); val lx = center.x - lw / 2; val lh = 5 * density
                for (i in 0 until 32) drawRect(Color(ColorMath.palette(i / 31.0)), Offset(lx + i * lw / 32, ly - lh - 1 * density), Size(lw / 32 + .5f, lh))
                text(paint, if (mode == DisplayMode.DELTA) "0" else "−110", lx - 2 * density, ly, ts * .9f, 0xffc7bfd6.toInt(), Paint.Align.RIGHT)
                text(paint, if (mode == DisplayMode.DELTA) "100%" else "0 dBFS", lx + lw + 2 * density, ly, ts * .9f, 0xffc7bfd6.toInt(), Paint.Align.LEFT)
            }
            val i = inspect
            if (i.column >= 0) {
                val x = all.left + (i.column + .5f) * all.width / cols
                drawLine(Color.White, Offset(x, all.top), Offset(x, h.bottom), 1.2f * density * .8f)
                if (i.age >= 0) { val y = h.top + (i.age + .5f) * h.height / model.rows; drawLine(Color.White, Offset(all.left, y), Offset(all.right, y), 1f) }
            }
            drawCircle(Color(0xff2a3446), radius - 1f, center, style = Stroke(1f))
        }
    }
}
