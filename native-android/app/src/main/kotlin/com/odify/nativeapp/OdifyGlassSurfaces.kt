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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private val LocalNeutralBackdrop=staticCompositionLocalOf<OdifyBackdrop?> {null}

internal data class OdifyModalSnapshot(val image:ImageBitmap,val origin:Offset,val size:IntSize)

private tailrec fun Context.modalActivity():Activity?=when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.modalActivity()
    else -> null
}

/** PixelCopy scales into a bounded buffer, including hardware-rendered artwork.
 * Copy only the source window, before opening the modal, never our own overlay. */
internal suspend fun odifyModalSnapshot(window:Window):OdifyModalSnapshot? {
    val view=window.decorView
    if(view.width<=0 || view.height<=0)return null
    val width=view.width.coerceAtMost(720)
    val bitmap=Bitmap.createBitmap(width,(view.height.toFloat()*width/view.width).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
    val location=IntArray(2);view.getLocationOnScreen(location)
    return suspendCancellableCoroutine {continuation ->
        try {PixelCopy.request(window,bitmap,{result ->
            if(continuation.isActive) {
                if(result==PixelCopy.SUCCESS) continuation.resume(OdifyModalSnapshot(bitmap.asImageBitmap(),
                    Offset(location[0].toFloat(),location[1].toFloat()),IntSize(view.width,view.height)))
                else {bitmap.recycle();continuation.resume(null)}
            } else bitmap.recycle()
        },Handler(Looper.getMainLooper()))}
        catch(_:IllegalArgumentException) {bitmap.recycle();if(continuation.isActive)continuation.resume(null)}
    }
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
    LaunchedEffect(sourceWindow) {snapshot=sourceWindow?.let {odifyModalSnapshot(it)};captured=true}
    if(!captured)return
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=true)) {
        val view=LocalView.current
        DisposableEffect(view) {
            val window=(view.parent as? DialogWindowProvider)?.window
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setDimAmount(.22f)
            onDispose {}
        }
        val backdrop=rememberOdifyBackdrop()
        var origin by remember {mutableStateOf(Offset.Zero)}
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).onGloballyPositioned {origin=it.positionOnScreen()}) {
            Canvas(Modifier.matchParentSize().recordOdifyBackdrop(backdrop)) {
                drawRect(Color(0xFF171A21))
                snapshot?.let {frame -> drawImage(frame.image,
                    dstOffset=IntOffset((frame.origin.x-origin.x).roundToInt(),(frame.origin.y-origin.y).roundToInt()),
                    dstSize=frame.size)}
            }
            Column(Modifier.fillMaxWidth().heightIn(max=LocalContext.current.resources.configuration.screenHeightDp.dp*.86f)
                .odifyGlass(backdrop,RoundedCornerShape(28.dp),tint,transparencyOverride=.72f)
                .padding(21.dp),content=content)
        }
    }
}

