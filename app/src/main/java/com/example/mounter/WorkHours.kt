package com.example.mounter

internal fun workDurationMillis(startedAt: Long, endedAt: Long, now: Long): Long =
    if(startedAt <= 0) 0 else ((endedAt.takeIf { it > 0 } ?: now) - startedAt).coerceAtLeast(0)

internal fun formatWorkHours(durationMillis: Long): String {
    val seconds = durationMillis.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
}
