package com.odify.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class ListeningSignal(val count: Int, val lastPlayed: Long)
data class OdifyMix(val key: String, val name: String, val reason: String, val songs: List<OdifySong>)

/** Bounded device-only listening history. A seek or a brief preview is not a play. */
class OdifyListeningStore(context: Context) {
    val preferences = context.getSharedPreferences("odify_listening_v1", Context.MODE_PRIVATE)
    private companion object { val writeLock=Any() }

    fun record(entry: String, songId: String, at: Long = System.currentTimeMillis()) = synchronized(writeLock) {
        if(at<=preferences.getLong("forgotten:$songId",Long.MIN_VALUE))return@synchronized
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

    fun forget(songIds:Set<String>,at:Long=System.currentTimeMillis()) = synchronized(writeLock) {
        val existing=runCatching {JSONArray(preferences.getString("events","[]"))}.getOrDefault(JSONArray())
        val events=JSONArray()
        for(index in 0 until existing.length()) {
            val event=existing.optJSONObject(index) ?: continue
            if(event.optString("song") !in songIds)events.put(event)
        }
        val edit=preferences.edit().putString("events",events.toString())
        songIds.forEach {edit.putLong("forgotten:$it",at)}
        edit.apply()
    }

    fun signals(at: Long = System.currentTimeMillis()): Map<String, ListeningSignal> {
        val data = runCatching { JSONArray(preferences.getString("events", "[]")) }.getOrDefault(JSONArray())
        val events = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { it.optLong("at") >= at - 60L * 24 * 60 * 60 * 1000 }
        return events.groupBy { it.optString("song") }.filterKeys(String::isNotBlank)
            .mapValues { (_, rows) -> ListeningSignal(rows.size, rows.maxOf { it.optLong("at") }) }
    }
}

data class OdifyRecommendation(val track:ExploreTrack,val owned:OdifySong?)
internal data class OdifyTasteSeed(val song:OdifySong,val weight:Double)
internal data class OdifyRelatedMusic(
    val bySeed:Map<String,List<ExploreTrack>> = emptyMap(),
    val tags:Map<String,List<String>> = emptyMap()
)

/** Explainable mixes drawn only from accessible Drive/local songs, never invented tracks. */
object OdifyRecommendations {
    private const val DAY=86_400_000.0
    private val moodTerms=mapOf(
        "calm" to setOf("chill","mellow","calm","relaxing","soft","잔잔한","편안한"),
        "wistful" to setOf("sad","melancholic","nostalgic","emotional","감성","슬픈","애절한"),
        "energetic" to setOf("upbeat","energetic","danceable","powerful","신나는","경쾌한"),
        "dreamy" to setOf("dreamy","atmospheric","ethereal","몽환적","몽환")
    ).mapValues {(_,terms)->terms.map(OdifyMusicMatching::norm).toSet()}

    internal fun key(title:String,artist:String)=
        OdifyMusicMatching.artistKey(artist)+"|"+OdifyMusicMatching.titleKey(title,artist)

    private fun age(at:Long,now:Long)=(now-at).coerceAtLeast(0)/DAY

    internal fun seeds(songs:List<OdifySong>,liked:Set<String>,signals:Map<String,ListeningSignal>,now:Long):List<OdifyTasteSeed> {
        val perArtist=HashMap<String,Int>()
        val albums=HashSet<Pair<String,String>>()
        return songs.asSequence().filter {it.title.isNotBlank() && it.artist.isNotBlank()}
            .map { song ->
                val libraryWeight=when {
                    song.id.startsWith("youtube:") -> 0.0
                    song.isOfflineSaved -> 0.8
                    song.isCloud -> 0.25
                    else -> 0.45
                }
                val listening=signals[song.id]?.let {2.5*ln(1.0+it.count)*0.5.pow(age(it.lastPlayed,now)/14)} ?: 0.0
                OdifyTasteSeed(song,libraryWeight+listening+if(song.id in liked)4.0 else 0.0)
            }.filter {it.weight>0}.sortedByDescending {it.weight}
            .distinctBy {key(it.song.title,it.song.artist)}
            .filter {
                val artist=OdifyMusicMatching.artistKey(it.song.artist)
                val album=OdifyMusicMatching.norm(it.song.album)
                val count=perArtist[artist] ?: 0
                if(count>=2 || album.isNotBlank() && album!="youtube" && !albums.add(artist to album))false
                else {perArtist[artist]=count+1;true}
            }.take(12).toList()
    }

