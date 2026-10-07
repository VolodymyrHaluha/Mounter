package com.example.mounter

import android.content.ComponentName
import android.content.Context
import android.content.Intent

internal data class AttendanceApp(val component: ComponentName, val label: String)

@Suppress("DEPRECATION")
internal fun attendanceApps(context: Context): List<AttendanceApp> =
    context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        0
    ).mapNotNull { result ->
        val info = result.activityInfo ?: return@mapNotNull null
        if(!info.exported || !info.enabled || info.packageName == context.packageName) return@mapNotNull null
        AttendanceApp(ComponentName(info.packageName, info.name), result.loadLabel(context.packageManager).toString())
    }.distinctBy { it.component }.sortedWith(compareBy<AttendanceApp> {
        val label = it.label.lowercase()
        if(it.component.packageName.startsWith("com.example.app") || "відміт" in label || "attendance" in label) 0 else 1
    }.thenBy { it.label })

internal fun selectedAttendanceApp(context: Context): ComponentName? =
    context.getSharedPreferences("attendance_launch", Context.MODE_PRIVATE)
        .getString("component", null)?.let(ComponentName::unflattenFromString)

internal fun selectAttendanceApp(context: Context, component: ComponentName) {
    context.getSharedPreferences("attendance_launch", Context.MODE_PRIVATE).edit()
        .putString("component", component.flattenToString()).apply()
}