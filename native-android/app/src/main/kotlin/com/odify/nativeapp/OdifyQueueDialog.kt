package com.odify.nativeapp

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius

private data class QueuedTrack(val key:String,val song:OdifySong)

/** One reorder is committed by stable entry identity when the finger is released. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OdifyQueueDialog(items:List<OdifySong>,currentIndex:Int,
    onMove:(String,String)->Unit,onSelect:(Int)->Unit,onClose:()->Unit,
    entryKeys:List<String> = emptyList(),glow:Color=Color.Transparent,
    visible:Boolean=true,anchor:Offset?=null) {
    val incoming=remember(items,entryKeys) {
        items.mapIndexed {index,song -> QueuedTrack(entryKeys.getOrNull(index) ?: "${song.id}#$index",song)}
    }
    val currentKey=incoming.getOrNull(currentIndex)?.key
    val upcoming=remember(incoming,currentIndex) {incoming.drop((currentIndex+1).coerceAtLeast(0))}
    val latestUpcoming by rememberUpdatedState(upcoming)
    var ordered by remember {mutableStateOf(upcoming)}
    var dragging by remember {mutableStateOf<String?>(null)}
    var pointerY by remember {mutableFloatStateOf(0f)}
    var dragTop by remember {mutableFloatStateOf(0f)}
    var pending by remember {mutableStateOf<List<String>?>(null)}
    var settling by remember {mutableStateOf<String?>(null)}
    val dropOffset=remember {Animatable(0f)}
    val scope=rememberCoroutineScope()
    var dropJob by remember {mutableStateOf<Job?>(null)}
    val list=rememberLazyListState()
    val density=LocalDensity.current
    val rowPx=with(density) {68.dp.toPx()}
    val edgePx=with(density) {64.dp.toPx()}
    val latestOnMove by rememberUpdatedState(onMove)
    LaunchedEffect(upcoming,currentKey,dragging) {
        if(dragging==null) {
            if(pending==null || upcoming.map {it.key}==pending) {ordered=upcoming;pending=null}
        }
    }
    LaunchedEffect(currentKey) {dragging=null;pending=null;ordered=upcoming}
    // A controller rejection or a concurrently replaced queue must not leave a fictitious order.
    LaunchedEffect(pending) {if(pending!=null) {delay(1200);pending=null;ordered=latestUpcoming}}
    fun updateTarget() {
        val key=dragging ?: return
        val from=ordered.indexOfFirst {it.key==key}
        val center=dragTop+rowPx/2
        val target=list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key!=key && center>=it.offset && center<=it.offset+it.size
        } ?: return
        val to=ordered.indexOfFirst {it.key==target.key}
        if(from>=0 && to>=0 && from!=to) {
            // LazyColumn otherwise anchors the first visible key to its NEW
            // index, scrolling with the dragged first row and overshooting.
            list.requestScrollToItem(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)
            ordered=ordered.moved(from,to)
        }
    }
    LaunchedEffect(dragging) {
        while(dragging!=null && isActive) {
            val end=list.layoutInfo.viewportEndOffset.toFloat()
            val start=list.layoutInfo.viewportStartOffset.toFloat()
            val delta=when {
                pointerY>end-edgePx -> ((pointerY-(end-edgePx))/edgePx).coerceIn(0f,1f)*with(density){13.dp.toPx()}
                pointerY<start+edgePx -> -((start+edgePx-pointerY)/edgePx).coerceIn(0f,1f)*with(density){13.dp.toPx()}
                else -> 0f
            }
            if(delta!=0f) {list.scroll {scrollBy(delta)};updateTarget()}
            delay(16)
        }
    }
    fun finish(commit:Boolean) {
        val key=dragging
        val from=latestUpcoming.indexOfFirst {it.key==key}
        val to=ordered.indexOfFirst {it.key==key}
        if(commit && key!=null && from>=0 && to in latestUpcoming.indices && from!=to) {
            pending=ordered.map {it.key}
            latestOnMove(key,latestUpcoming[to].key)
        } else {ordered=latestUpcoming;pending=null}
        val offset=dragTop-(list.layoutInfo.visibleItemsInfo.firstOrNull {it.key==key}?.offset ?: dragTop.toInt())
        dropJob?.cancel()
        dropJob=scope.launch {
            dropOffset.snapTo(offset)
            settling=key;dragging=null
            dropOffset.animateTo(0f,spring(dampingRatio=1f,stiffness=320f))
            settling=null
        }
    }
    OdifyGlassSheet(onClose,tint=glow,maxHeightFraction=.84f,visible=visible,anchor=anchor) {
        Row(Modifier.fillMaxWidth().padding(start=4.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("다음 재생",fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                Text("${upcoming.size}곡 · 손잡이를 끌어 순서 변경",fontSize=11.sp,color=Color.White.copy(alpha=.58f),modifier=Modifier.padding(top=4.dp))
            }
            IconButton(onClick=onClose,modifier=Modifier.size(44.dp)) {Icon(Icons.Default.Close,"대기열 닫기",Modifier.size(21.dp))}
        }
        incoming.getOrNull(currentIndex)?.let {current ->
            Text("현재 곡",fontSize=10.sp,color=Color.White.copy(alpha=.56f),modifier=Modifier.fillMaxWidth().padding(start=8.dp,bottom=8.dp))
            OdifySheetTrackHeader(current.song)
        }
        if(upcoming.isEmpty()) {
            Text("다음 곡이 없습니다",fontSize=14.sp,color=Color.White.copy(alpha=.72f),modifier=Modifier.padding(vertical=30.dp))
        } else LazyColumn(state=list,modifier=Modifier.fillMaxWidth().weight(1f,fill=LocalOdifySheetExpanded.current)
            .heightIn(max=if(LocalOdifySheetExpanded.current) androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp else 420.dp),contentPadding=PaddingValues(bottom=12.dp)) {
            items(ordered,key={it.key}) {queued ->
                val selected=dragging==queued.key || settling==queued.key
                val lift by animateFloatAsState(if(selected)1f else 0f,spring(dampingRatio=1f,stiffness=380f),label="queue-lift")
                Row(Modifier.fillMaxWidth().height(68.dp)
                    .animateItem(fadeInSpec=null,fadeOutSpec=null,placementSpec=if(selected)null else spring(dampingRatio=1f,stiffness=340f))
                    .zIndex(if(selected)1f else 0f)
                    .graphicsLayer {
                        val info=list.layoutInfo.visibleItemsInfo.firstOrNull {it.key==queued.key}
                        translationY=when(queued.key) {dragging->dragTop-(info?.offset ?: 0);settling->dropOffset.value;else->0f}
                        scaleX=1f+.015f*lift;scaleY=1f+.015f*lift
                    }
                    .drawBehind {drawRoundRect(Color.White.copy(alpha=.12f*lift),cornerRadius=CornerRadius(16.dp.toPx()))}
                    .clickable(enabled=dragging==null) {val index=incoming.indexOfFirst {it.key==queued.key};if(index>=0)onSelect(index)}
                    .padding(start=8.dp,end=2.dp),verticalAlignment=Alignment.CenterVertically) {
                    AlbumArt(queued.song,Modifier.size(43.dp),RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                        Text(queued.song.title,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(queued.song.artist,fontSize=11.sp,color=Color.White.copy(alpha=.58f),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
                    }
                    Box(Modifier.size(48.dp).semantics {
                        contentDescription="${queued.song.title} 순서 이동"
                        customActions=listOf(CustomAccessibilityAction("위로 이동") {
                            val i=upcoming.indexOfFirst {it.key==queued.key};if(i>0){latestOnMove(queued.key,upcoming[i-1].key);true}else false
                        },CustomAccessibilityAction("아래로 이동") {
                            val i=upcoming.indexOfFirst {it.key==queued.key};if(i>=0 && i<upcoming.lastIndex){latestOnMove(queued.key,upcoming[i+1].key);true}else false
                        })
                    }.pointerInput(queued.key) {
                        detectDragGestures(onDragStart={position ->
                            val row=list.layoutInfo.visibleItemsInfo.firstOrNull {it.key==queued.key}
                            if(row!=null) {dropJob?.cancel();settling=null;dragging=queued.key;dragTop=row.offset.toFloat();pointerY=dragTop+position.y}
                        },onDragCancel={finish(false)},onDragEnd={finish(true)},onDrag={change,movement ->
                            change.consume();dragTop+=movement.y;pointerY+=movement.y;updateTarget()
                        })
                    },contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.DragHandle,null,tint=Color.White.copy(alpha=.58f),modifier=Modifier.size(23.dp))
                    }
                }
            }
        }
    }
}
