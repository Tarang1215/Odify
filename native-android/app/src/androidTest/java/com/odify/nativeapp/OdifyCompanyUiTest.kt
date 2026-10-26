package com.odify.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset

/** Office-safe regression suite. No play(), prepare(), audio decoding, or playback-service commands. */
class OdifyCompanyUiTest {
    private val test=InstrumentationRegistry.getInstrumentation()
    private val context get()=test.targetContext
    private fun main(block:()->Unit)=test.runOnMainSync(block)

    @Test fun nativeQueueReorderKeepsCurrentPositionAndMakesMovedEntryNextWithoutPlaying() {
        main {
            val player=ExoPlayer.Builder(context).build()
            try {
                val songs=listOf("past","current","duplicate","duplicate","last").map {
                    OdifySong(it,it,"Artist","Album","/never-opened/$it.mp3")
                }
                player.setMediaItems(songs.map {it.mediaItem()},1,12345L)
                val keys=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaMetadata.extras!!.getString("odify.queueEntry")!!}
                assertNotEquals(keys[2],keys[3])
                player.shuffleModeEnabled=true
                assertTrue(odifyMoveUpcoming(player,keys[4],keys[2]))
                assertEquals("current",player.currentMediaItem?.mediaId)
                assertEquals(12345L,player.currentPosition)
                assertFalse(player.shuffleModeEnabled)
                assertEquals("last",player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
                assertFalse(odifyMoveUpcoming(player,keys[1],keys[3]))
                assertTrue(odifyMoveUpcoming(player,keys[3],keys[2]))
                assertEquals(listOf(keys[0],keys[1],keys[4],keys[3],keys[2]),
                    (0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaMetadata.extras!!.getString("odify.queueEntry")})
                assertEquals(Player.STATE_IDLE,player.playbackState)
                assertFalse(player.playWhenReady)
                assertFalse(player.isPlaying)
            } finally {player.release()}
        }
    }

    @Test fun audioOverridePreservesAlbumPresentationWithoutRenamingTheVideoRecord() {
        val name="company-replacement-${System.nanoTime()}"
        val store=OdifyStreamingLibrary(context,"$name-stream")
        val overrides=OdifyAudioOverrides(context,name,"$name-stream")
        try {
            val original=OdifySong("gdrive:original","Original title","Original artist","Original album","",
                artworkPath="/original-cover.jpg",lyrics="Original lyrics")
            val video=YoutubeVideo("abcdefghijk","Video upload title","Uploader","")
            overrides.choose(original,video)
            val resolved=overrides.resolve(original)
            assertEquals(original.id,resolved.id)
            assertEquals(original.artworkPath,resolved.artworkPath)
            assertEquals(original.lyrics,resolved.lyrics)
            assertEquals(original.title,resolved.title)
            assertEquals(video.title,store.video(resolved).title)
            assertEquals(video.channel,store.video(resolved).channel)
            assertEquals(video.videoId,queueSong(resolved.mediaItem()).playbackVideoId)
        } finally {context.deleteSharedPreferences(name);context.deleteSharedPreferences("$name-stream")}
    }