    internal fun recommend(
        songs:List<OdifySong>, seeds:List<OdifyTasteSeed>, signals:Map<String,ListeningSignal>,
        online:List<ExploreTrack>, related:OdifyRelatedMusic, now:Long, excluded:Set<String> = emptySet()
    ):List<OdifyRecommendation> {
        if(seeds.isEmpty())return emptyList()
        val catalog=OdifySongPresentation.visible(songs)
        val index=OdifyChartMatching.buildIndex(songs)
        val consideredSignals=songs.asSequence().filter {it.artist.isNotBlank() && signals[it.id]!=null}
            .groupBy {key(it.title,it.artist)}.mapValues {(_,copies)->copies.maxOf {signals.getValue(it.id).lastPlayed}}
        val relationships=HashMap<String,Double>()
        val maxWeight=seeds.first().weight
        fun tags(track:ExploreTrack)=track.genres+related.tags[key(track.title,track.artist)].orEmpty()
        fun mood(features:Set<String>)=moodTerms.filterValues {terms->terms.any {it in features}}.keys
        fun features(tags:List<String>)=tags.flatMap {it.split('/', ';', ',')}.map(OdifyMusicMatching::norm)
            .filter {it.length>2 && it !in setOf("music","favorites","favourite","seenlive")}.toSet()
        val profiles=seeds.map {seed->
            val id=key(seed.song.title,seed.song.artist)
            val descriptors=features(listOf(seed.song.genre)+related.tags[id].orEmpty())
            related.bySeed[id].orEmpty().forEachIndexed {rank,track->
                val relatedKey=key(track.title,track.artist)
                relationships.merge(relatedKey,seed.weight/maxWeight/(1+rank*.25),Double::plus)
            }
            Triple(seed,descriptors,mood(descriptors))
        }
        val candidates=linkedMapOf<String,OdifyRecommendation>()
        for(song in catalog) {
            val track=ExploreTrack(song.id,song.title,song.artist,"",listOf(song.genre),0)
            val id=key(song.title,song.artist)
            if(song.title.isNotBlank() && song.artist.isNotBlank())candidates.putIfAbsent(id,OdifyRecommendation(track,song))
        }
        for(track in online+seeds.flatMap {related.bySeed[key(it.song.title,it.song.artist)].orEmpty()}) {
            if(track.title.isBlank() || track.artist.isBlank())continue
            val id=key(track.title,track.artist)
            val owned=index.resolve(track)
            if(owned!=null && key(owned.title,owned.artist) in candidates)continue
            candidates.putIfAbsent(id,OdifyRecommendation(track,owned))
        }
        val ranked=candidates.entries.mapNotNull { (id,candidate)->
            if(id in excluded)return@mapNotNull null
            val track=candidate.track
            val descriptors=features(tags(track))
            val moods=mood(descriptors)
            val artist=OdifyMusicMatching.artistKey(track.artist)
            val affinity=profiles.maxOf { (seed,preferred,preferredMoods)->
                val sameArtist=if(artist==OdifyMusicMatching.artistKey(seed.song.artist))1.15 else 0.0
                val sameMood=if(moods.any {it in preferredMoods})1.0 else 0.0
                val sameDescriptors=(descriptors intersect preferred).size.coerceAtMost(3)*.65
                (sameArtist+sameMood+sameDescriptors)*seed.weight/maxWeight
            }
            val relatedScore=(relationships[id] ?: 0.0).coerceAtMost(2.0)*2.0
            if(affinity+relatedScore<=0)return@mapNotNull null
            val recency=consideredSignals[id]?.let {1.4*0.5.pow(age(it,now)/2)} ?: 0.0
            candidate to (affinity+relatedScore-recency+if(candidate.owned==null)0.35 else 0.0)
        }.sortedByDescending {it.second}
        val counts=HashMap<String,Int>()
        return ranked.mapNotNull { (candidate,_)->
            val artist=OdifyMusicMatching.artistKey(candidate.track.artist)
            val count=counts[artist] ?: 0
            if(count>=2)null else {counts[artist]=count+1;candidate}
        }.take(20)
    }

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
                val artist = OdifyMusicMatching.artistKey(song.artist)
                val genre = OdifyYouTubeMatcher.norm(song.genre)
                if (artist.isNotEmpty()) artists[artist] = artists.getOrDefault(artist, 0.0) + weight
                if (genre.isNotEmpty()) genres[genre] = genres.getOrDefault(genre, 0.0) + weight
            }
        }
        val repeat = catalog.filter { (signals[it.id]?.count ?: 0) > 0 }
            .sortedWith(compareByDescending<OdifySong> { signals[it.id]?.count ?: 0 }
                .thenByDescending { signals[it.id]?.lastPlayed ?: 0 }).take(30)
        val favorites = catalog.filter { it.id in liked }.take(40)
        val day=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay().toInt()
        val shortTracks=catalog.filter {it.durationMs==0L || it.durationMs in 45_000L..900_000L}
        val unheard=shortTracks.filter {(signals[it.id]?.count ?: 0)==0 && it.id !in liked}
            .shuffled(kotlin.random.Random(day)).take(30)
        val rediscover=shortTracks.filter {signals[it.id]?.lastPlayed?.let {last -> now-last>14L*86_400_000}==true}
            .sortedByDescending {signals[it.id]?.count ?: 0}.take(30)
        val offline=shortTracks.filter {it.isOfflineSaved}.shuffled(kotlin.random.Random(day+17)).take(30)
        val scored = catalog.map { song ->
            val score = artists.getOrDefault(OdifyMusicMatching.artistKey(song.artist), 0.0) * 2 +
                genres.getOrDefault(OdifyYouTubeMatcher.norm(song.genre), 0.0) +
                (if (song.id in liked) 1.0 else 0.0)
            song to score / (1.0 + (signals[song.id]?.count ?: 0) * .15)
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<OdifySong, Double>> { it.second }.thenBy { it.first.id })
        val artistCounts = HashMap<String, Int>()
        val daily = scored.mapNotNull { (song, _) ->
            val artist = OdifyMusicMatching.artistKey(song.artist).ifBlank { song.id }
            if (artistCounts.getOrDefault(artist, 0) >= 3) null else {
                artistCounts[artist] = artistCounts.getOrDefault(artist, 0) + 1
                song
            }
        }.take(30)
        return listOfNotNull(
            daily.takeIf { it.isNotEmpty() }?.let { OdifyMix("daily", "Your daily mix", "자주 듣는 아티스트와 좋아요를 바탕으로", it) },
            repeat.takeIf { it.isNotEmpty() }?.let { OdifyMix("repeat", "On repeat", "최근 60일, 충분히 들은 음악부터", it) },
            favorites.takeIf { it.isNotEmpty() }?.let { OdifyMix("liked", "Close to your heart", "좋아요한 음악을 한자리에", it) },
            unheard.takeIf {it.isNotEmpty()}?.let {OdifyMix("unheard","A little discovery","아직 듣지 않은 내 음악 · 매일 다른 선택",it)},
            rediscover.takeIf {it.isNotEmpty()}?.let {OdifyMix("rediscover","Worth another listen","2주 넘게 만나지 못한, 익숙한 취향",it)},
            offline.takeIf {it.isNotEmpty()}?.let {OdifyMix("offline","Offline essentials","연결 없이도 함께할 내 음악",it)},
            catalog.takeIf { daily.isEmpty() && repeat.isEmpty() && favorites.isEmpty() && it.isNotEmpty() }
                ?.let { OdifyMix("start", "A little discovery", "내 음악에서 시작해요 · 들을수록 취향이 쌓여요", it.take(30)) }
        )
    }
}
