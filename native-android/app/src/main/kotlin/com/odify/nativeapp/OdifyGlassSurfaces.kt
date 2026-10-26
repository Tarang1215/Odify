package com.odify.nativeapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal val LocalOdifyModalTint=staticCompositionLocalOf {Color.Transparent}
internal val LocalOdifySheetExpanded=staticCompositionLocalOf {false}
private val LocalNeutralBackdrop=staticCompositionLocalOf<OdifyBackdrop?> {null}

internal data class OdifyModalSnapshot(val image:ImageBitmap,val origin:Offset,val size:IntSize,val frost:ImageBitmap=image)

private tailrec fun Context.modalActivity():Activity?=when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.modalActivity()
    else -> null
}

/** PixelCopy scales into a bounded buffer, including hardware-rendered artwork.
 * Copy only the source window, before opening the modal, never our own overlay. */
internal suspend fun odifyModalSnapshot(window:Window,transparency:Float=.76f):OdifyModalSnapshot? {
    val view=window.decorView
    if(view.width<=0 || view.height<=0)return null
    val width=view.width.coerceAtMost(480)
    val bitmap=Bitmap.createBitmap(width,(view.height.toFloat()*width/view.width).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
    val location=IntArray(2);view.getLocationOnScreen(location)
    val captured=suspendCancellableCoroutine<OdifyModalSnapshot?> {continuation ->
        try {PixelCopy.request(window,bitmap,{result ->
            if(continuation.isActive) {
                if(result==PixelCopy.SUCCESS) continuation.resume(OdifyModalSnapshot(bitmap.asImageBitmap(),
                    Offset(location[0].toFloat(),location[1].toFloat()),IntSize(view.width,view.height)))
                else {bitmap.recycle();continuation.resume(null)}
            } else bitmap.recycle()
        },Handler(Looper.getMainLooper()))}
        catch(_:IllegalArgumentException) {bitmap.recycle();if(continuation.isActive)continuation.resume(null)}
    }
    return captured?.let {frame ->withContext(Dispatchers.Default) {
        val radius=((3f+12f*(1-transparency))*view.resources.displayMetrics.density*width/view.width*.6f).roundToInt().coerceAtLeast(1)
        frame.copy(frost=OdifyModalRaster.blur(bitmap,radius).asImageBitmap())
    }}
}

/** Modal pixels are sampled once per opening. This works on phones with system
 * cross-window blur disabled, without continuously copying or blurring a screen. */
@Composable internal fun OdifyGlassDialog(onDismiss:()->Unit,tint:Color=LocalOdifyModalTint.current,
    content:@Composable ColumnScope.()->Unit) {
    val hostView=LocalView.current
    val context=LocalContext.current
    val sourceWindow=(hostView.parent as? DialogWindowProvider)?.window ?: context.modalActivity()?.window
    var snapshot by remember(sourceWindow) {mutableStateOf<OdifyModalSnapshot?>(null)}
    var captured by remember(sourceWindow) {mutableStateOf(false)}
    LaunchedEffect(sourceWindow) {snapshot=sourceWindow?.let {odifyModalSnapshot(it,.72f)};captured=true}
    if(!captured)return
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=true)) {
        val view=LocalView.current
        DisposableEffect(view) {
            val window=(view.parent as? DialogWindowProvider)?.window
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setDimAmount(.22f)
            onDispose {}
        }
        var origin by remember {mutableStateOf(Offset.Zero)}
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).onGloballyPositioned {origin=it.positionOnScreen()}) {
            OdifyFrozenGlassBackground(snapshot,origin,RoundedCornerShape(28.dp),tint,.72f,Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().heightIn(max=LocalContext.current.resources.configuration.screenHeightDp.dp*.86f)
                .padding(21.dp),content=content)
        }
    }
}

