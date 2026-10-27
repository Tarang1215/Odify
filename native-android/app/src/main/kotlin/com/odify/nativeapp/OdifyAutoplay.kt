package com.odify.nativeapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extend only an exhausted queue, never reorder or replace explicit entries. */
internal object OdifyAutoplay {
    suspend fun following(context:Context,current:OdifySong,queue:List<OdifySong>):List<OdifySong> = withContext(Dispatchers.IO) {
        val app=context.applicationContext
        val songs=OdifyLibrary(app).songs()+DriveCatalog(app).tracks()+OdifyPhoneFolder(app).tracks()+OdifyStreamingLibrary(app).all()
        val prefs=app.getSharedPreferences("odify_native_ui",0)
        val history=prefs.getString("history","").orEmpty().split("|").toSet()
        val available=songs.filter {it.isYoutubeStream || it.isCloud || !it.sourceUri.isNullOrBlank() ||
            it.filePath.isNotBlank() && java.io.File(it.filePath).isFile}
        val planned=OdifyAutoplayPolicy.following(current,available,queue,history,
            prefs.getStringSet("likes",emptySet()).orEmpty(),OdifyListeningStore(app).signals())
        val overrides=OdifyAudioOverrides(app)
        planned.map(overrides::resolve)
    }
}

internal object OdifyAutoplayPolicy {
    fun shouldExtend(playIntent:Boolean,repeat:Boolean,hasFollowing:Boolean)=playIntent && !repeat && !hasFollowing
    fun following(current:OdifySong,catalog:List<OdifySong>,queue:List<OdifySong>,recent:Set<String>,
        liked:Set<String> = emptySet(),signals:Map<String,ListeningSignal> = emptyMap()):List<OdifySong> {
        fun key(song:OdifySong)=OdifyMusicMatching.norm(song.artist)+"|"+OdifyArtistTracks.titleKey(song.title,song.artist)
        val excluded=(queue+current+catalog.filter {it.id in recent}).map(::key).toSet()
        val candidates=LinkedHashMap<String,OdifySong>()
        fun quality(song:OdifySong)=(if(!song.isYoutubeStream)100 else 0)+(if(!song.isCloud)10 else 0)+
            (if(!song.artworkPath.isNullOrBlank())4 else 0)
        for(song in catalog) {
            val identity=key(song)
            if(song.id in recent || song.durationMs in 1L..44_999L || song.durationMs>900_000L || identity in excluded)continue
            val old=candidates[identity]
            if(old==null || quality(song)>quality(old))candidates[identity]=song
        }
        val artist=OdifyMusicMatching.norm(current.artist);val genre=OdifyMusicMatching.norm(current.genre)
        fun score(song:OdifySong)=(if(artist.isNotBlank() && OdifyMusicMatching.norm(song.artist)==artist)6.0 else 0.0)+
            (if(genre.isNotBlank() && OdifyMusicMatching.norm(song.genre)==genre)4.0 else 0.0)+
            (if(song.id in liked)2.0 else 0.0)+1.0/(1+(signals[song.id]?.count ?: 0))
        val ranked=candidates.values.map {it to score(it)}
            .sortedWith(compareByDescending<Pair<OdifySong,Double>> {it.second}.thenBy {it.first.id}).map {it.first}
        val counts=HashMap<String,Int>();val selected=ArrayList<OdifySong>()
        for(song in ranked) {
            val name=OdifyMusicMatching.norm(song.artist).ifBlank {song.id}
            if(counts.getOrDefault(name,0)>=2)continue
            counts[name]=counts.getOrDefault(name,0)+1;selected.add(song)
            if(selected.size==6)break
        }
        return selected.ifEmpty {ranked.take(6)}
    }
}

internal object OdifyPlaybackContinuity {
    fun foreground(state:YoutubeWebState)=state.error.isBlank() && (state.playing || state.buffering || !state.ready)
    fun stalled(state:YoutubeWebState,paused:Boolean,withoutProgress:Long)=!paused && state.error.isBlank() &&
        (state.playing || state.buffering) && withoutProgress>=45_000L
}

/** Bound recovery; a broken renderer must not create an endless reload loop. */
internal class OdifyRecoveryBudget {
    private var began=Long.MIN_VALUE
    private var attempts=0
    fun allow(now:Long):Boolean {
        if(began==Long.MIN_VALUE || now-began>600_000L){began=now;attempts=0}
        if(attempts>=2)return false
        attempts++;return true
    }
}
