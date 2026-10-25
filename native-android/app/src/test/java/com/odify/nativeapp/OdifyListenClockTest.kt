package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class OdifyListenClockTest {
    @Test fun seekingPausesAndTrackChangesCannotInventTwentySeconds() {
        val clock=OdifyListenClock()
        assertEquals(0L,clock.observe("a",0,true))
        for(i in 1..19) assertEquals(i*1000L,clock.observe("a",i*1000L,true))
        assertEquals(19000L,clock.observe("a",20000,false))
        assertEquals(19000L,clock.observe("a",200000,true))
        assertEquals(20000L,clock.observe("a",201000,true))
        assertEquals(0L,clock.observe("b",202000,true))
        assertEquals(1500L,clock.observe("b",500000,true))
    }
}
