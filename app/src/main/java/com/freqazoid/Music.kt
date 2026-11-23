package com.freqazoid

import kotlin.math.*

/** Music basics, colors and chord naming — ported from Chromatic Resonance (web v2.0). */
object Music {
    val SHARP = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    val FLAT = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
    val INT_NAMES = arrayOf("unison", "minor 2nd", "major 2nd", "minor 3rd", "major 3rd", "perfect 4th", "tritone",
        "perfect 5th", "minor 6th", "major 6th", "minor 7th", "major 7th")
    val INT_SHORT = arrayOf("P1", "m2", "M2", "m3", "M3", "P4", "TT", "P5", "m6", "M6", "m7", "M7")
    val JUST = doubleArrayOf(1.0, 16 / 15.0, 9 / 8.0, 6 / 5.0, 5 / 4.0, 4 / 3.0, 45 / 32.0, 3 / 2.0, 8 / 5.0, 5 / 3.0, 9 / 5.0, 15 / 8.0)
    const val MIN_M = 24   // C1
    const val MAX_M = 107  // B7

    /** Reference tuning: A4 = 440 Hz (standard). */
    const val a4 = 440.0
    fun mtof(m: Int) = a4 * 2.0.pow((m - 69) / 12.0)
    fun mtof(m: Double) = a4 * 2.0.pow((m - 69) / 12.0)
    fun ftom(f: Double) = 69 + 12 * ln(f / a4) / ln(2.0)

