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
            // Separate phrases, a clear climax and tapered edges create the
            // reference's speech-like silhouette instead of a uniform EQ band.
            fun phrase(center: Double, width: Double) = exp(-((x-center)/width).pow(2.0))
            val phraseA = phrase(.16 + (variant % 3)*.025, .10)
            val phraseB = phrase(.39 + (variant % 4)*.025, .075)
            val phraseC = phrase(.62 + (variant % 3)*.025, .13)
            val envelope = .02 + .22*phraseA + 1.14*phraseB + .65*phraseC
            val detail = .08 + .92*abs(sin(i*1.91+phase)).pow(1.6)
            val spikes = if ((i+variant*3)%7==0) 1.0 else detail
            (.07 + envelope*spikes).toFloat().coerceIn(.07f, .94f)
        }
    }
}
