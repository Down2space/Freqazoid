package com.freqazoid.membrane

import com.freqazoid.analysis.AnalysisSettings
import com.freqazoid.analysis.AnalysisSnapshot
import com.freqazoid.analysis.CaptureStats
import com.freqazoid.analysis.CaptureStatus
import com.freqazoid.analysis.MonitorModel
import java.util.Locale
import kotlin.math.abs

/** The four selectable modes. All read the same shared analysis stream. */
enum class DisplayMode(val label: String, val short: String) {
    SOUND("Sound visualizer", "Sound"),
    SPECTRUM("Spectrum", "Spectrum"),
    WATERFALL("Waterfall", "Waterfall"),
    DELTA("Color Δ", "Color Δ")
}

enum class QualityChoice(val label: String) { AUTO("Auto (round screen → watch)"), WATCH("Watch · 30 fps target"), PHONE("Phone · 60 fps target") }

/** Pure text for every on-screen reading, so wording and units are testable off-device. */
object Readouts {
    private fun f(fmt: String, vararg a: Any?) = String.format(Locale.US, fmt, *a)

    fun hz(value: Double): String = when {
        value >= 10000 -> f("%.1f kHz", value / 1000)
        value >= 1000 -> f("%.2f kHz", value / 1000)
        value >= 100 -> f("%.1f Hz", value)
        else -> f("%.2f Hz", value)
    }
    fun hzShort(value: Double): String = if (value >= 1000) f("%.0fk", value / 1000) else f("%.0f", value)

    fun note(hz: Double): String = f("%s %+.0f¢", PitchNames.name(hz), PitchNames.cents(hz))

    fun status(s: CaptureStatus, frame: AnalysisSnapshot?): String = when (s) {
        CaptureStatus.Idle -> "Mic off · tap Start to listen"
        CaptureStatus.RequestingPermission -> "Requesting microphone permission…"
        is CaptureStatus.Starting -> if (s.test) "Starting TEST signal…" else "Opening microphone…"
        is CaptureStatus.Listening -> buildString {
            if (s.test) append("TEST · not the microphone")
            else append("LIVE")
            if (frame != null) append(f(" · %.0f dBFS", frame.rmsDbfs))
            if (frame?.clipped == true) append(" · CLIP")
            if (s.silenced) append(" · SILENCED by system")
            else if (s.digitalSilence) append(" · input is digital zero (mic muted?)")
        }
        is CaptureStatus.Frozen -> "FROZEN · mic off · tap Resume"
        is CaptureStatus.PermissionDenied -> if (s.canAskAgain) "Mic permission denied · tap Start to ask again" else "Mic blocked · allow it in app Settings"
        is CaptureStatus.Error -> s.message
    }

    fun startLabel(s: CaptureStatus, testActive: Boolean): String = when {
        s is CaptureStatus.Starting -> "Starting…"
        s is CaptureStatus.Listening && testActive -> "Stop test"
        s is CaptureStatus.Listening -> "Stop"
        s is CaptureStatus.Frozen -> "Resume"
        s is CaptureStatus.PermissionDenied && !s.canAskAgain -> "Allow mic"
        s is CaptureStatus.Error -> "Retry"
        else -> "Start"
    }

    /** Sound mode, line 1: the relationship being drawn. */
    fun ratioLine(h: HarmonyHud): String = when {
        !h.active -> "Harmony & depth · ratios of measured peaks"
        h.singleComponent && h.referenceOnly -> f("vs %s visual REFERENCE (generated)", hz(h.comparedHz))
        h.singleComponent -> "single component · 1:1 self figure"
        h.simpleRatio -> f("%s · %+.1f¢ · %s vs %s", h.ratioLabel, h.ratioErrorCents, hzShortPrecise(h.anchorHz), hzShortPrecise(h.comparedHz))
        else -> f("≈%s %+.0f¢ · no small ratio · open figure", h.ratioLabel, h.ratioErrorCents)
    }

    private fun hzShortPrecise(v: Double) = if (v >= 1000) f("%.2fk", v / 1000) else f("%.1f", v)

