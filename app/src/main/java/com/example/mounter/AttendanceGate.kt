package com.example.mounter

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import java.util.UUID
import com.example.mounter.attendance.hasActiveAttendance

private const val ATTENDANCE_PACKAGE = "com.example.app.test"
private const val ATTENDANCE_ACTION = "com.example.app.action.MOUNTER_ATTENDANCE"
private const val RETURN_CALLBACK = "com.example.mounter.extra.ATTENDANCE_RETURN"
private const val REQUEST_ID = "com.example.mounter.extra.ATTENDANCE_REQUEST_ID"
@Composable
internal fun AttendanceGate(activity: MainActivity) {
    val store=remember { AttendanceClockStore(activity) }
    var clock by remember { mutableStateOf(store.load()) }
    var message by remember { mutableStateOf("Прикладіть картку тут: перша відмітка починає роботу, повторна завершує.") }
    var launching by remember { mutableStateOf(false) }
    var appChoices by remember { mutableStateOf<List<AttendanceApp>?>(null) }
    var appSearch by remember { mutableStateOf("") }
    var forwardedTag by remember { mutableStateOf<Tag?>(null) }

    fun save(updated: AttendanceClock) {
        store.save(updated)
        clock=updated
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        launching=false
        activity.suppressAttendanceScan()
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
        launching = true
        var callback: PendingIntent? = null
        val before=clock
        try {
            val id = UUID.randomUUID().toString()
            if(tag != null) {
                val key=tag.id.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val name=android.nfc.tech.Ndef.get(tag)?.cachedNdefMessage?.records
                    ?.firstNotNullOfOrNull { record ->
                        if(record.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                            record.type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)) attendanceName(record.payload)
                        else null
                    } ?: "Картка $key"
                save(clock.toggle(key,name,System.currentTimeMillis()))
            }
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
            val failureMessage = when(error) {
                is ActivityNotFoundException -> "Не вдалося запустити ${component.flattenToShortString()}. Натисніть «Вибрати додаток відміток» і виберіть установлену версію в цьому профілі Android."
                is SecurityException -> "Android заборонив запуск APP-TEST. Перевірте android:exported=\"true\" для MainActivity та дозволи режиму кіоску."
                else -> error.message ?: "Не вдалося відкрити додаток відміток."
            }
            try { save(before) } catch(_: Exception) {}
            message = failureMessage
        }
    }
    val currentOpen by rememberUpdatedState<(Tag) -> Unit>({ openAttendance(it) })
    DisposableEffect(activity) {
        activity.onAttendanceTag = { currentOpen(it) }
        onDispose { activity.onAttendanceTag = null }
    }
    val allowed = hasActiveAttendance(clock.sessions)
    Box(Modifier.fillMaxSize()) {
        if(allowed) MounterApp(sessions=clock.sessions)
        if(!allowed) {
            Image(painterResource(R.drawable.frop_logo_preview_01_1), contentDescription=null,
                contentScale=ContentScale.Crop, modifier=Modifier.matchParentSize())
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha=.4f)),
                contentAlignment=Alignment.Center) {
                Surface(shape=RoundedCornerShape(20.dp), modifier=Modifier.widthIn(max=540.dp).padding(24.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("Відмітка перед початком роботи", style=MaterialTheme.typography.headlineSmall)
                        Text(message)
                        Button(enabled=!launching, onClick={ openAttendance() }) {
                            Text("Відкрити додаток відміток")
                        }
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