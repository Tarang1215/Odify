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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
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
                .odifyGlass(backdrop,RoundedCornerShape(28.dp),tint,transparencyOverride=.48f)
                .padding(21.dp),content=content)
        }
    }
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
    content:@Composable ColumnScope.()->Unit) {
    if(expanded) OdifyGlassDialog(onDismissRequest,content=content)
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
