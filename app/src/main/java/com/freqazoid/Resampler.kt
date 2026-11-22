package com.freqazoid

/** Streaming linear-interpolation resampler (input rate → 48 kHz). */
class Resampler(inRate: Int, private val outRate: Int) {
    private var step = inRate.toDouble() / outRate
    private var pos = 0.0          // read position relative to the current chunk; −1 means the previous chunk's last sample
    private var prev = 0f
    fun setInputRate(r: Int) { step = r.toDouble() / outRate }
    inline fun process(input: FloatArray, n: Int, emit: (Float) -> Unit) {
        if (n <= 0) return
        while (true) {
            val i = kotlin.math.floor(posValue()).toInt()
            if (i + 1 > n - 1) break
            val frac = (posValue() - i).toFloat()
            val a = if (i < 0) prevValue() else input[i]; val b = input[i + 1]
            emit(a + (b - a) * frac)
            advance()
        }
        finishChunk(input[n - 1], n)
    }
    fun posValue() = pos
    fun prevValue() = prev
    fun advance() { pos += step }
    fun finishChunk(last: Float, n: Int) { pos -= n; prev = last }
}
