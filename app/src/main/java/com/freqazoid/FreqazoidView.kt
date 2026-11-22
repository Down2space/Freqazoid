package com.freqazoid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * The whole app: one round watch face.
 *  Tap = Wheel ⇄ Overlay · Press and hold = Off → Demo chords → Microphone · Bezel (or two-finger tap) = A4 440 ⇄ 432 Hz
 */
class FreqazoidView(context: Context, private val processor: Processor, private val onSourceRequest: (Source) -> Unit) : View(context) {
    private val renderer = Renderer()
    private val gfx = AndroidGfx()
    private val frame = processor.newFrame()
    private val clip = Path()
    private var lastFrame = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var held = false; private var multi = false
    private var downX = 0f; private var downY = 0f
    private var lastBezel = 0L
    private val hold = Runnable { held = true; cycleSource() }

    init { isFocusable = true; isFocusableInTouchMode = true; contentDescription = "Freqazoid watch face" }

    private fun now() = SystemClock.uptimeMillis() / 1000.0
    fun toast(t: String) { renderer.toast(t, now()); invalidate(); handler.postDelayed({ invalidate() }, 2300) }

    private fun cycleSource() {
        val next = when (processor.source) { Source.OFF -> Source.DEMO; Source.DEMO -> Source.MIC; Source.MIC -> Source.OFF }
        onSourceRequest(next)
    }
    fun sourceChanged(s: Source) {
        toast(s.label)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); invalidate()
    }
    fun toggleTuning() {
        Music.a4 = if (Music.a4 == 440.0) 432.0 else 440.0
        toast("A4 = ${Music.a4.toInt()} Hz")
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
            MotionEvent.ACTION_DOWN -> { held = false; multi = false; downX = e.x; downY = e.y; handler.postDelayed(hold, 600) }
            MotionEvent.ACTION_POINTER_DOWN -> { handler.removeCallbacks(hold); if (!multi) { multi = true; toggleTuning() } }
            MotionEvent.ACTION_MOVE -> if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > 40) handler.removeCallbacks(hold)   // a swipe is not a hold
            MotionEvent.ACTION_UP -> { handler.removeCallbacks(hold); if (!held && !multi && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < 40) { tap(e.x, e.y); performClick() } }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(hold)
        }
        return true
    }
    private fun tap(x: Float, y: Float) {
        when (renderer.hit(x, y, width.toFloat(), height.toFloat(), processor.source)) {
            Renderer.Hit.DEMO -> onSourceRequest(Source.DEMO)
            Renderer.Hit.MIC -> onSourceRequest(Source.MIC)
            Renderer.Hit.STOP -> onSourceRequest(Source.OFF)
            Renderer.Hit.NONE -> renderer.overlay = !renderer.overlay
        }
        invalidate()
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            if (e.eventTime - lastBezel > 400) { lastBezel = e.eventTime; toggleTuning() }
            return true
        }
        return super.onGenericMotionEvent(e)
    }
}
