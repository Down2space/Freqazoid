package com.freqazoid

import kotlin.math.*

/**
 * Harmonic-series note detector + drum filter, ported from Chromatic Resonance web v2.0.
 * The spectrum reproduces the browser AnalyserNode the web version used (fftSize 16384, Blackman window,
 * |X|/N magnitude, smoothingTimeConstant 0.35, dB), so its thresholds carry over unchanged.
 * Call [analyze] at a steady rate [fps] (time-based constants are scaled from the web's 60 fps).
 */
class NoteDetector(val sampleRate: Int, val fps: Double = 30.0) {
    val n = 16384
    private val bins = n / 2
    private val window = FloatArray(n) { val x = 2 * PI * it / n; (.42 - .5 * cos(x) + .08 * cos(2 * x)).toFloat() }
    private val re = FloatArray(n); private val im = FloatArray(n)
    private val smooth = FloatArray(bins)
    val db = FloatArray(bins)
    private val cosT = FloatArray(n / 2) { cos(-2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(-2 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(n)) }
    // Recency gate (added to the web's drum filter): the newest ~85 ms, same window & scale as the long spectrum.
    // A drum hit stays inside the long 341 ms window after it has died, so it looks "steady" there; it is gone here.
    val ns = 4096
    private val sWin = FloatArray(ns) { val x = 2 * PI * it / ns; (.42 - .5 * cos(x) + .08 * cos(2 * x)).toFloat() }
    private val sRe = FloatArray(ns); private val sIm = FloatArray(ns)
    private val sCos = FloatArray(ns / 2) { cos(-2 * PI * it / ns).toFloat() }; private val sSin = FloatArray(ns / 2) { sin(-2 * PI * it / ns).toFloat() }
    private val sRev = IntArray(ns) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(ns)) }
    private val shortDb = FloatArray(ns / 2)
    var recencyGateDb = 12.0
    /** Spectral flatness (100 Hz–8 kHz) of the last frame: ~0 for tones, higher for hiss / cymbal wash. */
    var flatness = 0.0; private set
    var noiseGate = 0.30
    // ---- room gate (learned): ignore steady room noise / hum, only react to sound clearly above it ----
    var roomGateDb = 10.0          // overall level must be this far above the learned quiet level
    var binGateDb = 10.0           // each peak must stand this far above the learned room spectrum
    var absoluteFloorDb = -70.0    // nothing quieter than this (dBFS RMS) ever counts
    var maxRoomDb = -45.0          // assumed upper bound of a room's background level
    var noiseLevelDb = Double.NaN; private set
    /** Room gate on/off: on for the live mic, off for songs (a file has no room noise). */
    var roomGate = true
    private val levelHist = DoubleArray(max(30, (10 * fps).roundToInt())) { Double.NaN }; private var levelHead = 0
    var levelDb = -160.0; private set
    var loud = false; private set
    private val roomDb = FloatArray(bins); private var roomFrames = 0
    // ---- notes-and-chords strictness ----
    var nextNoteRatio = .25        // a further note must be at least this fraction as salient as the strongest
    var overtoneGuard = .6         // a note sitting on an overtone of a stronger note needs this fraction of its salience
    var steadyCents = 45.0

    val detState = FloatArray(128); val detFreq = FloatArray(128); val detConf = FloatArray(128)
    private val stab = IntArray(128); private val stabF = FloatArray(128); private val stPeak = FloatArray(128)
    private val decay = 0.9.pow(60.0 / fps).toFloat()
    var holdMs = 200.0              // a pitch must hold this long to light up (held notes yes, speech glides no)
    private val stabN get() = max(3, (holdMs / 1000 * fps).roundToInt())
    private val stabLow get() = stabN + max(1, (.07 * fps).roundToInt())   // a bit longer for notes below D2
    private val lowCollapse = max(3, (10 * fps / 60).roundToInt())

    private fun spectrum(samples: FloatArray) {
        for (i in 0 until n) { re[rev[i]] = samples[i] * window[i]; im[i] = 0f }
        var len = 2
        while (len <= n) {
            val half = len / 2; val stride = n / len; var s = 0
            while (s < n) {
                for (k in 0 until half) {
                    val a = s + k; val b = a + half; val j = k * stride
                    val tr = re[b] * cosT[j] - im[b] * sinT[j]; val ti = re[b] * sinT[j] + im[b] * cosT[j]
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                }
                s += len
            }
            len = len shl 1
        }
        for (k in 0 until bins) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / n
            smooth[k] = .35f * smooth[k] + .65f * mag
            db[k] = if (smooth[k] > 0) 20 * log10(smooth[k]) else -1000f
        }
    }

    private fun shortSpectrum(samples: FloatArray) {
        val off = n - ns
        for (i in 0 until ns) { sRe[sRev[i]] = samples[off + i] * sWin[i]; sIm[i] = 0f }
        var len = 2
        while (len <= ns) { val half = len / 2; val stride = ns / len; var s0 = 0
            while (s0 < ns) { for (k in 0 until half) { val a = s0 + k; val b = a + half; val j = k * stride
                    val tr = sRe[b] * sCos[j] - sIm[b] * sSin[j]; val ti = sRe[b] * sSin[j] + sIm[b] * sCos[j]
                    sRe[b] = sRe[a] - tr; sIm[b] = sIm[a] - ti; sRe[a] += tr; sIm[a] += ti }
                s0 += len }
            len = len shl 1 }
        for (k in 0 until ns / 2) { val mag = sqrt(sRe[k] * sRe[k] + sIm[k] * sIm[k]) / ns; shortDb[k] = if (mag > 0) 20 * log10(mag) else -1000f }
    }
    /** Is a note at [f] still sounding in the newest 85 ms about as loudly as across the long window? */
    private fun recent(f: Double): Boolean {
        fun lv(arr: FloatArray, hz: Double, size: Int): Float { val c = (hz * size / sampleRate).roundToInt(); var m = -1000f
            for (k in c - 1..c + 1) if (k in 1 until arr.size) m = max(m, arr[k]); return m }
        return lv(shortDb, f, ns) >= lv(db, f, n) - recencyGateDb
    }

    private class Pk(val f: Double, val db: Double, val amp: Double, var rem: Double, val prom: Double)
    private class Cand(val f: Double, var dead: Boolean = false)
    private class Score(val sal: Double, val match: List<Pair<Pk, Int>>, val h1: Pk?, val cnt: Int)
    private fun hTol(h: Int) = doubleArrayOf(-(22.0 + 2 * h), min(170.0, 22 + .55 * h * h))

    /** [samples]: the newest [n] mono samples (oldest first), in -1..1. */
    fun analyze(samples: FloatArray) {
        require(samples.size == n)
        spectrum(samples)
        shortSpectrum(samples)
        val f = db; val binHz = sampleRate.toDouble() / n
        run { var ls = 0.0; var ps = 0.0; var c = 0
            for (k in (100 / binHz).toInt() until min(bins, (8000 / binHz).toInt())) { val pw = 10.0.pow(f[k] / 10.0).coerceAtLeast(1e-30); ls += ln(pw); ps += pw; c++ }
            flatness = if (ps <= 1e-25) 0.0 else exp(ls / c) / (ps / c) }
        val noisy = flatness > noiseGate
        run { var e = 0.0; for (i in n - ns until n) e += samples[i] * samples[i]; levelDb = 10 * log10(e / ns + 1e-16) }
        // Quiet level = quietest moment of the last 10 s (dead digital zero, e.g. mic start-up, is ignored).
        if (levelDb > -110) { levelHist[levelHead] = levelDb; levelHead = (levelHead + 1) % levelHist.size }
        var mn = Double.NaN; for (v in levelHist) if (!v.isNaN() && (mn.isNaN() || v < mn)) mn = v
        // a room's background is never louder than about −45 dBFS, so music already playing at start-up can't become "quiet"
        if (!mn.isNaN()) mn = min(mn, maxRoomDb)
        noiseLevelDb = mn
        loud = !roomGate || (levelDb > absoluteFloorDb && (mn.isNaN() || levelDb >= max(mn, absoluteFloorDb - 10) + roomGateDb))
        if (roomGate && !loud && levelDb > -110) { // learn the room's spectrum from quiet moments
            if (roomFrames == 0) System.arraycopy(f, 0, roomDb, 0, bins) else for (k in 0 until bins) roomDb[k] = .9f * roomDb[k] + .1f * f[k]
            roomFrames++
        }
        val lo = floor(27 / binHz).toInt(); val hi = min(bins - 12, ceil(9000 / binHz).toInt())
        var maxDb = -200.0
        for (i in lo until hi) if (f[i] > maxDb) maxDb = f[i].toDouble()
        val thr = max(maxDb - 42, -90.0)
        val peaks = ArrayList<Pk>()
        for (i in lo + 2 until hi) {
            val d = f[i]; if (d < thr) continue
            if (roomGate && roomFrames > 0 && d < roomDb[i] + binGateDb) continue
            if (d >= f[i - 1] && d > f[i + 1] && d >= f[i - 2] && d >= f[i + 2]) {
                var mn = d
                for (k in -10..10) { val j = i + k; if (j in 0 until bins && f[j] < mn) mn = f[j] }
                if (d - mn < 7) continue
                val a = f[i - 1].toDouble(); val b = d.toDouble(); val c = f[i + 1].toDouble()
                val den = (a - 2 * b + c).let { if (it == 0.0) 1e-9 else it }
                val p = .5 * (a - c) / den
                val pdb = b - .25 * (a - c) * p; val amp = 10.0.pow((pdb - thr) / 20)
                peaks += Pk((i + p) * binHz, pdb, amp, amp, (d - mn).toDouble())
            }
        }
        peaks.sortByDescending { it.db }
        if (peaks.size > 48) peaks.subList(48, peaks.size).clear()
        val cands = peaks.filter { it.f <= 4300 }.map { Cand(it.f) }
        fun score(c: Cand): Score {
            var sal = 0.0; var cnt = 0; var h1: Pk? = null; val match = ArrayList<Pair<Pk, Int>>()
            for (pk in peaks) {
                if (pk.rem <= 0) continue
                val h = (pk.f / c.f).roundToInt(); if (h < 1 || h > 16) continue
                val ce = 1200 * ln(pk.f / (c.f * h)) / ln(2.0); val t = hTol(h); if (ce < t[0] || ce > t[1]) continue
                sal += pk.rem / (1 + .18 * (h - 1)); cnt++; match += pk to h
                if (h == 1 && (h1 == null || pk.amp > h1.amp)) h1 = pk
            }
            if (h1 == null || h1.rem < .35 * h1.amp) sal = 0.0
            return Score(sal, match, h1, cnt)
        }
        class Acc(val f: Double, val db: Double, val prom: Double, val cnt: Int, val sal: Double)
        val acc = ArrayList<Acc>(); var first = 0.0
        for (it in 0 until 8) {
            var best: Cand? = null; var bs: Score? = null
            for (c in cands) { if (c.dead) continue; val r = score(c); if (r.sal > 0 && (bs == null || r.sal > bs.sal)) { bs = r; best = c } }
            if (best == null || bs == null) break
            if (first == 0.0) first = bs.sal else if (bs.sal < first * nextNoteRatio) break
            val fr = bs.h1?.f ?: best.f
            // A voice's or instrument's own overtones are not extra notes: a candidate on an overtone of a
            // stronger accepted note must be nearly as strong as that note to count.
            val onOvertone = acc.any { a -> val r = fr / a.f; val h = r.roundToInt(); h in 2..10 && abs(1200 * ln(r / h) / ln(2.0)) < 35 && bs.sal < a.sal * overtoneGuard }
            if (onOvertone) { best.dead = true; for (c in cands) if (abs(1200 * ln(c.f / fr) / ln(2.0)) < 60) c.dead = true; continue }
            // Expected overtone level = local average of neighbouring overtones; a partial sticking out
            // above its neighbours is probably another real note and keeps the excess.
            val byH = HashMap<Int, Double>(); for ((pk, h) in bs.match) byH[h] = (byH[h] ?: 0.0) + pk.rem
            val exp = HashMap<Int, Double>()
            for ((h, _) in byH) {
                if (h == 1) { exp[h] = Double.POSITIVE_INFINITY; continue }
                var sm = 0.0; var k = 0; for (d in -1..1) byH[h + d]?.let { sm += it; k++ }; exp[h] = 1.15 * sm / k
            }
            for ((pk, h) in bs.match) {
                val share = pk.rem / byH.getValue(h); pk.rem = max(0.0, pk.rem - exp.getValue(h) * share)
                if (pk.rem < .3 * pk.amp) pk.rem = 0.0
            }
            for (c in cands) if (abs(1200 * ln(c.f / fr) / ln(2.0)) < 60) c.dead = true
            acc += Acc(fr, thr + 20 * log10(bs.sal), bs.h1?.prom ?: 12.0, bs.cnt, bs.sal)
        }
        val fresh = FloatArray(128)
        for (a in acc) {
            val m = Music.ftom(a.f).roundToInt(); if (m < Music.MIN_M || m > Music.MAX_M) continue
            if (noisy || !loud || !recent(a.f)) continue
            val s = ((a.db - thr) / 34).coerceIn(0.0, 1.0).toFloat()
            if (s > fresh[m]) { fresh[m] = s; detFreq[m] = a.f.toFloat(); detConf[m] = min(1.0, a.prom / 24 * (if (a.cnt > 1) 1.0 else .6)).toFloat() }
        }
        for (m in Music.MIN_M..Music.MAX_M) {
            if (fresh[m] > 0) {
                val steady = stab[m] > 0 && abs(1200 * ln(detFreq[m] / stabF[m].toDouble()) / ln(2.0)) < steadyCents
                if (steady) { stab[m] = min(255, stab[m] + 1); stPeak[m] = max(stPeak[m], fresh[m]) } else { stab[m] = 1; stPeak[m] = fresh[m] }
                stabF[m] = detFreq[m]
                // a kick drum's boom has a pitch but collapses within ~200 ms; a bass note doesn't
                if (m < 38 && stab[m] < lowCollapse && fresh[m] < stPeak[m] - .25f) stab[m] = 0
            } else stab[m] = 0
            val need = if (m < 38) stabLow else stabN
            val ok = stab[m] >= need || detState[m] > .12f   // drum hits never persist long enough
            detState[m] = max(if (ok) fresh[m] else 0f, detState[m] * decay)
        }
    }

    fun reset() { detState.fill(0f); stab.fill(0); smooth.fill(0f); noiseLevelDb = Double.NaN; roomFrames = 0; levelHist.fill(Double.NaN) }

    /** Two lit neighbours a half-step apart are almost always one wavering pitch: keep the stronger. */
    var mergeHalfSteps = true
    fun notes(): List<Note> {
        val out = ArrayList<Note>()
        for (m in Music.MIN_M..Music.MAX_M) {
            if (detState[m] < .12f) continue
            if (mergeHalfSteps && (detState[m - 1] > detState[m] || detState[m + 1] >= detState[m])) continue
            val fr = detFreq[m].toDouble()
            out += Note(m, fr, detState[m], detConf[m], 1200 * ln(fr / Music.mtof(m)) / ln(2.0))
        }
        return out.sortedBy { it.freq }
    }
}
