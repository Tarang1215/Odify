package com.odify.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OdifyAudioRequestPolicyTest {
    @Test fun canonicalizesOnlyOnePublicYoutubeVideo() {
        for (value in listOf("https://youtu.be/abcdefghijk?t=5", "https://www.youtube.com/watch?v=abcdefghijk&list=PLabc",
            "https://music.youtube.com/watch?v=abcdefghijk", "https://youtube.com/shorts/abcdefghijk"))
            assertEquals("https://www.youtube.com/watch?v=abcdefghijk", OdifyAudioRequestPolicy.youtubeUrl(value))
    }
    @Test fun rejectsOtherHostsAndAmbiguousIds() {
        for (value in listOf("https://youtube.com.evil.test/watch?v=abcdefghijk", "https://user@youtube.com/watch?v=abcdefghijk",
            "file:///etc/passwd", "https://youtube.com/playlist?list=PLabc", "https://youtu.be/abcdefghijk/extra",
            "https://youtube.com/watch?v=abcdefghijk&v=lmnopqrstuv", "http://youtu.be/abcdefghijk")) {
            try { OdifyAudioRequestPolicy.youtubeUrl(value); fail(value) } catch (_: IllegalArgumentException) { }
        }
    }
}
