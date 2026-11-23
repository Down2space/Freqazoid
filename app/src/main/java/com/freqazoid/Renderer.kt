package com.freqazoid

import kotlin.math.*

/** Minimal drawing surface (Android Canvas on the device, Java2D for previews). Text is centred vertically on y. */
interface Gfx {
    enum class Align { LEFT, CENTER, RIGHT }
    fun fill(color: Int)
    fun circle(cx: Float, cy: Float, r: Float, color: Int, stroke: Float = 0f)       // stroke 0 = filled
    fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int, width: Float, round: Boolean = false)
    fun quad(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, color: Int, width: Float)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float)
    /** Polyline through [n] points; [fill] != 0 also fills it (even-odd). */
    fun poly(xs: FloatArray, ys: FloatArray, n: Int, color: Int, width: Float, fill: Int = 0)
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true, mono: Boolean = false, align: Align = Align.CENTER)
    fun textWidth(s: String, size: Float, bold: Boolean = true, mono: Boolean = false): Float
    fun additive(on: Boolean)
}

/**
 * The watch face: seven-octave wheel or overlay, plus the rim (consonance gauge, beat pulse, chord name).
 * Ported from Chromatic Resonance web v2.0's watch mode. Glows are drawn as wide translucent strokes.
 */
class Renderer {
    var overlay = false
    /** 0 = wheel / overlay, 1 = stars (swipe to switch). */
    var screen = 0
    val harmony = Harmony()
    private class Ripple(val midi: Int, val pc: Int, val oct: Int, val t0: Double, val r: Float)
    private val ripples = ArrayList<Ripple>()
    private val lastActive = HashSet<Int>()
    private var toastText = ""; private var toastUntil = 0.0
    var chord = Music.ChordName("—", ""); private set
    private val px = FloatArray(700); private val py = FloatArray(700)
    var wavePoints = 420
    private val tSlice = .020   // every note shows the same 20 ms of real time

    // ---- tuner: how close the strongest note is to its exact pitch (A4 = 440 Hz) ----
    private var tunerMidi = -1; private var tunerCents = 0.0
    var tuner: Pair<Int, Double>? = null; private set
    companion object Tuner {
        const val IN_TUNE = 10.0   // within ±10 cents: green
        const val CLOSE = 25.0     // within ±25 cents: yellow; beyond: red
        fun tuneColor(c: Double, a: Float = 1f): Int = when {
            abs(c) <= IN_TUNE -> Music.rgba(124, 242, 154, a); abs(c) <= CLOSE -> Music.rgba(255, 209, 102, a); else -> Music.rgba(255, 93, 115, a) }
        fun centsText(c: Double): String { val r = c.roundToInt(); return if (r == 0) "±0¢" else (if (r > 0) "+" else "−") + abs(r) + "¢" }
        const val SAMPLE_RATE = 48000
    }
    private fun updateTuner(notes: List<Note>, dt: Double) {
        val top = notes.maxByOrNull { it.strength }
        if (top == null) { tunerMidi = -1; tuner = null; return }
        // light smoothing (~0.1 s) so the number is readable; the arc on the wheel stays instant
        tunerCents = if (top.midi == tunerMidi) tunerCents + (top.cents - tunerCents) * min(1.0, dt / .1) else top.cents
        tunerMidi = top.midi; tuner = top.midi to tunerCents
    }

    fun toast(t: String, now: Double) { toastText = t; toastUntil = now + 2.2 }

    // geometry (W = face diameter)
    private var w = 600f; private var cx = 300f; private var cy = 300f; private var r = 250f; private var rc = 0f; private var r1 = 0f; private var dr = 0f
    private fun layout(width: Float, height: Float) {
        w = min(width, height); cx = width / 2; cy = height / 2; r = w / 2 * .84f; rc = r * .07f; r1 = r * .17f; dr = (r * .93f - r1) / 6
    }
    private fun ang(pc: Int) = (-PI / 2 + pc * PI / 6).toFloat()
    private fun ringR(o: Int) = r1 + (o - 1) * dr
    private fun posX(m: Int) = cx + ringR(m / 12 - 1) * cos(ang(m % 12)); private fun posY(m: Int) = cy + ringR(m / 12 - 1) * sin(ang(m % 12))

