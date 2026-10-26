package com.odify.nativeapp

import androidx.media3.common.Player

/** Resolve identities at commit time: a delayed UI frame cannot move a different occurrence. */
internal fun odifyMoveUpcoming(player:Player,fromKey:String,toKey:String):Boolean {
    val keys=(0 until player.mediaItemCount).map {
        player.getMediaItemAt(it).mediaMetadata.extras?.getString("odify.queueEntry")
    }
    val from=keys.indexOf(fromKey);val to=keys.indexOf(toKey)
    if(from !in keys.indices || to !in keys.indices || from==to ||
        from<=player.currentMediaItemIndex || to<=player.currentMediaItemIndex)return false
    player.shuffleModeEnabled=false
    player.moveMediaItem(from,to)
    return true
}