    // ---- colors (HSL like the web version) ----
    fun hue(pc: Int) = ((pc * 30 + 350) % 360).toFloat()
    fun octL(o: Int) = 24f + (o.coerceIn(1, 7) - 1) * 10.5f
    fun hsla(h: Float, s: Float, l: Float, a: Float): Int {
        val ss = s / 100f; val ll = l / 100f
        val c = (1 - abs(2 * ll - 1)) * ss; val hp = (h % 360) / 60f; val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = when { hp < 1 -> Triple(c, x, 0f); hp < 2 -> Triple(x, c, 0f); hp < 3 -> Triple(0f, c, x)
            hp < 4 -> Triple(0f, x, c); hp < 5 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        val m = ll - c / 2
        fun ch(v: Float) = ((v + m).coerceIn(0f, 1f) * 255 + .5f).toInt()
        return ((a.coerceIn(0f, 1f) * 255 + .5f).toInt() shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
    fun col(pc: Int, l: Float = 62f, a: Float = 1f) = hsla(hue(pc), 88f, l, a)
    fun colO(pc: Int, oct: Int, a: Float = 1f, dl: Float = 0f) = hsla(hue(pc), if (oct <= 2) 95f else 88f, min(94f, octL(oct) + dl), a)
    fun rgba(r: Int, g: Int, b: Int, a: Float) = ((a.coerceIn(0f, 1f) * 255 + .5f).toInt() shl 24) or (r shl 16) or (g shl 8) or b
    private val INT_RGB = mapOf(1 to intArrayOf(255, 93, 115), 11 to intArrayOf(255, 93, 115), 2 to intArrayOf(255, 150, 90), 10 to intArrayOf(255, 150, 90),
        3 to intArrayOf(106, 168, 255), 9 to intArrayOf(106, 168, 255), 4 to intArrayOf(124, 242, 154), 8 to intArrayOf(124, 242, 154),
        5 to intArrayOf(255, 209, 102), 7 to intArrayOf(255, 209, 102), 6 to intArrayOf(215, 139, 255), 0 to intArrayOf(200, 210, 235))
    fun intCol(ic0: Int, a: Float = 1f): Int { val c = INT_RGB.getValue(((ic0 % 12) + 12) % 12); return rgba(c[0], c[1], c[2], a) }

    // ---- chord naming ----
    private class Q(val key: String, val suffix: String, val iv: IntArray, val long: String)
    private val QUAL = listOf(
        Q("maj", "", intArrayOf(0, 4, 7), "major"), Q("min", "m", intArrayOf(0, 3, 7), "minor"), Q("dim", "dim", intArrayOf(0, 3, 6), "diminished"),
        Q("aug", "aug", intArrayOf(0, 4, 8), "augmented"), Q("sus2", "sus2", intArrayOf(0, 2, 7), "suspended 2"), Q("sus4", "sus4", intArrayOf(0, 5, 7), "suspended 4"),
        Q("7", "7", intArrayOf(0, 4, 7, 10), "dominant 7"), Q("maj7", "maj7", intArrayOf(0, 4, 7, 11), "major 7"), Q("m7", "m7", intArrayOf(0, 3, 7, 10), "minor 7"),
        Q("m7b5", "m7♭5", intArrayOf(0, 3, 6, 10), "half-diminished"), Q("dim7", "dim7", intArrayOf(0, 3, 6, 9), "diminished 7"), Q("6", "6", intArrayOf(0, 4, 7, 9), "major 6"),
        Q("m6", "m6", intArrayOf(0, 3, 7, 9), "minor 6"), Q("add9", "add9", intArrayOf(0, 4, 7, 14), "add 9"), Q("9", "9", intArrayOf(0, 4, 7, 10, 14), "dominant 9"),
        Q("maj9", "maj9", intArrayOf(0, 4, 7, 11, 14), "major 9"), Q("m9", "m9", intArrayOf(0, 3, 7, 10, 14), "minor 9"), Q("7sus4", "7sus4", intArrayOf(0, 5, 7, 10), "7 sus 4"),
        Q("mMaj7", "m(maj7)", intArrayOf(0, 3, 7, 11), "minor-major 7"), Q("5", "5", intArrayOf(0, 7), "power chord"))
    private val FLAT_ROOTS = setOf(1, 3, 5, 8, 10)
    @Volatile var preferFlat = false
    fun nm(pc: Int) = (if (preferFlat) FLAT else SHARP)[pc]

    data class ChordName(val name: String, val sub: String)

    /** [notes] sorted by frequency (lowest = bass). */
    fun nameChord(notes: List<Note>): ChordName {
        if (notes.isEmpty()) return ChordName("—", "")
        val pcs = LinkedHashSet<Int>().apply { notes.forEach { add(it.pc) } }.toList()
        val bass = notes[0].pc
        if (pcs.size == 1) { preferFlat = false; return ChordName(nm(bass), if (notes.size > 1) "octaves" else "single note") }
        if (pcs.size == 2 && notes.size == 2) {
            val ic = ((notes[1].midi - notes[0].midi) % 12 + 12) % 12; preferFlat = bass in FLAT_ROOTS
            return ChordName("${nm(notes[0].pc)}–${nm(notes[1].pc)}", INT_NAMES[ic] + if (notes[1].midi - notes[0].midi > 12) " (compound)" else "")
        }
        val s = pcs.toSet()
        var bestSc = Double.NEGATIVE_INFINITY; var bestR = 0; var bestQ: Q? = null
        for (r in pcs) for (q in QUAL) {
            val t = q.iv.map { (r + it) % 12 }.toSet()
            var match = 0; var miss = 0.0; var extra = 0
            for (x in t) if (x in s) match++ else miss += if ((x - r + 12) % 12 == 7) .6 else 2.5
            for (x in s) if (x !in t) extra++
            val sc = match * 2 - miss - extra * 1.6 + (if (r == bass) .6 else 0.0) - q.iv.size * .05
            if (bestQ == null || sc > bestSc) { bestSc = sc; bestR = r; bestQ = q }
        }
        val q = bestQ
        if (q == null || bestSc < 3) { preferFlat = false; return ChordName(pcs.joinToString(" ") { nm(it) }, "cluster") }
        preferFlat = bestR in FLAT_ROOTS || (3 in q.iv && bestR in intArrayOf(0, 2, 5, 7)) || (10 in q.iv && bestR in intArrayOf(0, 5, 7))
        val name = nm(bestR) + q.suffix + if (bass != bestR) "/" + nm(bass) else ""
        return ChordName(name, nm(bestR) + " " + q.long + if (bass != bestR) " · " + nm(bass) + " in the bass" else "")
    }
}

/** One sounding note. [strength] 0..1; [cents] = offset from the reference-tuned piano pitch. */
data class Note(val midi: Int, val freq: Double, val strength: Float, val conf: Float, val cents: Double) {
    val pc get() = midi % 12
    val oct get() = midi / 12 - 1
}