    /** [now]: UI clock in seconds; [dt]: seconds since the last frame. */
    fun draw(g: Gfx, width: Float, height: Float, f: Processor.Frame, now: Double, dt: Double) {
        layout(width, height)
        val notes = f.notes
        chord = Music.nameChord(notes)
        harmony.update(notes, dt)
        updateTuner(notes, dt)
        g.fill(0xff050812.toInt())
        if (screen == 1) drawStars(g, f, dt) else { if (overlay) drawOverlay(g, f) else drawWheel(g, f, now); drawRim(g, notes, now) }
        drawToast(g, now)
        drawControls(g, f)
        drawPageDots(g)
    }

    private fun drawToast(g: Gfx, now: Double) {
        if (now < toastUntil) { val ts = w * .034f; val lines = toastText.split('\n')
            lines.forEachIndexed { i, line -> val tw = g.textWidth(line, ts); val y = cy - w / 2 + w * (.76f + .062f * i)
                g.rect(cx - tw / 2 - 10, y - w * .031f, tw + 20, w * .062f, Music.rgba(17, 26, 48, .9f))
                g.text(line, cx, y, ts, 0xff6ee7d8.toInt()) } }
    }
    private fun drawPageDots(g: Gfx) {
        val y = cy + w * .465f
        for (k in 0..1) g.circle(cx + (k - .5f) * w * .045f, y, w * .009f, if (k == screen) 0xffffffff.toInt() else Music.rgba(150, 165, 200, .45f))
    }

