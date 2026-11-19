package com.freqazoid.membrane

import com.freqazoid.analysis.AnalysisSnapshot
import com.freqazoid.analysis.Peak
import kotlin.math.*

/**
 * Sound-mode harmony & depth engine (pure Kotlin, single owner: one render coroutine).
 *
 * Grammar (explicit visualization rules, not a physical simulation):
 *  - Components are measured spectral peaks (optionally grouped into harmonic series). Their
 *    frequencies are used exactly as measured; nothing is snapped to a fraction.
 *  - The anchor is the strongest stable component. It changes only when another component is
 *    ≥ [ANCHOR_SWITCH_DB] stronger for ≥ [ANCHOR_HOLD_S], or when the anchor has been missing for
 *    [ANCHOR_LOST_S]. Unmatched tracks fade and are dropped after [TRACK_TIMEOUT_S].
 *  - For each pair (f_low, f_high) the curve is
 *        x = A_x sin(2π f_low s + φ_low),  y = A_y cos(2π f_high s + φ_high),
 *        z = 0.42 sin((2π f_high s + φ_high) − (2π f_low s + φ_low))
 *    over s ∈ [0, q/f_low] where p:q is the nearest small fraction (a VIEW WINDOW choice).
 *    Exact p:q closes; detuned ratios stay open and drift.
 *  - φ are GENERATED display phases: integrated oscillators advanced by 2π f k dt, where k is a
 *    common slow display clock (k ≤ 1, chosen so the fastest drawn component advances ≤ 1.5
 *    cycles/s). They are not measured acoustic phase. Frequency updates never reset them.
 *  - Amplitude (digital energy) controls brightness and extent only — never frequency.
 *  - Depth: rotate (optional slow yaw) + fixed tilt + perspective; per-point shade uses the
 *    projected depth and the Euclidean radius sqrt(x²+y²+z²). (This is the Pythagorean theorem
 *    in space, unrelated to "Pythagorean" musical ratios such as 3:2.)
 *  - A single component is compared with an explicit, user-toggleable 110 Hz visual REFERENCE,
 *    drawn desaturated and dashed, never presented as a detected sound.
 */
enum class RenderQuality(val label: String, val fps: Int, val maxCurves: Int, val maxCyclesHigh: Int, val samplesPerCycle: Int, val maxPoints: Int, val glow: Boolean) {
    WATCH("Watch · 30 fps target", 30, 3, 16, 36, 1400, false),
    PHONE("Phone · 60 fps target", 60, 4, 32, 48, 3072, true),
    REDUCED("Reduced detail (under load)", 30, 2, 10, 32, 720, false);
    companion object { const val MAX_POINTS = 3072 }
}

data class HarmonySettings(
    val groupHarmonics: Boolean = false,
    val showReference: Boolean = true,
    val rotate: Boolean = true,
    val referenceHz: Double = 110.0
)

/** Frequency → hue, shared by renderer and legend. 30 Hz red … 20 kHz violet, log scale. */
object HueScale {
    const val MIN_HZ = 30.0; const val MAX_HZ = 20000.0; const val MAX_DEGREES = 285f
    val TICKS = doubleArrayOf(50.0, 200.0, 1000.0, 5000.0)
    fun position(hz: Double) = (ln(hz.coerceIn(MIN_HZ, MAX_HZ) / MIN_HZ) / ln(MAX_HZ / MIN_HZ)).toFloat()
    fun degrees(hz: Double) = position(hz) * MAX_DEGREES
}

data class Component(val hz: Double, val amplitude: Double, val dbfs: Double, val partials: Int = 1, val confidence: Double = 1.0)

/**
 * Optional, confidence-aware harmonic grouping. The fundamental must itself be detected, and a
 * lone "harmonic" only groups if weaker than its candidate fundamental (so a plain octave of two
 * equal tones stays two components). Raw spectra are never modified by this.
 */