    @Test fun playlistNamesAndPrivateCoversPersistWithoutChangingSongsOrLosingThePreviousCoverOnFailure() {
        val name="company-playlist-${System.nanoTime()}"
        val store=OdifyPlaylists(context,name,"$name-stream")
        val source=File(context.cacheDir,"$name.png")
        val invalid=File(context.cacheDir,"$name-invalid.txt").apply {writeText("not an image")}
        val image=Bitmap.createBitmap(4096,1024,Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        source.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        try {
            val first=store.create("First");store.addSong(first.id,"fixture:original")
            val second=store.create("Other")
            store.changeCover(first.id,Uri.fromFile(source))
            store.rename(first.id,"  Evening collection  ")
            var loaded=OdifyPlaylists(context,name,"$name-stream").all().first {it.id==first.id}
            assertEquals("Evening collection",loaded.name)
            assertEquals(listOf("fixture:original"),loaded.songIds)
            assertTrue(File(loaded.coverPath!!).isFile)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(loaded.coverPath,bounds)
            assertTrue(maxOf(bounds.outWidth,bounds.outHeight)<=1024)
            assertTrue(source.isFile)
            assertTrue(runCatching {store.rename(first.id,"Other")}.isFailure)
            assertTrue(runCatching {store.rename(first.id,"  ")}.isFailure)
            assertTrue(runCatching {store.changeCover(first.id,Uri.fromFile(invalid))}.isFailure)
            assertEquals(loaded,store.all().first {it.id==first.id})
            val old=loaded.coverPath!!
            store.changeCover(first.id,Uri.fromFile(source))
            assertFalse(File(old).exists())
            loaded=store.all().first {it.id==first.id}
            store.changeCover(first.id,null)
            assertFalse(File(loaded.coverPath!!).exists())
            assertNull(store.all().first {it.id==first.id}.coverPath)
            assertEquals(listOf("fixture:original"),store.all().first {it.id==first.id}.songIds)
            store.changeCover(second.id,Uri.fromFile(source))
            val deleted=store.all().first {it.id==second.id}.coverPath!!
            store.delete(second.id);assertFalse(File(deleted).exists())
            store.delete(first.id)
            context.getSharedPreferences(name,0).edit().putString("playlists","""[{"id":"legacy","name":"Legacy","songs":["original"]}]""").commit()
            assertEquals(OdifyPlaylist("legacy","Legacy",listOf("original")),store.all().single())
        } finally {
            store.all().forEach {store.delete(it.id)}
            source.delete();invalid.delete()
            context.deleteSharedPreferences(name);context.deleteSharedPreferences("$name-stream")
        }
    }

    private fun node(label:String):AccessibilityNodeInfo? {
        fun find(value:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(value==null)return null
            if(value.text?.toString()==label || value.contentDescription?.toString()==label)return value
            for(i in 0 until value.childCount)find(value.getChild(i))?.let {return it}
            return null
        }
        return find(test.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String):AccessibilityNodeInfo {
        repeat(50) {node(label)?.let {return it};Thread.sleep(100)}
        error("Missing UI: $label")
    }
    private fun tap(label:String,pause:Long=450) {
        var target:AccessibilityNodeInfo?=waitFor(label)
        while(target!=null && !target.isClickable)target=target.parent
        assertTrue("Not clickable: $label",target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        Thread.sleep(pause)
    }
    private fun capture(name:String) {
        val image=test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.cacheDir,"company-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    private fun typeName(value:String) {
        fun editable(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.isEditable)return node
            for(i in 0 until node.childCount)editable(node.getChild(i))?.let {return it}
            return null
        }
        val field=editable(test.uiAutomation.rootInActiveWindow) ?: error("Name editor unavailable")
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }))
        Thread.sleep(150)
    }
    private fun drag(from:String,to:String) {
        val start=Rect();waitFor(from).getBoundsInScreen(start)
        val end=Rect();waitFor(to).getBoundsInScreen(end)
        val began=SystemClock.uptimeMillis()
        fun event(action:Int,x:Float,y:Float,at:Long) {
            val event=MotionEvent.obtain(began,at,action,x,y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            test.uiAutomation.injectInputEvent(event,true);event.recycle()
        }
        event(MotionEvent.ACTION_DOWN,start.centerX().toFloat(),start.centerY().toFloat(),began)
        for(step in 1..24) {
            val fraction=step/24f
            event(MotionEvent.ACTION_MOVE,start.centerX().toFloat(),start.centerY()+(end.centerY()-start.centerY())*fraction,
                began+step*16L)
            Thread.sleep(16)
        }
        event(MotionEvent.ACTION_UP,end.centerX().toFloat(),end.centerY().toFloat(),began+400)
        Thread.sleep(700)
    }

    @Test fun playerSheetsQueueDragPlaylistAndCountryChangesNeverInvokePlayback() {
        val cover=File(context.cacheDir,"company-fixture-cover.png")
        val bitmap=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(0f,0f,400f,400f,Paint().apply {shader=LinearGradient(0f,0f,400f,400f,
            intArrayOf(0xFF4976A5.toInt(),0xFFB583A9.toInt(),0xFF26374C.toInt()),null,Shader.TileMode.CLAMP)})
        cover.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val fixture=(0..8).map {i -> OdifySong("fixture:$i",if(i==0)"A quiet moment" else "Track $i","Odify Artist","Collection","",
            artworkPath=cover.path,durationMs=240000,lyrics="A quiet moment\nA little more space")}
        val queue=mutableStateOf(fixture)
        val keys=mutableStateOf(fixture.indices.map {"entry:$it"})
        val sheet=mutableStateOf(false)
        val anchor=mutableStateOf<Offset?>(null)
        val screen=mutableStateOf("player")
        val playlistName=mutableStateOf("Evening collection")
        var plays=0;var moves=0
        var observedOrder:List<String> = emptyList()
        var observedMoves=0
        val noPlay:()->Unit={plays++}
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity -> activity.setContent {
                MaterialTheme(typography=OdifyTypography,colorScheme=darkColorScheme()) {
                    Surface(Modifier.fillMaxSize(),color=Color(0xFF0C0E12),contentColor=Color.White) {
                        when(screen.value) {
                            "player" -> OdifyNowPlaying(song=fixture[0],glow=Color(0xFF8BAECC),previousSong=null,nextSong=fixture[1],
                                playing=false,position=20000,duration=240000,liked=false,shuffleEnabled=false,repeatOne=false,
                                onDismiss={},onToggle=noPlay,onPrevious=noPlay,onNext=noPlay,onSeek={plays++},onLike={},onShuffle={},onRepeat={},
                                onShowQueue={sheet.value=true},onQueueAnchor={anchor.value=it},onAddToPlaylist={},onSaveDevice={},onAudioRequest={_,_,_->},onReplaceAudio={})
                            "playlist" -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                OdifyPlaylistDetail(OdifyPlaylist("fixture",playlistName.value,fixture.map {it.id}),fixture,
                                    fixture[0].id,onDismiss={},onPlay={_,_->plays++},onDelete={},onRemoveSong={},onRename={playlistName.value=it;""})
                            }
                            "explore" -> OdifyExplore(OdifyChartMatching.buildIndex(emptyList()),OdifyExploreRepository(context),{},
                                {_,_->plays++},{plays++},{_,_->},{},{_,_->})
                            "home" -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                OdifyPersonalHome(fixture,false,listOf(OdifyMix("fixture","A very long artist name with a collection for quiet evenings",
                                    "A long collection description that stays inside its glass surface",fixture)),emptyList(),{},{},{},{},{_,_->plays++},{},{})
                            }
                        }
                        OdifyQueueDialog(queue.value,0,onMove={from,to ->
                            val start=keys.value.indexOf(from);val end=keys.value.indexOf(to)
                            queue.value=queue.value.moved(start,end);keys.value=keys.value.moved(start,end);moves++
                        },onSelect={plays++},onClose={sheet.value=false},entryKeys=keys.value,glow=Color(0xFF8BAECC),visible=sheet.value,anchor=anchor.value)
                    }
                }
            }}
            waitFor("곡 옵션 열기");capture("player")
            tap("가사 열기");waitFor("가사 닫기");Thread.sleep(300);capture("lyrics")
            tap("전체 화면으로 보기");waitFor("앨범커버 보기");Thread.sleep(300);capture("lyrics-fullscreen");tap("앨범커버 보기")
            tap("곡 옵션 열기");waitFor("음원 교체");capture("player-options")
            test.uiAutomation.executeShellCommand("input keyevent 4").close();Thread.sleep(400)
            tap("다음 재생 열기");waitFor("Track 1 순서 이동");Thread.sleep(350)
            drag("Track 1 순서 이동","Track 3 순서 이동")
            main {
                observedOrder=keys.value.take(4);observedMoves=moves
            }
            capture("queue")
            tap("대기열 닫기",90)
            assertNotNull("Sheet must remain mounted during its closing motion",node("대기열 닫기"))
            Thread.sleep(500)
            assertNull("Sheet must release its window after closing",node("대기열 닫기"))
            main {sheet.value=false;screen.value="playlist"}
            waitFor("Evening collection");Thread.sleep(300);capture("playlist")
            tap("플레이리스트 옵션");tap("이름 수정");waitFor("플레이리스트 이름")
            typeName("My renamed collection");tap("저장");waitFor("My renamed collection");capture("playlist-renamed")
            tap("플레이리스트 커버 변경");Thread.sleep(500)
            test.uiAutomation.executeShellCommand("input keyevent 4").close();Thread.sleep(450)
            main {screen.value="explore"}
            waitFor("한국 Top 100");tap("일본");waitFor("일본 Top 100")
            tap("미국");waitFor("미국 Top 100");capture("explore")
            main {screen.value="home"}
            tap("A very long artist name with a collection for quiet evenings");waitFor("컬렉션 닫기");Thread.sleep(400);capture("home-collection")
            main {
                assertEquals(0,plays)
                assertEquals(1,observedMoves)
                assertEquals(listOf("entry:0","entry:2","entry:3","entry:1"),observedOrder)
            }
        }
    }
}
