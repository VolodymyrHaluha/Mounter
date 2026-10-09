package com.example.mounter

import android.app.PendingIntent
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.mounter.attendance.CardWorkSession as AttendanceCardSession
import com.example.mounter.attendance.hasActiveAttendance

private const val ATTENDANCE_PACKAGE = "com.example.app.test"
private const val ATTENDANCE_ACTION = "com.example.app.action.MOUNTER_ATTENDANCE"
private const val RETURN_CALLBACK = "com.example.mounter.extra.ATTENDANCE_RETURN"
private const val REQUEST_ID = "com.example.mounter.extra.ATTENDANCE_REQUEST_ID"
private fun attendanceUris(packageName: String): List<Uri> = listOf(
    Uri.parse("content://$packageName.mounter.attendance/status"),
    // Earlier companion integrations use a fixed authority despite a different applicationId.
    Uri.parse("content://com.example.app.mounter.attendance/status")
).distinct()

internal data class AttendanceAccess(
    val sessions: List<AttendanceCardSession> = emptyList(),
    val readError: String? = null,
    val message: String = "Відмітьте «Прихід», щоб відкрити головне меню."
) {
    val allowed: Boolean get() = hasActiveAttendance(sessions)
}

private fun readAttendanceAccess(activity: MainActivity): AttendanceAccess {
    val expectedPackage = selectedAttendanceApp(activity)?.packageName ?: ATTENDANCE_PACKAGE
    val attendanceUri = attendanceUris(expectedPackage).firstOrNull { uri ->
        activity.packageManager.resolveContentProvider(uri.authority!!, 0)?.packageName == expectedPackage
    } ?: error("Вибраний додаток відміток $expectedPackage не надає стан для Mounter. Оновіть його інтеграцію.")
    val cursor = activity.contentResolver.query(attendanceUri, null, null, null, null)
        ?: error("Оновіть додаток відміток: він ще не підтримує зв’язок із Mounter.")
    return cursor.use {
        check(it.moveToFirst()) { "Додаток відміток не повернув стан." }
        // This endpoint is only a data transport; no status or server metadata is checked.
        val sessionsIndex = it.getColumnIndex("sessions")
        val cardsIndex = it.getColumnIndex("cards")
        val sessions = when {
            sessionsIndex >= 0 -> parseCardRows(it.getString(sessionsIndex) ?: "[]")
            cardsIndex >= 0 -> parseCardRows(it.getString(cardsIndex) ?: "[]")
            else -> emptyList()
        }
        val messageIndex = it.getColumnIndex("message")
        AttendanceAccess(
            sessions=sessions,
            message=if(messageIndex >= 0) it.getString(messageIndex).orEmpty()
                else "Відмітьте «Прихід», щоб відкрити головне меню."
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
    var appChoices by remember { mutableStateOf<List<AttendanceApp>?>(null) }
    var appSearch by remember { mutableStateOf("") }
    var forwardedTag by remember { mutableStateOf<Tag?>(null) }
    var providerPackage by remember { mutableStateOf(selectedAttendanceApp(activity)?.packageName ?: ATTENDANCE_PACKAGE) }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            checking = true
            try {
                access = withContext(Dispatchers.IO) { readAttendanceAccess(activity) }
            } catch(error: kotlinx.coroutines.CancellationException) { throw error }
            catch(error: Exception) {
                access = access.copy(readError="Не вдалося оновити відмітки. Показано останні отримані дані.", message=error.message ?: "Немає зв’язку з додатком відміток. Установіть його версію з підтримкою Mounter.")
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
        val apps = attendanceApps(activity)
        val saved = selectedAttendanceApp(activity)
        val component = apps.firstOrNull { it.component == saved }?.component
            ?: apps.singleOrNull { it.component.packageName == ATTENDANCE_PACKAGE }?.component
        if(component == null) {
            forwardedTag = tag
            appSearch = ""
            appChoices = apps
            return
        }
        refreshJob?.cancel()
        checking = false
        // Receive updated times from APP-TEST after returning; keep existing timers during the transfer.
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
            val intent = Intent().setComponent(component)
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
                is ActivityNotFoundException -> "Не вдалося запустити ${component.flattenToShortString()}. Натисніть «Вибрати додаток відміток» і виберіть установлену версію в цьому профілі Android."
                is SecurityException -> "Android заборонив запуск APP-TEST. Перевірте android:exported=\"true\" для MainActivity та дозволи режиму кіоску."
                else -> error.message ?: "Не вдалося відкрити додаток відміток."
            }
            access = access.copy(message=message)
        }
    }
    val currentOpen by rememberUpdatedState<(Tag) -> Unit>({ openAttendance(it) })
    val currentRefresh by rememberUpdatedState<() -> Unit>({ refresh() })
    DisposableEffect(activity, lifecycleOwner, providerPackage) {
        activity.onAttendanceTag = { currentOpen(it) }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { currentRefresh() }
        }
        val registered = attendanceUris(providerPackage).map { uri ->
            runCatching { activity.contentResolver.registerContentObserver(uri, false, observer) }.isSuccess
        }.any { it }
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
        if(allowed) MounterApp(sessions=access.sessions)
        if(allowed && access.readError != null) {
            Text(access.readError.orEmpty(), modifier=Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface).padding(8.dp))
        }
        if(!allowed) {
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
                        TextButton(enabled=!launching, onClick={
                            forwardedTag = null
                            appSearch = ""
                            appChoices = attendanceApps(activity)
                        }) { Text("Вибрати додаток відміток") }
                    }
                }
            }
        }
    }
    appChoices?.let { choices ->
        AlertDialog(onDismissRequest={ appChoices=null; forwardedTag=null },
            modifier=Modifier.dismissInputOnOutsideTouch(),
            title={ Text("Виберіть додаток відміток") },
            text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value=appSearch, onValueChange={ appSearch=it }, label={ Text("Назва або пакет додатка") }, modifier=Modifier.finishEditingOnOutsideTouch())
                if(choices.isEmpty()) Text("У цьому профілі Android немає доступних додатків. Установіть «Відмітка TEST» в тому самому профілі, що й «Монтажник».")
                LazyColumn(Modifier.heightIn(max=320.dp)) {
                    items(choices.filter { it.label.contains(appSearch, true) || it.component.packageName.contains(appSearch, true) }, key={ it.component.flattenToString() }) { app ->
                        TextButton(onClick={
                            selectAttendanceApp(activity, app.component)
                            access = AttendanceAccess()
                            providerPackage = app.component.packageName
                            val tag = forwardedTag
                            appChoices = null
                            forwardedTag = null
                            openAttendance(tag)
                        }) { Column(Modifier.fillMaxWidth()) {
                            Text(app.label)
                            Text(app.component.flattenToShortString(), style=MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
            } },
            confirmButton={ TextButton(onClick={ appChoices=null; forwardedTag=null }) { Text("Закрити") } })
    }
}