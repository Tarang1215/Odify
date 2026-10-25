package com.odify.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual height/type/radius with a larger, consistent touch target. */
@Composable fun OdifyPill(label:String,selected:Boolean,onClick:()->Unit) {
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(17.dp))
        .clickable(role=Role.Tab,onClick=onClick).semantics {this.selected=selected}
        .padding(vertical=5.dp),contentAlignment=Alignment.Center) {
        Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
            .background(if(selected) OdifyPalette.text else OdifyPalette.surface)
            .padding(horizontal=14.dp),contentAlignment=Alignment.Center) {
            Text(label,fontSize=11.sp,color=if(selected) OdifyPalette.background else OdifyPalette.muted,maxLines=1)
        }
    }
}
