package com.odify.nativeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal class OdifyNativeAudioFocus(
    private val context: Context,
    private val player: Player,
    private val attributes: android.media.AudioAttributes
) : ForwardingPlayer(player), Player.Listener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(player.applicationLooper)
    private var request: AudioFocusRequest? = null
    private var interruptedItem: MediaItem? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pause()
        }
    }

    fun attach() {
        player.addListener(this)
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val item = player.currentMediaItem
        val canPlay = item != null && item.mediaMetadata.extras?.getBoolean("odify.youtubeStream") != true &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (!canPlay) {
            abandon()
            return
        }
        if (!player.playWhenReady) {
            if (item != interruptedItem) abandon()
            return
        }
        if (request == null) acquire()
    }

    override fun play() = setPlayWhenReady(true)

    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady || interruptedItem != null) abandon()
        player.playWhenReady = playWhenReady
    }

    override fun stop() {
        abandon()
        player.stop()
    }

    override fun release() {
        abandon()
        context.unregisterReceiver(noisy)
        player.removeListener(this)
        player.release()
    }

    private fun acquire() {
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
        lateinit var candidate: AudioFocusRequest
        candidate = next.setOnAudioFocusChangeListener({ change ->
            if (request === candidate) onFocusChange(change)
        }, handler).build()
        val result = audio.requestAudioFocus(candidate)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            request = candidate
        } else {
            Log.w("OdifyAudioFocus", "Media3 focus request denied ($result)")
            player.pause()
        }
    }

    private fun onFocusChange(change: Int) {
        Log.d("OdifyAudioFocus", "Media3 focus change: $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (player.playWhenReady) {
                    interruptedItem = player.currentMediaItem
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val interrupted = interruptedItem
                interruptedItem = null
                if (interrupted != null && interrupted == player.currentMediaItem) player.play()
            }
            AudioManager.AUDIOFOCUS_LOSS -> pause()
        }
    }

    private fun abandon() {
        val previous = request
        request = null
        interruptedItem = null
        if (previous != null) audio.abandonAudioFocusRequest(previous)
    }
}
