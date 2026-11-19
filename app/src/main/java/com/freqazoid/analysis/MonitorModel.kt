package com.freqazoid.analysis

import com.freakazoid.watch.ColorMath
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Display ranges for the monitoring modes; the upper bound is always clamped below Nyquist. */
enum class MonitorRange(val label: String, val lowHz: Double, val highHz: Double) {
    FULL("Full · 20 Hz–20 kHz", 20.0, 20000.0),
    BASS("Bass · 20–500 Hz", 20.0, 500.0),
    MID("Mid · 200 Hz–4 kHz", 200.0, 4000.0),
    HIGH("High · 2–20 kHz", 2000.0, 20000.0)
}

/**
 * Raw spectral monitoring state for Spectrum, Waterfall and Color Δ, fed directly by
 * [AnalysisSnapshot.spectrumDbfs] (never by peaks / harmony selection).
 *
 *  - Column pooling is peak-preserving (max over every FFT bin whose centre falls in the column).
 *    Where a column contains no bin centre the nearest bin is reused and the column is flagged
 *    [View.repeated]; such columns are not independent measurements.
 *  - The column count is fixed, so screen size/rotation never changes history or color deltas.
 *  - History rows are added only for new analysis frames (duplicate/older timestamps ignored),
 *    store the frame's window-end time, and are cleared when sample rate, FFT size or range change.
 *  - Color Δ compares consecutive level-palette colors at the same column:
 *    d = sqrt(ΔR²+ΔG²+ΔB²), percent = 100 d / sqrt(3·255²). First frame after any reset is 0.
 *
 * Thread-safety: all public methods are synchronized; renderers copy state with [copyTo].
 */
class MonitorModel(val columns: Int = 160, val rows: Int = 96) {
    companion object { const val NO_DATA = -200.0; const val FLOOR_DB = -110.0 }

    var range = MonitorRange.FULL; private set
    private val bands = DoubleArray(columns) { NO_DATA }
    private val peaks = DoubleArray(columns) { NO_DATA }
    private val deltas = DoubleArray(columns)
    private val repeated = BooleanArray(columns)
    private var previousColors: IntArray? = null
    private val levelPixels = IntArray(rows * columns)
    private val deltaPixels = IntArray(rows * columns)
    private val levelValues = FloatArray(rows * columns)
    private val deltaValues = FloatArray(rows * columns)
    private val rowTime = LongArray(rows)
    private var head = 0
    private var count = 0
    private var layoutRate = 0
    private var layoutFft = 0
    private var lastStamp = Long.MIN_VALUE
    private var lastSession = -1L
    private var hasLive = false
    private var meanDelta = 0.0
    private var lastRms = -160.0
    private var lastClipped = false
    private var lastPeakHz = 0.0
    private var lastNyquist = 24000.0
    private var lastBinHz = 0.0
    var version = 0L; private set
    /** Rows actually added (for duplicate-suppression tests). */
    var rowsAdded = 0L; private set

    @Synchronized fun setRange(r: MonitorRange) { if (r != range) { range = r; resetAll() } }
    @Synchronized fun resetPeaks() { peaks.fill(NO_DATA); version++ }
    @Synchronized fun clearHistory() {
        levelPixels.fill(0xff000000.toInt()); deltaPixels.fill(0xff000000.toInt()); levelValues.fill(0f); deltaValues.fill(0f)
        head = 0; count = 0; previousColors = null; meanDelta = 0.0; deltas.fill(0.0); version++
    }
    /** Capture stopped: live readings are no longer current. History and peak hold remain. */
    @Synchronized fun clearLive() { if (hasLive) { hasLive = false; bands.fill(NO_DATA); deltas.fill(0.0); meanDelta = 0.0; previousColors = null; version++ } }
    @Synchronized private fun resetAll() { bands.fill(NO_DATA); resetPeaks(); clearHistory(); lastStamp = Long.MIN_VALUE }

    fun lowHz() = range.lowHz
    fun highHz(nyquist: Double) = min(range.highHz, nyquist * .995)
    fun columnHz(column: Double, nyquist: Double): Double =
        MeasurementMath.frequencyAtLogPosition(column / columns, lowHz(), max(lowHz() * 1.01, highHz(nyquist)))

