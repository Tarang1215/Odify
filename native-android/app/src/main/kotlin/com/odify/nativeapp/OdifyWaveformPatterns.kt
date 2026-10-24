package com.odify.nativeapp

import kotlin.math.*

/** Decorative seek-bar artwork, not sampled audio or an EQ measurement. */
object OdifyWaveformPatterns {
    const val count = 12
    private data class Swell(val center: Double, val width: Double, val height: Double)
    // Each preset is one continuous envelope. Neighboring bars follow that
    // contour; they are never assigned independent random heights.
    private val profiles = listOf(
        listOf(Swell(.31,.026,.88), Swell(.46,.027,.86), Swell(.61,.043,1.0)),
        listOf(Swell(.25,.035,.74), Swell(.45,.030,1.0), Swell(.65,.047,.85)),
        listOf(Swell(.29,.033,1.0), Swell(.49,.024,.79), Swell(.69,.041,.88)),
        listOf(Swell(.22,.029,.67), Swell(.39,.037,1.0), Swell(.60,.038,.93)),
        listOf(Swell(.33,.032,.91), Swell(.51,.029,1.0), Swell(.70,.046,.69)),
        listOf(Swell(.26,.025,.85), Swell(.43,.032,.76), Swell(.62,.044,1.0)),
        listOf(Swell(.20,.035,.61), Swell(.36,.028,.88), Swell(.55,.036,1.0), Swell(.72,.030,.54)),
        listOf(Swell(.28,.039,.83), Swell(.48,.026,1.0), Swell(.67,.039,.78)),
        listOf(Swell(.24,.029,.72), Swell(.42,.037,1.0), Swell(.64,.035,.91)),
        listOf(Swell(.32,.028,1.0), Swell(.50,.034,.82), Swell(.70,.033,.74)),
        listOf(Swell(.23,.032,.75), Swell(.41,.025,.89), Swell(.59,.042,1.0), Swell(.75,.027,.44)),
        listOf(Swell(.27,.038,.89), Swell(.47,.031,1.0), Swell(.66,.044,.84))
    )
    fun samples(pattern: Int): List<Float> {
        val variant = Math.floorMod(pattern, count)
        val envelope = List(96) { i ->
            val x = i / 95.0
            fun phrase(center: Double, width: Double) = exp(-((x-center)/width).pow(2.0))
            val contour = .028 + .18*phrase(.21,.10) + .16*phrase(.74,.15) +
                profiles[variant].sumOf { it.height*phrase(it.center,it.width) }
            // Restrained texture sits inside the shared silhouette, rather
            // than breaking it into isolated spikes or an EQ-style forest.
            contour*(.91 + .09*cos(i*2.55+variant*.45))
        }
        val peak = envelope.max()
        return envelope.map { (it/peak*.98).toFloat().coerceIn(.025f,.98f) }
    }
}
