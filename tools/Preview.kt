package com.freqazoid
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
/** Renders watch-face preview PNGs by running the real Processor (DEMO) and Renderer off-device. */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "previews" }).apply { mkdirs() }
    val size = args.getOrElse(1) { "450" }.toInt()
    fun shot(name: String, chord: List<Int>, overlay: Boolean, just: Boolean = false, det: Double = 0.0, seconds: Double = 1.2) {
        val p = Processor(48000); p.setSource(Source.DEMO)
        p.synth.just = just; p.synth.play(chord, hold = 10.0, detTop = det)
        val r = Renderer().apply { this.overlay = overlay }
        val frame = p.newFrame(); val buf = FloatArray(512); var t = 0.0; var nextDraw = 0.0
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        while (t < seconds) {
            p.synth.render(buf, buf.size); p.feed(buf, buf.size); t += 512 / 48000.0
            if (t >= nextDraw) { nextDraw += 1 / 30.0; p.snapshot(frame); val g = Java2dGfx(img); g.clipCircle(); r.draw(g, size.toFloat(), size.toFloat(), frame, t, 1 / 30.0) }
        }
        ImageIO.write(img, "png", File(out, "$name.png")); println("$name: ${r.chord.name} · ${r.harmony.word} · beat ${r.harmony.beat?.let { String.format("%.2f Hz", it.hz) } ?: "none"}")
    }
    shot("wheel_Cmaj", listOf(60, 64, 67), false)
    shot("wheel_G7", listOf(43, 55, 59, 62, 65), false)
    shot("wheel_fifth_beating", listOf(60, 67), false, just = true, det = 14.0)
    shot("overlay_Cmaj", listOf(60, 64, 67), true)
    shot("overlay_Cmaj9", listOf(48, 55, 64, 71, 74), true)
}