object HarmonicGrouper {
    const val TOLERANCE_CENTS = 20.0
    const val LONE_HARMONIC_RATIO = 0.708   // −3 dB
    fun cents(a: Double, b: Double) = 1200 * ln(b / a) / ln(2.0)
    fun group(peaks: List<Peak>, nyquistHz: Double): List<Component> {
        val pool = peaks.filter { it.hz >= 30 }.sortedByDescending { it.amplitude }.take(16).sortedBy { it.hz }
        val used = BooleanArray(pool.size)
        val out = ArrayList<Component>()
        for (i in pool.indices) {
            if (used[i]) continue
            val base = pool[i]
            val members = ArrayList<Int>(); var expected = 0
            for (k in 2..8) {
                val target = base.hz * k
                if (target >= nyquistHz) break
                expected++
                var best = -1; var bestErr = TOLERANCE_CENTS
                for (j in i + 1 until pool.size) {
                    if (used[j] || j in members) continue
                    val e = abs(cents(target, pool[j].hz))
                    if (e < bestErr && base.amplitude >= pool[j].amplitude * .18) { best = j; bestErr = e }
                }
                if (best >= 0) members += best
            }
            val matched = members.size
            // One lone match only groups if clearly (≥ 3 dB) weaker; two near-equal tones stay separate.
            val group = matched >= 2 || (matched == 1 && pool[members[0]].amplitude < base.amplitude * LONE_HARMONIC_RATIO)
            if (group) {
                members.forEach { used[it] = true }
                var power = base.amplitude * base.amplitude
                for (j in members) power += pool[j].amplitude * pool[j].amplitude
                val amp = sqrt(power).coerceAtMost(1.0)
                val confidence = if (expected == 0) 0.0 else (matched.toDouble() / min(expected, 4)).coerceAtMost(1.0)
                out += Component(base.hz, amp, 20 * log10(amp.coerceAtLeast(1e-12)), 1 + matched, confidence)
            } else out += Component(base.hz, base.amplitude, base.dbfs)
            used[i] = true
        }
        return out.sortedByDescending { it.amplitude }
    }
}

data class HarmonyHud(
    val active: Boolean = false,
    val isTest: Boolean = false,
    val anchorHz: Double = 0.0,
    val anchorDbfs: Double = -160.0,
    val rmsDbfs: Double = -160.0,
    val clipped: Boolean = false,
    val comparedHz: Double = 0.0,
    val ratioLabel: String? = null,
    val ratioErrorCents: Double = 0.0,
    val simpleRatio: Boolean = false,
    val referenceOnly: Boolean = false,
    val singleComponent: Boolean = false,
    val partialWindow: Boolean = false,
    val displayScale: Double = 1.0,
    val components: Int = 0,
    val groupedPartials: Int = 0,
    val frozen: Boolean = false
)

/** Renderer-owned output buffers. Written only by [HarmonyEngine.fill]; read by one renderer. */
class CurveSet(val maxCurves: Int = 8, val maxPoints: Int = RenderQuality.MAX_POINTS) {
    var count = 0
    val points = IntArray(maxCurves)
    val xy = Array(maxCurves) { FloatArray(2 * maxPoints) }
    /** 0 = far/dim … 1 = near/bright. */
    val shade = Array(maxCurves) { FloatArray(maxPoints) }
    val hue = FloatArray(maxCurves)
    val saturation = FloatArray(maxCurves)
    val strength = FloatArray(maxCurves)
    val reference = BooleanArray(maxCurves)
}

class HarmonyEngine {
    companion object {
        const val ANCHOR_SWITCH_DB = 3.0
        const val ANCHOR_HOLD_S = 0.30
        const val ANCHOR_LOST_S = 0.15
        const val TRACK_TIMEOUT_S = 0.50
        const val STALE_S = 0.60
        const val RELATIVE_FLOOR_DB = -36.0
        const val MAX_DISPLAY_CPS = 1.5
        const val TILT = 0.48
        const val YAW_CPS = 0.035
        private const val REF = -1
        private const val SELF = -2
        private const val TWO_PI = 2 * PI
    }

    private class Track(val id: Int, var hz: Double, var target: Double, var amp: Double, var dbfs: Double,
                        var phase: Double = 0.0, var missed: Double = 0.0, var partials: Int = 1)
    private data class PairKey(val a: Int, val b: Int)

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var anchorId = 0
    private var challengerId = 0
    private var challengerSince = 0.0
    private var clockSeconds = 0.0
    private var referencePhase = 0.0
    private var k = 1.0
    private var yaw = 0.0
    private var lastStamp = Long.MIN_VALUE
    private var lastSession = -1L
    private val pairs = LinkedHashMap<PairKey, Double>()
    private var hud = HarmonyHud()
    private var lastRms = -160.0
    private var lastClipped = false
    private var lastIsTest = false
    private var grouped = 0

