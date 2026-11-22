package com.freqazoid

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.nio.ByteOrder

/**
 * One audio thread at a time: listens to the live mic, or plays a song from the watch through the speaker
 * (decoded, mixed to mono, resampled to 48 kHz) while the same samples are analyzed. The demo tour is kept
 * for tests and previews.
 */
class AudioEngine(private val context: Context, val processor: Processor) {
    @Volatile private var running = false
    private var thread: Thread? = null
    /** Called from the audio thread when playback/listening ends on its own (error or song finished). */
    var onStopped: ((String) -> Unit)? = null

    @Synchronized fun start(source: Source, song: Uri? = null) {
        stop()
        processor.setSource(source)
        if (source == Source.OFF) return
        running = true
        thread = Thread({
            when (source) { Source.DEMO -> demoLoop(); Source.MIC -> micLoop(); Source.SONG -> songLoop(song); Source.OFF -> Unit }
        }, "freqazoid-audio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    @Synchronized fun stop() {
        running = false
        thread?.join(1500); thread = null
        processor.setSource(Source.OFF)
    }

    private fun newTrack(): AudioTrack? {
        val sr = processor.sampleRate
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, 4096 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { null }
    }

    private fun demoLoop() {
        val track = newTrack() ?: run { onStopped?.invoke("Speaker unavailable"); return }
        val buf = FloatArray(512)
        try {
            track.play()
            while (running) { processor.renderDemo(buf, buf.size); processor.feed(buf, buf.size); track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING) }
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
        if (rec == null) { onStopped?.invoke("Microphone unavailable"); return }
        val buf = FloatArray(512)
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) { onStopped?.invoke("Microphone stopped"); break }
                processor.feed(buf, n)
            }
        } finally { try { rec.stop() } catch (_: Exception) {}; rec.release() }
    }

    private fun songLoop(uri: Uri?) {
        if (uri == null) { onStopped?.invoke("No song chosen"); return }
        val track = newTrack() ?: run { onStopped?.invoke("Speaker unavailable"); return }
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var finished = false
        try {
            ex.setDataSource(context, uri, null)
            val index = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: run { onStopped?.invoke("Can't read that song"); return }
            ex.selectTrack(index)
            val fmt = ex.getTrackFormat(index)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); codec = c
            c.configure(fmt, null, null, 0); c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val resampler = Resampler(rate, processor.sampleRate)
            val block = FloatArray(512); var fill = 0
            var mono = FloatArray(4096)
            val info = MediaCodec.BufferInfo()
            var inEos = false; var outEos = false
            track.play()
            while (running && !outEos) {
                if (!inEos) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    resampler.setInputRate(rate)
                } else if (o >= 0) {
                    val out = c.getOutputBuffer(o)!!
                    out.position(info.offset); out.limit(info.offset + info.size); out.order(ByteOrder.nativeOrder())
                    val ch = channels.coerceAtLeast(1)
                    val frames = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 / ch else info.size / 2 / ch
                    if (mono.size < frames) mono = FloatArray(frames)
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) { val fb = out.asFloatBuffer(); for (f in 0 until frames) { var s = 0f; for (k in 0 until ch) s += fb.get(f * ch + k); mono[f] = s / ch } }
                    else { val sb = out.asShortBuffer(); for (f in 0 until frames) { var s = 0f; for (k in 0 until ch) s += sb.get(f * ch + k); mono[f] = s / ch / 32768f } }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    resampler.process(mono, frames) { v ->
                        block[fill++] = v
                        if (fill == block.size) {
                            processor.feed(block, fill)
                            track.write(block, 0, fill, AudioTrack.WRITE_BLOCKING)   // real-time pacing
                            fill = 0
                        }
                    }
                }
            }
            finished = outEos
        } catch (e: Exception) {
            onStopped?.invoke("Can't play that song")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}; codec?.release(); ex.release()
            try { track.stop() } catch (_: Exception) {}; track.release()
        }
        if (finished && running) onStopped?.invoke("Song finished")
    }
}
