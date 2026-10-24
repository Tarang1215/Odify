package com.odify.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

data class ListeningSignal(val count: Int, val lastPlayed: Long)
data class OdifyMix(val key: String, val name: String, val reason: String, val songs: List<OdifySong>)

/** Bounded device-only listening history. A seek or a brief preview is not a play. */
class OdifyListeningStore(context: Context) {
    val preferences = context.getSharedPreferences("odify_listening_v1", Context.MODE_PRIVATE)

    @Synchronized fun record(entry: String, songId: String, at: Long = System.currentTimeMillis()) {
        val existing = runCatching { JSONArray(preferences.getString("events", "[]")) }.getOrDefault(JSONArray())
        val cutoff = at - 60L * 24 * 60 * 60 * 1000
        val events = (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }
            .filter { it.optLong("at") >= cutoff && it.optString("entry") != entry }.takeLast(999)
        if ((0 until existing.length()).any { existing.optJSONObject(it)?.optString("entry") == entry }) return
        val next = JSONArray().apply {
            events.forEach(::put)
            put(JSONObject().put("entry", entry).put("song", songId).put("at", at))
        }
        preferences.edit().putString("events", next.toString()).apply()
    }

    fun signals(at: Long = System.currentTimeMillis()): Map<String, ListeningSignal> {
        val data = runCatching { JSONArray(preferences.getString("events", "[]")) }.getOrDefault(JSONArray())
        val events = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { it.optLong("at") >= at - 60L * 24 * 60 * 60 * 1000 }
        return events.groupBy { it.optString("song") }.filterKeys(String::isNotBlank)
            .mapValues { (_, rows) -> ListeningSignal(rows.size, rows.maxOf { it.optLong("at") }) }
    }
}

/** Explainable mixes drawn only from accessible Drive/local songs, never invented tracks. */
object OdifyRecommendations {
    fun mixes(songs: List<OdifySong>, liked: Set<String>, signals: Map<String, ListeningSignal>,
              now: Long = System.currentTimeMillis()): List<OdifyMix> {
        val catalog = OdifySongPresentation.visible(songs).sortedBy { it.id }
        val artists = HashMap<String, Double>()
        val genres = HashMap<String, Double>()
        catalog.forEach { song ->
            val signal = signals[song.id]
            val age = signal?.let { (now - it.lastPlayed).coerceAtLeast(0).toDouble() / 86_400_000 }
            val weight = (if (song.id in liked) 3.0 else 0.0) +
                if (signal != null) ln(1.0 + signal.count) * exp(-(age ?: 0.0) / 21.0) else 0.0
            if (weight > 0) {
                val artist = OdifyYouTubeMatcher.norm(song.artist)
                val genre = OdifyYouTubeMatcher.norm(song.genre)
                if (artist.isNotEmpty()) artists[artist] = artists.getOrDefault(artist, 0.0) + weight
                if (genre.isNotEmpty()) genres[genre] = genres.getOrDefault(genre, 0.0) + weight
            }
        }
        val repeat = catalog.filter { (signals[it.id]?.count ?: 0) > 0 }
            .sortedWith(compareByDescending<OdifySong> { signals[it.id]?.count ?: 0 }
                .thenByDescending { signals[it.id]?.lastPlayed ?: 0 }).take(30)
        val favorites = catalog.filter { it.id in liked }.take(40)
        val scored = catalog.map { song ->
            val score = artists.getOrDefault(OdifyYouTubeMatcher.norm(song.artist), 0.0) * 2 +
                genres.getOrDefault(OdifyYouTubeMatcher.norm(song.genre), 0.0) +
                (if (song.id in liked) 1.0 else 0.0)
            song to score / (1.0 + (signals[song.id]?.count ?: 0) * .15)
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<OdifySong, Double>> { it.second }.thenBy { it.first.id })
        val artistCounts = HashMap<String, Int>()
        val daily = scored.mapNotNull { (song, _) ->
            val artist = OdifyYouTubeMatcher.norm(song.artist).ifBlank { song.id }
            if (artistCounts.getOrDefault(artist, 0) >= 3) null else {
                artistCounts[artist] = artistCounts.getOrDefault(artist, 0) + 1
                song
            }
        }.take(30)
        return listOfNotNull(
            daily.takeIf { it.isNotEmpty() }?.let { OdifyMix("daily", "Your daily mix", "자주 듣는 아티스트와 좋아요를 바탕으로", it) },
            repeat.takeIf { it.isNotEmpty() }?.let { OdifyMix("repeat", "On repeat", "최근 60일, 충분히 들은 음악부터", it) },
            favorites.takeIf { it.isNotEmpty() }?.let { OdifyMix("liked", "Close to your heart", "좋아요한 음악을 한자리에", it) },
            catalog.takeIf { daily.isEmpty() && repeat.isEmpty() && favorites.isEmpty() && it.isNotEmpty() }
                ?.let { OdifyMix("start", "A little discovery", "내 음악에서 시작해요 · 들을수록 취향이 쌓여요", it.take(30)) }
        )
    }
}
