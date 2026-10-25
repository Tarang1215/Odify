package com.odify.nativeapp

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

internal val LocalOdifyModalTint=staticCompositionLocalOf {Color.Transparent}
private val LocalNeutralBackdrop=staticCompositionLocalOf<OdifyBackdrop?> {null}

/** Dialog windows use Android's live cross-window blur, not a screenshot of
 * another Compose owner. One bounded native blur is created only while open. */
@Composable internal fun OdifyGlassDialog(onDismiss:()->Unit,tint:Color=LocalOdifyModalTint.current,
    content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=true)) {
        val view=LocalView.current
        val context=LocalContext.current
        val density=LocalDensity.current
        DisposableEffect(view,tint) {
            val window=(view.parent as? DialogWindowProvider)?.window
            val wm=context.getSystemService(WindowManager::class.java)
            val enabled=Build.VERSION.SDK_INT>=31 && wm.isCrossWindowBlurEnabled
            val glow=if(tint.alpha>0)tint else Color(0xFFCCD2DC)
            val bg=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(
                glow.copy(alpha=if(enabled).14f else .14f).compositeOver(Color(0xFF20232A).copy(alpha=if(enabled).52f else .96f)).toArgb(),
                Color(0xFF14171D).copy(alpha=if(enabled).66f else .98f).toArgb()))
            bg.cornerRadius=with(density){28.dp.toPx()};bg.setStroke(with(density){1.dp.toPx()}.toInt(),Color.White.copy(alpha=.18f).toArgb())
            window?.setBackgroundDrawable(bg)
            window?.setDimAmount(.22f)
            if(Build.VERSION.SDK_INT>=31 && enabled) {
                window?.setBackgroundBlurRadius(with(density){20.dp.toPx()}.toInt().coerceAtMost(90))
                window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window?.attributes=window?.attributes?.apply {blurBehindRadius=16}
            }
            onDispose {if(Build.VERSION.SDK_INT>=31)window?.setBackgroundBlurRadius(0)}
        }
        Column(Modifier.fillMaxWidth().heightIn(max=LocalContext.current.resources.configuration.screenHeightDp.dp*.86f)
            .padding(21.dp),content=content)
    }
}

@Composable internal fun OdifyGlassAlertDialog(onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,
    modifier:Modifier=Modifier,dismissButton:(@Composable ()->Unit)?=null,
    icon:(@Composable ()->Unit)?=null,title:(@Composable ()->Unit)?=null,text:(@Composable ()->Unit)?=null,
    shape:Shape=RoundedCornerShape(28.dp),containerColor:Color=Color.Transparent) {
    OdifyGlassDialog(onDismissRequest) {
        Column(modifier,verticalArrangement=Arrangement.spacedBy(15.dp)) {
            icon?.invoke();title?.invoke();text?.invoke()
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