    /** Sound mode, line 2: strongest component, optional note, digital level. */
    fun soundReading(h: HarmonyHud, noteNames: Boolean, status: CaptureStatus, frame: AnalysisSnapshot?): String {
        if (!h.active || h.anchorHz <= 0) {
            return if (status is CaptureStatus.Listening) "Inactive · no peaks above −75 dBFS" + (if (status.test) " · TEST" else "")
                   else status(status, frame)
        }
        val tag = when { h.frozen -> "FROZEN · "; h.isTest -> "TEST · "; else -> "" }
        val note = if (noteNames) " · " + note(h.anchorHz) else ""
        val clip = if (h.clipped) " · CLIP" else ""
        return f("%s%s%s · %.0f dBFS%s", tag, hz(h.anchorHz), note, h.rmsDbfs, clip)
    }

    fun fftOption(n: Int, rate: Int): String = f("%d · %.0f ms window · %.1f Hz bins", n, 1000.0 * n / rate, rate.toDouble() / n)

    const val FFT_TRADEOFF = "Larger FFT = finer frequency bins but a longer window, so slower response to change. " +
        "Peak interpolation refines a peak's estimate; it does not add independent resolution."

    fun spectrumLine(v: MonitorModel.View): String =
        if (!v.hasLive) "No live spectrum" else if (v.peakHz <= 0) "No strong peak (full spectrum shown)"
        else f("Peak %s · %s", hz(v.peakHz), note(v.peakHz))

    fun waterfallLine(v: MonitorModel.View): String =
        if (v.count == 0) "No history yet" else f("Newest at top · %d rows · %.1f s span", v.count, v.spanSeconds())

    fun deltaLine(v: MonitorModel.View): String = f("Mean palette-color change %.1f%%", if (v.hasLive) v.meanDelta else 0.0)

    fun inspection(v: MonitorModel.View, column: Int, age: Int, nowNanos: Long): String {
        val c = column.coerceIn(0, v.columns - 1)
        val f0 = v.columnHz(c.toDouble()); val f1 = v.columnHz(c + 1.0)
        val band = if (v.repeated[c]) " (shared bin)" else ""
        if (age < 0 || age >= v.count) {
            val level = v.bands[c]
            return if (level <= MonitorModel.NO_DATA) f("%s–%s Hz · no live data", hzShort(f0), hzShort(f1))
                   else f("%s–%s Hz%s · %.0f dBFS · Δ%.1f%%", hzShort(f0), hzShort(f1), band, level, v.deltas[c])
        }
        val i = age * v.columns + c
        val before = (v.rowTime[0] - v.rowTime[age]) / 1e9
        val ago = (nowNanos - v.rowTime[age]) / 1e9
        return f("%s–%s Hz · %.0f dBFS · Δ%.1f%% · %.2f s before newest (%.1f s ago)",
            hzShort(f0), hzShort(f1), v.levelValues[i], v.deltaValues[i], before, ago)
    }

    fun debug(fps: Float, quality: RenderQuality, stats: CaptureStats, analysisRate: Double, frame: AnalysisSnapshot?, conflated: Long, nowNanos: Long): List<String> {
        val lines = ArrayList<String>()
        lines += f("render %.0f fps · %s", fps, quality.name.lowercase())
        if (frame != null) {
            lines += f("%d Hz · FFT %d · hop %d · %.1f/s", frame.sampleRate, frame.fftSize, stats.hopSize, analysisRate)
            lines += f("age %.0f ms (+%.0f ms window) · %s", (nowNanos - frame.windowEndNanos) / 1e6, frame.windowDurationNanos / 1e6, frame.timestampSource.name.lowercase())
            lines += f("conflated %d · analysis %.1f ms · hop↑%d", conflated, stats.lastAnalysisMillis, stats.hopIncreases)
        } else lines += "no analysis frame"
        return lines
    }