/** One retained glass surface grows from its control and returns on dismissal.
 * A single snapshot is shared for the whole gesture, including queue reorders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun OdifyGlassSheet(onDismiss:()->Unit,tint:Color=LocalOdifyModalTint.current,
    maxHeightFraction:Float=.82f,visible:Boolean=true,anchor:Offset?=null,transparency:Float=.76f,
    content:@Composable ColumnScope.()->Unit) {
    val host=LocalView.current
    val context=LocalContext.current
    val source=(host.parent as? DialogWindowProvider)?.window ?: context.modalActivity()?.window
    val bottomInset=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(16.dp)
    var snapshot by remember(source) {mutableStateOf<OdifyModalSnapshot?>(null)}
    var mounted by remember {mutableStateOf(false)}
    var backgroundReady by remember {mutableStateOf(false)}
    val progress=remember {Animatable(0f)}
    val fullyOpen by remember {derivedStateOf {progress.value>=.999f}}
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible,source) {
        if(visible) {
            if(!mounted) {backgroundReady=false;snapshot=source?.let {odifyModalSnapshot(it,transparency)};mounted=true}
            // Warm the static surface before animating it; the foreground is
            // still measured at native resolution while its glass is cached.
            withTimeoutOrNull(450) {snapshotFlow {backgroundReady}.first {it}}
            progress.animateTo(1f,tween(520,easing=CubicBezierEasing(.20f,.75f,.18f,1f)))
        } else {
            progress.animateTo(0f,tween(400,easing=FastOutSlowInEasing))
            mounted=false;snapshot=null
        }
    }
    if(!mounted)return
    val density=LocalDensity.current
    val scope=rememberCoroutineScope()
    val listBoundary=remember {object:NestedScrollConnection {
        override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource)=Offset(0f,available.y)
        override suspend fun onPostFling(consumed:Velocity,available:Velocity)=Velocity(0f,available.y)
    }}
    val expansion=remember {Animatable(0f)}
    var expanded by remember {mutableStateOf(false)}
    var collapsedHeight by remember {mutableIntStateOf(0)}
    val screenHeight=LocalConfiguration.current.screenHeightDp.dp
    val statusInset=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val fullHeight=screenHeight-statusInset-bottomInset
    val fullPx=with(density) {fullHeight.toPx()}
    fun settleExpansion(open:Boolean) {
        expanded=open
        scope.launch {expansion.animateTo(if(open)1f else 0f,tween(360,easing=FastOutSlowInEasing))}
    }
    // Start at the final Material anchor: our glass morph owns the entrance,
    // while Material still provides the tested window geometry and safe insets.
    val state=remember(density) {SheetState(skipPartiallyExpanded=true,density=density,initialValue=SheetValue.Expanded)}
    ModalBottomSheet(onDismissRequest={if(visible)currentDismiss()},sheetState=state,
        modifier=Modifier.padding(horizontal=12.dp*(1-expansion.value)),containerColor=Color.Transparent,
        scrimColor=Color.Black.copy(alpha=.20f*progress.value),tonalElevation=0.dp,
        shape=RoundedCornerShape(30.dp),dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        BackHandler(enabled=expanded) {settleExpansion(false)}
        var origin by remember {mutableStateOf(Offset.Zero)}
        var panelSize by remember {mutableStateOf(IntSize.Zero)}
        val circlePx=with(density) {50.dp.toPx()}
        // Keep refraction geometry fixed: animating its radius re-created two
        // full-size RenderEffects every frame. Only the outer GPU clip morphs.
        val shape=remember {RoundedCornerShape(30.dp)}
        Box(Modifier.onGloballyPositioned {origin=it.positionOnScreen();panelSize=it.size}) {
        Box(Modifier.fillMaxWidth().semantics {stateDescription=if(fullyOpen)"열림" else "전환 중"}.graphicsLayer {
            val p=progress.value
            val width=panelSize.width.toFloat().coerceAtLeast(1f)
            val height=panelSize.height.toFloat().coerceAtLeast(1f)
            val start=anchor ?: Offset(origin.x+width/2,origin.y+height+circlePx)
            transformOrigin=TransformOrigin.Center
            scaleX=circlePx/width+(1-circlePx/width)*p
            scaleY=circlePx/height+(1-circlePx/height)*p
            translationX=(start.x-(origin.x+width/2))*(1-p)
            translationY=(start.y-(origin.y+height/2))*(1-p)
            alpha=(p*4).coerceIn(.001f,1f)
            val initialRadius=minOf(width,height)/2
            this.shape=RoundedCornerShape((initialRadius+(30.dp.toPx()-initialRadius)*p).toDp())
            clip=true
        }) {
            OdifyFrozenGlassBackground(snapshot,origin,shape,tint,transparency,Modifier.matchParentSize(),onReady={backgroundReady=true})
            val expansionHeight=if(collapsedHeight>0 && expansion.value>0f) Modifier.height(
                with(density) {(collapsedHeight+(fullPx-collapsedHeight)*expansion.value).toDp()}) else Modifier
            Column(Modifier.fillMaxWidth().nestedScroll(listBoundary).then(expansionHeight)
                .heightIn(max=if(expansion.value>0f)fullHeight else screenHeight*maxHeightFraction)
                .onSizeChanged {if(expansion.value==0f)collapsedHeight=it.height}
                .padding(horizontal=16.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                var pulled by remember {mutableFloatStateOf(0f)}
                var dragStart by remember {mutableFloatStateOf(0f)}
                Box(Modifier.fillMaxWidth().height(32.dp)
                    .semantics {contentDescription=if(expanded)"작은 창으로 접기" else "전체 화면으로 펼치기"}
                    .clickable {settleExpansion(!expanded)}.pointerInput(visible,collapsedHeight) {
                    detectVerticalDragGestures(onDragStart={pulled=0f;dragStart=expansion.value},
                        onDragCancel={settleExpansion(expanded)},onDragEnd={
                            when {
                                dragStart==0f && pulled>circlePx -> currentDismiss()
                                pulled < -circlePx*.6f -> settleExpansion(true)
                                pulled > circlePx*.6f -> settleExpansion(false)
                                else -> settleExpansion(expansion.value>.5f)
                            }
                        },onVerticalDrag={change,amount ->
                            change.consume();pulled+=amount
                            val fraction=(dragStart-pulled/(fullPx-collapsedHeight).coerceAtLeast(circlePx*2)).coerceIn(0f,1f)
                            scope.launch {expansion.snapTo(fraction)}
                        })
                },contentAlignment=androidx.compose.ui.Alignment.Center) {
                    Box(Modifier.size(32.dp,4.dp).background(Color.White.copy(alpha=.30f),RoundedCornerShape(2.dp)))
                }
                CompositionLocalProvider(LocalContentColor provides Color(0xFFF5F6F7),LocalOdifySheetExpanded provides (expansion.value>0f)) {
                    Column(Modifier.weight(1f,fill=expansion.value>0f).graphicsLayer {alpha=((progress.value-.22f)/.78f).coerceIn(0f,1f)}) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp)) {content()}
                    }
                }
            }
        }
        }
        Spacer(Modifier.height((bottomInset+10.dp)*(1-expansion.value)))
    }
}

/** Viewport sampling stays at window scale while the panel moves or changes height. */
@Composable private fun OdifyFrozenGlassBackground(frame:OdifyModalSnapshot?,origin:Offset,shape:Shape,tint:Color,
    transparency:Float,modifier:Modifier=Modifier,onReady:()->Unit={}) {
    val lens=remember(frame) {if(frame!=null && android.os.Build.VERSION.SDK_INT>=33)
        runCatching {OdifySnapshotLens(frame)}.getOrNull() else null}
    val paint=remember {android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)}
    val readyCallback by rememberUpdatedState(onReady)
    LaunchedEffect(frame) {readyCallback()}
    Canvas(modifier.clip(shape)
        .border(.45.dp,Brush.verticalGradient(listOf(Color.White.copy(alpha=.22f),Color.White.copy(alpha=.035f),Color.White.copy(alpha=.14f))),shape)) {
        drawRect(Color(0xFF171A21))
        if(frame!=null && lens!=null && android.os.Build.VERSION.SDK_INT>=33) {
            val outline=shape.createOutline(size,layoutDirection,this)
            val radius=(outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 30.dp.toPx()
            lens.update(origin,size.width,size.height,radius,density,transparency)
            paint.shader=lens.shader
            drawContext.canvas.nativeCanvas.drawRect(0f,0f,size.width,size.height,paint)
        } else if(frame!=null) {
            drawImage(frame.frost,dstOffset=IntOffset((frame.origin.x-origin.x).roundToInt(),(frame.origin.y-origin.y).roundToInt()),
                dstSize=frame.size,filterQuality=FilterQuality.Medium)
            drawRect(Color.Black.copy(alpha=(.035f+1.08f*(1-transparency)*(1-transparency)).coerceAtMost(.88f)))
        }
        if(tint.alpha>0)drawRect(Brush.radialGradient(listOf(tint.copy(alpha=.10f),Color.Transparent),
            center=Offset(size.width*.22f,size.height*.8f),radius=size.width*.85f))
    }
}