    // ---------------- stars screen ----------------
    private var ghostAngle = 0.0; private var ghostMidi = -1
    private val gx = FloatArray(361); private val gy = FloatArray(361)
    var lastMatch = -1; private set          // for tests: 0..100 or −1
    var lastStarAngle = 0.0; private set     // for tests: user star's lobe orientation
    /**
     * Your star is your real wave looped over exactly k waves of the PERFECT pitch of the nearest note
     * (k ≈ 20 ms, as on the wheel). On pitch, it closes and stands still with k points; sharp it turns
     * clockwise, flat counter-clockwise. The thin white target star (k points) follows your star's
     * orientation slowly, so on pitch it sits right on top of yours and off pitch yours drifts away.
     */
    private fun drawStars(g: Gfx, f: Processor.Frame, dt: Double) {
        val vis = f.notes.filter { it.strength > .06f }.sortedByDescending { it.strength }
        val r0 = w * .23f
        if (vis.isEmpty()) { if (f.source != Source.OFF) g.text("sing or play a note", cx, cy, w * .04f, Music.rgba(150, 165, 200, .7f), false); ghostMidi = -1; lastMatch = -1; return }
        val top = vis[0]
        val single = vis.size == 1 || vis[1].strength < top.strength * .35f
        if (!single) {
            // several notes: their stars overlap, each in its own colour
            ghostMidi = -1; lastMatch = -1
            g.additive(true)
            for (n in vis.take(6).reversed()) {
                val buf = f.waves[n.midi] ?: continue
                waveRing(buf, SAMPLE_RATE, f.audioTime, cx, cy, r0, min(w * .1f, w * (.03f + .08f * n.strength)), n.freq)
                glowStroke(g, px, py, wavePoints + 1, Music.colO(n.pc, n.oct, .9f, 14f), 2.2f, 9f)
            }
            g.additive(false)
            val ts = w * .05f; val tw = g.textWidth(chord.name, ts)
            g.rect(cx - tw / 2 - 8, cy - w * .035f, tw + 16, w * .07f, Music.rgba(0, 0, 0, .55f)); g.text(chord.name, cx, cy, ts, 0xffffffff.toInt())
            return
        }
        val buf = f.waves[top.midi] ?: return
        val f0 = Music.mtof(top.midi); val k = max(1, (tSlice * f0).roundToInt())
        val loop = k * SAMPLE_RATE / f0                                // samples in k perfect waves
        val l = buf.size; val nNow = f.audioTime * SAMPLE_RATE; val posNow = nNow % loop
        val m = wavePoints
        var mx = 1e-6f; for (i in (l - 1 - loop.toInt()).coerceAtLeast(0) until l) mx = max(mx, abs(buf[i]))
        // your star, anchored to the perfect pitch's clock (points ordered by angle)
        var sre = 0.0; var sim = 0.0
        val vals = FloatArray(m + 1)
        for (j in 0..m) {
            val th = 2 * PI * j / m
            val back = ((posNow - th / (2 * PI) * loop) % loop + loop) % loop      // how long ago that angle was written
            val pos = (l - 1) - back; val i0 = pos.toInt().coerceIn(0, l - 2); val fr = (pos - i0).toFloat()
            val v = (buf[i0] + (buf[i0 + 1] - buf[i0]) * fr) / mx; vals[j] = v
            if (j < m) { sre += v * cos(k * th); sim += v * sin(k * th) }
        }
        val lobe = atan2(sim, sre) / k                                   // where your star's points face
        lastStarAngle = -lobe   // on screen the star is mirrored so that sharp turns clockwise
        if (ghostMidi != top.midi) { ghostAngle = lobe; ghostMidi = top.midi }
        else { val period = 2 * PI / k; var d = (lobe - ghostAngle) % period; if (d > period / 2) d -= period; if (d < -period / 2) d += period
            ghostAngle += d * min(1.0, dt / .7) }
        val amp = w * .075f; val rot = (-PI / 2).toFloat()
        // target: the perfect k-point star
        for (j in 0..360) { val th = 2 * PI * j / 360; val rr = r0 + amp * cos(k * (th - ghostAngle)).toFloat()
            gx[j] = cx + rr * cos(rot - th).toFloat(); gy[j] = cy + rr * sin(rot - th).toFloat() }
        g.poly(gx, gy, 361, Music.rgba(255, 255, 255, .55f), 2f)
        val cents = tuner?.second ?: top.cents
        for (j in 0..m) { val th = rot - (2 * PI * j / m).toFloat(); val rr = r0 + amp * vals[j]; px[j] = cx + rr * cos(th); py[j] = cy + rr * sin(th) }
        g.additive(true); glowStroke(g, px, py, m + 1, tuneColor(cents, .95f), 2.6f, 10f); g.additive(false)
        val match = (100 * (1 - min(1.0, abs(cents) / 50))).roundToInt(); lastMatch = match
        val label = Music.nm(top.pc) + top.oct
        g.text(label, cx, cy - w * .045f, w * .075f, Music.colO(top.pc, top.oct, 1f, 20f))
        g.text(centsText(cents), cx, cy + w * .025f, w * .045f, tuneColor(cents), true, true)
        g.text("$match% match", cx, cy + w * .07f, w * .03f, Music.rgba(200, 210, 235, .85f), true, true)
    }

