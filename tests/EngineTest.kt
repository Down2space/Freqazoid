package com.freqazoid
import java.awt.image.BufferedImage
import kotlin.math.*
import kotlin.random.Random

fun cents(a: Double, b: Double) = 1200 * ln(b / a) / ln(2.0)
/** Warm-timbre test chord at real-time rate through the MIC path (detector), returns detected midis. */
fun detect(midis: List<Int>, seconds: Double = 1.0, a4: Double = 440.0, drums: Boolean = false, noise: Double = 0.0): List<Note> {
    Music.a4 = a4
    val p = Processor(48000); p.setSource(Source.MIC)
    val rnd = Random(4); val buf = FloatArray(512); var i = 0L
    val fs = midis.map { a4 * 2.0.pow((it - 69) / 12.0) }
    while (i < seconds * 48000) {
        for (k in buf.indices) { val t = (i + k) / 48000.0; var v = 0.0
            for (f in fs) { v += .12 * (cos(2 * PI * f * t) + .28 * cos(4 * PI * f * t) + .1 * cos(6 * PI * f * t) + .05 * cos(8 * PI * f * t)) }
            if (drums) { val beat = t % .5; if (beat < .12) { v += .6 * exp(-beat / .03) * sin(2 * PI * (50 + 150 * exp(-beat / .02)) * beat) + .25 * exp(-beat / .02) * (rnd.nextDouble() * 2 - 1) } }
            if (noise > 0) v += noise * (rnd.nextDouble() * 2 - 1)
            buf[k] = v.toFloat() }
        p.feed(buf, buf.size); i += buf.size
    }
    val f = p.newFrame(); p.snapshot(f); Music.a4 = 440.0; return f.notes
}
fun n(m: Int, s: Float = 1f, a4: Double = 440.0) = Note(m, a4 * 2.0.pow((m - 69) / 12.0), s, 1f, 0.0)