/** One retained glass surface grows from its control and returns on dismissal.
 * A single snapshot is shared for the whole gesture, including queue reorders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun OdifyGlassSheet(onDismiss:()->Unit,tint:Color=LocalOdifyModalTint.current,
    maxHeightFraction:Float=.82f,visible:Boolean=true,anchor:Offset?=null,content:@Composable ColumnScope.()->Unit) {
    val host=LocalView.current
    val context=LocalContext.current
    val source=(host.parent as? DialogWindowProvider)?.window ?: context.modalActivity()?.window
    val bottomInset=WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(16.dp)
    var snapshot by remember(source) {mutableStateOf<OdifyModalSnapshot?>(null)}
    var mounted by remember {mutableStateOf(false)}
    val progress=remember {Animatable(0f)}
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible,source) {
        if(visible) {
            if(!mounted) {snapshot=source?.let {odifyModalSnapshot(it)};mounted=true}
            progress.animateTo(1f,tween(520,easing=CubicBezierEasing(.20f,.75f,.18f,1f)))
        } else {
            progress.animateTo(0f,tween(400,easing=FastOutSlowInEasing))
            mounted=false;snapshot=null
        }
    }
    if(!mounted)return
    val density=LocalDensity.current
    // Start at the final Material anchor: our glass morph owns the entrance,
    // while Material still provides the tested window geometry and safe insets.
    val state=remember(density) {SheetState(skipPartiallyExpanded=true,density=density,initialValue=SheetValue.Expanded)}
    ModalBottomSheet(onDismissRequest={if(visible)currentDismiss()},sheetState=state,
        modifier=Modifier.padding(horizontal=12.dp),containerColor=Color.Transparent,
        scrimColor=Color.Black.copy(alpha=.20f*progress.value),tonalElevation=0.dp,
        shape=RoundedCornerShape(30.dp),dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        val backdrop=rememberOdifyBackdrop()
        var origin by remember {mutableStateOf(Offset.Zero)}
        var panelSize by remember {mutableStateOf(IntSize.Zero)}
        val circlePx=with(density) {50.dp.toPx()}
        val p=progress.value
        val initialRadius=with(density) {minOf(panelSize.width,panelSize.height).toDp()/2}
        // Outline.Rounded supplies the correct radius to the refraction shader;
        // a generic path otherwise falls back to a giant circular lens.
        val shape=RoundedCornerShape(30.dp+(initialRadius-30.dp)*(1-p))
        Box(Modifier.onGloballyPositioned {origin=it.positionOnScreen();panelSize=it.size}) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            val width=panelSize.width.toFloat().coerceAtLeast(1f)
            val height=panelSize.height.toFloat().coerceAtLeast(1f)
            val start=anchor ?: Offset(origin.x+width/2,origin.y+height+circlePx)
            transformOrigin=TransformOrigin.Center
            scaleX=circlePx/width+(1-circlePx/width)*p
            scaleY=circlePx/height+(1-circlePx/height)*p
            translationX=(start.x-(origin.x+width/2))*(1-p)
            translationY=(start.y-(origin.y+height/2))*(1-p)
            alpha=(p*4).coerceIn(0f,1f)
        }.clip(shape)) {
            Canvas(Modifier.matchParentSize().recordOdifyBackdrop(backdrop)) {
                drawRect(Color(0xFF171A21))
                snapshot?.let {frame -> drawImage(frame.image,
                    dstOffset=IntOffset((frame.origin.x-origin.x).roundToInt(),(frame.origin.y-origin.y).roundToInt()),
                    dstSize=frame.size)}
            }
            Column(Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*maxHeightFraction)
                .odifyGlass(backdrop,shape,tint,transparencyOverride=.78f)
                .padding(horizontal=16.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                var pulled by remember {mutableFloatStateOf(0f)}
                Box(Modifier.fillMaxWidth().height(22.dp).pointerInput(visible) {
                    detectVerticalDragGestures(onDragStart={pulled=0f},onDragEnd={if(pulled>circlePx)currentDismiss()},
                        onVerticalDrag={change,amount ->change.consume();pulled+=amount})
                },contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
                    Box(Modifier.size(32.dp,4.dp).background(Color.White.copy(alpha=.30f),RoundedCornerShape(2.dp)))
                }
                CompositionLocalProvider(LocalContentColor provides Color(0xFFF5F6F7)) {
                    Column(Modifier.graphicsLayer {alpha=((p-.22f)/.78f).coerceIn(0f,1f)}) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp)) {content()}
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(bottomInset+10.dp))
    }
}

/** Keep the last item alive until the shared closing animation has finished. */
@Composable internal fun <T:Any> OdifyRetainedGlassSheet(item:T?,onDismiss:()->Unit,
    tint:Color=LocalOdifyModalTint.current,maxHeightFraction:Float=.82f,content:@Composable ColumnScope.(T)->Unit) {
    var retained by remember {mutableStateOf(item)}
    SideEffect {if(item!=null)retained=item}
    val displayed=item ?: retained ?: return
    OdifyGlassSheet(onDismiss,tint,maxHeightFraction,visible=item!=null) {content(displayed)}
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
