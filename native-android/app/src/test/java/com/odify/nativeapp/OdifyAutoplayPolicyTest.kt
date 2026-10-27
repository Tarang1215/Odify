package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class OdifyAutoplayPolicyTest {
    private fun song(id:String,title:String=id,artist:String="Artist",genre:String="Pop")=
        OdifySong(id,title,artist,"Album","",durationMs=180000,genre=genre)
    @Test fun singleChoiceContinuesButExplicitQueueAndRepeatKeepTheirOrder() {
        assertTrue(OdifyAutoplayPolicy.shouldExtend(true,false,false))
        assertFalse(OdifyAutoplayPolicy.shouldExtend(false,false,false))
        assertFalse(OdifyAutoplayPolicy.shouldExtend(true,true,false))
        assertFalse(OdifyAutoplayPolicy.shouldExtend(true,false,true))
        val current=song("now");val next=song("next")
        assertEquals(listOf(next),OdifyAutoplayPolicy.following(current,listOf(current,next),listOf(current),emptySet()))
    }
    @Test fun relatedRecordingsArePreparedWithoutRepeatingHistoryOrDuplicateFiles() {
        val now=song("now");val related=song("related","Song")
        val old=song("old");val duplicate=related.copy(id="duplicate",title="Song (2024 Remastered)")
        val other=song("other",artist="Another artist",genre="Jazz")
        val result=OdifyAutoplayPolicy.following(now,listOf(now,related,old,duplicate,other),listOf(now),setOf("old"))
        assertEquals(listOf(related,other),result)
        assertEquals(5,listOf(now,related,old,duplicate,other).size)
    }
    @Test fun bufferingKeepsForegroundProtectionButPauseAndErrorReleaseIt() {
        assertTrue(OdifyPlaybackContinuity.foreground(YoutubeWebState(ready=true,buffering=true)))
        assertTrue(OdifyPlaybackContinuity.foreground(YoutubeWebState()))
        assertTrue(OdifyPlaybackContinuity.foreground(YoutubeWebState(playing=true,ready=true)))
        assertFalse(OdifyPlaybackContinuity.foreground(YoutubeWebState(ready=true)))
        assertFalse(OdifyPlaybackContinuity.foreground(YoutubeWebState(error="unavailable")))
    }
    @Test fun rendererRecoveryIsBoundedAndAllowsAFreshWindowLater() {
        val budget=OdifyRecoveryBudget()
        assertTrue(budget.allow(1000));assertTrue(budget.allow(2000));assertFalse(budget.allow(3000))
        assertTrue(budget.allow(602000))
    }
    @Test fun stallRecoveryNeverResumesAUserPauseOrAnOrdinaryShortBuffer() {
        val buffering=YoutubeWebState(ready=true,buffering=true)
        assertFalse(OdifyPlaybackContinuity.stalled(buffering,false,44_999))
        assertTrue(OdifyPlaybackContinuity.stalled(buffering,false,45_000))
        assertFalse(OdifyPlaybackContinuity.stalled(buffering,true,90_000))
        assertFalse(OdifyPlaybackContinuity.stalled(buffering.copy(error="unavailable"),false,90_000))
        assertFalse(OdifyPlaybackContinuity.stalled(YoutubeWebState(ready=true),false,90_000))
    }
}
