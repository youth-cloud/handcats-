package com.handcats.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.handcats.app.data.remote.DetectionItem
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.handcats.app.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handcats.app.data.CatsViewModel
import com.handcats.app.ui.theme.*
import kotlinx.coroutines.CancellationException

enum class Symbol { Home, Book, Camera, History, Person, Back, Arrow, Heart, Settings, Check, Plus, Refresh, Image, Close, Lock, Leaf }

@Composable fun CatCover(code: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val resource = when(code) {
        "birman" -> R.drawable.cat_birman
        "british_shorthair" -> R.drawable.cat_british_shorthair
        "maine_coon" -> R.drawable.cat_maine_coon
        "persian" -> R.drawable.cat_persian
        "ragdoll" -> R.drawable.cat_ragdoll
        "russian_blue" -> R.drawable.cat_russian_blue
        "siamese" -> R.drawable.cat_siamese
        "sphynx" -> R.drawable.cat_sphynx
        "domestic_tabby" -> R.drawable.cat_domestic_tabby
        "domestic_orange" -> R.drawable.cat_domestic_orange
        "domestic_white" -> R.drawable.cat_domestic_white
        else -> R.drawable.cat_domestic_calico
    }
    Image(painterResource(resource), "猫咪外观参考照片", modifier, contentScale=contentScale, alignment=Alignment.Center)
}

@Composable fun Glyph(symbol: Symbol, modifier: Modifier = Modifier, color: Color = Forest) {
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x, y), Offset(a, b), 1.7f, StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
                val p = Path(); p.moveTo(points[0].first, points[0].second)
                points.drop(1).forEach { p.lineTo(it.first, it.second) }; if (close) p.close()
                drawPath(p, color, style = stroke)
            }
            when (symbol) {
                Symbol.Home -> { path(3f to 10f, 12f to 3f, 21f to 10f); path(5f to 9f, 5f to 21f, 19f to 21f, 19f to 9f); path(10f to 21f, 10f to 14f, 14f to 14f, 14f to 21f) }
                Symbol.Book -> { path(12f to 6f, 4f to 3f, 3f to 3f, 3f to 19f, 12f to 22f, 21f to 19f, 21f to 3f, 20f to 3f, 12f to 6f); line(12f,6f,12f,22f) }
                Symbol.Camera -> { path(3f to 7f, 7f to 7f, 9f to 4f, 15f to 4f, 17f to 7f, 21f to 7f, 21f to 20f, 3f to 20f, close = true); drawCircle(color,4f,Offset(12f,13f),style=stroke) }
                Symbol.History -> { drawCircle(color,9f,Offset(12f,12f),style=stroke); path(12f to 6f,12f to 12f,16f to 14f) }
                Symbol.Person -> { drawCircle(color,4f,Offset(12f,7f),style=stroke); drawArc(color,180f,180f,false,Offset(4f,13f),Size(16f,14f),style=stroke) }
                Symbol.Back -> { path(15f to 5f,8f to 12f,15f to 19f) }
                Symbol.Arrow -> { path(14f to 6f,20f to 12f,14f to 18f); line(4f,12f,20f,12f) }
                Symbol.Heart -> { val p=Path();p.moveTo(12f,21f);p.cubicTo(-5f,10f,4f,-2f,12f,6f);p.cubicTo(20f,-2f,29f,10f,12f,21f);drawPath(p,color,style=stroke) }
                Symbol.Settings -> { drawCircle(color,7f,Offset(12f,12f),style=stroke);drawCircle(color,2.5f,Offset(12f,12f),style=stroke);line(12f,2f,12f,5f);line(12f,19f,12f,22f);line(2f,12f,5f,12f);line(19f,12f,22f,12f) }
                Symbol.Check -> path(4f to 12f,10f to 18f,21f to 5f)
                Symbol.Plus -> { line(12f,4f,12f,20f);line(4f,12f,20f,12f) }
                Symbol.Refresh -> { drawArc(color,40f,285f,false,Offset(4f,4f),Size(16f,16f),style=stroke);path(19f to 2f,20f to 8f,14f to 7f) }
                Symbol.Image -> { drawRoundRect(color,Offset(3f,3f),Size(18f,18f),CornerRadius(3f),style=stroke);drawCircle(color,2f,Offset(8f,8f),style=stroke);path(4f to 18f,10f to 12f,14f to 16f,17f to 13f,21f to 17f) }
                Symbol.Close -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
                Symbol.Lock -> { drawRoundRect(color,Offset(5f,10f),Size(14f,11f),CornerRadius(2f),style=stroke);drawArc(color,180f,180f,false,Offset(8f,2f),Size(8f,14f),style=stroke);line(12f,14f,12f,17f) }
                Symbol.Leaf -> { val p=Path();p.moveTo(4f,20f);p.cubicTo(0f,5f,16f,3f,21f,3f);p.cubicTo(22f,17f,15f,23f,4f,20f);drawPath(p,color,style=stroke);line(4f,20f,15f,9f) }
            }
        }
    }
}