/** Keep the last item alive until the shared closing animation has finished. */
@Composable internal fun <T:Any> OdifyRetainedGlassSheet(item:T?,onDismiss:()->Unit,
    tint:Color=LocalOdifyModalTint.current,maxHeightFraction:Float=.82f,transparency:Float=.76f,
    content:@Composable ColumnScope.(T)->Unit) {
    var retained by remember {mutableStateOf(item)}
    SideEffect {if(item!=null)retained=item}
    val displayed=item ?: retained ?: return
    OdifyGlassSheet(onDismiss,tint,maxHeightFraction,visible=item!=null,transparency=transparency) {content(displayed)}
}

/** Overflow belongs beside its top control, rather than travelling up from the footer. */
@Composable internal fun OdifyGlassAnchoredMenu(expanded:Boolean,onDismissRequest:()->Unit,
    content:@Composable ColumnScope.()->Unit) {
    val view=LocalView.current
    val context=LocalContext.current
    val source=(view.parent as? DialogWindowProvider)?.window ?: context.modalActivity()?.window
    var snapshot by remember {mutableStateOf<OdifyModalSnapshot?>(null)}
    var mounted by remember {mutableStateOf(false)}
    var backgroundReady by remember {mutableStateOf(false)}
    val motion=remember {Animatable(0f)}
    val fullyOpen by remember {derivedStateOf {motion.value>=.999f}}
    LaunchedEffect(expanded) {
        if(expanded) {
            if(!mounted) {backgroundReady=false;snapshot=source?.let {odifyModalSnapshot(it)};mounted=true}
            withTimeoutOrNull(450) {snapshotFlow {backgroundReady}.first {it}}
            motion.animateTo(1f,tween(360,easing=FastOutSlowInEasing))
        } else {
            motion.animateTo(0f,tween(240,easing=FastOutSlowInEasing));mounted=false;snapshot=null
        }
    }
    if(!mounted)return
    val gap=with(LocalDensity.current) {8.dp.roundToPx()}
    val position=remember(gap) {object:PopupPositionProvider {
        override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset =
            IntOffset((anchorBounds.right-popupContentSize.width).coerceIn(gap,(windowSize.width-popupContentSize.width-gap).coerceAtLeast(gap)),
                (anchorBounds.bottom+gap).coerceAtMost((windowSize.height-popupContentSize.height-gap).coerceAtLeast(gap)))
    }}
    Popup(popupPositionProvider=position,onDismissRequest=onDismissRequest,properties=PopupProperties(focusable=true)) {
        var origin by remember {mutableStateOf(Offset.Zero)}
        val shape=RoundedCornerShape(26.dp)
        Box(Modifier.width(310.dp).heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.78f)
            .semantics {stateDescription=if(fullyOpen)"열림" else "전환 중"}
            .onGloballyPositioned {origin=it.positionOnScreen()}.graphicsLayer {
                transformOrigin=TransformOrigin(1f,0f);scaleX=.90f+.10f*motion.value;scaleY=.90f+.10f*motion.value
                alpha=motion.value;this.shape=shape;clip=true
            }) {
            OdifyFrozenGlassBackground(snapshot,origin,shape,LocalOdifyModalTint.current,.76f,Modifier.matchParentSize(),onReady={backgroundReady=true})
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),content=content)
        }
    }
}