    /** Info panel text: measured context first, then the rules of each mode. */
    fun info(status: CaptureStatus, frame: AnalysisSnapshot?, settings: AnalysisSettings, stats: CaptureStats): String = buildString {
        appendLine(status(status, frame)); appendLine()
        if (frame != null) {
            appendLine("Source: ${frame.sourceLabel}")
            appendLine(f("Sampling rate: %d Hz (Nyquist %s)", frame.sampleRate, hz(frame.nyquistHz)))
            appendLine(f("FFT: %d samples · window %.1f ms · bin spacing %.2f Hz", frame.fftSize, frame.windowDurationNanos / 1e6, frame.binSpacingHz))
            appendLine(f("Hop: %d samples (%.1f ms) · timestamps: %s", stats.hopSize, 1000.0 * stats.hopSize / frame.sampleRate, frame.timestampSource.label))
            appendLine(f("Digital RMS: %.1f dBFS%s", frame.rmsDbfs, if (frame.clipped) " · CLIPPING detected" else ""))
            appendLine(f("Spectral flatness %.2f (0 tonal … 1 noise-like) · centroid %s", frame.spectralFlatness, hz(frame.centroidHz)))
            if (frame.peaks.isEmpty()) appendLine("No peaks above ${settings.peakThresholdDb.toInt()} dBFS.")
            else {
                appendLine("Strongest spectral peaks (estimates, not separate sources):")
                for (p in frame.peaks.take(6)) appendLine(f("  %s · %s · %.0f dBFS", hz(p.hz), note(p.hz), p.dbfs))
            }
        } else appendLine(f("No analysis frame. FFT setting: %d samples.", settings.fftSize))
        appendLine()
        appendLine("FFT SIZE: $FFT_TRADEOFF")
        appendLine()
        appendLine("SOUND: colored 3D curves from measured frequency ratios. Pair (f_low, f_high): " +
            "x = A·sin(2π f_low t + φ), y = A·cos(2π f_high t + φ), z = 0.42·sin of their phase difference. " +
            "Exact small ratios (2:1, 3:2, 4:3) close into repeating figures; detuned ratios stay open and drift. " +
            "Frequencies are used as measured; p:q is only a label and view window. " +
            "Phases are generated display oscillators on a slowed common clock (shown as ×1/N), not measured acoustic phase. " +
            "Brightness and size follow digital level; line width/brightness also show depth (near = brighter, thicker), " +
            "using perspective and the Euclidean distance √(x²+y²+z²) — the Pythagorean theorem in space, unrelated to Pythagorean tuning ratios. " +
            "Hue follows frequency (legend: red ≈ 30 Hz … violet ≈ 20 kHz). " +
            "The anchor is the strongest stable peak; it changes only after another is 3 dB stronger for 0.3 s. " +
            "With one component, a dashed gray 110 Hz visual reference (toggleable) is generated for comparison; it is not a detected sound. " +
            "Equal-tempered intervals differ slightly from small ratios (a tempered fifth is −2.0¢ from 3:2, a major third +13.7¢ from 5:4); that is normal, not 'bad'. " +
            "No ratio is labeled happy, sad, healthy or objectively good. Mono input cannot always separate octave chords from harmonics; harmonic grouping is optional and confidence-based.")
        appendLine()
        appendLine("SPECTRUM: full measured FFT (all bins, noise included), log frequency axis, dBFS vertical, white peak hold (Reset in menu). " +
            "Each column shows the maximum bin it covers; columns marked as shared reuse one coarse bin and are not new measurements. Tap/drag to inspect.")
        appendLine()
        appendLine("WATERFALL: frequency across, time down (newest at top), colors black → blue → cyan → yellow → red → white for −110 … 0 dBFS. " +
            "Rows are added only for new analysis frames and keep their capture times. Tap a row for its level and age.")
        appendLine()
        appendLine("COLOR Δ: sqrt(ΔR²+ΔG²+ΔB²) between consecutive waterfall-palette colors at each frequency, as % of the maximum sqrt(3·255²). " +
            "It measures palette-color change only — not harmony, perceptual ΔE, quality or calibrated dB. Silence→tone and tone→silence both show change. " +
            "The first frame after any reset is zero.")
        appendLine()
        appendLine("FREEZE stops the microphone and holds the last frame (labeled FROZEN). Leaving the app stops listening; returning never restarts it on its own. " +
            "TEST signals are generated inside the app and never use the microphone. Levels are dBFS (digital), not calibrated dB SPL. Nothing is recorded, saved or uploaded.")
    }
}