@Composable fun CatArt(code: String = "domestic_orange", modifier: Modifier = Modifier, muted: Boolean = false) {
    Canvas(modifier.semantics { contentDescription = "猫咪插画" }) {
        val s = minOf(size.width / 220f, size.height / 230f)
        val body = if (muted) Color(0xFFC9CEC3) else when (code) {
            "domestic_white", "ragdoll", "birman", "persian" -> Color(0xFFF7F3E9)
            "british_shorthair", "russian_blue" -> Color(0xFF94A9AB)
            "domestic_tabby", "maine_coon" -> Color(0xFFA8A28D)
            "siamese" -> Color(0xFFDECBB3)
            "sphynx" -> Color(0xFFDDB3A4)
            "domestic_calico" -> Color(0xFFF1EADF)
            else -> Color(0xFFD8A467)
        }
        val dark = if (muted) Color(0xFF8D998D) else Color(0xFF5B6854)
        scale(s, s, pivot = Offset.Zero) {
            drawOval(Forest.copy(alpha=.09f),Offset(22f,205f),Size(185f,18f))
            val tail=Path();tail.moveTo(146f,198f);tail.cubicTo(224f,225f,215f,149f,187f,147f)
            drawPath(tail,body,style=Stroke(23f,cap=StrokeCap.Round))
            drawOval(body,Offset(51f,101f),Size(119f,112f))
            drawOval(body,Offset(45f,195f),Size(67f,24f));drawOval(body,Offset(119f,195f),Size(57f,24f))
            val ears=Path();ears.moveTo(47f,73f);ears.lineTo(43f,19f);ears.quadraticTo(69f,24f,83f,48f);ears.lineTo(138f,48f);ears.quadraticTo(160f,22f,179f,20f);ears.lineTo(176f,83f);ears.close()
            drawPath(ears,body)
            val inner=Path();inner.moveTo(51f,33f);inner.lineTo(55f,65f);inner.lineTo(77f,52f);inner.close();inner.moveTo(169f,33f);inner.lineTo(146f,53f);inner.lineTo(168f,66f);inner.close()
            drawPath(inner,Color(0xFFC88F7B).copy(alpha=.65f))
            drawOval(body,Offset(40f,41f),Size(145f,111f))
            if (code=="domestic_calico" && !muted) { drawOval(Color(0xFFD9A366),Offset(40f,47f),Size(68f,72f));drawOval(dark,Offset(125f,45f),Size(60f,58f));drawOval(Color(0xFFD9A366),Offset(121f,159f),Size(38f,36f)) }
            if (code in listOf("siamese","ragdoll","birman") && !muted) drawOval(Color(0xFF978273),Offset(64f,63f),Size(95f,71f))
            drawOval(Color.White.copy(alpha=.5f),Offset(87f,139f),Size(52f,54f))
            if (code in listOf("domestic_tabby","domestic_orange","maine_coon")) {
                for (x in listOf(96f,112f,128f)) drawLine(dark.copy(alpha=.55f),Offset(x,45f),Offset(x,65f),5f,StrokeCap.Round)
                for (y in listOf(164f,179f)) drawLine(dark.copy(alpha=.4f),Offset(55f,y),Offset(74f,y+4f),6f,StrokeCap.Round)
            }
            drawArc(Ink,190f,160f,false,Offset(71f,88f),Size(21f,13f),style=Stroke(3.5f,cap=StrokeCap.Round))
            drawArc(Ink,190f,160f,false,Offset(133f,88f),Size(21f,13f),style=Stroke(3.5f,cap=StrokeCap.Round))
            val nose=Path();nose.moveTo(105f,106f);nose.lineTo(119f,106f);nose.lineTo(112f,113f);nose.close();drawPath(nose,Color(0xFFAA7564))
            drawLine(Ink,Offset(112f,113f),Offset(112f,119f),2f)
            drawArc(Ink,0f,150f,false,Offset(102f,114f),Size(10f,10f),style=Stroke(2f))
            drawArc(Ink,30f,150f,false,Offset(112f,114f),Size(10f,10f),style=Stroke(2f))
            for (y in listOf(106f,116f)) { drawLine(dark,Offset(48f,y-4f),Offset(77f,y),1.5f);drawLine(dark,Offset(150f,y),Offset(181f,y-4f),1.5f) }
            drawLine(dark.copy(alpha=.4f),Offset(89f,183f),Offset(89f,210f),2f,StrokeCap.Round)
            drawLine(dark.copy(alpha=.4f),Offset(137f,183f),Offset(137f,210f),2f,StrokeCap.Round)
        }
    }
}

