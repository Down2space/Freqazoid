package com.freqazoid.membrane

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freqazoid.analysis.AnalysisSettings
import com.freqazoid.analysis.AndroidMicOpener
import com.freqazoid.analysis.AudioController
import com.freqazoid.analysis.MonitorModel
import com.freqazoid.analysis.MonitorRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class UiSettings(
    val mode: DisplayMode = DisplayMode.SOUND,
    val range: MonitorRange = MonitorRange.FULL,
    val groupHarmonics: Boolean = false,
    val showReference: Boolean = true,
    val noteNames: Boolean = true,
    val rotate: Boolean = true,
    val legend: Boolean = true,
    val quality: QualityChoice = QualityChoice.AUTO,
    val debug: Boolean = false
) {
    val harmony get() = HarmonySettings(groupHarmonics = groupHarmonics, showReference = showReference, rotate = rotate)
}

/**
 * Retained across configuration changes. Owns the ONE [AudioController] (and therefore at most one
 * AudioRecord) and the shared [MonitorModel]. Every mode subscribes to [AudioController.frames].
 */
class FreqazoidViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("freqazoid", Context.MODE_PRIVATE)

    val controller = AudioController(
        scope = viewModelScope,
        microphone = AndroidMicOpener(app),
        hasPermission = { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        initialSettings = AnalysisSettings(fftSize = prefs.getInt("fft", 8192).takeIf { it in AnalysisSettings.FFT_SIZES } ?: 8192)
    )
    val monitor = MonitorModel()
    private val mutableMonitorVersion = MutableStateFlow(-1L)
    val monitorVersion: StateFlow<Long> = mutableMonitorVersion.asStateFlow()
    private val mutableUi = MutableStateFlow(load())
    val ui: StateFlow<UiSettings> = mutableUi.asStateFlow()
    private val mutableZoom = MutableStateFlow(1f)
    val zoom: StateFlow<Float> = mutableZoom.asStateFlow()
    /** Snapshots that were published but superseded before the monitor consumer saw them. */
    val conflated = AtomicLong()
    /** Permission was granted while not RESUMED; start on the next resume (the user's own tap). */
    var pendingStartAfterPermission = false

    init {
        monitor.setRange(mutableUi.value.range)
        viewModelScope.launch(Dispatchers.Default) {
            var lastSequence = -1L
            controller.frames.collect { s ->
                if (s == null) monitor.clearLive()
                else {
                    if (lastSequence >= 0 && s.sequence > lastSequence + 1) conflated.addAndGet(s.sequence - lastSequence - 1)
                    lastSequence = s.sequence
                    monitor.consume(s)
                }
                mutableMonitorVersion.value = monitor.version
            }
        }
    }

    fun update(change: (UiSettings) -> UiSettings) {
        val next = change(mutableUi.value)
        mutableUi.value = next
        monitor.setRange(next.range)
        mutableMonitorVersion.value = monitor.version
        prefs.edit()
            .putString("mode", next.mode.name).putString("range", next.range.name)
            .putBoolean("group", next.groupHarmonics).putBoolean("reference", next.showReference)
            .putBoolean("notes", next.noteNames).putBoolean("rotate", next.rotate).putBoolean("legend", next.legend)
            .putString("quality", next.quality.name).putBoolean("debug", next.debug).apply()
    }

    fun setFftSize(size: Int) { controller.setFftSize(size); prefs.edit().putInt("fft", size).apply() }
    fun resetPeaks() { monitor.resetPeaks(); mutableMonitorVersion.value = monitor.version }
    fun clearHistory() { monitor.clearHistory(); mutableMonitorVersion.value = monitor.version }
    fun zoomBy(factor: Float) { mutableZoom.value = (mutableZoom.value * factor).coerceIn(.6f, 1.8f) }

    private fun load(): UiSettings {
        fun <T : Enum<T>> enumOf(values: Array<T>, key: String, default: T) = values.firstOrNull { it.name == prefs.getString(key, null) } ?: default
        val d = UiSettings()
        return UiSettings(
            mode = enumOf(DisplayMode.values(), "mode", d.mode),
            range = enumOf(MonitorRange.values(), "range", d.range),
            groupHarmonics = prefs.getBoolean("group", d.groupHarmonics),
            showReference = prefs.getBoolean("reference", d.showReference),
            noteNames = prefs.getBoolean("notes", d.noteNames),
            rotate = prefs.getBoolean("rotate", d.rotate),
            legend = prefs.getBoolean("legend", d.legend),
            quality = enumOf(QualityChoice.values(), "quality", d.quality),
            debug = prefs.getBoolean("debug", d.debug)
        )
    }
}
