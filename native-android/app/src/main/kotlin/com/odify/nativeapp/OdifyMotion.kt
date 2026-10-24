package com.odify.nativeapp

/** A portrait layer keeps its aspect ratio while blending back to its origin. */
object OdifyMotion {
    fun playerOpacity(progress: Float): Float {
        val x = (progress / .55f).coerceIn(0f, 1f)
        return x*x*(3f-2f*x)
    }
    fun adjacentTab(tab: OdifyTab, horizontalDistance: Float): OdifyTab? {
        val index = odifyPrimaryTabs.indexOf(tab)
        if (index < 0 || horizontalDistance == 0f) return null
        return odifyPrimaryTabs.getOrNull(index + if (horizontalDistance < 0) 1 else -1)
    }
}