@Composable internal fun OdifySheetTrackHeader(song:OdifySong) {
    Row(Modifier.fillMaxWidth().padding(start=6.dp,end=6.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
        AlbumArt(song,Modifier.size(44.dp),RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f).padding(start=12.dp)) {
            Text(song.title,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(song.artist,fontSize=11.sp,color=Color.White.copy(alpha=.62f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color=Color.White.copy(alpha=.10f),modifier=Modifier.padding(bottom=6.dp))
}

@Composable internal fun OdifyGlassAlertDialog(onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,
    modifier:Modifier=Modifier,dismissButton:(@Composable ()->Unit)?=null,
    icon:(@Composable ()->Unit)?=null,title:(@Composable ()->Unit)?=null,text:(@Composable ()->Unit)?=null,
    shape:Shape=RoundedCornerShape(28.dp),containerColor:Color=Color.Transparent) {
    OdifyGlassDialog(onDismissRequest) {
        Column(modifier,verticalArrangement=Arrangement.spacedBy(15.dp)) {
            icon?.invoke();title?.invoke()
            text?.let {body -> Box(Modifier.weight(1f,fill=false)) {body()}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                dismissButton?.invoke();Spacer(Modifier.width(8.dp));confirmButton()
            }
        }
    }
}

@Composable internal fun OdifyGlassMenu(expanded:Boolean,onDismissRequest:()->Unit,
    shape:Shape=RoundedCornerShape(24.dp),containerColor:Color=Color.Transparent,
    anchor:Offset?=null,
    content:@Composable ColumnScope.()->Unit) {
    OdifyGlassSheet(onDismissRequest,maxHeightFraction=.72f,visible=expanded,anchor=anchor,content=content)
}

/** Neutral source has no connection to album color, including while scrolling. */
@Composable internal fun OdifyNeutralScene(content:@Composable ()->Unit) {
    val backdrop=rememberOdifyBackdrop()
    Box(Modifier.fillMaxSize().nestedScroll(backdrop.scrolling)) {
        Box(Modifier.fillMaxSize().recordOdifyBackdrop(backdrop).background(Brush.linearGradient(
            listOf(Color(0xFF101217),Color(0xFF30343C),Color(0xFF111318)))))
        CompositionLocalProvider(LocalNeutralBackdrop provides backdrop,LocalOdifyModalTint provides Color.Transparent,content=content)
    }
}

internal fun Modifier.odifyNeutralPanel(radius:Int=20):Modifier=composed {
    val backdrop=LocalNeutralBackdrop.current
    val shape=RoundedCornerShape(radius.dp)
    if(backdrop!=null) this.odifyGlass(backdrop,shape,transparencyOverride=.72f)
    else this.background(Color.White.copy(alpha=.035f),shape).border(.5.dp,Color.White.copy(alpha=.09f),shape)
}
