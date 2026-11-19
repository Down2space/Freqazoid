package com.freqazoid.membrane

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.freqazoid.analysis.AnalysisSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

val BackgroundColor = Color(0xff05060b)
private const val DEPTH_BINS = 8

data class RenderStats(val fps: Float = 0f, val quality: RenderQuality = RenderQuality.WATCH)

/**
 * One frame of drawable geometry. Three of these rotate: the producer writes buffer i+1 on
 * Dispatchers.Default while Compose may still draw buffer i; buffer i−1 is untouched. Paths are
 * built from the projected points once; every stroke pass reuses them (no per-pass trig/projection).
 */
class GeometryBuffer {
    val curves = CurveSet()
    val paths = Array(curves.maxCurves) { Array(DEPTH_BINS) { Path() } }
    val colors = Array(curves.maxCurves) { Color.White }
    var glow = false

    fun build(quality: RenderQuality) {
        glow = quality.glow
        for (c in 0 until curves.maxCurves) for (b in 0 until DEPTH_BINS) paths[c][b].reset()
        for (c in 0 until curves.count) {
            val xy = curves.xy[c]; val shade = curves.shade[c]; val n = curves.points[c]
            var previousBin = -1
            for (i in 1 until n) {
                // Depth-binned segments: each segment lands in the bin of its mean depth.
                val bin = ((shade[i - 1] + shade[i]) * .5f * DEPTH_BINS).toInt().coerceIn(0, DEPTH_BINS - 1)
                val p = paths[c][bin]
                if (bin != previousBin) p.moveTo(xy[2 * i - 2], xy[2 * i - 1])
                p.lineTo(xy[2 * i], xy[2 * i + 1])
                previousBin = bin
            }
            colors[c] = Color.hsv(curves.hue[c].coerceIn(0f, 359f), curves.saturation[c], 1f)
        }
    }
}

