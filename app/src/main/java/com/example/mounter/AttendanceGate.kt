package com.example.mounter

import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ATTENDANCE_PACKAGE = "com.example.app"
private const val ATTENDANCE_ACTION = "com.example.app.action.MOUNTER_ATTENDANCE"
private val attendanceUri = Uri.parse("content://com.example.app.mounter.attendance/status")

private data class AttendanceAccess(
    val state: String = "locked",
    val generation: Long = 0,
    val eventId: String = "",
    val message: String = "Відмітьте «Прихід», щоб відкрити головне меню."
) {
    val allowed: Boolean get() = state == "arrival" && eventId.isNotBlank()
}

private fun readAttendanceAccess(activity: MainActivity): AttendanceAccess {
    val cursor = activity.contentResolver.query(attendanceUri, null, null, null, null)
        ?: error("Оновіть додаток відміток: він ще не підтримує зв’язок із Mounter.")
    return cursor.use {
        check(it.moveToFirst()) { "Додаток відміток не повернув стан." }
        AttendanceAccess(
            state=it.getString(it.getColumnIndexOrThrow("state")),
            generation=it.getLong(it.getColumnIndexOrThrow("generation")),
            eventId=it.getString(it.getColumnIndexOrThrow("event_id")),
            message=it.getString(it.getColumnIndexOrThrow("message"))
        )
    }
}

@Composable
internal fun AttendanceGate(activity: MainActivity) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(AttendanceAccess()) }
    var checking by remember { mutableStateOf(true) }
    var launching by remember { mutableStateOf(false) }
    var blockedGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            checking = true
            try {
                access = withContext(Dispatchers.IO) { readAttendanceAccess(activity) }
            } catch(error: kotlinx.coroutines.CancellationException) { throw error }
            catch(_: Exception) {
                access = access.copy(state="locked", eventId="", message="Немає зв’язку з додатком відміток. Установіть його версію з підтримкою Mounter.")
            } finally { checking = false }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        activity.suppressAttendanceScan()
        launching = false
        refresh()
    }
    fun openAttendance(tag: Tag? = null) {
        if(launching) return
        // A previous arrival must not reopen the menu after a cancelled/new scan.
        blockedGeneration = maxOf(blockedGeneration ?: 0L, access.generation)
        access = access.copy(state="locked", message="Очікуємо підтвердження нової відмітки від сервера.")
        launching = true
        try {
            val intent = Intent(ATTENDANCE_ACTION).apply {
                component = ComponentName(ATTENDANCE_PACKAGE, "$ATTENDANCE_PACKAGE.MainActivity")
                tag?.let { putExtra(NfcAdapter.EXTRA_TAG, it) }
            }
            launcher.launch(intent)
        } catch(_: Exception) {
            launching = false
            access = access.copy(state="locked", eventId="", message="Не вдалося відкрити додаток відміток. Перевірте, чи він установлений.")
        }
    }
    val currentOpen by rememberUpdatedState<(Tag) -> Unit>({ openAttendance(it) })
    val currentRefresh by rememberUpdatedState<() -> Unit>({ refresh() })
    DisposableEffect(activity, lifecycleOwner) {
        activity.onAttendanceTag = { currentOpen(it) }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { currentRefresh() }
        }
        val registered = runCatching {
            activity.contentResolver.registerContentObserver(attendanceUri, false, observer)
        }.isSuccess
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_RESUME) { launching=false; currentRefresh() }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        currentRefresh()
        onDispose {
            activity.onAttendanceTag = null
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            if(registered) activity.contentResolver.unregisterContentObserver(observer)
            refreshJob?.cancel()
        }
    }
    val allowed = access.allowed && (blockedGeneration == null || access.generation > blockedGeneration!!)
    Box(Modifier.fillMaxSize()) {
        if(allowed) MounterApp()
        if(!allowed || checking) {
            Image(painterResource(R.drawable.frop_logo_preview_01_1), contentDescription=null,
                contentScale=ContentScale.Crop, modifier=Modifier.matchParentSize())
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha=.4f)),
                contentAlignment=Alignment.Center) {
                Surface(shape=RoundedCornerShape(20.dp), modifier=Modifier.widthIn(max=540.dp).padding(24.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("Відмітка перед початком роботи", style=MaterialTheme.typography.headlineSmall)
                        Text(if(checking) "Перевіряємо відмітку…" else access.message)
                        if(checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(enabled=!checking && !launching, onClick={ openAttendance() }) {
                            Text("Відкрити додаток відміток")
                        }
                        TextButton(enabled=!checking, onClick={ refresh() }) { Text("Перевірити відмітку") }
                    }
                }
            }
        }
    }
}
