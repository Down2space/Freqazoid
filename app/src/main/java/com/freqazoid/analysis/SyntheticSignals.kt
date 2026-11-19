package com.freqazoid.analysis

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Labeled synthetic inputs. TEST mode feeds these through the same analyzer as the microphone,
 * so every mode shows exactly what the analysis does with a known input. It never opens the mic.
 */
enum class TestSignal(val label: String, val partials: List<Pair<Double, Double>>, val noise: Double = 0.0) {
    SINGLE("Single tone 440 Hz", listOf(440.0 to .40)),
    OCTAVE("Octave 220 + 440 Hz (2:1)", listOf(220.0 to .30, 440.0 to .30)),
    FIFTH("Fifth 220 + 330 Hz (3:2)", listOf(220.0 to .30, 330.0 to .30)),
    FOURTH("Fourth 300 + 400 Hz (4:3)", listOf(300.0 to .30, 400.0 to .30)),
    DETUNED_FIFTH("Detuned fifth 220 + 331.5 Hz (≈3:2 +7.8¢)", listOf(220.0 to .30, 331.5 to .30)),
    CHORD("Chord 261.6/329.6/392 Hz, unequal levels", listOf(261.63 to .32, 329.63 to .16, 392.00 to .08)),
    GUITAR("Guitar-like 110 Hz, harmonics 2–8", (1..8).map { k -> 110.0 * k * sqrt(1 + 1e-4 * k * k) to .34 / k }),
    NOISE("Broadband noise", emptyList(), noise = .18);

    /** Display text always marks the input as TEST. */
    val testLabel get() = "TEST · $label"
}

/**
 * Generates [signal] at [sampleRate]. When [paced] the read blocks so samples arrive at real-time
 * rate (like a recorder); tests may disable pacing. Timestamps come from the synthetic sample clock.
 */
class SyntheticPcmSource(
    val signal: TestSignal,
    override val sampleRate: Int = 48000,
    private val paced: Boolean = true,
    seed: Int = 7,
    private val clock: () -> Long = System::nanoTime
) : PcmSource {
    override val label get() = signal.testLabel
    override val isMicrophone = false
    private val random = Random(seed)
    private var frame = 0L
    private var startNanos = 0L
    private var closed = false
    override fun start() { startNanos = clock() }
    fun sample(i: Long): Short {
        val t = i.toDouble() / sampleRate
        var v = 0.0
        for ((hz, a) in signal.partials) if (hz < sampleRate / 2.0) v += a * sin(2 * PI * hz * t)
        if (signal.noise > 0) v += signal.noise * (random.nextDouble() * 2 - 1)
        return (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
    }
    override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
        check(!closed) { "Synthetic source closed" }
        for (i in 0 until length) buffer[offset + i] = sample(frame + i)
        frame += length
        if (paced) {
            val due = startNanos + (frame * 1e9 / sampleRate).toLong()
            val wait = due - clock()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
        return length
    }
    override fun frameTimeNanos(framePosition: Long): Long = startNanos + (framePosition * 1e9 / sampleRate).toLong()
    override fun close() { closed = true }

}
