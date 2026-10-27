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
import androidx.compose.ui.graphics.asImageBitmap
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

    @Test fun retainedPagesKeepCompositionAndHideInactiveTouchAndAccessibilityTargets() {
        val selected=mutableStateOf(true);var homeMounts=0;var exploreMounts=0;var inactiveClicks=0
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity ->activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        OdifyPageLayer(selected.value,true,1,{}) {
                            remember {homeMounts++;true}
                            TextButton(onClick={inactiveClicks++},modifier=Modifier.fillMaxSize()) {Text("Retained home")}
                        }
                        OdifyPageLayer(!selected.value,false,1,{}) {
                            remember {exploreMounts++;true}
                            Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center){Text("Retained explore")}
                        }
                    }
                }
            }}
            waitFor("Retained home")
            repeat(3) {
                main {selected.value=false};waitFor("Retained explore")
                assertNull(node("Retained home"))
                // The active empty page intentionally has no clickable node.
                // Inject a real tap to test hit testing and click-through.
                val area=Rect();waitFor("Retained explore").getBoundsInScreen(area)
                val began=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(began,SystemClock.uptimeMillis(),action,area.centerX().toFloat(),area.centerY().toFloat(),0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    test.uiAutomation.injectInputEvent(event,true);event.recycle()
                }
                Thread.sleep(250)
                main {selected.value=true};waitFor("Retained home")
            }
            main {assertEquals(1,homeMounts);assertEquals(1,exploreMounts);assertEquals(0,inactiveClicks)}
        }
    }

    @Test fun snapshotLensCompilesAndAcceptsChangingPanelGeometry() {
        if(android.os.Build.VERSION.SDK_INT<33)return
        val image=Bitmap.createBitmap(64,128,Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        try {main {
            val frame=OdifyModalSnapshot(image.asImageBitmap(),Offset.Zero,androidx.compose.ui.unit.IntSize(1440,2880))
            val lens=OdifySnapshotLens(frame)
            lens.update(Offset(40f,1500f),1360f,1300f,105f,3.5f,.28f)
            lens.update(Offset(0f,100f),1440f,2700f,105f,3.5f,.28f)
        }} finally {image.recycle()}
    }

    @Test fun officialRecordingPresentationPersistsWithoutChangingVideoOrAudioIdentity() {
        val name="company-recording-${System.nanoTime()}"
        val store=OdifyStreamingLibrary(context,name)
        val cover=File(context.cacheDir,"$name.jpg").apply {writeBytes(byteArrayOf(1))}
        try {
            val video=YoutubeVideo("abcdefghijk","ROSÉ Messy Lyrics","Lemonade","")
            store.remember(video)
            val music=OdifyMusicIdentity("123","Messy","ROSÉ","F1 The Album","","1406242696","","",180000)
            store.presentation(video.videoId,music,null)
            assertNull(store.find("youtube:${video.videoId}")!!.artworkPath)
            store.presentation(video.videoId,music,cover.absolutePath)
            store.remember(video,185000)
            val song=OdifyStreamingLibrary(context,name).find("youtube:${video.videoId}")!!
            assertEquals("Messy",song.title);assertEquals("ROSÉ",song.artist);assertEquals("F1 The Album",song.album)
            assertEquals(cover.absolutePath,song.artworkPath);assertEquals(185000L,song.durationMs)
            assertEquals(video,store.video(song));assertTrue(song.isYoutubeStream)
        } finally {context.deleteSharedPreferences(name);cover.delete()}
    }

    @Test fun pausedMediaCardRefreshesMetadataAndRoutesEveryControlWithoutStartingAnyPlayer() {
        val calls=java.util.concurrent.CopyOnWriteArrayList<Pair<String,Long>>()
        var card:OdifyYoutubeMediaCard?=null
        try {
            main {
                card=OdifyYoutubeMediaCard(context,8099) {action,position ->calls.add(action to position)}
                val video=YoutubeVideo("abcdefghijk","Video title","Uploader","")
                val song=OdifySong("youtube:abcdefghijk","Messy","ROSÉ","F1 The Album","",durationMs=180000)
                val value=YoutubeSessionState(video,playback=YoutubeWebState(ready=true),presentation=song,following=listOf(song))
                card!!.update(value)
                val controller=android.media.session.MediaController(context,card!!.session.sessionToken)
                assertEquals("Messy",controller.metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
                assertEquals("Messy",controller.metadata!!.description.title.toString())
                assertEquals("ROSÉ",controller.metadata!!.description.subtitle.toString())
                assertNotNull(controller.sessionActivity)
                assertEquals("ROSÉ",controller.metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
                assertEquals(android.media.session.PlaybackState.STATE_PAUSED,controller.playbackState!!.state)
                assertEquals(180000L,controller.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION))
                card!!.update(value.copy(playback=value.playback.copy(duration=185000),presentation=song.copy(title="Updated title")))
                assertEquals("Updated title",controller.metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
                assertEquals("Updated title",controller.metadata!!.description.title.toString())
                assertEquals(185000L,controller.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION))
                val notification=card!!.notification()
                assertEquals(android.app.Notification.CATEGORY_TRANSPORT,notification.category)
                assertEquals("Updated title",notification.tickerText.toString())
                assertNotNull(notification.deleteIntent)
                assertEquals(0,notification.flags and android.app.Notification.FLAG_ONGOING_EVENT)
                controller.transportControls.apply {play();pause();seekTo(42000);skipToNext();skipToPrevious();stop()}
            }
            val deadline=SystemClock.elapsedRealtime()+3000
            while(calls.size<6 && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20)
            assertEquals(listOf("play","pause","seek","next","previous","stop"),calls.map {it.first})
            assertEquals(42000L,calls.first {it.first=="seek"}.second)
            main {card!!.dismiss();assertFalse(card!!.session.isActive)}
            // NotificationManager removes the card asynchronously in system_server.
            val manager=context.getSystemService(android.app.NotificationManager::class.java)
            val removalDeadline=SystemClock.elapsedRealtime()+3000
            while(manager.activeNotifications.any {it.id==8099} && SystemClock.elapsedRealtime()<removalDeadline)SystemClock.sleep(20)
            assertFalse(manager.activeNotifications.any {it.id==8099})
        } finally {main {card?.close()}}
    }

    @Test fun widgetUsesOfficialPresentationAndRejectsStaleControlsWithoutStartingPlayback() {
        val previous=OdifyWidgetPlayback.read(context)
        val calls=mutableListOf<String>()
        try {main {
            assertTrue(android.appwidget.AppWidgetManager.getInstance(context).installedProviders.any {
                it.provider==android.content.ComponentName(context,OdifyMusicWidget::class.java)})
            OdifyWidgetPlayback.bind(context,"widget-fixture") {calls.add(it)}
            val track=OdifyWidgetTrack("widget-fixture","fixture:messy","Messy","ROSÉ",next=true,previous=true)
            OdifyWidgetPlayback.publish(context,track)
            val views=OdifyWidgetPlayback.views(context,track,true).apply(context,android.widget.FrameLayout(context))
            assertEquals("Messy",views.findViewById<android.widget.TextView>(R.id.widget_title).text.toString())
            assertEquals("ROSÉ",views.findViewById<android.widget.TextView>(R.id.widget_artist).text.toString())
            assertTrue(views.findViewById<android.view.View>(R.id.widget_toggle).hasOnClickListeners())
            val density=context.resources.displayMetrics.density
            val width=(360*density).toInt();val height=(156*density).toInt()
            views.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
            views.layout(0,0,width,height)
            val preview=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            views.draw(Canvas(preview))
            File(context.cacheDir,"company-widget.png").outputStream().use {preview.compress(Bitmap.CompressFormat.PNG,100,it)}
            preview.recycle()
            assertTrue(OdifyWidgetPlayback.dispatch("widget-fixture","toggle"))
            assertTrue(OdifyWidgetPlayback.dispatch("widget-fixture","next"))
            assertFalse(OdifyWidgetPlayback.dispatch("another-engine","toggle"))
            OdifyWidgetPlayback.unbind(context,"widget-fixture")
            assertFalse(OdifyWidgetPlayback.dispatch("widget-fixture","toggle"))
            assertEquals(listOf("toggle","next"),calls)
            val idle=OdifyWidgetPlayback.views(context,track,false).apply(context,android.widget.FrameLayout(context))
            assertEquals("Odify 열기",idle.findViewById<android.view.View>(R.id.widget_toggle).contentDescription.toString())
            assertNull(OdifyYoutubeRuntime.service)
        }} finally {main {OdifyWidgetPlayback.unbind(context,"widget-fixture");OdifyWidgetPlayback.publish(context,previous)}}
    }

    @Test fun compactWidgetFitsOneRowAndHasTransparentGlassMaterial() {
        main {
            assertTrue(android.appwidget.AppWidgetManager.getInstance(context).installedProviders.any {
                it.provider==android.content.ComponentName(context,OdifyCompactMusicWidget::class.java)})
            val track=OdifyWidgetTrack("fixture","fixture:compact","A very long title with an album version","Odify Artist",next=true,previous=true)
            val density=context.resources.displayMetrics.density
            for((widthDp,heightDp) in listOf(360 to 64,276 to 51)) {
                val views=OdifyWidgetPlayback.views(context,track,true,compact=true,widthDp=widthDp)
                    .apply(context,android.widget.FrameLayout(context))
                val width=(widthDp*density).toInt();val height=(heightDp*density).toInt()
                views.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                views.layout(0,0,width,height)
                val title=views.findViewById<android.widget.TextView>(R.id.widget_title)
                assertTrue("Title retains useful width",title.width>50*density)
                assertEquals(if(widthDp<330)android.view.View.GONE else android.view.View.VISIBLE,
                    views.findViewById<android.view.View>(R.id.widget_previous).visibility)
                val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);views.draw(Canvas(image))
                assertTrue("Glass must retain transparency",android.graphics.Color.alpha(image.getPixel(width/2,(2*density).toInt())) in 1..254)
                File(context.cacheDir,"company-widget-compact-$widthDp.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
        }
    }

    @Test fun immutableGlassRasterBlursTheSceneAndKeepsItsInteriorOpaque() {
        val source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(android.graphics.Color.BLACK)
            drawRect(32f,0f,64f,64f,Paint().apply {color=android.graphics.Color.WHITE})
        }
        val frost=OdifyModalRaster.blur(source,4)
        val blurred=android.graphics.Color.red(frost.getPixel(32,32))
        assertTrue(blurred in 50..220)
        val frame=OdifyModalSnapshot(source.asImageBitmap(),Offset.Zero,androidx.compose.ui.unit.IntSize(64,64),frost.asImageBitmap())
        val surface=OdifyModalRaster.surface(frame,Offset.Zero,androidx.compose.ui.unit.IntSize(64,64),10f,1f,.76f)
        assertEquals(255,android.graphics.Color.alpha(surface.getPixel(32,32)))
        assertEquals(255,android.graphics.Color.alpha(surface.getPixel(0,0)))
        assertTrue(android.graphics.Color.red(surface.getPixel(32,32)) in 30..220)
        surface.recycle();frost.recycle();source.recycle()
    }

    @Test fun artistCatalogRejectsOtherArtistsAndNonSongsAndDeduplicatesAlbums() {
        val raw="""{"results":[
          {"kind":"song","trackId":1,"trackName":"Song","artistName":"Vaundy","primaryGenreName":"J-Pop"},
          {"kind":"song","trackId":2,"trackName":"SONG","artistName":"Vaundy"},
          {"kind":"song","trackId":3,"trackName":"Unrelated","artistName":"Vaundy Tribute"},
          {"kind":"music-video","trackId":4,"trackName":"Video","artistName":"Vaundy"},
          {"kind":"song","trackId":5,"trackName":"Another song","artistName":"Vaundy"}]}"""
        val tracks=OdifyArtistCatalog(context).decode(raw,"Vaundy")
        assertEquals(listOf("Song","Another song"),tracks.map {it.title})
        assertTrue(tracks.all {it.artwork.isEmpty()})
        val aliases="""{"results":[
          {"kind":"song","trackId":11,"trackName":"Palette","artistName":"IU","artistId":409076743},
          {"kind":"song","trackId":12,"trackName":"Love wins all","artistName":"IU","artistId":409076743},
          {"kind":"song","trackId":13,"trackName":"Palette","artistName":"Cover artist","artistId":99},
          {"kind":"song","trackId":14,"trackName":"Other song","artistName":"IU","artistId":409076743}]}"""
        assertEquals(3,OdifyArtistCatalog(context).decode(aliases,"아이유",listOf("Palette","Love wins all")).size)
        assertTrue("An ambiguous single-title alias must not select another artist",OdifyArtistCatalog(context).decode(aliases,"아이유",listOf("Palette")).isEmpty())
    }

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
        // Android 17 can retain the outgoing pager subtree after a gesture.
        // Read fresh accessibility data instead of failing against stale nodes.
        if(android.os.Build.VERSION.SDK_INT>=34)test.uiAutomation.clearCache()
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
        capture("missing-ui")
        error("Missing UI: $label")
    }
    private fun tap(label:String,pause:Long=450) {
        var target:AccessibilityNodeInfo?=waitFor(label)
        while(target!=null && !target.isClickable)target=target.parent
        assertTrue("Not clickable: $label",target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        Thread.sleep(pause)
    }
    private fun waitForOpenSurface() {
        // Older APKs have no readiness semantics; settle identically when profiling.
        if(InstrumentationRegistry.getArguments().getString("benchmark")=="true") {Thread.sleep(950);return}
        fun open(value:AccessibilityNodeInfo?):Boolean {
            if(value==null)return false
            if(value.stateDescription?.toString()=="열림")return true
            for(i in 0 until value.childCount)if(open(value.getChild(i)))return true
            return false
        }
        repeat(50) {if(open(test.uiAutomation.rootInActiveWindow))return;Thread.sleep(100)}
        error("Glass surface did not finish opening")
    }
    private fun performancePhase(name:String?) {
        if(InstrumentationRegistry.getArguments().getString("benchmark")!="true")return
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command))
            .use {it.bufferedReader().readText()}
        if(name!=null)File(context.cacheDir,"company-performance-$name.txt")
            .writeText(shell("dumpsys gfxinfo ${context.packageName} framestats"))
        shell("dumpsys gfxinfo ${context.packageName} reset")
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("screenshots")=="false")return
        val image=test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.cacheDir,"company-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    private fun back() {
        // Drain the shell pipe so the key injection finishes before closing it.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("input keyevent 4"))
            .use {it.readBytes()}
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
    private fun swipe(startX:Float,startY:Float,endX:Float,endY:Float,settleMillis:Long=600) {
        val began=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(began,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            // Do not wait for display sync and then sleep another frame. Use
            // real event times at roughly 120 Hz, like this phone's touch input.
            test.uiAutomation.injectInputEvent(event,false);event.recycle()
        }
        send(MotionEvent.ACTION_DOWN,startX,startY)
        while(true) {
            SystemClock.sleep(8)
            val fraction=((SystemClock.uptimeMillis()-began)/400f).coerceAtMost(1f)
            send(MotionEvent.ACTION_MOVE,startX+(endX-startX)*fraction,startY+(endY-startY)*fraction)
            if(fraction>=1f)break
        }
        send(MotionEvent.ACTION_UP,endX,endY)
        Thread.sleep(settleMillis)
    }
    private fun verifyCountryFollowsFinger(pager:androidx.compose.foundation.pager.PagerState) {
        val width=context.resources.displayMetrics.widthPixels.toFloat()
        val height=context.resources.displayMetrics.heightPixels.toFloat()
        // Stay below the horizontal genre chips: that row intentionally owns
        // its own horizontal drags and must not turn the country page.
        val began=SystemClock.uptimeMillis();val y=height*.68f
        fun event(action:Int,x:Float) {
            val value=MotionEvent.obtain(began,SystemClock.uptimeMillis(),action,x,y,0)
            value.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            test.uiAutomation.injectInputEvent(value,false);value.recycle()
        }
        event(MotionEvent.ACTION_DOWN,width*.86f)
        try {
            for(i in 1..35){SystemClock.sleep(8);event(MotionEvent.ACTION_MOVE,width*(.86f-.28f*i/35))}
            test.waitForIdleSync()
            // Accessibility bounds are cached until the scroll settles. Read
            // the actual pager position while the pointer is still pressed.
            main {assertTrue("Country content must follow the finger before ACTION_UP",
                pager.currentPage+pager.currentPageOffsetFraction>.10f)}
            capture("pager-held")
            for(i in 1..30){SystemClock.sleep(8);event(MotionEvent.ACTION_MOVE,width*(.58f-.43f*i/30))}
        } finally {event(MotionEvent.ACTION_UP,width*.15f)}
        waitFor("일본 Top 100")
    }
    private fun drag(from:String,to:String) {
        val start=Rect();waitFor(from).getBoundsInScreen(start)
        val end=Rect();waitFor(to).getBoundsInScreen(end)
        swipe(start.centerX().toFloat(),start.centerY().toFloat(),end.centerX().toFloat(),end.centerY().toFloat())
    }
    private fun swipeFooter(next:Boolean) {
        val bounds=Rect();waitFor("음원 출처와 다음 곡").getBoundsInScreen(bounds)
        val start=bounds.left+bounds.width()*(if(next).85f else .15f)
        val end=bounds.left+bounds.width()*(if(next).15f else .85f)
        swipe(start,bounds.centerY().toFloat(),end,bounds.centerY().toFloat())
    }
    private fun pullHandle(up:Boolean) {
        val label=if(up)"전체 화면으로 펼치기" else "작은 창으로 접기"
        val bounds=Rect();waitFor(label).getBoundsInScreen(bounds)
        val start=bounds.centerY().toFloat()
        val end=(start+if(up)-720 else 720).coerceIn(140f,context.resources.displayMetrics.heightPixels-180f)
        swipe(bounds.centerX().toFloat(),start,bounds.centerX().toFloat(),end)
    }

    @Test fun playerSheetsQueueDragPlaylistAndCountryChangesNeverInvokePlayback() {
        val benchmark=InstrumentationRegistry.getArguments().getString("benchmark")=="true"
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
        var explorePager:androidx.compose.foundation.pager.PagerState?=null
        val playlistName=mutableStateOf("Evening collection")
        var plays=0;var moves=0
        var observedOrder:List<String> = emptyList()
        var observedMoves=0
        var onlineSelected:String?=null
        val noPlay:()->Unit={plays++}
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity -> activity.setContent {
                MaterialTheme(typography=OdifyTypography,colorScheme=darkColorScheme()) {
                    Surface(Modifier.fillMaxSize(),color=Color(0xFF0C0E12),contentColor=Color.White) {
                        when(screen.value) {
                            "player", "player-empty" -> OdifyNowPlaying(song=if(screen.value=="player")fixture[0] else fixture[0].copy(id="fixture:without-lyrics",title="No lyrics yet",lyrics=""),
                                glow=Color(0xFF8BAECC),previousSong=null,nextSong=queue.value.getOrNull(1),
                                playing=false,position=20000,duration=240000,liked=false,shuffleEnabled=false,repeatOne=false,
                                onDismiss={},onToggle=noPlay,onPrevious=noPlay,onNext=noPlay,onSeek={plays++},onLike={},onShuffle={},onRepeat={},
                                onShowQueue={sheet.value=true},onQueueAnchor={anchor.value=it},onAddToPlaylist={},onSaveDevice={},onAudioRequest={_,_,_->},onReplaceAudio={})
                            "playlist" -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                OdifyPlaylistDetail(OdifyPlaylist("fixture",playlistName.value,fixture.map {it.id}),fixture,
                                    fixture[0].id,onDismiss={},onPlay={_,_->plays++},onDelete={},onRemoveSong={},onRename={playlistName.value=it;""})
                            }
                            "explore" -> {
                                val pager=androidx.compose.foundation.pager.rememberPagerState {3}
                                SideEffect {explorePager=pager}
                                OdifyExplore(OdifyChartMatching.buildIndex(emptyList()),OdifyExploreRepository(context),{},
                                    {_,_->plays++},{plays++},{_,_->},{},{_,_->},pagerState=pager)
                            }
                            "home" -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                                OdifyPersonalHome(fixture.take(1),false,listOf(OdifyMix("fixture","A very long artist name with a collection for quiet evenings",
                                    "A long collection description that stays inside its glass surface",fixture)),emptyList(),{},{},{},{},{_,_->plays++},{},{},
                                    catalogSongs=fixture.take(1)+fixture[0].copy(id="fixture:duplicate",title=" A quiet moment "),loadArtistTracks={artist ->(1..60).map {
                                        ExploreTrack("online-$it","New artist song $it",artist,"",listOf("Pop"),it)
                                    }+listOf(ExploreTrack("online-duplicate","New artist song 1 [Official Audio]",artist,"",emptyList(),61),
                                        ExploreTrack("online-owned","A quiet moment (Official MV)",artist,"",emptyList(),62))},onPlayOnline={onlineSelected=it.id})
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
            performancePhase(null)
            waitFor("음원 출처: Local")
            swipeFooter(true)
            waitFor("다음 곡: Track 1")
            capture("player-next")
            swipeFooter(false);waitFor("음원 출처: Local")
            swipeFooter(true);waitFor("다음 곡: Track 1")
            performancePhase("footer")
            tap("가사 열기");waitFor("앨범커버 보기");Thread.sleep(300);capture("lyrics");tap("앨범커버 보기")
            performancePhase("lyrics")
            tap("곡 옵션 열기");waitFor("음원 교체");waitForOpenSurface();capture("player-options")
            if(!benchmark) {
                val bounds=Rect();waitFor("플레이리스트에 추가").getBoundsInScreen(bounds)
                assertTrue("Overflow should open beside its top button",bounds.centerY()<context.resources.displayMetrics.heightPixels/2)
            }
            back();Thread.sleep(400)
            performancePhase("options")
            tap("다음 재생 열기");waitFor("Track 1 순서 이동");waitForOpenSurface()
            drag("Track 1 순서 이동","Track 3 순서 이동")
            main {
                observedOrder=keys.value.take(4);observedMoves=moves
            }
            capture("queue")
            if(!benchmark) {
                val small=Rect();waitFor("대기열 닫기").getBoundsInScreen(small)
                pullHandle(true);waitFor("작은 창으로 접기")
                val full=Rect();waitFor("대기열 닫기").getBoundsInScreen(full)
                assertTrue("Dragging up should expand the sheet toward the top",full.top<small.top-100)
                capture("queue-expanded")
                pullHandle(false);waitFor("전체 화면으로 펼치기")
            }
            tap("대기열 닫기",90)
            assertNotNull("Sheet must remain mounted during its closing motion",node("대기열 닫기"))
            Thread.sleep(500)
            assertNull("Sheet must release its window after closing",node("대기열 닫기"))
            waitFor("다음 곡: Track 2");capture("next-track-updated")
            performancePhase("queue")
            if(benchmark) {
                main {assertEquals(0,plays);assertEquals(1,observedMoves)}
                return
            }
            main {screen.value="player-empty"}
            waitFor("No lyrics yet")
            tap("가사 열기");waitFor("가사 추가·수정");waitForOpenSurface();capture("lyrics-empty")
            assertNull(node("전체 화면으로 보기"))
            back();Thread.sleep(500)
            main {sheet.value=false;screen.value="playlist"}
            waitFor("Evening collection");Thread.sleep(300);capture("playlist")
            tap("플레이리스트 옵션");tap("이름 수정");waitFor("플레이리스트 이름")
            typeName("My renamed collection");tap("저장");waitFor("My renamed collection");capture("playlist-renamed")
            tap("플레이리스트 커버 변경");Thread.sleep(500)
            back();Thread.sleep(450)
            repeat(3) {
                if(node("My renamed collection")==null) {
                    back();Thread.sleep(650)
                }
            }
            waitFor("My renamed collection")
            main {screen.value="explore"}
            waitFor("한국 Top 100");verifyCountryFollowsFinger(requireNotNull(explorePager))
            tap("미국");waitFor("미국 Top 100");capture("explore")
            main {screen.value="home"}
            tap("A very long artist name with a collection for quiet evenings");waitFor("컬렉션 닫기");waitForOpenSurface();capture("home-collection")
            tap("컬렉션 닫기");Thread.sleep(450)
            tap("Odify Artist");waitFor("내 음악 1곡 · 더 많은 곡 60");waitForOpenSurface()
            pullHandle(true);waitFor("New artist song 1");capture("artist-expanded")
            val height=context.resources.displayMetrics.heightPixels.toFloat()
            repeat(2) {
                swipe(750f,height*.85f,750f,height*.46f,0)
                swipe(750f,height*.46f,750f,height*.85f,0)
            }
            swipe(750f,height*.85f,750f,height*.46f,0)
            val scrollDeadline=SystemClock.elapsedRealtime()+1500
            while(node("New artist song 1")!=null && SystemClock.elapsedRealtime()<scrollDeadline)SystemClock.sleep(20)
            assertNull("Immediate reversal must still scroll the artist list",node("New artist song 1"))
            waitFor("작은 창으로 접기")
            repeat(10){if(node("New artist song 1")==null)swipe(750f,height*.46f,750f,height*.85f,0)}
            waitFor("New artist song 1")
            tap("New artist song 1")
            main {
                assertEquals(0,plays)
                assertEquals("online-1",onlineSelected)
                assertEquals(1,observedMoves)
                assertEquals(listOf("entry:0","entry:2","entry:3","entry:1"),observedOrder)
            }
        }
    }
}
