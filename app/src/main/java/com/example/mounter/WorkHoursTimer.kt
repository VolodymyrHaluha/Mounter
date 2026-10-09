package com.example.mounter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mounter.attendance.CardWorkSession
import kotlinx.coroutines.delay

internal fun orderedCardSessions(sessions: List<CardWorkSession>): List<CardWorkSession> = sessions
    .sortedWith(compareByDescending<CardWorkSession> { it.active }.thenBy { it.endedAt > 0 }.thenBy { it.cardId }.thenByDescending { it.startedAt })

@Composable
internal fun WorkHoursTimer(startedAt: Long, endedAt: Long) {
    CardWorkHours(listOf(CardWorkSession("", startedAt=startedAt, endedAt=endedAt)))
}

@Composable
internal fun CardWorkHours(sessions: List<CardWorkSession>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var expanded by remember { mutableStateOf(false) }
    val ordered = remember(sessions) { orderedCardSessions(sessions) }
    val running = sessions.any { it.startedAt > 0 && it.endedAt == 0L }
    LaunchedEffect(running) {
        while(running) { now=System.currentTimeMillis(); delay(1000) }
        now=System.currentTimeMillis()
    }
    fun line(session: CardWorkSession): String = when {
        session.syncStatus == "pending_confirmation" || session.serverRevision == 0L -> "Очікуємо LOCAL"
        session.startedAt <= 0 -> "—"
        else -> formatWorkHours(workDurationMillis(session.startedAt, session.endedAt, now)) + if(session.endedAt > 0) " ✓" else ""
    }
    val activeCount = sessions.filter { it.active }.distinctBy { it.employeeId?.let { id -> "employee:$id" } ?: it.cardKey }.size
    Surface(shape=RoundedCornerShape(13.dp), color=MaterialTheme.colorScheme.surface, modifier=Modifier.widthIn(max=300.dp)) {
        Column(Modifier.padding(horizontal=14.dp, vertical=6.dp)) {
            Text("Робочі години", style=MaterialTheme.typography.labelSmall)
            ordered.take(2).forEach { session ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(session.cardId.ifBlank { "Картка очікує звірки" }, maxLines=1, overflow=TextOverflow.Ellipsis, modifier=Modifier.weight(1f), style=MaterialTheme.typography.bodySmall)
                    Text(line(session), style=MaterialTheme.typography.bodySmall)
                }
            }
            Text("Активних працівників: $activeCount", style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={ expanded=true }, contentPadding=PaddingValues(0.dp)) { Text("Усі картки та зміни (${sessions.size})") }
        }
    }
    if(expanded) AlertDialog(onDismissRequest={ expanded=false }, title={ Text("Робочі години") },
        text={ LazyColumn(Modifier.heightIn(max=420.dp).widthIn(min=320.dp)) {
            itemsIndexed(ordered) { _, session ->
                Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                    Text(session.cardId.ifBlank { "Картка очікує звірки" }, style=MaterialTheme.typography.bodyMedium)
                    Text(line(session), style=MaterialTheme.typography.titleMedium)
                }
            }
        } }, confirmButton={ TextButton(onClick={ expanded=false }) { Text("Закрити") } })
}
