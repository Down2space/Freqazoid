package com.freqazoid

import kotlin.math.*

/** The demo: the web version's "warm" synth voices playing the looping chord tour. */
class Synth(val sampleRate: Int) {
    private class Voice(val midi: Int, val base: Double, val t0: Double, var tRel: Double, val cents: Double) { var phase = 0.0 }
    private val voices = ArrayList<Voice>()
    var time = 0.0; private set
    var just = false
    // warm periodic wave: cosine partials 1, .28, .1, .05, normalized to peak 1 (as WebAudio does)
    private val partials = doubleArrayOf(1.0, .28, .1, .05)
    private val norm = run { var mx = 0.0; for (i in 0 until 2048) { val x = 2 * PI * i / 2048; var v = 0.0; partials.forEachIndexed { k, a -> v += a * cos((k + 1) * x) }; mx = max(mx, abs(v)) }; 1 / mx }

    private fun env(v: Voice, now: Double): Double {
        if (now < v.t0) return 0.0
        val a = now - v.t0
        var x = if (a < .012) a / .012 else .8 + .2 * exp(-(a - .012) / .12)
        if (now > v.tRel) x *= exp(-(now - v.tRel) / .14)
        return x
    }
    private fun tuned(midis: List<Int>): List<Double> {
        if (!just) return midis.map { Music.mtof(it) }
        val root = midis.min(); val rf = Music.mtof(root)
        return midis.map { m -> val d = m - root; rf * 2.0.pow(d / 12) * Music.JUST[d % 12] }
    }
    @Synchronized fun play(midis0: List<Int>, arp: Boolean = false, hold: Double = 3.6, detTop: Double = 0.0) {
        for (v in voices) if (v.tRel > time) v.tRel = time
        val midis = midis0.sorted(); val fs = tuned(midis); val top = midis.size - 1
        val step = if (arp) .45 else 0.0; val end = time + top * step + hold
        midis.forEachIndexed { i, m -> voices += Voice(m, fs[i], time + i * step, end, if (i == top) detTop else 0.0) }
    }
    @Synchronized fun stopAll() { for (v in voices) if (v.tRel > time) v.tRel = time }

    @Synchronized fun render(out: FloatArray, count: Int) {
        val dt = 1.0 / sampleRate
        for (i in 0 until count) {
            var s = 0.0
            for (v in voices) {
                val e = env(v, time); if (e <= 0) continue
                val f = v.base * 2.0.pow(v.cents / 1200)
                var w = 0.0; partials.forEachIndexed { k, a -> w += a * cos((k + 1) * v.phase) }
                s += .22 * e * w * norm
                v.phase = (v.phase + 2 * PI * f * dt) % (2 * PI * 1000)
            }
            out[i] = tanh(1.6 * s).toFloat() / 1.6f * 1.25f   // gentle limiter standing in for the web compressor
            time += dt
        }
        voices.removeAll { env(it, time) < .002 && time > it.tRel + .3 }
    }

    @Synchronized fun notes(): List<Note> {
        val map = HashMap<Int, Note>()
        for (v in voices) {
            val e = env(v, time); if (e < .015) continue
            val f = v.base * 2.0.pow(v.cents / 1200)
            val cur = map[v.midi]
            if (cur == null || e > cur.strength) map[v.midi] = Note(v.midi, f, e.toFloat(), 1f, 1200 * ln(f / Music.mtof(v.midi)) / ln(2.0))
        }
        return map.values.sortedBy { it.freq }
    }
}

/** The looping chord tour from the web version's watch demo. */
class Tour(private val synth: Synth) {
    class Step(val m: List<Int>, val arp: Boolean = false, val hold: Double = 3.6, val just: Boolean? = null, val det: Double = 0.0)
    companion object {
        val STEPS = listOf(
            Step(listOf(60, 64, 67)), Step(listOf(72, 76, 79)), Step(listOf(60, 63, 67)),
            Step(listOf(48, 52, 55, 60), arp = true, hold = 3.4), Step(listOf(60, 67), just = true), Step(listOf(60, 67), just = false),
            Step(listOf(60, 67), just = true, det = 14.0), Step(listOf(48, 60), just = true), Step(listOf(60, 64), just = true),
            Step(listOf(43, 55, 59, 62, 65)), Step(listOf(48, 57, 60, 64)), Step(listOf(48, 55, 64, 71, 74)))
    }
    var index = -1; private set
    private var next = 0.0
    fun start() { index = -1; next = synth.time }
    /** Call regularly from the audio thread. */
    fun tick() {
        if (synth.time < next) return
        index = (index + 1) % STEPS.size
        val s = STEPS[index]
        synth.just = s.just ?: false
        synth.play(s.m, s.arp, s.hold, s.det)
        next = synth.time + if (s.arp) 5.6 else 4.6
    }
}
