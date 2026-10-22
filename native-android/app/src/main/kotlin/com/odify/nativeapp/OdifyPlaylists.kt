package com.odify.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-owned playlists of Odify song IDs. Source files are never moved or deleted. */
data class OdifyPlaylist(val id: String, val name: String, val songIds: List<String>)

/** Single-user local playlist catalog; never confuses Drive/phone-folder origins. */
class OdifyPlaylists(context: Context) {
    private val prefs = context.getSharedPreferences("odify_playlists_v1", Context.MODE_PRIVATE)
    private val key = "playlists"

    @Synchronized fun all(): List<OdifyPlaylist> = runCatching {
        val data = JSONArray(prefs.getString(key, "[]").orEmpty())
        (0 until data.length()).mapNotNull { i ->
            val obj = data.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val entries = obj.optJSONArray("songs") ?: JSONArray()
            OdifyPlaylist(
                id = id,
                name = obj.optString("name").ifBlank { "이름 없는 플레이리스트" },
                songIds = (0 until entries.length()).mapNotNull { j ->
                    entries.optString(j).takeIf(String::isNotBlank)
                }
            )
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<OdifyPlaylist>) {
        val json = JSONArray()
        items.forEach { list ->
            json.put(JSONObject().put("id", list.id).put("name", list.name)
                .put("songs", JSONArray().apply { list.songIds.forEach(::put) }))
        }
        check(prefs.edit().putString(key, json.toString()).commit()) {
            "플레이리스트를 저장하지 못했습니다."
        }
    }

    @Synchronized fun create(name: String): OdifyPlaylist {
        val clean = name.trim().take(80)
        require(clean.isNotEmpty()) { "플레이리스트 이름을 입력해 주세요." }
        require(all().none { it.name.equals(clean, ignoreCase = true) }) {
            "같은 이름의 플레이리스트가 있습니다."
        }
        val added = OdifyPlaylist(UUID.randomUUID().toString(), clean, emptyList())
        save(all() + added)
        return added
    }

    @Synchronized fun addSong(playlistId: String, songId: String): Boolean {
        require(songId.isNotBlank())
        val items = all()
        val target = items.firstOrNull { it.id == playlistId }
            ?: error("플레이리스트를 찾을 수 없습니다.")
        if (songId in target.songIds) return false
        save(items.map {
            if (it.id == playlistId) it.copy(songIds = it.songIds + songId) else it
        })
        return true
    }

    @Synchronized fun removeSong(playlistId: String, songId: String) {
        save(all().map {
            if (it.id == playlistId) it.copy(songIds = it.songIds.filterNot { id -> id == songId })
            else it
        })
    }

    @Synchronized fun delete(playlistId: String) {
        save(all().filterNot { it.id == playlistId })
    }

    fun resolved(playlist: OdifyPlaylist, songs: List<OdifySong>): List<OdifySong> {
        val index = songs.associateBy(OdifySong::id)
        return playlist.songIds.mapNotNull(index::get)
    }
}
