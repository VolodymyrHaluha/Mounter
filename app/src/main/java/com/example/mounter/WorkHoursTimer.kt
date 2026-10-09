package com.example.mounter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mounter.attendance.CardWorkSession
import kotlinx.coroutines.delay

internal fun orderedCardSessions(sessions: List<CardWorkSession>): List<CardWorkSession> = sessions
    .filter { it.active }
    .sortedWith(compareBy<CardWorkSession> { it.cardId }.thenByDescending { it.startedAt })

@Composable
internal fun CardWorkHours(sessions: List<CardWorkSession>) {
    val active = remember(sessions) { orderedCardSessions(sessions) }
    if (active.isEmpty()) return

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val activeCount = active.distinctBy { it.employeeId }.size
    Surface(shape=RoundedCornerShape(13.dp), color=MaterialTheme.colorScheme.surface, modifier=Modifier.widthIn(max=300.dp)) {
        Column(Modifier.padding(horizontal=14.dp, vertical=6.dp)) {
            Text("Робочі години", style=MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=200.dp)) {
                items(active) { session ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(session.cardId, maxLines=1, overflow=TextOverflow.Ellipsis, modifier=Modifier.weight(1f), style=MaterialTheme.typography.bodySmall)
                        Text(formatWorkHours(workDurationMillis(session.startedAt, 0, now)), style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Активних працівників: $activeCount", style=MaterialTheme.typography.labelSmall)
        }
    }
}