fun main() {
    // 1. Detection: chords, octave shift, major→minor, every octave, 432 tuning.
    for (ch in listOf(listOf(60, 64, 67), listOf(72, 76, 79), listOf(60, 63, 67), listOf(48, 55, 64), listOf(55, 59, 62, 65))) {
        val got = detect(ch).map { it.midi }
        check(got.containsAll(ch)) { "missed notes in $ch: got $got" }
        check(got.size <= ch.size + 1) { "too many false notes for $ch: $got" }
    }
    for (m in listOf(36, 48, 60, 72, 84, 96)) { val got = detect(listOf(m)).map { it.midi }; check(got.firstOrNull() == m && got.size <= 2) { "single $m -> $got" } }
    val t432 = detect(listOf(69), a4 = 432.0); check(t432.any { it.midi == 69 && abs(it.cents) < 5 }) { "432 tuning: $t432" }
    val at440 = detect(listOf(69).map { it }, a4 = 440.0).first(); check(abs(at440.freq - 440) < 1.0)
    println("ok 1 detection: triads, octave shift, minor, G7, C2–C7 singles, A4=432 (A reads ${"%.1f".format(t432.first { it.midi == 69 }.freq)} Hz)")
    // 2. Drum filter: kick + hat bursts alone light nothing steady; with a chord the chord survives.
    val drumsOnly = detect(emptyList(), 2.0, drums = true); check(drumsOnly.size <= 1) { "drums produced notes: ${drumsOnly.map { it.midi }}" }
    val withDrums = detect(listOf(60, 64, 67), 2.0, drums = true).map { it.midi }; check(withDrums.containsAll(listOf(60, 64, 67))) { "chord lost under drums: $withDrums" }
    val noisy = detect(emptyList(), 1.0, noise = .2); check(noisy.size <= 1) { "white noise produced ${noisy.map { it.midi }}" }
    val inNoise = detect(listOf(60, 64, 67), 1.0, noise = .05).map { it.midi }; check(inNoise.containsAll(listOf(60, 64, 67))) { "chord lost in light noise: $inNoise" }
    println("ok 2 drum filter: drums alone -> ${drumsOnly.size} notes; chord kept under drums; white noise -> ${noisy.size}; chord found in light noise")
    // 3. Chord names.
    val names = mapOf(listOf(60, 64, 67) to "C", listOf(60, 63, 67) to "Cm", listOf(43, 55, 59, 62, 65) to "G7", listOf(48, 57, 60, 64) to "Am/C",
        listOf(48, 55, 64, 71, 74) to "Cmaj9", listOf(60, 67) to "C–G", listOf(48, 60) to "C")
    for ((m, want) in names) { val got = Music.nameChord(m.map { n(it) }); check(got.name == want) { "$m named ${got.name}, want $want" } }
    check(Music.nameChord(listOf(n(60), n(67))).sub == "perfect 5th")
    println("ok 3 chord names: ${names.values.joinToString(", ")}")
    // 4. Consonance meter matches the web version's measured values.
    fun cons(semi: Int) = Harmony.consonance(listOf(n(60), n(60 + semi)))!!
    val expect = mapOf(12 to .93, 7 to .78, 4 to .51, 6 to .37, 1 to 0.0)
    for ((s, v) in expect) check(abs(cons(s) - v) < .03) { "consonance semis $s = ${cons(s)} want $v" }
    val maj = Harmony.consonance(listOf(n(60), n(64), n(67)))!!; val min = Harmony.consonance(listOf(n(60), n(63), n(67)))!!
    check(abs(maj - min) < .03 && abs(maj - .56) < .03) { "triads $maj $min" }
    println("ok 4 consonance: 8ve %.2f 5th %.2f M3 %.2f TT %.2f m2 %.2f; major %.2f = minor %.2f (%s)".format(cons(12), cons(7), cons(4), cons(6), cons(1), maj, min, Harmony.wordFor(maj)))
    // 5. Beats: piano fifth ≈ 0.9 Hz, just fifth locked.
    val h = Harmony(); repeat(30) { h.update(listOf(n(60), n(67)), 1 / 30.0) }
    check(abs(h.beat!!.hz - .89) < .05 && !h.beat!!.locked) { "ET fifth beat ${h.beat!!.hz}" }
    val hj = Harmony(); val c4 = Music.mtof(60); repeat(30) { hj.update(listOf(Note(60, c4, 1f, 1f, 0.0), Note(67, c4 * 1.5, 1f, 1f, 2.0)), 1 / 30.0) }
    check(hj.beat!!.locked)
    println("ok 5 beats: piano C4–G4 %.2f Hz; pure 3:2 locked".format(h.beat!!.hz))
    // 6. Demo tour loops; per-note isolated waves have the note's period; renderer draws every step in both views.
    val p = Processor(48000); p.setSource(Source.DEMO); val r = Renderer(); val fr = p.newFrame(); val buf = FloatArray(512)
    val img = BufferedImage(450, 450, BufferedImage.TYPE_INT_ARGB); var t = 0.0; var next = 0.0; val seen = HashSet<Int>(); var drawn = 0
    while (t < 60.0) { p.renderDemo(buf, 512); p.feed(buf, 512); t += 512 / 48000.0; seen += p.tour.index
        if (t >= next) { next += .25; p.snapshot(fr); r.overlay = (drawn % 2 == 1); r.draw(Java2dGfx(img), 450f, 450f, fr, t, .25); drawn++ } }
    check(seen.containsAll((0 until Tour.STEPS.size).toList())) { "tour steps seen $seen" }
    run { val q = Processor(48000); q.setSource(Source.DEMO); q.synth.play(listOf(69), hold = 5.0); val b = FloatArray(512)
        repeat(200) { q.synth.render(b, 512); q.feed(b, 512) }; val f = q.newFrame(); q.snapshot(f); val w = f.waves.getValue(69)
        var zc = 0; for (i in w.size - 960 until w.size - 1) if (w[i] <= 0 && w[i + 1] > 0) zc++
        check(zc in 8..10) { "A4 wave crossings in 20 ms = $zc (expect ~8.8)" } }
    println("ok 6 tour: all ${Tour.STEPS.size} steps play and loop; $drawn frames rendered (wheel + overlay); A4 wave shows 8.8 cycles / 20 ms")
    // 7. Song path: 44.1 kHz stereo-mixed audio resampled to 48 kHz in odd-sized chunks stays continuous and in tune.
    run { val rs = Resampler(44100, 48000); val out = ArrayList<Float>(); var t = 0L
        for (chunk in listOf(1000, 37, 4096, 1, 2048, 333, 8000, 4410, 4410, 4410, 4410, 4410, 4410, 4410, 4410, 4410)) {
            val x = FloatArray(chunk) { kotlin.math.sin(2 * PI * 440 * (t + it) / 44100.0).toFloat() }; t += chunk
            rs.process(x, chunk) { out += it } }
        val n = out.size; check(abs(n - t * 48000.0 / 44100) < 3) { "resampled length $n" }
        var maxJump = 0f; for (i in 1 until n) maxJump = max(maxJump, abs(out[i] - out[i - 1])); check(maxJump < 2 * PI * 440 / 48000 * 1.05) { "discontinuity $maxJump" }
        val p = Processor(48000); p.setSource(Source.SONG); val arr = out.toFloatArray(); var k = 0
        while (k + 512 <= arr.size) { p.feed(arr.copyOfRange(k, k + 512), 512); k += 512 }
        val f = p.newFrame(); p.snapshot(f); check(f.notes.any { it.midi == 69 && abs(it.cents) < 5 }) { "song path notes ${f.notes}" }
        println("ok 7 song path: 44.1 kHz → 48 kHz across odd chunk sizes, continuous; detected A4 from the song stream") }
    println("PASS: Freqazoid engine — detection, drum filter, chord names, consonance, beats, tour, waves, renderer")
}