    val isInactive get() = tracks.isEmpty() && pairs.isEmpty()
    val displayScale get() = k
    /** Current display phase of the anchor track (for continuity tests). */
    fun anchorPhase(): Double = tracks.firstOrNull { it.id == anchorId }?.phase ?: Double.NaN
    fun anchorHz(): Double = tracks.firstOrNull { it.id == anchorId }?.hz ?: 0.0

    fun reset() { tracks.clear(); pairs.clear(); anchorId = 0; challengerId = 0; lastStamp = Long.MIN_VALUE; lastSession = -1; hud = HarmonyHud() }

    /**
     * Advance by [dt] seconds of display time. [snapshot] is the latest published analysis (or null
     * when capture is off). [frozen]: hold the figure (no clock, no fades). [nowNanos] is the
     * monotonic clock, used only to detect stale input — never as capture time.
     */
    fun step(snapshot: AnalysisSnapshot?, dt: Double, settings: HarmonySettings, quality: RenderQuality, nowNanos: Long, frozen: Boolean = false): HarmonyHud {
        val d = if (frozen) 0.0 else dt.coerceIn(0.0, .25)
        clockSeconds += d
        val fresh = snapshot != null && (frozen || nowNanos - snapshot.windowEndNanos < (STALE_S * 1e9).toLong() + snapshot.windowDurationNanos)
        if (snapshot != null && fresh && (snapshot.sessionId != lastSession || snapshot.windowEndNanos > lastStamp)) {
            val dtSnap = if (lastStamp == Long.MIN_VALUE || snapshot.sessionId != lastSession) .05 else ((snapshot.windowEndNanos - lastStamp) / 1e9).coerceIn(.005, .5)
            lastStamp = snapshot.windowEndNanos; lastSession = snapshot.sessionId
            ingest(snapshot, dtSnap, settings)
        } else if (!fresh) {
            for (t in tracks) { t.target = 0.0; t.missed += d }
        }
        if (!frozen) {
            for (t in tracks) {
                val tau = if (t.target > t.amp) .05 else if (t.target == 0.0) .12 else .25
                t.amp += (t.target - t.amp) * (1 - exp(-d / tau))
            }
            // Missing components have faded (release τ = 0.12 s) to ~1% by the documented timeout.
            tracks.removeAll { it.missed > TRACK_TIMEOUT_S || (it.target == 0.0 && it.amp < 1e-5) }
        }
        chooseAnchor(d)
        val anchor = tracks.firstOrNull { it.id == anchorId }
        val others = tracks.filter { it !== anchor && it.target > 0 }.sortedByDescending { it.target }.take(quality.maxCurves)
        // Desired pairs (anchor vs. others, or explicit reference / self-figure for one component).
        val desired = HashSet<PairKey>()
        if (anchor != null && anchor.target > 0) {
            if (others.isNotEmpty()) others.forEach { desired += PairKey(anchor.id, it.id) }
            else desired += PairKey(anchor.id, if (settings.showReference) REF else SELF)
        }
        for (key in desired) pairs.putIfAbsent(key, 0.0)
        if (!frozen) {
            val it = pairs.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val goal = if (e.key in desired) 1.0 else 0.0
                e.setValue(e.value + (goal - e.value) * (1 - exp(-d / .15)))
                val alive = tracks.any { t -> t.id == e.key.a } && (e.key.b < 0 || tracks.any { t -> t.id == e.key.b })
                if ((goal == 0.0 && e.value < .01) || !alive) it.remove()
            }
        }
        // Common display clock: preserves every ratio; smoothed so a new component never jumps phase.
        val drawn = ArrayList<Double>()
        for (key in pairs.keys) { hzOf(key.a, settings)?.let { drawn += it }; hzOf(key.b, settings)?.let { drawn += it } }
        val fMax = drawn.maxOrNull()
        if (fMax != null && fMax > 0) {
            val target = min(1.0, MAX_DISPLAY_CPS / fMax)
            // Slow down immediately (never spin fast), speed up gently. Phases are integrated, so
            // either change alters drift speed only, never the current phase.
            k = if (target < k) target else exp(ln(k) + (ln(target) - ln(k)) * (1 - exp(-d / .4)))
        }
        for (t in tracks) t.phase = (t.phase + TWO_PI * t.hz * k * d) % TWO_PI
        referencePhase = (referencePhase + TWO_PI * settings.referenceHz * k * d) % TWO_PI
        if (settings.rotate && !isInactive) yaw = (yaw + TWO_PI * YAW_CPS * d) % TWO_PI
        hud = buildHud(anchor, others, settings, frozen, quality)
        return hud
    }

    private fun hzOf(id: Int, s: HarmonySettings): Double? = when (id) {
        REF -> s.referenceHz
        SELF -> null
        else -> tracks.firstOrNull { it.id == id }?.hz
    }

    private fun ingest(s: AnalysisSnapshot, dtSnap: Double, settings: HarmonySettings) {
        lastRms = s.rmsDbfs; lastClipped = s.clipped; lastIsTest = s.isTest
        val raw = if (settings.groupHarmonics) HarmonicGrouper.group(s.peaks, s.nyquistHz)
                  else s.peaks.filter { it.hz >= 30 }.map { Component(it.hz, it.amplitude, it.dbfs) }
        val strongest = raw.maxOfOrNull { it.amplitude } ?: 0.0
        val floor = strongest * 10.0.pow(RELATIVE_FLOOR_DB / 20)
        val comps = raw.filter { it.amplitude > 0 && it.amplitude >= floor }.sortedByDescending { it.amplitude }.take(8)
        grouped = comps.count { it.partials > 1 }
        val available = tracks.toMutableList()
        for (c in comps) {
            val tolerance = max(35.0, 1200 * ln(1 + 1.5 * s.binSpacingHz / c.hz) / ln(2.0))
            val match = available.minByOrNull { abs(HarmonicGrouper.cents(it.hz, c.hz)) }
            if (match != null && abs(HarmonicGrouper.cents(match.hz, c.hz)) < tolerance) {
                val p = 1 - exp(-dtSnap / .04)
                match.hz = exp(ln(match.hz) * (1 - p) + ln(c.hz) * p)   // log-domain smoothing, phase untouched
                match.target = c.amplitude; match.dbfs = c.dbfs; match.missed = 0.0; match.partials = c.partials
                available.remove(match)
            } else {
                tracks += Track(nextId++, c.hz, c.amplitude, 0.0, c.dbfs, partials = c.partials) // fades in from 0
            }
        }
        // Brief dropouts (< 100 ms) hold their level instead of flickering; longer ones fade.
        for (t in available) { t.missed += dtSnap; if (t.missed > .10) t.target = 0.0 }
    }

    private fun chooseAnchor(d: Double) {
        val live = tracks.filter { it.target > 0 }
        val strongest = live.maxByOrNull { it.target }
        val anchor = tracks.firstOrNull { it.id == anchorId }
        if (strongest == null) { if (anchor == null) anchorId = 0; challengerId = 0; return }
        if (anchor == null || (anchor.missed > ANCHOR_LOST_S && anchor.target == 0.0)) { anchorId = strongest.id; challengerId = 0; return }
        if (strongest.id == anchor.id) { challengerId = 0; return }
        if (strongest.target > anchor.target * 10.0.pow(ANCHOR_SWITCH_DB / 20)) {
            if (challengerId != strongest.id) { challengerId = strongest.id; challengerSince = clockSeconds }
            else if (clockSeconds - challengerSince >= ANCHOR_HOLD_S) { anchorId = strongest.id; challengerId = 0 }
        } else challengerId = 0
    }

    private fun buildHud(anchor: Track?, others: List<Track>, settings: HarmonySettings, frozen: Boolean, quality: RenderQuality): HarmonyHud {
        if (anchor == null || anchor.target <= 0) return HarmonyHud(rmsDbfs = lastRms, clipped = lastClipped, isTest = lastIsTest, frozen = frozen,
            active = !isInactive, displayScale = k)
        val compared = others.firstOrNull()
        val otherHz = compared?.hz ?: if (settings.showReference) settings.referenceHz else anchor.hz
        val low = min(anchor.hz, otherHz); val high = max(anchor.hz, otherHz)
        val r = HarmonyGeometry.ratio(low, high)
        val window = r.lower / low
        val partial = high * window > quality.maxCyclesHigh
        return HarmonyHud(
            active = true, isTest = lastIsTest, anchorHz = anchor.hz, anchorDbfs = anchor.dbfs, rmsDbfs = lastRms, clipped = lastClipped,
            comparedHz = otherHz, ratioLabel = r.label, ratioErrorCents = r.errorCents, simpleRatio = abs(r.errorCents) <= 12,
            referenceOnly = compared == null && settings.showReference, singleComponent = compared == null,
            partialWindow = partial, displayScale = k, components = tracks.count { it.target > 0 },
            groupedPartials = if (settings.groupHarmonics) grouped else 0, frozen = frozen
        )
    }

    /** Sample and project every visible pair into [out]. Pure arithmetic: no allocation per point. */
    fun fill(out: CurveSet, quality: RenderQuality, settings: HarmonySettings) {
        out.count = 0
        val ordered = pairs.entries.sortedByDescending { it.value }
        for ((key, weight) in ordered) {
            if (out.count >= out.maxCurves || weight < .01) break
            val a = tracks.firstOrNull { it.id == key.a } ?: continue
            val bTrack = if (key.b >= 0) tracks.firstOrNull { it.id == key.b } ?: continue else null
            val isRef = key.b == REF
            val bHz = when { isRef -> settings.referenceHz; bTrack != null -> bTrack.hz; else -> a.hz }
            val bPhase = when { isRef -> referencePhase; bTrack != null -> bTrack.phase; else -> a.phase }
            val bAmp = bTrack?.amp ?: a.amp
            val aIsLow = a.hz <= bHz
            val low = if (aIsLow) a.hz else bHz; val high = if (aIsLow) bHz else a.hz
            val phiLow = if (aIsLow) a.phase else bPhase; val phiHigh = if (aIsLow) bPhase else a.phase
            val ampLow = if (aIsLow) a.amp else bAmp; val ampHigh = if (aIsLow) bAmp else a.amp
            val r = HarmonyGeometry.ratio(low, high)
            var window = r.lower / low
            if (high * window > quality.maxCyclesHigh) window = quality.maxCyclesHigh / high   // shorter partial view
            val n = (ceil(high * window * quality.samplesPerCycle).toInt() + 1).coerceIn(64, min(quality.maxPoints, out.maxPoints))
            val m = max(ampLow, ampHigh).coerceAtLeast(1e-9)
            val ax = .4 + .6 * sqrt(ampLow / m); val ay = .4 + .6 * sqrt(ampHigh / m); val az = .42
            val rMax = sqrt(ax * ax + ay * ay + az * az)
            val c = out.count
            projectCurve(out.xy[c], out.shade[c], n, phiLow, TWO_PI * low * window / (n - 1), phiHigh, TWO_PI * high * window / (n - 1), ax, ay, az, rMax)
            out.points[c] = n
            // Hue = frequency of the compared (non-anchor) measured component; reference/self use the anchor.
            out.hue[c] = HueScale.degrees(bTrack?.hz ?: a.hz)
            out.saturation[c] = if (isRef) .22f else .85f
            out.reference[c] = isRef
            out.strength[c] = ((1 - exp(-6 * sqrt(ampLow * ampLow + ampHigh * ampHigh))) * weight).toFloat()
            out.count++
        }
    }

    private fun projectCurve(xy: FloatArray, shade: FloatArray, n: Int, l0: Double, dl: Double, h0: Double, dh: Double,
                             ax: Double, ay: Double, az: Double, rMax: Double) {
        // Rotation recurrences replace per-point trig (error ~1e-13 per step in double).
        var sl = sin(l0); var cl = cos(l0); var sh = sin(h0); var ch = cos(h0)
        val sdl = sin(dl); val cdl = cos(dl); val sdh = sin(dh); val cdh = cos(dh)
        val cy = cos(yaw); val sy = sin(yaw); val ct = cos(TILT); val st = sin(TILT)
        for (i in 0 until n) {
            val x = ax * sl
            val y = ay * ch
            val z = az * (sh * cl - ch * sl)                     // sin(θh − θl)
            val xr = x * cy + z * sy; val zr = -x * sy + z * cy   // optional slow yaw (display)
            val yy = y * ct - zr * st; val zz = y * st + zr * ct  // fixed tilt
            val scale = 1.65 / (2.65 - zz)                        // perspective
            xy[2 * i] = (xr * scale).toFloat(); xy[2 * i + 1] = (yy * scale).toFloat()
            val radius = sqrt(x * x + y * y + z * z)              // Euclidean distance from origin
            val near = ((zz + 1.3) / 2.6).coerceIn(0.0, 1.0)
            shade[i] = (.8 * near + .2 * (1 - radius / rMax)).coerceIn(0.0, 1.0).toFloat()
            val nsl = sl * cdl + cl * sdl; cl = cl * cdl - sl * sdl; sl = nsl
            val nsh = sh * cdh + ch * sdh; ch = ch * cdh - sh * sdh; sh = nsh
        }
    }
}
