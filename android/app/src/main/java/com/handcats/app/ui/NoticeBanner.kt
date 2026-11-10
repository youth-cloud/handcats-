package com.handcats.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.handcats.app.ui.theme.Forest
import com.handcats.app.ui.theme.Mint
import kotlinx.coroutines.delay

@Composable fun NoticeBanner(error: String?, notice: String?, dismiss: () -> Unit) {
    val message = error ?: notice
    // Retain the outgoing text for the exit animation.
    var displayed by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    LaunchedEffect(message, error != null) {
        if (message != null) {
            displayed = message
            isError = error != null
            if (!isError) { delay(3500); dismiss() }
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(240)) { -it } + fadeOut(tween(240))
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(18.dp), shadowElevation = 6.dp,
            color = if (isError) MaterialTheme.colorScheme.errorContainer else Mint
        ) {
            Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(displayed, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else Forest)
                TextButton(onClick = dismiss) { Text("知道了") }
            }
        }
    }
}