    enum class Hit { MIC, SONG, STOP, NONE }
    private fun btnMicX() = cx - w * .17f; private fun btnSongX() = cx + w * .17f; private fun btnR() = w * .14f
    private fun stopY() = cy + w * .385f; private fun stopW() = w * .30f; private fun stopH() = w * .10f
    /** Visible buttons: when sound is off, big Demo / Mic buttons; while on, a Stop button at the bottom. */
    fun hit(x: Float, y: Float, width: Float, height: Float, source: Source): Hit {
        layout(width, height)
        if (source == Source.OFF) {
            if (hypot(x - btnMicX(), y - cy) < btnR() * 1.15f) return Hit.MIC
            if (hypot(x - btnSongX(), y - cy) < btnR() * 1.15f) return Hit.SONG
        } else if (abs(x - cx) < stopW() / 2 + w * .03f && abs(y - stopY()) < stopH() / 2 + w * .04f) return Hit.STOP
        return Hit.NONE
    }
    private fun drawControls(g: Gfx, f: Processor.Frame) {
        if (f.source == Source.OFF) {
            g.circle(cx, cy, w * .36f, Music.rgba(5, 8, 18, .82f))
            g.text("What are you listening to?", cx, cy - w * .2f, w * .04f, Music.rgba(220, 228, 245, .95f))
            for ((x, label, c) in listOf(Triple(btnMicX(), "Live|mic", 0xffffd166.toInt()), Triple(btnSongX(), "Song on|watch", 0xff6ee7d8.toInt()))) {
                g.circle(x, cy, btnR(), (c and 0xffffff) or 0x33000000); g.circle(x, cy, btnR(), c, w * .008f)
                val parts = label.split('|'); g.text(parts[0], x, cy - w * .028f, w * .044f, c); g.text(parts[1], x, cy + w * .03f, w * .044f, c)
            }
        } else {
            val y = stopY(); val bw = stopW(); val bh = stopH()
            g.rect(cx - bw / 2, y - bh / 2, bw, bh, Music.rgba(60, 20, 34, .9f))
            g.text("■ Stop", cx, y, w * .04f, 0xffffffff.toInt())
            if ((f.source == Source.MIC || f.source == Source.SONG) && f.notes.isEmpty()) {
                g.text(if (f.source == Source.MIC) "Listening… play a note" else "Listening for notes…", cx, cy, w * .04f, Music.rgba(220, 228, 245, .9f))
                val lv = f.level.coerceIn(0f, 1f)
                g.arc(cx, cy, w * .11f, (-PI / 2).toFloat(), (2 * PI * lv).toFloat(), 0xffffd166.toInt(), w * .012f, true)
            }
        }
    }

    private fun glowStroke(g: Gfx, xs: FloatArray, ys: FloatArray, n: Int, color: Int, width: Float, glow: Float, fill: Int = 0) {
        if (glow > 0) g.poly(xs, ys, n, (color and 0xffffff) or (((color ushr 24) * .22f).toInt() shl 24), width + glow, 0)
        g.poly(xs, ys, n, color, width, fill)
    }

    /**
     * Trace the note's isolated wave around a circle like an oscilloscope bent into a ring. Returns pen angle.
     * The loop covers a WHOLE number of the note's own waves (≈20 ms, from the pitch actually heard), so the
     * line's end meets its start seamlessly; the wave still moves and wobbles as before.
     */
    private fun waveRing(buf: FloatArray, sampleRate: Int, audioTime: Double, x: Float, y: Float, r0: Float, a: Float, freq: Double? = null): Float {
        val l = buf.size
        val span: Double = if (freq != null && freq > 0) {
            val cycles = max(1, (tSlice * freq).roundToInt()); min((l - 3).toDouble(), cycles * sampleRate / freq)
        } else min(l - 3, (tSlice * sampleRate).roundToInt()).toDouble()
        val spanSec = span / sampleRate
        var mx = 1e-6f; for (i in l - 1 - span.toInt() until l) mx = max(mx, abs(buf[i]))
        val pen = (2 * PI * ((audioTime / spanSec) % 1) - PI / 2).toFloat()
        val m = wavePoints
        for (j in 0..m) {
            val back = j.toDouble() / m; val th = pen - (back * 2 * PI).toFloat()
            val pos = (l - 1) - back * span; val i0 = pos.toInt(); val fr = (pos - i0).toFloat()
            val v = (buf[i0] + (buf[min(l - 1, i0 + 1)] - buf[i0]) * fr) / mx   // in-between samples interpolated
            val rr = r0 + a * v; px[j] = x + rr * cos(th); py[j] = y + rr * sin(th)
        }
        return pen
    }

    /** Gap between the start and end of the most recently drawn wave line (for tests). */
    fun lastSeamGap(): Float = hypot(px[0] - px[wavePoints], py[0] - py[wavePoints])
    /** Largest step between neighbouring points of that line (for tests). */
    fun lastMaxStep(): Float { var m = 0f; for (j in 1..wavePoints) m = max(m, hypot(px[j] - px[j - 1], py[j] - py[j - 1])); return m }

