package com.odify.nativeapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import android.util.SizeF
import android.view.View
import kotlinx.coroutines.*
import org.json.JSONObject

internal data class OdifyWidgetTrack(val owner:String="",val id:String="",val title:String="Odify",
    val artist:String="음악을 골라 들어보세요",val artwork:String="",val playing:Boolean=false,
    val previous:Boolean=false,val next:Boolean=false)

/** One event-driven presentation for both engines. Position ticks never redraw
 * launcher widgets. A dead service must never be revived by a stale control. */
internal object OdifyWidgetPlayback {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private data class Endpoint(val available:()->Boolean,val command:(String)->Unit)
    private val commands=mutableMapOf<String,Endpoint>()
    private var current:OdifyWidgetTrack?=null
    private var rendering:Job?=null
    private data class Material(val art:Bitmap?,val compact:Bitmap,val full:Bitmap)
    private val materials=android.util.LruCache<String,Material>(3)
    private fun prefs(context:Context)=context.getSharedPreferences("odify_music_widget",0)
    fun bind(context:Context,owner:String,available:()->Boolean={true},command:(String)->Unit) {
        commands[owner]=Endpoint(available,command);refresh(context)
    }
    fun unbind(context:Context,owner:String) {
        commands.remove(owner)
        if(current?.owner==owner)current=current?.copy(playing=false)
        refresh(context)
    }
    fun read(context:Context):OdifyWidgetTrack=current ?: runCatching {
        val item=JSONObject(prefs(context).getString("track","{}").orEmpty())
        OdifyWidgetTrack(item.optString("owner"),item.optString("id"),item.optString("title","Odify"),
            item.optString("artist","음악을 골라 들어보세요"),item.optString("artwork"))
    }.getOrDefault(OdifyWidgetTrack())
    fun publish(context:Context,value:OdifyWidgetTrack) {
        if(current==value)return
        current=value
        prefs(context).edit().putString("track",JSONObject().put("owner",value.owner).put("id",value.id)
            .put("title",value.title).put("artist",value.artist).put("artwork",value.artwork).toString()).apply()
        refresh(context)
    }
    fun dispatch(owner:String,command:String):Boolean {
        if(command !in setOf("toggle","previous","next"))return false
        val value=current ?: return false
        if(value.owner!=owner || command=="previous" && !value.previous || command=="next" && !value.next)return false
        val target=commands[owner] ?: return false
        if(!target.available())return false
        target.command(command);return true
    }
    fun refresh(context:Context):Job? {
        val app=context.applicationContext
        val manager=AppWidgetManager.getInstance(app)
        val ids=(manager.getAppWidgetIds(ComponentName(app,OdifyMusicWidget::class.java)).toList()+
            manager.getAppWidgetIds(ComponentName(app,OdifyCompactMusicWidget::class.java)).toList()).toIntArray()
        if(ids.isEmpty())return null
        rendering?.cancel()
        val state=read(app);val controllable=commands[state.owner]?.available?.invoke()==true
        rendering=scope.launch {
            val material=withContext(Dispatchers.IO){
                val key="${state.artwork}:${java.io.File(state.artwork).lastModified()}"
                materials.get(key) ?: decode(state.artwork).let {art ->
                    Material(art,OdifyWidgetGlass.render(art,true),OdifyWidgetGlass.render(art,false)).also {materials.put(key,it)}
                }
            }
            for(id in ids) {
                val result=if(android.os.Build.VERSION.SDK_INT>=31)RemoteViews(mapOf(
                    SizeF(250f,48f) to views(app,state,controllable,material.art,true,250,material.compact),
                    SizeF(330f,48f) to views(app,state,controllable,material.art,true,330,material.compact),
                    SizeF(250f,140f) to views(app,state,controllable,material.art,false,360,material.full))) else {
                    val options=manager.getAppWidgetOptions(id)
                    val height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    val compact=if(height>0)height<140 else manager.getAppWidgetInfo(id)?.provider?.className==OdifyCompactMusicWidget::class.java.name
                    views(app,state,controllable,material.art,compact,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,250),
                        if(compact)material.compact else material.full)
                }
                manager.updateAppWidget(id,result)
            }
        }
        return rendering
    }
    internal fun views(context:Context,state:OdifyWidgetTrack,controllable:Boolean,art:Bitmap?=null,
        compact:Boolean=false,widthDp:Int=360,glass:Bitmap?=null):RemoteViews {
        val open=PendingIntent.getActivity(context,9000,Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun control(command:String,code:Int):PendingIntent=if(!controllable)open else PendingIntent.getBroadcast(context,code,
            Intent(context,OdifyMusicWidget::class.java).setAction(ACTION_CONTROL)
                .putExtra("owner",state.owner).putExtra("command",command),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return RemoteViews(context.packageName,if(compact)R.layout.odify_music_widget_compact else R.layout.odify_music_widget).apply {
            setTextViewText(R.id.widget_title,state.title)
            setTextViewText(R.id.widget_artist,state.artist)
            setImageViewBitmap(R.id.widget_glow,glass ?: OdifyWidgetGlass.render(art,compact))
            setViewVisibility(R.id.widget_previous,if(compact && widthDp<330)View.GONE else View.VISIBLE)
            setOnClickPendingIntent(R.id.widget_root,open)
            if(art!=null)setImageViewBitmap(R.id.widget_art,art) else setImageViewResource(R.id.widget_art,R.drawable.widget_music)
            setImageViewResource(R.id.widget_toggle,if(controllable && state.playing)R.drawable.widget_pause else R.drawable.widget_play)
            setContentDescription(R.id.widget_toggle,if(!controllable)"Odify 열기" else if(state.playing)"일시정지" else "재생")
            setOnClickPendingIntent(R.id.widget_toggle,control("toggle",9001))
            setOnClickPendingIntent(R.id.widget_previous,control("previous",9002))
            setOnClickPendingIntent(R.id.widget_next,control("next",9003))
            setFloat(R.id.widget_previous,"setAlpha",if(controllable && !state.previous).35f else 1f)
            setFloat(R.id.widget_next,"setAlpha",if(controllable && !state.next).35f else 1f)
        }
    }
    private fun decode(path:String):Bitmap?=runCatching {
        if(path.isBlank())return@runCatching null
        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(path,options)
        if(options.outWidth !in 1..8192 || options.outHeight !in 1..8192)return@runCatching null
        var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>192)sample*=2
        BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=sample})
    }.getOrNull()
    const val ACTION_CONTROL="com.odify.nativeapp.WIDGET_CONTROL"
}

open class OdifyMusicWidget:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {OdifyWidgetPlayback.refresh(context)}
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:android.os.Bundle) {
        OdifyWidgetPlayback.refresh(context)
    }
    override fun onReceive(context:Context,intent:Intent) {
        super.onReceive(context,intent)
        if(intent.action==OdifyWidgetPlayback.ACTION_CONTROL) {
            // Explicit immutable PendingIntents; only this application's receiver
            // can address its registered engine. Never start an empty service.
            OdifyWidgetPlayback.dispatch(intent.getStringExtra("owner").orEmpty(),intent.getStringExtra("command").orEmpty())
        }
        // Keep the process alive until the cover decode and launcher update
        // finish, including a launcher update after the app process was killed.
        val pending=goAsync()
        val job=OdifyWidgetPlayback.refresh(context)
        if(job==null)pending.finish() else job.invokeOnCompletion {pending.finish()}
    }
}

class OdifyCompactMusicWidget:OdifyMusicWidget()
