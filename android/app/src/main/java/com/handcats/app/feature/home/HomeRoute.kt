package com.handcats.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeRoute(
    onStartCamera: () -> Unit,
) {
    Scaffold { innerPadding ->
        HomeScreen(
            contentPadding = innerPadding,
            onStartCamera = onStartCamera,
        )
    }
}

@Composable
private fun HomeScreen(
    contentPadding: PaddingValues,
    onStartCamera: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "拾猫",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "拍下路边遇到的猫，做粗粒度识别并点亮你的猫猫图鉴。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "当前阶段",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = "1. Android 与后端骨架初始化")
                Text(text = "2. 下一步接入拍照、上传和识别联调")
            }
        }
        Button(
            onClick = onStartCamera,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "开始拍猫")
        }
    }
}
