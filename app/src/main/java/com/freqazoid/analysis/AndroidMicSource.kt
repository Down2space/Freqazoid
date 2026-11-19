package com.freqazoid.analysis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import kotlin.math.max

/**
 * Opens one AudioRecord. Prefers 48 kHz, then 44.1 kHz, then 16 kHz. Uses the UNPROCESSED source
 * when the device declares support (no AGC/noise suppression), otherwise VOICE_RECOGNITION.
 * Nothing is saved or uploaded. The caller (AudioController) guarantees single ownership.
 */
class AndroidMicOpener(context: Context) : PcmSourceOpener {
    private val app = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun open(settings: AnalysisSettings): PcmSource {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microphone permission is required")
        val manager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessed = manager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = if (unprocessed) intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION)
                      else intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        for (sourceType in sources) for (rate in intArrayOf(48000, 44100, 16000)) {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            // ~250 ms of device buffering; the analysis window itself is kept in the controller's ring.
            val bytes = max(minimum * 2, rate / 4 * 2)
            val record = try {
                AudioRecord(sourceType, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
            } catch (_: IllegalArgumentException) { continue }
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                val name = if (sourceType == MediaRecorder.AudioSource.UNPROCESSED) "unprocessed" else "voice-recognition"
                return AndroidMicSource(record, "microphone · $name · ${record.sampleRate} Hz")
            }
            record.release()
        }
        throw IllegalStateException("No supported microphone format (tried 48/44.1/16 kHz mono PCM16)")
    }
}

class AndroidMicSource(private val record: AudioRecord, override val label: String) : PcmSource {
    override val sampleRate: Int = record.sampleRate
    override val isMicrophone = true
    private val stamp = AudioTimestamp()
    private var released = false

    override fun start() {
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone unavailable (busy or blocked)" }
    }

    override fun read(buffer: ShortArray, offset: Int, length: Int): Int =
        record.read(buffer, offset, length, AudioRecord.READ_BLOCKING)

    /** Extrapolates the hardware (CLOCK_MONOTONIC) timestamp to [framePosition]; null if unavailable. */
    override fun frameTimeNanos(framePosition: Long): Long? {
        if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return null
        if (stamp.nanoTime <= 0L) return null
        return stamp.nanoTime + ((framePosition - stamp.framePosition) * 1_000_000_000L) / sampleRate
    }

    override fun isSilenced(): Boolean = try {
        record.activeRecordingConfiguration?.isClientSilenced == true
    } catch (_: RuntimeException) { false }

    override fun close() {
        if (released) return
        released = true
        try { record.stop() } catch (_: IllegalStateException) {}
        record.release()
    }
}
