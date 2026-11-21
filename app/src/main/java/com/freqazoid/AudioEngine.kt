package com.freqazoid

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/** One audio thread: plays the demo through the speaker, or listens to the mic. Never both. */
class AudioEngine(val processor: Processor) {
    @Volatile private var running = false
    private var thread: Thread? = null
    var onError: ((String) -> Unit)? = null

    @Synchronized fun start(source: Source) {
        stop()
        processor.setSource(source)
        if (source == Source.OFF) return
        running = true
        thread = Thread({ if (source == Source.DEMO) demoLoop() else micLoop() }, "freqazoid-audio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    @Synchronized fun stop() {
        running = false
        thread?.join(1500); thread = null
        processor.setSource(Source.OFF)
    }

    private fun demoLoop() {
        val sr = processor.sampleRate
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, 4096 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { onError?.invoke("Speaker unavailable"); return }
        val buf = FloatArray(512)
        try {
            track.play()
            while (running) {
                processor.renderDemo(buf, buf.size)
                processor.feed(buf, buf.size)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)   // paces the loop in real time
            }
        } finally { try { track.stop() } catch (_: Exception) {}; track.release() }
    }

    @SuppressLint("MissingPermission")
    private fun micLoop() {
        val sr = processor.sampleRate
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        fun open(src: Int): AudioRecord? = try {
            AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, 8192 * 4)).let { r ->
                if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null } }
        } catch (e: Exception) { null }
        // UNPROCESSED = no automatic gain / noise suppression, when the device offers it
        val rec = open(MediaRecorder.AudioSource.UNPROCESSED) ?: open(MediaRecorder.AudioSource.MIC)
        if (rec == null) { onError?.invoke("Microphone unavailable"); return }
        val buf = FloatArray(512)
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) { onError?.invoke("Microphone stopped"); break }
                processor.feed(buf, n)
            }
        } finally { try { rec.stop() } catch (_: Exception) {}; rec.release() }
    }
}