    private fun drawStatic(g: Gfx) {
        for (o in 1..7) g.circle(cx, cy, ringR(o), if (o % 2 == 1) Music.rgba(110, 130, 180, .22f) else Music.rgba(110, 130, 180, .13f), 1f)
        for (pc in 0 until 12) {
            val a = ang(pc)
            g.line(cx + rc * 1.05f * cos(a), cy + rc * 1.05f * sin(a), cx + r * .96f * cos(a), cy + r * .96f * sin(a), Music.col(pc, 55f, .13f), 1f)
            for (o in 1..7) { val rr = ringR(o); g.circle(cx + rr * cos(a), cy + rr * sin(a), max(2f, dr * .11f), Music.colO(pc, o, .3f)) }
        }
        val la = ang(0) + (PI / 12).toFloat()
        for (o in 1..7) { val rr = ringR(o); g.text("$o", cx + rr * cos(la), cy + rr * sin(la), max(8f, dr * .36f), Music.rgba(133, 147, 184, .7f), false, true) }
        g.circle(cx, cy, 3f, Music.rgba(110, 231, 216, .5f))
    }

    private fun drawWheel(g: Gfx, f: Processor.Frame, now: Double) {
        drawStatic(g)
        val notes = f.notes
        val active = notes.map { it.pc }.toSet()
        for (pc in 0 until 12) { val a = ang(pc); val rr = r * 1.08f
            g.text(Music.nm(pc), cx + rr * cos(a), cy + rr * sin(a), max(11f, r * .065f), if (pc in active) Music.col(pc, 72f) else Music.col(pc, 60f, .55f)) }
        // lattice: consecutive notes (low → high), plus the beating pair
        val vis = notes.filter { it.strength > .05f }
        val links = ArrayList<Triple<Note, Note, Boolean>>()
        for (i in 0 until vis.size - 1) links += Triple(vis[i], vis[i + 1], false)
        val bt = harmony.beat?.takeIf { !it.locked }
        fun isPair(a: Note, b: Note) = bt != null && ((a.midi == bt.a.midi && b.midi == bt.b.midi) || (a.midi == bt.b.midi && b.midi == bt.a.midi))
        if (bt != null && links.none { isPair(it.first, it.second) }) {
            val a = vis.firstOrNull { it.midi == bt.a.midi }; val b = vis.firstOrNull { it.midi == bt.b.midi }; if (a != null && b != null) links += Triple(a, b, true)
        }
        class Label(val x: Float, val y: Float, val t: String, val ic: Int, val al: Float)
        val labels = ArrayList<Label>()
        for ((a, b, extra) in links) {
            val x1 = posX(a.midi); val y1 = posY(a.midi); val x2 = posX(b.midi); val y2 = posY(b.midi)
            val semis = b.midi - a.midi; val ic = ((semis % 12) + 12) % 12
            val mx = (x1 + x2) / 2 - cx; val my = (y1 + y2) / 2 - cy; val d = hypot(mx, my)
            val ux: Float; val uy: Float
            if (d < 6) { var ux0 = -(y2 - y1); var uy0 = x2 - x1; val l = hypot(ux0, uy0).let { if (it == 0f) 1f else it }; ux = ux0 / l; uy = uy0 / l } else { ux = mx / d; uy = my / d }
            val cd = max(d * 1.18f, rc * 1.35f); val qx = cx + ux * cd; val qy = cy + uy * cd
            val al = min(a.strength, b.strength)
            if (isPair(a, b) && bt != null) {
                val col = if (extra) Music.rgba(255, 255, 255, .15f + .75f * bt.pulse) else Music.intCol(ic, .2f + .8f * bt.pulse)
                g.quad(x1, y1, qx, qy, x2, y2, (col and 0xffffff) or (((col ushr 24) * .25f).toInt() shl 24), 1.2f + 5 * bt.pulse + 6 + 10 * bt.pulse)
                g.quad(x1, y1, qx, qy, x2, y2, col, 1.2f + 5 * bt.pulse)
            } else g.quad(x1, y1, qx, qy, x2, y2, Music.intCol(ic, .25f + .7f * al), 1.5f + 2.5f * al)
            if (extra || semis == 0) continue
            labels += Label(.25f * x1 + .5f * qx + .25f * x2, .25f * y1 + .5f * qy + .25f * y2, Music.INT_SHORT[ic] + if (semis > 12) "+" else "", ic, al)
        }
        // ripples when a note starts
        val it = ripples.iterator()
        while (it.hasNext()) { val rp = it.next(); val age = now - rp.t0; if (age > .9) { it.remove(); continue }
            g.circle(posX(rp.midi), posY(rp.midi), rp.r * (1 + age.toFloat() * 3), Music.colO(rp.pc, rp.oct, .5f * (1 - age.toFloat() / .9f), 14f), 1.2f) }
        val act = HashSet<Int>()
        for (n in notes) {
            val x = posX(n.midi); val y = posY(n.midi); val a = ang(n.pc); val s = n.strength
            act += n.midi
            val solid = n.conf > .45f
            val r0 = dr * (.36f + .08f * s); val amp = dr * (.06f + .26f * s)
            g.circle(x, y, r0, Music.colO(n.pc, n.oct, if (solid) .14f + .2f * s else .06f))
            g.circle(x, y, r0, Music.colO(n.pc, n.oct, .35f), .8f)
            val buf = f.waves[n.midi]
            if (buf != null) {
                val pen = waveRing(buf, SAMPLE_RATE, f.audioTime, x, y, r0, amp, n.freq)
                glowStroke(g, px, py, wavePoints + 1, Music.colO(n.pc, n.oct, if (solid) .7f + .3f * s else .35f, 16f), 1.6f, 4f + 8 * s)
                g.circle(x + r0 * cos(pen), y + r0 * sin(pen), 1.8f, Music.colO(n.pc, n.oct, .95f, 30f))
                if (n.midi !in lastActive && s > .3f) ripples += Ripple(n.midi, n.pc, n.oct, now, r0 + amp)
            }
            if (abs(n.cents) > 3) { val da = (n.cents / 100 * (PI / 6)).toFloat(); g.arc(cx, cy, ringR(n.oct), a, da, tuneColor(n.cents, .9f), 2.5f) }
        }
        lastActive.clear(); lastActive += act
        val ls = max(9f, r * .045f)
        for (l in labels) { val tw = g.textWidth(l.t, ls, true, true) + 6; g.rect(l.x - tw / 2, l.y - ls * .7f, tw, ls * 1.4f, Music.rgba(7, 11, 22, .85f))
            g.text(l.t, l.x, l.y, ls, Music.intCol(l.ic, .5f + .5f * l.al), true, true) }
    }

