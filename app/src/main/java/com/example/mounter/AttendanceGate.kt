package com.example.mounter

import android.app.PendingIntent
import android.content.ActivityNotFoundException
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
import java.util.UUID

private const val ATTENDANCE_PACKAGE = "com.example.app"
private const val ATTENDANCE_ACTION = "com.example.app.action.MOUNTER_ATTENDANCE"
private const val RETURN_CALLBACK = "com.example.mounter.extra.ATTENDANCE_RETURN"
private const val REQUEST_ID = "com.example.mounter.extra.ATTENDANCE_REQUEST_ID"
private val attendanceUri = Uri.parse("content://com.example.app.mounter.attendance/status")

private data class AttendanceAccess(
    val state: String = "locked",
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
        refreshJob?.cancel()
        checking = false
        // Re-read the confirmed attendance state when returning to Mounter.
        access = access.copy(state="locked", message="Очікуємо підтвердження нової відмітки від сервера.")
        launching = true
        var callback: PendingIntent? = null
        try {
            val id = UUID.randomUUID().toString()
            callback = PendingIntent.getActivity(activity, 0,
                Intent(activity, MainActivity::class.java).apply {
                    action = "com.example.mounter.action.ATTENDANCE_RETURN"
                    data = Uri.parse("mounter-attendance://return/$id")
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            // The companion's exported MainActivity is known from its manifest.
            // A null launcher lookup does not prove the app is absent.
            val intent = activity.packageManager.getLaunchIntentForPackage(ATTENDANCE_PACKAGE)
                ?: Intent().setComponent(ComponentName(ATTENDANCE_PACKAGE, "$ATTENDANCE_PACKAGE.MainActivity"))
            intent.apply {
                action = ATTENDANCE_ACTION
                // Keep the result relationship: launch intents normally contain NEW_TASK.
                flags = 0
                putExtra(RETURN_CALLBACK, callback)
                putExtra(REQUEST_ID, id)
                tag?.let { putExtra(NfcAdapter.EXTRA_TAG, it) }
            }
            launcher.launch(intent)
        } catch(error: Exception) {
            callback?.cancel()
            launching = false
            val message = when(error) {
                is ActivityNotFoundException -> "Не вдалося запустити com.example.app.MainActivity. Перевірте, що APP-TEST встановлено в тому самому профілі Android, що й Mounter."
                is SecurityException -> "Android заборонив запуск APP-TEST. Перевірте android:exported=\"true\" для MainActivity та дозволи режиму кіоску."
                else -> error.message ?: "Не вдалося відкрити додаток відміток."
            }
            access = access.copy(state="locked", eventId="", message=message)
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
    val allowed = access.allowed
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
                        Button(enabled=!launching, onClick={ openAttendance() }) {
                            Text("Відкрити додаток відміток")
                        }
                        TextButton(enabled=!checking, onClick={ refresh() }) { Text("Перевірити відмітку") }
                    }
                }
            }
        }
    }
}
