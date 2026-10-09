package com.example.mounter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mounter.attendance.CardWorkSession
import kotlinx.coroutines.delay

internal fun orderedCardSessions(sessions: List<CardWorkSession>): List<CardWorkSession> = sessions
    .filter { it.active }
    .sortedByDescending { it.startedAt }
    .distinctBy { it.employeeId }
    .sortedWith(compareBy<CardWorkSession> { it.cardId }.thenBy { it.employeeId })

@Composable
internal fun CardWorkHours(sessions: List<CardWorkSession>) {
    val active = remember(sessions) { orderedCardSessions(sessions) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Робочі години", style=MaterialTheme.typography.titleMedium, fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        if(active.isEmpty()) {
            Text("Ще немає працівників, які відмітили прихід.", style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(active, key={checkNotNull(it.employeeId)}) { session ->
                Surface(shape=RoundedCornerShape(14.dp), color=MaterialTheme.colorScheme.surfaceVariant,
                    modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(session.cardId, style=MaterialTheme.typography.bodyMedium,
                            fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(formatWorkHours(workDurationMillis(session.startedAt, 0, now)),
                            style=MaterialTheme.typography.headlineSmall, color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
