package com.example.mounter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun WorkHoursTimer(startedAt: Long, endedAt: Long) {
    var now by remember(startedAt, endedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt, endedAt) {
        while(startedAt > 0 && endedAt == 0L) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Surface(shape=RoundedCornerShape(13.dp), color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal=14.dp, vertical=6.dp)) {
            Text("Робочі години", style=MaterialTheme.typography.labelSmall)
            Text(if(startedAt > 0) formatWorkHours(workDurationMillis(startedAt, endedAt, now)) else "—",
                style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.SemiBold)
        }
    }
}