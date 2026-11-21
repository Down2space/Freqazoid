package com.freqazoid

import kotlin.math.*

/**
 * Consonance meter + beat visualizer (web v2.0).
 * Consonance mixes, for every pair of sounding notes, roughness (Plomp–Levelt / Sethares: how much their
 * overtones rub) and ratio simplicity (3:2 smooth, 45:32 tense). Beats: two notes close to a simple
 * ratio but not on it swell and fade at |q·f2 − p·f1| times per second.
 */
class Harmony {
    class Beat(val a: Note, val b: Note, val hz: Double, val locked: Boolean, val pulse: Float)
    var cons: Double? = null; private set
    var word = "—"; private set
    var beat: Beat? = null; private set
    private var beatKey: String? = null; private var beatHz = 0.0
    private val phases = HashMap<String, Double>()

    companion object {
        fun roughPair(f1: Double, f2: Double): Double {
            var t = 0.0
            for (h in 1..6) for (k in 1..6) { val a = f1 * h; val b = f2 * k; val fm = min(a, b); val x = .24 / (.0207 * fm + 18.96) * abs(a - b); t += (exp(-3.5 * x) - exp(-5.75 * x)) / (h * k) }
            return t
        }
        class Ratio(val p: Int, val q: Int, val err: Double)
        fun bestRatio(r: Double, maxPQ: Int, tolC: Double): Ratio? {
            var best: Ratio? = null; var bestSc = 0.0
            for (q in 1..maxPQ) {
                val p = (r * q).roundToInt(); if (p < 1 || p > maxPQ * 4) continue
                var a = p; var b = q; while (b != 0) { val t = a % b; a = b; b = t }; if (a != 1) continue
                val err = 1200 * ln(r / (p.toDouble() / q)) / ln(2.0); if (abs(err) > tolC) continue
                val sc = ln((p * q).toDouble()) / ln(2.0) + abs(err) / 10
                if (best == null || sc < bestSc) { best = Ratio(p, q, err); bestSc = sc }
            }
            return best
        }
        /** Instant consonance (0 harsh … 1 very smooth) for a set of notes, as the meter computes it. */
        fun consonance(notes: List<Note>): Double? {
            val vis = notes.filter { it.strength > .12f }.sortedByDescending { it.strength }.take(6)
            var tw = 0.0; var ws = 0.0
            for (i in vis.indices) for (j in i + 1 until vis.size) {
                val a = if (vis[i].freq < vis[j].freq) vis[i] else vis[j]; val b = if (a === vis[i]) vis[j] else vis[i]
                val w = (a.strength * b.strength).toDouble()
                val ro = min(1.0, roughPair(a.freq, b.freq) / max(1e-6, roughPair(a.freq, a.freq * 2.0.pow(1 / 12.0))))
                val br = bestRatio(b.freq / a.freq, 16, 16.0); val ten = if (br != null) min(1.0, ln((br.p * br.q).toDouble()) / ln(2.0) / 7.9) else 1.0
                tw += w * (.5 * ro + .5 * ten); ws += w
            }
            return if (ws > 0) 1 - tw / ws else null
        }
        fun wordFor(c: Double?) = when { c == null -> "—"; c >= .72 -> "Very smooth"; c >= .57 -> "Smooth"; c >= .38 -> "Warm"; c >= .24 -> "Tense"; else -> "Harsh" }
    }

    fun update(notes: List<Note>, dt: Double) {
        val c = consonance(notes)
        cons = if (c == null) null else cons?.let { it + (c - it) * min(1.0, dt * 6) } ?: c
        val vis = notes.filter { it.strength > .12f }.sortedByDescending { it.strength }.take(6)
        var best: Triple<Note, Note, Double>? = null; var bestW = 0.0
        for (i in vis.indices) for (j in i + 1 until vis.size) {
            val a = if (vis[i].freq < vis[j].freq) vis[i] else vis[j]; val b = if (a === vis[i]) vis[j] else vis[i]
            val w = (a.strength * b.strength).toDouble()
            val sr = bestRatio(b.freq / a.freq, 6, 35.0) ?: continue
            val bh = abs(sr.q * b.freq - sr.p * a.freq)
            if (best == null || w > bestW) { best = Triple(a, b, bh); bestW = w }
        }
        if (best != null) {
            val (a, b, bh) = best; val key = "${a.midi}-${b.midi}"
            beatHz = if (beatKey == key) beatHz + (bh - beatHz) * min(1.0, dt * 4) else bh
            val ph = ((phases[key] ?: 0.0) + 2 * PI * beatHz * dt) % (2 * PI * 1e3); phases[key] = ph; beatKey = key
            beat = Beat(a, b, beatHz, beatHz < .12, (.5 + .5 * cos(ph)).toFloat())
        } else { beat = null; beatKey = null }
        word = wordFor(cons)
    }
}