    /** Returns true if a new history row was added. */
    @Synchronized fun consume(s: AnalysisSnapshot): Boolean {
        if (s.sampleRate != layoutRate || s.fftSize != layoutFft) {
            layoutRate = s.sampleRate; layoutFft = s.fftSize; resetAll()
        }
        if (s.windowEndNanos <= lastStamp) return false        // duplicate or older frame
        if (s.sessionId != lastSession) { previousColors = null; lastSession = s.sessionId } // new capture: zero baseline
        lastStamp = s.windowEndNanos
        val binHz = s.binSpacingHz; val nyq = s.nyquistHz; val last = s.spectrumDbfs.lastIndex
        val colors = IntArray(columns); val change = IntArray(columns)
        val prev = previousColors
        var sum = 0.0
        for (x in 0 until columns) {
            val f0 = columnHz(x.toDouble(), nyq); val f1 = columnHz(x + 1.0, nyq)
            var a = max(1, ceil(f0 / binHz).toInt()); var b = min(last - 1, floor(f1 / binHz).toInt())
            val reuse = b < a
            if (reuse) { a = ((f0 * f1).pow(.5) / binHz).roundToInt().coerceIn(1, last - 1); b = a }
            var level = NO_DATA
            for (k in a..b) level = max(level, s.spectrumDbfs[k].toDouble())
            bands[x] = level; repeated[x] = reuse
            peaks[x] = max(peaks[x], level)
            colors[x] = ColorMath.fromDb(level)
            deltas[x] = if (prev == null) 0.0 else ColorMath.percent(colors[x], prev[x])
            sum += deltas[x]
            change[x] = ColorMath.palette(deltas[x] / 100)
            levelValues[head * columns + x] = level.toFloat(); deltaValues[head * columns + x] = deltas[x].toFloat()
        }
        meanDelta = sum / columns
        previousColors = colors
        System.arraycopy(colors, 0, levelPixels, head * columns, columns)
        System.arraycopy(change, 0, deltaPixels, head * columns, columns)
        rowTime[head] = s.windowEndNanos
        head = (head + 1) % rows; count = min(rows, count + 1)
        hasLive = true; lastRms = s.rmsDbfs; lastClipped = s.clipped; lastPeakHz = s.peaks.firstOrNull()?.hz ?: 0.0
        lastNyquist = nyq; lastBinHz = binHz
        rowsAdded++; version++
        return true
    }

    /** Immutable-by-ownership copy for one renderer. Rows are ordered newest first. */
    class View(val columns: Int, val rows: Int) {
        val bands = DoubleArray(columns); val peaks = DoubleArray(columns); val deltas = DoubleArray(columns)
        val repeated = BooleanArray(columns)
        val levelPixels = IntArray(rows * columns); val deltaPixels = IntArray(rows * columns)
        val levelValues = FloatArray(rows * columns); val deltaValues = FloatArray(rows * columns)
        val rowTime = LongArray(rows)
        var count = 0; var hasLive = false; var meanDelta = 0.0; var rms = -160.0; var clipped = false
        var peakHz = 0.0; var lowHz = 20.0; var highHz = 20000.0; var nyquist = 24000.0; var binHz = 0.0
        var version = -1L; var range = MonitorRange.FULL
        fun spanSeconds() = if (count < 2) 0.0 else (rowTime[0] - rowTime[count - 1]) / 1e9
        fun columnHz(column: Double) = MeasurementMath.frequencyAtLogPosition(column / columns, lowHz, max(lowHz * 1.01, highHz))
    }

    @Synchronized fun copyTo(v: View) {
        require(v.columns == columns && v.rows == rows)
        if (v.version == version) return
        System.arraycopy(bands, 0, v.bands, 0, columns); System.arraycopy(peaks, 0, v.peaks, 0, columns)
        System.arraycopy(deltas, 0, v.deltas, 0, columns); System.arraycopy(repeated, 0, v.repeated, 0, columns)
        for (age in 0 until rows) {
            val dst = age * columns
            if (age < count) {
                val row = (head - 1 - age + rows) % rows; val src = row * columns
                System.arraycopy(levelPixels, src, v.levelPixels, dst, columns); System.arraycopy(deltaPixels, src, v.deltaPixels, dst, columns)
                System.arraycopy(levelValues, src, v.levelValues, dst, columns); System.arraycopy(deltaValues, src, v.deltaValues, dst, columns)
                v.rowTime[age] = rowTime[row]
            } else {
                v.levelPixels.fill(0xff000000.toInt(), dst, dst + columns); v.deltaPixels.fill(0xff000000.toInt(), dst, dst + columns)
                v.levelValues.fill(0f, dst, dst + columns); v.deltaValues.fill(0f, dst, dst + columns); v.rowTime[age] = 0
            }
        }
        v.count = count; v.hasLive = hasLive; v.meanDelta = meanDelta; v.rms = lastRms; v.clipped = lastClipped
        v.peakHz = lastPeakHz; v.lowHz = lowHz(); v.highHz = highHz(lastNyquist); v.nyquist = lastNyquist; v.binHz = lastBinHz
        v.range = range; v.version = version
    }
}
