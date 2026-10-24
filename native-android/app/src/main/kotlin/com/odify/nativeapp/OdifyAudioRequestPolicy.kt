package com.odify.nativeapp

import java.net.URI
import java.net.URLDecoder

internal object OdifyAudioRequestPolicy {
    fun youtubeUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("YouTube 영상 링크를 입력해 주세요.") }
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1) { "https YouTube 영상 링크를 입력해 주세요." }
        val id = when (uri.host?.lowercase()) {
            "youtu.be" -> uri.path.removePrefix("/")
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> when {
                uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&').map { it.split('=', limit=2) }
                    .filter { it.size == 2 && it[0] == "v" }.singleOrNull()?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                uri.path.startsWith("/shorts/") || uri.path.startsWith("/embed/") -> uri.path.substringAfterLast('/')
                else -> null
            }
            else -> null
        }
        require(id != null && Regex("[A-Za-z0-9_-]{11}").matches(id)) { "재생목록이 아닌 YouTube 영상 링크를 입력해 주세요." }
        return "https://www.youtube.com/watch?v=$id"
    }
}
