package com.odify.nativeapp

import kotlin.math.*

/** Decorative seek-bar artwork, not sampled audio or an EQ measurement. */
object OdifyWaveformPatterns {
    const val count = 12
    fun samples(pattern: Int): List<Float> {
        val variant = Math.floorMod(pattern, count)
        return List(96) { i ->
            val x = i / 95.0
            val phase = variant * .73
            val pulse = abs(sin(i * (.63 + variant * .017) + phase))
            val envelope = .25 + .40 * abs(sin(x * PI * (1 + variant % 3) + phase))
            val bloom = exp(-((x - (.25 + (variant % 5) * .11)) / .18).pow(2.0)) * .24
            val air = .30 + .70 * abs(sin(i * 1.77 + phase * 2))
            (.08 + pulse * air * (envelope + bloom)).toFloat().coerceIn(.07f, .94f)
        }
    }
}
