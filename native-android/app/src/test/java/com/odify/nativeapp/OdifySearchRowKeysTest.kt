package com.odify.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class OdifySearchRowKeysTest {
    @Test fun savedAndRemoteResultsForTheReportedVideosCanShareOneList() {
        val reportedVideoIds = listOf("lNNPWLC8sTo", "L483dymSxB0")
        val beforePlayback = reportedVideoIds.map(OdifySearchRowKeys::youtube)
        val afterPlayback = reportedVideoIds.map { OdifySearchRowKeys.library("youtube:$it") } +
            reportedVideoIds.map(OdifySearchRowKeys::youtube)
        assertEquals(afterPlayback.size, afterPlayback.toSet().size)
        assertEquals(beforePlayback, afterPlayback.takeLast(reportedVideoIds.size))
    }
}
