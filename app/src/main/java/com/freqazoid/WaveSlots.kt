package com.freqazoid

import kotlin.math.*

/**
 * Isolates each lit note's own waveform, like the web version: the sound is passed through band-pass
 * filters (Q 24) at the note's frequency and its first overtones (h·f, h = 1..5), summed, and the
 * newest samples are kept so the note can be drawn as a real wave bent around a circle.
 */
class WaveSlots(val sampleRate: Int, val slots: Int = 8, val harmonics: Int = 5, val length: Int = 2048) {
    private class Biquad { var b0 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0; var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0; var on = false
        fun set(f: Double, q: Double, sr: Int) {
            if (f >= sr * .45) { on = false; return }
            val w = 2 * PI * f / sr; val alpha = sin(w) / (2 * q); val a0 = 1 + alpha
            b0 = alpha / a0; b2 = -alpha / a0; a1 = -2 * cos(w) / a0; a2 = (1 - alpha) / a0; on = true }
        fun step(x: Double): Double { if (!on) return 0.0; val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y }
        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 } }
    private class Slot(h: Int, len: Int) { var midi = -1; var freq = 0.0; var lastUse = 0L; val bp = Array(h) { Biquad() }; val buf = FloatArray(len) }
    private val all = Array(slots) { Slot(harmonics, length) }
    private val raw = FloatArray(length)
    private var head = 0
    var samples = 0L; private set

    /** Point slots at the current notes (called on the audio thread whenever the note list changes). */
    @Synchronized fun assign(notes: List<Note>) {
        val now = samples
        for (n in notes.sortedByDescending { it.strength }.take(slots)) {
            var s = all.firstOrNull { it.midi == n.midi }
            if (s == null) { s = all.firstOrNull { it.midi < 0 } ?: all.minBy { it.lastUse }; s.midi = n.midi; s.freq = 0.0; s.buf.fill(0f); s.bp.forEach { it.reset() } }
            s.lastUse = now
            if (abs(s.freq - n.freq) > .05) { s.freq = n.freq; for (h in 0 until harmonics) s.bp[h].set(n.freq * (h + 1), 24.0, sampleRate) }
        }
        for (s in all) if (s.midi >= 0 && now - s.lastUse > sampleRate / 2) s.midi = -1
    }

    @Synchronized fun process(x: FloatArray, count: Int) {
        for (i in 0 until count) {
            val v = x[i].toDouble(); raw[head] = x[i]
            for (s in all) { if (s.midi < 0) continue; var y = 0.0; for (b in s.bp) y += b.step(v); s.buf[head] = y.toFloat() }
            head = (head + 1) % length; samples++
        }
    }

    /** Copy the newest [length] samples (oldest first) for [midi] into [out]; false if no slot. */
    @Synchronized fun copy(midi: Int, out: FloatArray): Boolean {
        val s = all.firstOrNull { it.midi == midi } ?: return false
        for (i in 0 until length) out[i] = s.buf[(head + i) % length]; return true
    }
    @Synchronized fun copyRaw(out: FloatArray) { for (i in 0 until length) out[i] = raw[(head + i) % length] }
    @Synchronized fun clear() { for (s in all) { s.midi = -1; s.buf.fill(0f) }; raw.fill(0f) }
}
