package com.freqazoid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * The whole app: one round watch face.
 *  Start screen: Live mic / Song on watch. Then tap = Wheel ⇄ Overlay, Stop button = back to the start screen,
 *  Tuning is standard A4 = 440 Hz.
 */
class FreqazoidView(context: Context, private val processor: Processor, private val onSourceRequest: (Source) -> Unit) : View(context) {
    private val renderer = Renderer()
    private val gfx = AndroidGfx()
    private val frame = processor.newFrame()
    private val clip = Path()
    private var lastFrame = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f; private var downY = 0f

    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = "Freqazoid watch face" }

    private fun now() = SystemClock.uptimeMillis() / 1000.0
    fun toast(t: String) { renderer.toast(t, now()); invalidate(); handler.postDelayed({ invalidate() }, 2300) }

    fun sourceChanged(s: Source) {
        if (s == Source.MIC) toast("Live mic\nTap: wheel / overlay")
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFocus(); renderer.wavePoints = if (resources.configuration.isScreenRound) 300 else 420 }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1 / 60.0 else ((t - lastFrame) / 1000.0).coerceAtMost(.1); lastFrame = t
        val w = width.toFloat(); val h = height.toFloat(); val d = minOf(w, h)
        canvas.drawColor(0xff000000.toInt())
        clip.rewind(); clip.addCircle(w / 2, h / 2, d / 2, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        processor.snapshot(frame)
        gfx.canvas = canvas
        renderer.draw(gfx, w, h, frame, now(), dt)
        canvas.restore()
        if (processor.source != Source.OFF) postInvalidateOnAnimation() else lastFrame = 0L
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> { if (e.pointerCount == 1 && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < 40) { tap(e.x, e.y); performClick() } }
        }
        return true
    }
    private fun tap(x: Float, y: Float) {
        when (renderer.hit(x, y, width.toFloat(), height.toFloat(), processor.source)) {
            Renderer.Hit.MIC -> onSourceRequest(Source.MIC)
            Renderer.Hit.SONG -> onSourceRequest(Source.SONG)
            Renderer.Hit.STOP -> onSourceRequest(Source.OFF)
            Renderer.Hit.NONE -> if (processor.source != Source.OFF) renderer.overlay = !renderer.overlay
        }
        invalidate()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
