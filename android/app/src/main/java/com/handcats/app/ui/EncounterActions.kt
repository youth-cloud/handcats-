package com.handcats.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.handcats.app.ui.theme.Muted
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val HeartRed = Color(0xFFE34D63)

@Composable fun HeartIcon(modifier: Modifier = Modifier, filled: Boolean = false) {
    Canvas(modifier.size(24.dp)) {
        val path = Path().apply {
            moveTo(size.width*.5f,size.height*.88f)
            cubicTo(-size.width*.2f,size.height*.42f,size.width*.16f,-size.height*.1f,size.width*.5f,size.height*.25f)
            cubicTo(size.width*.84f,-size.height*.1f,size.width*1.2f,size.height*.42f,size.width*.5f,size.height*.88f)
            close()
        }
        if(filled) drawPath(path,HeartRed) else drawPath(path,Muted,style=Stroke(1.7.dp.toPx()))
    }
}

@Composable fun FavoriteButton(favorite: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(favorite) }
    LaunchedEffect(favorite) {
        if(favorite != previous) {
            previous = favorite
            launch {
                scale.snapTo(if(favorite) .78f else 1.12f)
                scale.animateTo(1f,spring(dampingRatio=.38f,stiffness=450f))
            }
            if(favorite) { burst.snapTo(0f); burst.animateTo(1f,tween(650,easing=FastOutSlowInEasing)) }
        }
    }
    Box(contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(48.dp)) {
            if(burst.value < 1f) repeat(12) { i ->
                val angle=i*Math.PI*2/12
                val distance=(10+22*burst.value).dp.toPx()
                val point=center+Offset(cos(angle).toFloat()*distance,sin(angle).toFloat()*distance)
                drawCircle(HeartRed.copy(alpha=1-burst.value), (2.8f*(1-burst.value)+.4f).dp.toPx(),point)
            }
        }
        IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.semantics {
            contentDescription=if(favorite)"取消收藏" else "收藏照片"
            stateDescription=if(favorite)"已收藏" else "未收藏"
        }) {
            HeartIcon(Modifier.size(25.dp).graphicsLayer { scaleX=scale.value;scaleY=scale.value },favorite)
        }
    }
}

@Composable fun HoldToDeleteButton(recordId: String, enabled: Boolean = true, onDelete: () -> Unit) {
    val progress = remember(recordId) { Animatable(0f) }
    var confirmed by remember(recordId) { mutableStateOf(false) }
    val currentDelete by rememberUpdatedState(onDelete)
    val haptic=LocalHapticFeedback.current
    val danger=MaterialTheme.colorScheme.error
    // Re-enable after a failed request. While a request is in flight, the parent
    // disables this control and the confirmed state stays visible.
    LaunchedEffect(enabled) {
        if(enabled && (confirmed || progress.value>0f)) { confirmed=false;progress.animateTo(0f,tween(220)) }
    }
    Box(Modifier.fillMaxWidth().heightIn(min=56.dp).clip(RoundedCornerShape(18.dp))
        .background(danger.copy(alpha=.045f))
        .drawWithContent {
            drawContent()
            val inset=1.5.dp.toPx()
            val path=Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(
                inset,inset,size.width-inset,size.height-inset,18.dp.toPx(),18.dp.toPx())) }
            drawPath(path,danger.copy(alpha=.18f),style=Stroke(1.dp.toPx()))
            val measure=PathMeasure().apply { setPath(path,false) }
            val filled=Path()
            measure.getSegment(0f,measure.length*progress.value,filled,true)
            drawPath(filled,danger,style=Stroke(3.dp.toPx(),cap=StrokeCap.Round))
        }
        .semantics {
            contentDescription="长按删除这次相遇"
            stateDescription=if(confirmed)"已确认删除" else "按住直至边框填满，松开取消"
            progressBarRangeInfo=ProgressBarRangeInfo(progress.value,0f..1f)
            if(!enabled) disabled()
            onLongClick(label="确认删除这次相遇") {
                if(enabled && !confirmed) { confirmed=true;haptic.performHapticFeedback(HapticFeedbackType.LongPress);currentDelete();true } else false
            }
        }
        .pointerInput(enabled,recordId) {
            if(enabled) detectTapGestures(onPress={
                if(!confirmed) coroutineScope {
                    val filling=launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f,tween(1400,easing=LinearEasing))
                        confirmed=true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentDelete()
                    }
                    try {
                        tryAwaitRelease()
                        if(!confirmed) { filling.cancelAndJoin();progress.animateTo(0f,spring(dampingRatio=.8f,stiffness=550f)) }
                    } finally {
                        filling.cancel()
                    }
                }
            })
        },contentAlignment=Alignment.Center) {
        Text(if(confirmed)"已确认删除" else "长按删除这次相遇",color=danger,modifier=Modifier.padding(16.dp))
    }
}
