package com.example.mounter

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DrawingEntry(val id: String, val name: String, val path: String)

internal fun readDrawings(block: JSONObject): List<DrawingEntry> {
    val values = block.optJSONArray("drawings") ?: return emptyList()
    return (0 until values.length()).map {
        val value = values.getJSONObject(it)
        DrawingEntry(value.getString("id"), value.getString("name"), value.getString("path"))
    }
}

internal fun drawingsJson(drawings: List<DrawingEntry>): JSONArray = JSONArray().apply {
    drawings.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path)) }
}

@Composable
internal fun ProductsScreen(projects: List<Project>, onUpdate: (Project) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestProjects by rememberUpdatedState(projects)
    val latestUpdate by rememberUpdatedState(onUpdate)
    var target by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var objectId by rememberSaveable { mutableStateOf<String?>(null) }
    var drawingId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val selection = target
        target = null
        if (selection != null && uris.isNotEmpty()) {
            busy = true
            scope.launch {
                val copied = mutableListOf<DrawingEntry>()
                try {
                    withContext(Dispatchers.IO) {
                        val folder = File(context.filesDir, "drawings")
                        check(folder.isDirectory || folder.mkdirs()) { "Не вдалося створити папку креслень." }
                        uris.forEach { uri ->
                            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: "Креслення.pdf"
                            val id = UUID.randomUUID().toString()
                            val file = File(folder, "$id.pdf")
                            try {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val header = ByteArray(5)
                                    var count = 0
                                    while (count < header.size) {
                                        val read = input.read(header, count, header.size - count)
                                        if (read < 0) break
                                        count += read
                                    }
                                    require(count == 5 && header.contentEquals("%PDF-".toByteArray())) { "Файл «$displayName» не є PDF." }
                                    file.outputStream().use { output -> output.write(header); input.copyTo(output) }
                                } ?: error("Не вдалося прочитати «$displayName».")
                                copied.add(DrawingEntry(id, displayName, file.absolutePath))
                            } catch (failure: Exception) { file.delete(); throw failure }
                        }
                    }
                    val project = latestProjects.find { it.id == selection[0] } ?: error("Об'єкт не знайдено.")
                    check(project.blocks.any { it.id == selection[1] }) { "Виріб не знайдено." }
                    latestUpdate(project.copy(blocks = project.blocks.map {
                        if (it.id == selection[1]) it.copy(drawings = it.drawings + copied) else it
                    }))
                } catch (failure: Exception) {
                    withContext(Dispatchers.IO) { copied.forEach { File(it.path).delete() } }
                    error = failure.message ?: "Не вдалося прикріпити креслення."
                } finally { busy = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(28.dp, 22.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Вироби", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Вироби з усіх об'єктів • PDF-креслення на пристрої", fontSize = 13.sp)
            }
            Button(onClick = { adding = true }, enabled = projects.isNotEmpty() && !busy) { Text("Додати виріб") }
        }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)); Text("Збереження креслень…") }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (projects.isEmpty()) item { Text("Спочатку додайте об'єкт у вкладці «Об'єкти».") }
            projects.forEach { project ->
                items(project.blocks, key = { "${project.id}-${it.id}" }) { block ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(block.name.ifBlank { "Виріб ${project.blocks.indexOf(block) + 1}" }, fontWeight = FontWeight.Bold)
                            Text("Об'єкт: ${project.displayName}", fontSize = 12.sp)
                            if (block.drawings.isEmpty()) Text("Креслення ще не прикріплено", fontSize = 13.sp)
                            block.drawings.forEach { drawing ->
                                Row(Modifier.fillMaxWidth()) {
                                    TextButton(modifier = Modifier.weight(1f), onClick = { drawingId = drawing.id }) { Text(drawing.name) }
                                    TextButton(enabled = !busy, onClick = {
                                        try {
                                            onUpdate(project.copy(blocks = project.blocks.map {
                                                if (it.id == block.id) it.copy(drawings = it.drawings.filterNot { d -> d.id == drawing.id }) else it
                                            }))
                                            File(drawing.path).delete()
                                        } catch (failure: Exception) { error = failure.message }
                                    }) { Text("Відкріпити") }
                                }
                            }
                            TextButton(enabled = !busy && target == null, onClick = {
                                target = listOf(project.id, block.id)
                                picker.launch(arrayOf("application/pdf"))
                            }) { Text("Прикріпити PDF-креслення") }
                        }
                    }
                }
            }
        }
    }
    projects.flatMap { it.blocks }.flatMap { it.drawings }.find { it.id == drawingId }?.let { drawing ->
        key(drawing.id) { PdfViewer(drawing, onClose = { drawingId = null }) }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Новий виріб") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text("Назва виробу") }, singleLine = true)
            Text("Оберіть об'єкт")
            LazyColumn(Modifier.heightIn(max = 180.dp)) {
                items(projects, key = { it.id }) { project ->
                    Row {
                        RadioButton(objectId == project.id, onClick = { objectId = project.id })
                        TextButton(onClick = { objectId = project.id }) { Text(project.displayName) }
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = name.isNotBlank() && projects.any { it.id == objectId }, onClick = {
            try {
                val project = projects.first { it.id == objectId }
                onUpdate(project.copy(blocks = project.blocks + WorkBlock(name = cleanName(name))))
                name = ""; adding = false
            } catch (failure: Exception) { error = failure.message }
        }) { Text("Додати") }
    }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Скасувати") } })
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Креслення") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Закрити") } })
    }
}