@Composable
fun HarmonyCanvas(
    frames: StateFlow<AnalysisSnapshot?>,
    frozen: Boolean,
    settings: HarmonySettings,
    quality: RenderQuality,
    zoom: Float,
    onHud: (HarmonyHud) -> Unit,
    onStats: (RenderStats) -> Unit,
    modifier: Modifier = Modifier
) {
    val pool = remember { Array(3) { GeometryBuffer() } }
    var shown by remember { mutableStateOf<GeometryBuffer?>(null) }
    var description by remember { mutableStateOf("Sound visualizer, inactive") }
    val currentFrozen by rememberUpdatedState(frozen)
    val currentSettings by rememberUpdatedState(settings)
    val currentQuality by rememberUpdatedState(quality)
    val hudCallback by rememberUpdatedState(onHud)
    val statsCallback by rememberUpdatedState(onStats)

    LaunchedEffect(frames) {
        val engine = HarmonyEngine()
        var index = 0; var last = 0L; var lastHudAt = 0L
        var level = currentQuality; var slowFor = 0.0; var fastFor = 0.0
        var fpsFrames = 0; var fpsStart = 0L
        while (isActive) {
            if (engine.isInactive && frames.value == null) {
                // Explicitly inactive: draw nothing and stop the frame loop until analysis resumes.
                shown = null; description = "Sound visualizer, inactive"
                hudCallback(HarmonyHud()); statsCallback(RenderStats(0f, level))
                frames.first { it != null }
                last = 0L
            }
            val now = withFrameNanos { it }
            val target = currentQuality
            if (level != RenderQuality.REDUCED) level = target
            val interval = 1e9 / level.fps
            if (last != 0L && now - last < interval * .9) continue
            val dt = if (last == 0L) 1.0 / level.fps else (now - last) / 1e9
            last = now
            // Load adaptation: reduce decorative detail (never measurement data) under sustained load.
            if (dt > 1.5 / level.fps) { slowFor += dt; fastFor = 0.0 } else { fastFor += dt; slowFor = 0.0 }
            if (slowFor > 1.0 && level != RenderQuality.REDUCED) { level = RenderQuality.REDUCED; slowFor = 0.0 }
            if (fastFor > 3.0 && level == RenderQuality.REDUCED) { level = target; fastFor = 0.0 }
            val buffer = pool[index]; index = (index + 1) % pool.size
            val s = currentSettings; val q = level; val f = currentFrozen
            val hud = withContext(Dispatchers.Default) {
                val h = engine.step(frames.value, dt, s, q, System.nanoTime(), f)
                engine.fill(buffer.curves, q, s)
                buffer.build(q)
                h
            }
            shown = buffer
            fpsFrames++
            if (fpsStart == 0L) fpsStart = now
            if (now - lastHudAt > 125_000_000L) {   // readouts at ~8 Hz; the figure itself at full rate
                lastHudAt = now
                hudCallback(hud)
                description = if (hud.active) "Sound visualizer. Strongest ${Readouts.hz(hud.anchorHz)}. " + Readouts.ratioLine(hud) else "Sound visualizer, inactive"
            }
            if (now - fpsStart >= 1_000_000_000L) {
                statsCallback(RenderStats(fpsFrames * 1e9f / (now - fpsStart), level)); fpsFrames = 0; fpsStart = now
            }
        }
    }

    Canvas(modifier.semantics { contentDescription = description }) {
        val radius = size.minDimension / 2
        val circle = Path().apply { addOval(Rect(center - Offset(radius, radius), center + Offset(radius, radius))) }
        clipPath(circle) {
            drawRect(BackgroundColor)
            val g = shown
            if (g != null && g.curves.count > 0) {
                val s = radius * .80f * zoom
                val px = 1f / s   // one screen pixel in curve units
                val dp = density * px
                val dash = PathEffect.dashPathEffect(floatArrayOf(6 * dp, 5 * dp))
                withTransform({ translate(center.x, center.y); scale(s, s, pivot = Offset.Zero) }) {
                    // Far → near, across all curves: coherent depth order with occlusion halos.
                    for (b in 0 until DEPTH_BINS) {
                        val shade = (b + .5f) / DEPTH_BINS
                        for (c in 0 until g.curves.count) {
                            val path = g.paths[c][b]
                            if (path.isEmpty) continue
                            val strength = g.curves.strength[c].coerceIn(0f, 1f)
                            if (strength < .003f) continue
                            val color = g.colors[c]
                            val reference = g.curves.reference[c]
                            if (b >= 2) drawPath(path, BackgroundColor.copy(alpha = .65f * shade), style = Stroke(width = (2.6f + 3f * shade) * dp, cap = StrokeCap.Round))
                            if (g.glow && b >= DEPTH_BINS / 2 && !reference)
                                drawPath(path, color.copy(alpha = .10f * strength * shade), style = Stroke(width = (4f + 4f * shade) * dp, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
                            drawPath(path, color.copy(alpha = ((.22f + .7f * shade) * strength).coerceIn(0f, 1f)),
                                style = Stroke(width = (.7f + 1.7f * shade) * dp, cap = StrokeCap.Round, pathEffect = if (reference) dash else null), blendMode = BlendMode.Plus)
                            if (b >= DEPTH_BINS - 3 && !reference)
                                drawPath(path, Color.White.copy(alpha = .45f * strength * shade), style = Stroke(width = .45f * dp, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
                        }
                    }
                    // "Now" point of each measured pair (index 0 of its integrated-phase curve).
                    for (c in 0 until g.curves.count) {
                        if (g.curves.reference[c] || g.curves.points[c] == 0) continue
                        val strength = g.curves.strength[c].coerceIn(0f, 1f)
                        drawCircle(g.colors[c].copy(alpha = strength), radius = 2.2f * dp, center = Offset(g.curves.xy[c][0], g.curves.xy[c][1]), blendMode = BlendMode.Plus)
                    }
                }
            }
            drawCircle(Color(0xff2a3446), radius - 1f, center, style = Stroke(1f))
        }
    }
}