    private fun drawOverlay(g: Gfx, f: Processor.Frame) {
        val base = w * .27f
        for (k in floatArrayOf(.5f, 1f, 1.5f)) g.circle(cx, cy, base * k, Music.rgba(110, 130, 180, .18f), 1f)
        val list = f.notes.filter { it.strength > .06f }.sortedByDescending { it.strength }.take(6).sortedBy { it.freq }
        if (list.isEmpty()) { g.text("play notes to layer their waves here", cx, cy, w * .03f, Music.rgba(133, 147, 184, .6f), false, true); return }
        g.additive(true)
        for (n in list) {
            val buf = f.waves[n.midi] ?: continue
            val a = min(base * (.08f + .3f * n.strength), base * .32f)
            val pen = waveRing(buf, SAMPLE_RATE, f.audioTime, cx, cy, base, a, n.freq)
            val fill = Music.colO(n.pc, n.oct, (.05f + .05f * n.strength) * min(1f, 3f / list.size), 4f)
            glowStroke(g, px, py, wavePoints + 1, Music.colO(n.pc, n.oct, min(.95f, .45f + 1.5f / list.size), 14f), if (list.size > 4) 1.6f else 2.2f, 10f, fill)
            g.circle(cx + base * cos(pen), cy + base * sin(pen), 3f, Music.colO(n.pc, n.oct, 1f, 30f))
        }
        g.additive(false)
        val b = harmony.beat
        if (b != null && !b.locked) { val pr = base * 1.62f
            g.circle(cx, cy, pr, Music.rgba(255, 255, 255, (.08f + .6f * b.pulse) * .2f), 2 + 8 * b.pulse + 4 + 10 * b.pulse)
            g.circle(cx, cy, pr, Music.rgba(255, 255, 255, .08f + .6f * b.pulse), 2 + 8 * b.pulse) }
        // the total sound reaching your ears, same time slice (white)
        val pen = waveRing(f.raw, SAMPLE_RATE, f.audioTime, cx, cy, base, base * .42f, list.maxByOrNull { it.strength }?.freq)
        @Suppress("UNUSED_VARIABLE") val unused = pen
        glowStroke(g, px, py, wavePoints + 1, Music.rgba(255, 255, 255, .9f), 1.6f, 6f)
    }

