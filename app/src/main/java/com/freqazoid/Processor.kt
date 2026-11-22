package com.freqazoid

/** What the watch face can be listening to. */
enum class Source(val label: String) { OFF("Sound off"), DEMO("Demo chords"), MIC("Microphone") }

/**
 * Audio-thread side: for DEMO the synth renders the tour (notes come straight from its voices, as in the
 * web version); for MIC the detector finds notes. Every block also feeds the per-note wave filters.
 * The UI reads a consistent [Frame] with [snapshot].
 */
class Processor(val sampleRate: Int) {
    val synth = Synth(sampleRate)
    val tour = Tour(synth)
    val detector = NoteDetector(sampleRate, 30.0)
    val slots = WaveSlots(sampleRate)
    @Volatile var source = Source.OFF; private set
    private val ring = FloatArray(detector.n); private var ringHead = 0
    private val window = FloatArray(detector.n)
    private val hop = sampleRate / 30
    private var sinceHop = 0
    private var notes: List<Note> = emptyList()

    @Synchronized fun setSource(s: Source) {
        source = s
        synth.stopAll(); detector.reset(); slots.clear(); notes = emptyList()
        if (s == Source.DEMO) tour.start()
    }

    /** DEMO: fill [out] with synth audio (to play and to analyze). */
    fun renderDemo(out: FloatArray, count: Int) { tour.tick(); synth.render(out, count) }

    /** Feed captured (MIC) or rendered (DEMO) audio. */
    @Volatile private var level = 0f
    fun feed(x: FloatArray, count: Int) {
        var e = 0.0; for (i in 0 until count) e += x[i] * x[i]
        val db = 10 * kotlin.math.log10(e / count.coerceAtLeast(1) + 1e-12)
        level = maxOf(((db + 70) / 60).toFloat().coerceIn(0f, 1f), level * .9f)   // mic level for the "listening" ring
        slots.process(x, count)
        if (source == Source.MIC) {
            for (i in 0 until count) { ring[ringHead] = x[i]; ringHead = (ringHead + 1) % ring.size }
            sinceHop += count
            if (sinceHop >= hop) {
                sinceHop = 0
                for (i in ring.indices) window[i] = ring[(ringHead + i) % ring.size]
                detector.analyze(window)
                publish(detector.notes())
            }
        } else if (source == Source.DEMO) publish(synth.notes())
    }

    @Synchronized private fun publish(n: List<Note>) { notes = n; slots.assign(n) }

    class Frame(len: Int) {
        var notes: List<Note> = emptyList()
        var audioTime = 0.0
        val waves = HashMap<Int, FloatArray>()
        val raw = FloatArray(len)
        var source = Source.OFF
        var level = 0f
    }
    fun newFrame() = Frame(slots.length)

    @Synchronized fun snapshot(f: Frame) {
        f.notes = if (source == Source.OFF) emptyList() else notes
        f.source = source
        f.level = if (source == Source.OFF) 0f else level
        f.audioTime = slots.samples.toDouble() / sampleRate
        val keep = f.notes.map { it.midi }.toSet(); f.waves.keys.retainAll(keep)
        for (n in f.notes) { val b = f.waves.getOrPut(n.midi) { FloatArray(slots.length) }; if (!slots.copy(n.midi, b)) b.fill(0f) }
        slots.copyRaw(f.raw)
    }
}
