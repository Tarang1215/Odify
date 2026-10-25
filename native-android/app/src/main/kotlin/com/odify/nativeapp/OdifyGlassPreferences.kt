package com.odify.nativeapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Stable
internal class OdifyGlassPreferences(context:Context) {
    private val prefs=context.getSharedPreferences("odify_glass_v1",Context.MODE_PRIVATE)
    var navigation by mutableFloatStateOf(prefs.getFloat("navigation",.72f).coerceIn(0f,1f))
    var player by mutableFloatStateOf(prefs.getFloat("player",.72f).coerceIn(0f,1f))
    fun save(){prefs.edit().putFloat("navigation",navigation).putFloat("player",player).apply()}
    fun reset(){navigation=.72f;player=.72f;save()}
}

internal val LocalOdifyGlassPreferences=staticCompositionLocalOf<OdifyGlassPreferences?> {null}

@Composable
internal fun OdifyGlassPreferencesProvider(content:@Composable ()->Unit) {
    val context=LocalContext.current.applicationContext
    val values=remember(context){OdifyGlassPreferences(context)}
    CompositionLocalProvider(LocalOdifyGlassPreferences provides values,content=content)
}

@Composable
internal fun OdifyGlassSettingsCard() {
    val values=LocalOdifyGlassPreferences.current ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .odifyNeutralPanel().padding(18.dp)) {
        Text("Liquid Glass",fontSize=16.sp,fontWeight=FontWeight.SemiBold,color=OdifyPalette.text)
        Spacer(Modifier.height(6.dp))
        Text("앨범 색과 배경 블러를 유지하며 유리의 투명도를 조절해요.",
            fontSize=11.sp,lineHeight=17.sp,color=OdifyPalette.muted)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("네비게이션 바",fontSize=13.sp,color=OdifyPalette.text)
            Text("${(values.navigation*100).roundToInt()}%",fontSize=12.sp,color=OdifyPalette.muted)
        }
        Slider(value=values.navigation,onValueChange={values.navigation=it},onValueChangeFinished=values::save,
            modifier=Modifier.fillMaxWidth().semantics{contentDescription="네비게이션 바 투명도"})
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("뮤직 플레이어",fontSize=13.sp,color=OdifyPalette.text)
            Text("${(values.player*100).roundToInt()}%",fontSize=12.sp,color=OdifyPalette.muted)
        }
        Slider(value=values.player,onValueChange={values.player=it},onValueChangeFinished=values::save,
            modifier=Modifier.fillMaxWidth().semantics{contentDescription="뮤직 플레이어 투명도"})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("차분하게",fontSize=10.sp,color=OdifyPalette.muted)
            Text("더 투명하게",fontSize=10.sp,color=OdifyPalette.muted)
        }
        TextButton(onClick=values::reset,modifier=Modifier.fillMaxWidth()) {Text("기본값으로 되돌리기")}
    }
}