    private fun drawRim(g: Gfx, notes: List<Note>, now: Double) {
        val rr = w / 2 - w * .018f
        val a0 = (PI * 1.18).toFloat(); val a1 = (PI * 1.82).toFloat(); val segs = 40
        val stops = listOf(0f to 0xffff4d6d.toInt(), .3f to 0xffff9f43.toInt(), .48f to 0xffffd166.toInt(), .7f to 0xff7cf29a.toInt(), 1f to 0xff6ee7d8.toInt())
        fun colAt(t: Float): Int { for (i in 1 until stops.size) if (t <= stops[i].first) return stops[i].second; return stops.last().second }
        val cons = harmony.cons
        for (i in 0 until segs) { val t = i.toFloat() / segs
            val c = colAt(t); g.arc(cx, cy, rr, a0 + (a1 - a0) * t, (a1 - a0) / segs + .002f, (c and 0xffffff) or ((if (cons == null) .25f else .55f) * 255).toInt().shl(24), w * .016f) }
        if (cons != null) { val a = a0 + (a1 - a0) * cons.toFloat()
            g.circle(cx + rr * cos(a), cy + rr * sin(a), w * .017f + 4, 0x40ffffff); g.circle(cx + rr * cos(a), cy + rr * sin(a), w * .017f, 0xffffffff.toInt()) }
        val b = harmony.beat
        if (b != null && !b.locked) g.circle(cx, cy, w / 2 - 2, Music.rgba(255, 255, 255, .05f + .55f * b.pulse), 3 + w * .012f * b.pulse)
        tuner?.let { (m, c) ->
            val label = Music.nm(m % 12) + (m / 12 - 1) + "  " + centsText(c)
            val ts = w * .05f; val tw = g.textWidth(label, ts, true, true); val y = cy - w * .105f
            g.rect(cx - tw / 2 - 8, y - w * .032f, tw + 16, w * .064f, Music.rgba(0, 0, 0, .6f))
            g.text(label, cx, y, ts, tuneColor(c), true, true)
            // small needle: centre = exact pitch, ends = ±50¢
            val nw = w * .16f; val ny = y + w * .043f
            g.line(cx - nw / 2, ny, cx + nw / 2, ny, Music.rgba(200, 210, 235, .35f), 2f)
            g.line(cx, ny - w * .012f, cx, ny + w * .012f, Music.rgba(200, 210, 235, .7f), 1.5f)
            g.circle(cx + (c.coerceIn(-50.0, 50.0) / 50 * nw / 2).toFloat(), ny, w * .011f, tuneColor(c))
        }
        val name = chord.name
        if (name != "—") { val ts = w * .055f; val tw = g.textWidth(name, ts)
            g.rect(cx - tw / 2 - 6, cy - w * .035f, tw + 12, w * .07f, Music.rgba(0, 0, 0, .6f)); g.text(name, cx, cy, ts, 0xffffffff.toInt()) }
        val sub = if (cons == null) "" else harmony.word + (b?.let { if (it.locked) " · locked" else " · " + String.format(java.util.Locale.US, "%.1f Hz", it.hz) } ?: "")
        if (sub.isNotEmpty()) g.text(sub, cx, cy + w * .06f, w * .03f, Music.rgba(200, 210, 235, .85f), true, true)
    }


}