@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, symbol: Symbol? = null) {
    Button(onClick = onClick, enabled = enabled && !loading, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(16.dp)) {
        if (loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
        else if (symbol != null) { Glyph(symbol, color = Color.White); Spacer(Modifier.width(10.dp)) }
        Text(text)
    }
}

@Composable fun PageHeader(title: String, subtitle: String = "", onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { Glyph(Symbol.Back) }; Spacer(Modifier.width(4.dp)) }
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall); if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) }
        action?.invoke()
    }
}

@Composable fun Tag(text: String, color: Color = Forest, background: Color = Mint) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable fun Paper(modifier: Modifier = Modifier, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(color).padding(horizontal=16.dp,vertical=20.dp), content = content)
}

@Composable fun EmptyState(title: String, description: String, button: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(150.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) { CatArt(modifier=Modifier.size(120.dp)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color=Muted, style=MaterialTheme.typography.bodyMedium, textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        if (button != null) OutlinedButton(onClick = onClick, shape=RoundedCornerShape(14.dp)) { Text(button) }
    }
}

@Composable fun RemotePhoto(vm: CatsViewModel, path: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop,
                            detections: List<DetectionItem> = emptyList(), focusedIndex: Int? = null) {
    var retry by remember(path) { mutableIntStateOf(0) }
    var failed by remember(path, retry) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, path, vm.user?.id, retry) {
        try { value = vm.image(path); failed = value == null }
        catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    Box(modifier.background(Mint), contentAlignment=Alignment.Center) {
        bitmap?.let { photo ->
            Image(photo.asImageBitmap(), "相遇照片", Modifier.fillMaxSize(), contentScale = contentScale)
            if (detections.isNotEmpty() && contentScale == ContentScale.Fit) Canvas(Modifier.fillMaxSize().semantics{contentDescription="猫咪编号与识别框"}) {
                val fitted = fittedPhotoRect(photo.width.toFloat(), photo.height.toFloat(), size.width, size.height)
                detections.forEachIndexed { index, detection ->
                    val b = detection.boundingBox
                    if (b.size == 4 && b.all { it.isFinite() } && b[2] > b[0] && b[3] > b[1]) {
                        val left = fitted.left + b[0].coerceIn(0f, 1f) * fitted.width
                        val top = fitted.top + b[1].coerceIn(0f, 1f) * fitted.height
                        val right = fitted.left + b[2].coerceIn(0f, 1f) * fitted.width
                        val bottom = fitted.top + b[3].coerceIn(0f, 1f) * fitted.height
                        val color = detectionColor(index)
                        val focused = focusedIndex == null || focusedIndex == index
                        drawRoundRect(color.copy(alpha=if(focused) 1f else .45f), Offset(left,top), Size(right-left,bottom-top), CornerRadius(6.dp.toPx()), style=Stroke(if(focused) 3.dp.toPx() else 1.5.dp.toPx()))
                        val radius = 13.dp.toPx()
                        val cx = (left + radius).coerceIn(radius, size.width-radius)
                        val cy = (top + radius).coerceIn(radius, size.height-radius)
                        drawCircle(color,radius,Offset(cx,cy))
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                this.color = Color.White.toArgb(); textSize = 14.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true
                            }
                            canvas.nativeCanvas.drawText("${index+1}",cx,cy-(paint.ascent()+paint.descent())/2,paint)
                        }
                    }
                }
            }
        }
            ?: if (failed) TextButton(onClick={ retry++ }) { Text("点击重载照片", style=MaterialTheme.typography.bodySmall) }
            else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth=2.dp)
    }
}

fun detectionColor(index: Int): Color = listOf(Forest, Orange, Color(0xFF496FAD), Color(0xFF885C99), Color(0xFF967020))[index % 5]

fun displayName(name: String): String = when (val normalized = name.replace('（', '(').replace('）', ')')) {
    "狸花猫", "狸花" -> "中华田园猫(狸花)"
    "橘猫", "橘" -> "中华田园猫(橘)"
    "白猫", "白" -> "中华田园猫(白)"
    "三花猫", "三花" -> "中华田园猫(三花)"
    else -> normalized
}
fun displayDate(date: String): String = runCatching { java.time.OffsetDateTime.parse(date).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM 月 dd 日 · HH:mm")) }.getOrDefault(date.take(16))
