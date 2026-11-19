package com.freqazoid.membrane

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometry for placing rectangular controls inside a circular viewport. All four corners of a
 * control must be inside the circle (minus [margin]); a rectangle's corners are never assumed
 * visible on a round screen. Units are arbitrary but consistent (the UI uses dp).
 */
object RoundSafeArea {
    /** Widest rectangle of [height] whose centre is [centerY] from the circle centre. 0 if none fits. */
    fun maxWidth(radius: Float, centerY: Float, height: Float, margin: Float): Float {
        val rr = radius - margin; val far = abs(centerY) + height / 2
        if (far >= rr) return 0f
        return 2 * sqrt(rr * rr - far * far)
    }
    /** Largest |centerY| for a [width]×[height] rectangle; negative if it can't fit at all. */
    fun maxOffset(radius: Float, width: Float, height: Float, margin: Float): Float {
        val rr = radius - margin; val v = rr * rr - (width / 2) * (width / 2)
        if (v <= 0) return -1f
        return sqrt(v) - height / 2
    }
    fun fits(radius: Float, cx: Float, cy: Float, width: Float, height: Float, margin: Float): Boolean {
        val rr = radius - margin
        for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
            val x = cx + sx * width / 2; val y = cy + sy * height / 2
            if (x * x + y * y > rr * rr + 1e-3f) return false
        }
        return true
    }

    /** Layout of the overlay controls for a viewport of [diameter] (dp). */
    data class Plan(
        val radius: Float,
        val topCenterY: Float, val topWidth: Float,
        val bottomCenterY: Float, val bottomWidth: Float,
        val controlHeight: Float,
        /** Free vertical band (relative to centre) between the controls for plots and readouts. */
        val bandTop: Float, val bandBottom: Float,
        val textWidth: Float
    )

    /**
     * One mode/menu chip at the top and one Start/Stop button at the bottom, both [controlHeight]
     * tall (≥ 48 dp). Widths shrink with the circle but never below 48.
     */
    fun plan(diameter: Float, controlHeight: Float = 48f, margin: Float = 4f): Plan {
        val r = diameter / 2
        val topWidth = (r * .98f).coerceIn(48f, 132f)
        val bottomWidth = (r * .72f).coerceIn(48f, 112f)
        val top = -max(0f, min(RoundSafeArea.maxOffset(r, topWidth, controlHeight, margin), r * .70f))
        val bottom = max(0f, min(RoundSafeArea.maxOffset(r, bottomWidth, controlHeight, margin), r * .72f))
        val bandTop = top + controlHeight / 2 + 2f
        val bandBottom = bottom - controlHeight / 2 - 2f
        val textWidth = min(maxWidth(r, bandBottom - 10f, 20f, margin + 6f), maxWidth(r, bandTop + 10f, 20f, margin + 6f))
        return Plan(r, top, topWidth, bottom, bottomWidth, controlHeight, bandTop, bandBottom, textWidth)
    }
}
