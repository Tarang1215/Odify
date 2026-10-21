package com.zarz.spotiflac

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.util.UUID

/**
 * Odify-owned extractor seam. The UI and playback transport are unaware
 * of the extraction engine. New implementations can be added without taking
 * GPL-licensed application code from third-party projects.
 */
internal interface OdifyAudioExtractor {
    val engine: String
    suspend fun extractJson(watchUrl: String, client: String): JSONObject
}

/**
 * Current backend: the pinned, bundled yt-dlp runtime. Client identity and
 * request header policy live separately in OdifyPlaybackProtocol. No signed
 * media URLs or extractor output are written to persistent logs.
 */
internal class OdifyYtDlpExtractor : OdifyAudioExtractor {
    override val engine = "yt-dlp"

    override suspend fun extractJson(watchUrl: String, client: String): JSONObject {
        val request = YoutubeDLRequest(watchUrl)
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        request.addOption("--socket-timeout", "5")
        request.addOption("--retries", "0")
        request.addOption("--extractor-retries", "0")
        request.addOption("--skip-download")
        request.addOption("--dump-single-json")
        request.addOption("--extractor-args", "youtube:player_client=$client")
        request.addOption("-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio")
        val processId = "odify-stream-${UUID.randomUUID()}"
        try {
            val result = runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId, null)
            }
            return JSONObject(result.out.trim())
        } finally {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }
}
