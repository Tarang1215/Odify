package com.odify.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex

/** Keep expensive page composition, animate its render layer, and leave hidden
 * pages unplaced and inaccessible. No list remeasurement on every motion frame. */
@Composable internal fun OdifyPageLayer(active:Boolean,initiallyVisible:Boolean,direction:Int,
    onHidden:()->Unit,content:@Composable ()->Unit) {
    val motion=remember {Animatable(if(initiallyVisible)1f else 0f)}
    val currentHidden by rememberUpdatedState(onHidden)
    val moving by remember {derivedStateOf {motion.value>.001f}}
    val place=active || moving
    LaunchedEffect(active) {
        motion.animateTo(if(active)1f else 0f,tween(210,easing=FastOutSlowInEasing))
        if(!active)currentHidden()
    }
    Box(Modifier.fillMaxSize().zIndex(if(active)1f else if(place)0f else -1f)
        .then(if(active)Modifier else Modifier.clearAndSetSemantics {})
        .layout {measurable,constraints ->
            val child=if(place)measurable.measure(constraints) else null
            layout(constraints.maxWidth,constraints.maxHeight) {child?.place(0,0)}
        }
        .graphicsLayer {
            alpha=motion.value
            translationX=(1-motion.value)*size.width*.12f*(if(active)direction else -direction)
        }
        // Register the active page's entire area for hit testing, so an empty
        // area cannot click through to an outgoing page during its fade.
        .pointerInput(Unit) {awaitPointerEventScope {while(true)awaitPointerEvent()}}) {content()}
}
