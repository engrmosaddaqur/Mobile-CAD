package com.example.cad.model

import kotlin.math.abs

object AciColors {
    private val basicTable = mapOf(
        1 to 0xFFFF3B30.toInt(), // Red
        2 to 0xFFFFD60A.toInt(), // Yellow
        3 to 0xFF34C759.toInt(), // Green
        4 to 0xFF00E5FF.toInt(), // Cyan
        5 to 0xFF2979FF.toInt(), // Blue
        6 to 0xFFFF40FF.toInt(), // Magenta
        7 to 0xFFFFFFFF.toInt(), // White / Contrast
        8 to 0xFF8E8E93.toInt(), // Dark Gray
        9 to 0xFFC7C7CC.toInt(), // Light Gray
        10 to 0xFFFF5252.toInt(),
        30 to 0xFFFF9500.toInt(), // Orange
        40 to 0xFFFFB300.toInt(), // Amber
        50 to 0xFFE5FF00.toInt(),
        70 to 0xFF00E676.toInt(), // Emerald
        130 to 0xFF00E5FF.toInt(),
        140 to 0xFF40C4FF.toInt(),
        150 to 0xFF448AFF.toInt(),
        210 to 0xFFE040FB.toInt(),
        250 to 0xFF3A3A3C.toInt(),
        251 to 0xFF545458.toInt(),
        252 to 0xFF7C7C80.toInt(),
        253 to 0xFFA2A2A7.toInt(),
        254 to 0xFFC8C8CD.toInt(),
        255 to 0xFFFFFFFF.toInt()
    )

    val presetSwatches: List<Pair<Int, Int>> = listOf(
        7 to 0xFFFFFFFF.toInt(),
        1 to 0xFFFF3B30.toInt(),
        2 to 0xFFFFD60A.toInt(),
        3 to 0xFF34C759.toInt(),
        4 to 0xFF00E5FF.toInt(),
        5 to 0xFF2979FF.toInt(),
        6 to 0xFFFF40FF.toInt(),
        30 to 0xFFFF9500.toInt(),
        40 to 0xFFFFB300.toInt(),
        70 to 0xFF00E676.toInt(),
        8 to 0xFF8E8E93.toInt()
    )

    fun toArgb(aci: Int): Int {
        val code = abs(aci)
        basicTable[code]?.let { return it }
        if (code in 10..249) {
            val hue = ((code - 10) / 240.0) * 360.0
            val sat = if (code % 2 == 0) 0.9f else 0.55f
            val valBright = when {
                code % 10 <= 2 -> 1.0f
                code % 10 <= 5 -> 0.8f
                else -> 0.6f
            }
            return hsvToArgb(hue.toFloat(), sat, valBright)
        }
        return 0xFFFFFFFF.toInt()
    }

    fun nearestAci(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        var bestCode = 7
        var bestDist = Int.MAX_VALUE
        for ((code, color) in basicTable) {
            val cr = (color ushr 16) and 0xFF
            val cg = (color ushr 8) and 0xFF
            val cb = color and 0xFF
            val d = (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb)
            if (d < bestDist) {
                bestDist = d
                bestCode = code
            }
        }
        return bestCode
    }

    private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rf, gf, bf) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rf + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((gf + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((bf + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
