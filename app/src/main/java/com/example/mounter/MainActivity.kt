package com.example.mounter

import android.os.Bundle
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.SystemClock
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

private val Ink = Color(0xFFF0F6F3)
private val Muted = Color(0xFFC0D1C9)
private val Teal = Color(0xFF60D5B6)
private val TealDark = Color(0xFFA4EBD6)
private val Mint = Color(0xCC214B3D)
private val Surface = Color(0xCC16261F)
private val SolidSurface = Color(0xFF16261F)
private val CanvasColor = Color(0xFF17261F)
private val Red = Color(0xFFD96C67)
private val Border = Color(0xFF537265)

data class PhotoEntry(
    val path: String,
    val id: String = UUID.randomUUID().toString(),
    val mimeType: String = "image/jpeg",
    val displayName: String = File(path).name
)
data class WorkBlock(
    val id: String = UUID.randomUUID().toString(),
    val photos: List<PhotoEntry> = emptyList(),
    val name: String = "",
    val note: String = "",
    val characteristics: String = "",
    val drawings: List<DrawingEntry> = emptyList()
)
data class Project(
    val name: String, val customer: String,
    val id: String = UUID.randomUUID().toString(),
    val blocks: List<WorkBlock> = listOf(WorkBlock()),
    val customerId: String? = null
) {
    // Keep the old name field readable for previously saved objects.
    val displayName: String get() = customer.ifBlank { name }
}
enum class Screen { HOME, OBJECTS, PRODUCTS, OBJECT_DETAIL }

private class ProjectStore(private val context: Context) {
    private val file get() = File(context.filesDir, "objects.json")
    fun load(): List<Project> {
        if (!file.exists()) return emptyList()
        val data = JSONArray(file.readText())
        return (0 until data.length()).map { i ->
            val p = data.getJSONObject(i)
            val blocks = p.getJSONArray("blocks")
            Project(p.getString("name"), p.getString("customer"), p.getString("id"),
                blocks = (0 until blocks.length()).map { j ->
                    val b = blocks.getJSONObject(j)
                    val photos = b.getJSONArray("photos")
                    WorkBlock(b.getString("id"), (0 until photos.length()).map { k ->
                        val photo = photos.getJSONObject(k)
                        PhotoEntry(path=photo.getString("path"), id=photo.getString("id"),
                            mimeType=photo.optString("mime_type", "image/jpeg"),
                            displayName=photo.optString("display_name", File(photo.getString("path")).name))
                    }, name = b.optString("name", ""), note = if(b.has("note")) b.optionalText("note") else {
                        // Preserve existing per-photo notes as one shared product note.
                        (0 until photos.length()).map {photos.getJSONObject(it).optionalText("note")}
                            .filter {it.isNotBlank()}.distinct().joinToString("\n\n")
                    }, characteristics = b.optionalText("characteristics"), drawings = readDrawings(b))
                }, customerId = p.optionalText("customer_id").takeIf { it.isNotBlank() })
        }
    }
    fun save(projects: List<Project>) {
        val data = JSONArray()
        projects.forEach { p ->
            val blocks = JSONArray()
            p.blocks.forEach { b ->
                val photos = JSONArray()
                b.photos.forEach { photo -> photos.put(JSONObject().put("id", photo.id).put("path", photo.path).put("mime_type", photo.mimeType).put("display_name", photo.displayName)) }
                blocks.put(JSONObject().put("id", b.id).put("name", b.name).put("note", b.note)
                    .put("characteristics", b.characteristics).put("photos", photos).put("drawings", drawingsJson(b.drawings)))
            }
            data.put(JSONObject().put("id", p.id).put("name", p.name).put("customer", p.customer).put("blocks", blocks).put("customer_id", p.customerId ?: JSONObject.NULL))
        }
        val temporary = File(context.filesDir, "objects.tmp")
        temporary.writeText(data.toString())
        check(temporary.renameTo(file)) { "Не вдалося зберегти об'єкти" }
    }
}

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    internal var onAttendanceTag: ((Tag) -> Unit)? = null
    private var lastScanAt = -10_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.action == "com.example.mounter.action.ATTENDANCE_RETURN") suppressAttendanceScan()
        setContent { MounterTheme { AttendanceGate(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.action == "com.example.mounter.action.ATTENDANCE_RETURN") suppressAttendanceScan()
    }

    override fun onResume() {
        super.onResume()
        if(nfcAdapter?.isEnabled == true) {
            nfcAdapter?.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
        }
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    @Synchronized
    internal fun suppressAttendanceScan() {
        lastScanAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    override fun onTagDiscovered(tag: Tag) {
        val now = SystemClock.elapsedRealtime()
        if(now - lastScanAt < 10_000L) return
        lastScanAt = now
        runOnUiThread { onAttendanceTag?.invoke(tag) }
    }
}

@Composable
fun MounterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal, onPrimary = CanvasColor,
            primaryContainer = Mint, onPrimaryContainer = Ink,
            secondary = TealDark, onSecondary = CanvasColor,
            background = CanvasColor, onBackground = Ink,
            surface = SolidSurface, onSurface = Ink,
            surfaceVariant = Color(0xFF243E32), onSurfaceVariant = Muted,
            surfaceContainer = SolidSurface, surfaceContainerHigh = SolidSurface,
            surfaceContainerHighest = SolidSurface,
            outline = Border, error = Red
        ),
        typography = Typography(),
        content = { CompositionLocalProvider(LocalContentColor provides Ink) { InputEditingHost { content() } } }
    )
}

@Composable
internal fun MounterApp(sessions: List<com.example.mounter.attendance.CardWorkSession> = emptyList()) {
    val context = LocalContext.current
    val store = remember { ProjectStore(context) }
    var projects by remember { mutableStateOf(store.load()) }
    val directoryStore = remember { LocalDirectoryStore(context) }
    var clients by remember { mutableStateOf(directoryStore.loadClients(projects)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun localError(error: Exception) {
        scope.launch { snackbar.showSnackbar(error.message ?: "Не вдалося зберегти дані на пристрої.", actionLabel="Закрити") }
    }
    fun addClient(value: String): Client? {
        val name = cleanName(value)
        if(name.isBlank()) return null
        clients.find { nameKey(it.name) == nameKey(name) }?.let { return it }
        val client = Client(UUID.randomUUID().toString(), name)
        return try {
            val updated = clients + client
            directoryStore.saveClients(updated)
            clients = updated
            client
        } catch(error: Exception) { localError(error); null }
    }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var photoSelection by rememberSaveable { mutableStateOf<List<String>?>(null) }
    fun update(project: Project) {
        val updated = projects.map { if (it.id == project.id) project else it }
        store.save(updated)
        projects = updated
    }
    fun open(project: Project) { selectedId = project.id; screen = Screen.OBJECT_DETAIL }
    fun openPhoto(project: Project, block: WorkBlock, photo: PhotoEntry) {
        photoSelection = listOf(project.id, block.id, photo.id)
    }
    fun changePhoto(newPath: String?) {
        val selection = checkNotNull(photoSelection)
        val project = checkNotNull(projects.find { it.id == selection[0] })
        val block = checkNotNull(project.blocks.find { it.id == selection[1] })
        val oldPhoto = checkNotNull(block.photos.find { it.id == selection[2] })
        val photos = if (newPath == null) block.photos.filterNot { it.id == oldPhoto.id }
        else block.photos.map { if (it.id == oldPhoto.id) it.copy(path = newPath, mimeType = "image/jpeg", displayName = File(newPath).name) else it }
        update(project.copy(blocks = project.blocks.map { if (it.id == block.id) it.copy(photos = photos) else it }))
        // Remove the old file only after the changed object has been saved.
        val stillUsed = projects.any { p -> p.blocks.any { b -> b.photos.any { it.path == oldPhoto.path } } }
        if (!stillUsed) runCatching { File(oldPhoto.path).delete() }
        if (newPath == null) photoSelection = null
    }
    Box(Modifier.fillMaxSize().background(CanvasColor)) {
        Image(
            painter = painterResource(R.drawable.frop_logo_preview_01_1),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
        Row(Modifier.fillMaxSize()) {
            NavigationRail(screen) { screen = it }
            AnimatedContent(targetState = screen, label = "screen", modifier = Modifier.weight(1f)) { current ->
                when (current) {
                    Screen.HOME -> HomeScreen(projects, ::open, { screen = Screen.OBJECTS }, sessions)
                    Screen.OBJECTS -> ObjectsScreen(projects, clients, ::addClient, ::open, ::openPhoto) { project ->
                        val updated = projects + project
                        store.save(updated)
                        projects = updated
                        open(project)
                    }
                    Screen.PRODUCTS -> ProductsScreen(projects, ::update)
                    Screen.OBJECT_DETAIL -> projects.find { it.id == selectedId }?.let { project ->
                        ObjectDetail(project, { screen = Screen.OBJECTS }, { block, photo -> openPhoto(project, block, photo) }, ::update)
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment=Alignment.BottomCenter) {
        SnackbarHost(snackbar, modifier=Modifier.padding(16.dp))
    }
    photoSelection?.let { selection ->
        val photo = projects.find { it.id == selection[0] }?.blocks
            ?.find { it.id == selection[1] }?.photos?.find { it.id == selection[2] }
        if (photo != null) key(selection) {
            if(!photo.mimeType.startsWith("image/")) FileViewer(photo,
                onClose = { photoSelection = null }, onDelete = { changePhoto(null) })
            else PhotoViewer(photo, onClose = { photoSelection = null },
                onReplace = { changePhoto(it) }, onDelete = { changePhoto(null) })
        }
    }
}

@Composable
private fun NavigationRail(active: Screen, onSelect: (Screen) -> Unit) {
    val items = listOf(Screen.HOME to "Головна", Screen.OBJECTS to "Об'єкти", Screen.PRODUCTS to "Вироби")
    Column(
        Modifier.width(116.dp).fillMaxHeight().background(Surface).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Teal), contentAlignment = Alignment.Center) {
            Text("М", color = CanvasColor, fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(34.dp))
        items.forEach { (screen, label) ->
            val selected = active == screen || (screen == Screen.OBJECTS && active in listOf(Screen.OBJECT_DETAIL))
            Column(
                Modifier.fillMaxWidth().clickable { onSelect(screen) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) Mint else Color.Transparent), contentAlignment = Alignment.Center) {
                    NavGlyph(screen, if (selected) Teal else Muted)
                }
                Text(label, color = if (selected) TealDark else Muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(Border))
}

@Composable
private fun NavGlyph(screen: Screen, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val s = size.minDimension
        when (screen) {
            Screen.HOME -> { drawRoundRect(color, Offset(s*.15f,s*.42f), Size(s*.7f,s*.48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)); val p=Path().apply{moveTo(s*.1f,s*.46f);lineTo(s*.5f,s*.1f);lineTo(s*.9f,s*.46f)};drawPath(p,color,style=Stroke(2.5f,cap=StrokeCap.Round)) }
            Screen.PRODUCTS -> { drawRect(color, Offset(s*.2f,s*.1f), Size(s*.6f,s*.8f), style=Stroke(2.5f)); repeat(3) { drawLine(color, Offset(s*.32f,s*(.32f+it*.18f)), Offset(s*.68f,s*(.32f+it*.18f)), strokeWidth=2f) } }
            Screen.OBJECTS, Screen.OBJECT_DETAIL -> { drawRoundRect(color, Offset(s*.13f,s*.1f), Size(s*.74f,s*.8f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f),style=Stroke(2.5f)); repeat(2){r->repeat(2){c->drawRect(color,Offset(s*(.26f+c*.31f),s*(.27f+r*.29f)),Size(s*.16f,s*.14f))}} }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink); Spacer(Modifier.height(3.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) }
        action?.invoke()
    }
}

@Composable
private fun HomeScreen(projects: List<Project>, onProject: (Project) -> Unit, onObjects: () -> Unit, sessions: List<com.example.mounter.attendance.CardWorkSession>) {
    var visibleProjectCount by rememberSaveable { mutableIntStateOf(5) }
    val visibleProjects = projects.take(visibleProjectCount)
    Column(Modifier.fillMaxSize().padding(28.dp, 22.dp, 28.dp, 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
            Text("Об’єктів: ${projects.size}", color=Muted, fontSize=13.sp, modifier=Modifier.weight(1f))
            Surface(shape=RoundedCornerShape(13.dp), color=Surface, border=androidx.compose.foundation.BorderStroke(1.dp,Border)) {
                Text("Дані на пристрої", color=Muted, fontSize=12.sp, modifier=Modifier.padding(14.dp,9.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            SurfaceCard(Modifier.weight(1.65f).fillMaxHeight()) {
                SectionTitle("Об'єкти", "Всі об'єкти", onObjects)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (projects.isEmpty()) item {
                        Text("Додайте об’єкт у вкладці «Об’єкти»", color=Muted)
                    }
                    items(visibleProjects, key={it.id}) { project ->
                        ProjectRow(project) { onProject(project) }
                        if (project.id != visibleProjects.last().id) HorizontalDivider(color=Border)
                    }
                    if (visibleProjectCount < projects.size) item {
                        TextButton(
                            onClick={ visibleProjectCount = (visibleProjectCount + 5).coerceAtMost(projects.size) },
                            modifier=Modifier.fillMaxWidth()
                        ) { Text("Показати більше") }
                    }
                }
            }
            SurfaceCard(Modifier.weight(1f).fillMaxHeight()) {
                CardWorkHours(sessions)
            }
        }
    }
}

@Composable private fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Surface(modifier, shape=RoundedCornerShape(20.dp), color=Surface, border=androidx.compose.foundation.BorderStroke(1.dp,Border)) { Column(Modifier.padding(18.dp), content=content) }

@Composable private fun SectionTitle(title:String, action:String, click:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,fontWeight=FontWeight.Bold,fontSize=17.sp,modifier=Modifier.weight(1f));Text(action,color=Teal,fontWeight=FontWeight.SemiBold,fontSize=12.sp,modifier=Modifier.clickable(onClick=click).padding(6.dp))}}

@Composable private fun ProjectRow(project: Project, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=16.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(project.displayName, fontWeight=FontWeight.Bold, fontSize=16.sp)
            Text("Виробів: ${project.blocks.size}", color=Muted, fontSize=12.sp)
        }
        Text(" ›", fontSize=25.sp, color=Muted)
    }
}

@Composable
private fun ObjectsScreen(projects: List<Project>, clients: List<Client>, onAddClient: (String) -> Client?, onProject: (Project) -> Unit, onPhoto: (Project, WorkBlock, PhotoEntry) -> Unit, onCreate: (Project) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var customer by rememberSaveable { mutableStateOf("") }
    var chosenClientId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosenClient = clients.find { it.id == chosenClientId }
    val matches = clients.filter { nameKey(it.name).contains(nameKey(customer)) }
    Column(Modifier.fillMaxSize().padding(28.dp,22.dp)) {
        PageHeader("Об'єкти", "${projects.size} об'єктів • медіа та примітки") {
            Button(onClick={ customer="";chosenClientId=null;creating=true }) { Text("Створити об'єкт") }
        }
        Spacer(Modifier.height(20.dp))
        if (projects.isEmpty()) Text("Створіть перший об'єкт: вкажіть замовника.", color=Muted)
        LazyRow(
            modifier=Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(16.dp)
        ) {
            items(projects, key={it.id}) { project ->
                ObjectCard(project, Modifier.width(290.dp).fillParentMaxHeight(), { block, photo -> onPhoto(project, block, photo) }) { onProject(project) }
            }
        }
    }
    if (creating) AlertDialog(
        modifier=Modifier.dismissInputOnOutsideTouch(),
        onDismissRequest={creating=false}, title={Text("Новий об'єкт")},
        text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(customer, {customer=it;chosenClientId=null}, label={Text("Замовник")}, singleLine=true, modifier=Modifier.finishEditingOnOutsideTouch())
            if(chosenClient!=null) Text("Обрано: ${chosenClient.name}",color=Teal)
            else {
                if(matches.isEmpty()) {
                    Text("Замовників не знайдено",color=Muted)
                    if(cleanName(customer).isNotBlank()) TextButton(onClick={
                        onAddClient(customer)?.let { client -> chosenClientId=client.id; customer=client.name }
                    }) {Text("Додати замовника")}
                }
                LazyColumn(Modifier.heightIn(max=220.dp)) {
                    items(matches,key={it.id}) {client ->
                        Text(client.name,modifier=Modifier.fillMaxWidth().clickable {
                            chosenClientId=client.id;customer=client.name
                        }.padding(vertical=12.dp))
                    }
                }
            }
        } },
        confirmButton={ TextButton(enabled=chosenClient!=null, onClick={
            chosenClient?.let {client -> onCreate(Project(name=client.name,customer=client.name,customerId=client.id))}
            customer="";chosenClientId=null;creating=false
        }) {Text("Створити")} },
        dismissButton={TextButton(onClick={creating=false}) {Text("Скасувати")}}
    )
}

@Composable
private fun ObjectCard(project: Project, modifier: Modifier, onPhoto: (WorkBlock, PhotoEntry) -> Unit, onOpen: () -> Unit) {
    SurfaceCard(modifier) {
        Text(project.displayName, fontWeight=FontWeight.Bold, fontSize=18.sp,
            modifier=Modifier.fillMaxWidth().clickable(onClick=onOpen))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
            Text("Виробів: ${project.blocks.size}", color=Muted, fontSize=12.sp, modifier=Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            items(project.blocks, key={it.id}) { product ->
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(product.name.ifBlank {"Виріб без назви"}, fontWeight=FontWeight.SemiBold, fontSize=13.sp)
                    Text("Характеристики", fontWeight=FontWeight.SemiBold, fontSize=12.sp)
                    Text(product.characteristics.ifBlank {"Характеристики ще не додані"},
                        color=if(product.characteristics.isBlank()) Muted else Ink, fontSize=12.sp)
                    if(product.photos.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp))
                            .background(CanvasColor).clickable(onClick=onOpen), contentAlignment=Alignment.Center) {
                            Text("Медіа ще не додані", color=Muted)
                        }
                    }
                    product.photos.forEach {photo ->
                        key(photo.id) {
                            Box(Modifier.clickable { onPhoto(product, photo) }) { MediaPreview(photo, height=160.dp) }
                        }
                    }
                    Text(product.note.ifBlank {"Примітка ще не додана"},
                        color=if(product.note.isBlank()) Muted else Ink, fontSize=12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick=onOpen, modifier=Modifier.fillMaxWidth()) { Text("Відкрити об'єкт") }
    }
}

@Composable
private fun ObjectDetail(project: Project, onBack: () -> Unit, onPhoto: (WorkBlock, PhotoEntry) -> Unit, onUpdate: (Project) -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp,20.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            BackButton(onBack); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {Text(project.displayName,fontWeight=FontWeight.Bold,fontSize=25.sp);Text("Виробів: ${project.blocks.size}",color=Muted,fontSize=12.sp)}
            Button(onClick={onUpdate(project.copy(blocks=project.blocks+WorkBlock()))}) {Text("Додати виріб")}
        }
        Spacer(Modifier.height(18.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(18.dp)) {
            items(project.blocks, key={it.id}) { block ->
                ProductBlock(block, { photo -> onPhoto(block, photo) }) { updated ->
                    onUpdate(project.copy(blocks=project.blocks.map {if(it.id==block.id)updated else it}))
                }
            }
        }
    }
}

@Composable
private fun ProductBlock(block: WorkBlock, onPhoto: (PhotoEntry) -> Unit, onUpdate: (WorkBlock) -> Unit) {
    val context=LocalContext.current
    val currentBlock by rememberUpdatedState(block)
    val update by rememberUpdatedState(onUpdate)
    var pendingPath by rememberSaveable {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var importing by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingPath?.let {path ->
            if(success) update(currentBlock.copy(photos=currentBlock.photos+PhotoEntry(path))) else File(path).delete()
        }
        pendingPath=null
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) {
            importing=true
            error=null
            scope.launch {
                val imported=mutableListOf<PhotoEntry>()
                var committed=false
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        uris.forEach { uri ->
                            var target: File? = null
                            try {
                                val resolver=context.contentResolver
                                val name=resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                    if(cursor.moveToFirst()) cursor.getString(0) else null
                                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Файл"
                                val mime=resolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                                val safeName=name.substringAfterLast('/').substringAfterLast('\\').takeUnless {it.isBlank() || it=="." || it==".."} ?: "Файл"
                                val file=File(context.filesDir,"media/${UUID.randomUUID()}/$safeName")
                                    .also {it.parentFile?.mkdirs()}
                                target=file
                                resolver.openInputStream(uri)?.use {input ->
                                    file.outputStream().use {output -> input.copyTo(output)}
                                } ?: error("Файл недоступний")
                                imported.add(PhotoEntry(file.absolutePath, mimeType=mime, displayName=name))
                            } catch(e: kotlinx.coroutines.CancellationException) {
                                target?.delete()
                                throw e
                            } catch(_: Exception) {
                                target?.delete()
                            }
                        }
                    }
                    if(imported.isNotEmpty()) update(currentBlock.copy(photos=currentBlock.photos+imported))
                    committed=true
                    if(imported.size<uris.size) error="Не вдалося додати ${uris.size-imported.size} файлів. Спробуйте ще раз."
                } catch(e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch(_: Exception) {
                    error="Не вдалося зберегти медіа. Спробуйте ще раз."
                } finally {
                    if(!committed) imported.forEach {File(it.path).delete()}
                    importing=false
                }
            }
        }
    }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(
                value=block.name,
                onValueChange={name -> update(currentBlock.copy(name=name))},
                label={Text("Назва виробу")},
                placeholder={Text("Наприклад, шафа")},
                singleLine=true,
                modifier=Modifier.weight(1f).finishEditingOnOutsideTouch()
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(enabled=!importing, onClick={gallery.launch(arrayOf("*/*"))}) {Text(if(importing) "Додаємо медіа…" else "Додати медіа")}
            Spacer(Modifier.width(10.dp))
            Button(onClick={
                try {
                    val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg").also {it.parentFile?.mkdirs();it.createNewFile()}
                    pendingPath=file.absolutePath
                    camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.photos",file))
                } catch(_: Exception) {pendingPath?.let {File(it).delete()};pendingPath=null;error="Камера недоступна. Додайте фото з галереї."}
            }) {Text("Сфотографувати")}
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value=block.characteristics,
            onValueChange={text -> update(currentBlock.copy(characteristics=text))},
            label={Text("Характеристики")},
            placeholder={Text("Наприклад: розміри, матеріал, колір — у довільній формі")},
            modifier=Modifier.fillMaxWidth().finishEditingOnOutsideTouch(), minLines=3
        )
        error?.let {Text(it,color=Red)}
        Spacer(Modifier.height(12.dp))
        if(block.photos.isEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                repeat(3) {
                    Box(Modifier.weight(1f).height(200.dp).clip(RoundedCornerShape(14.dp))
                        .background(CanvasColor).clickable(enabled=!importing) { gallery.launch(arrayOf("*/*")) },
                        contentAlignment=Alignment.Center) {
                        Text("Додати медіа", color=Muted)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        block.photos.chunked(3).forEach { rowPhotos ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                rowPhotos.forEach {photo ->
                    key(photo.id) {
                        Box(Modifier.weight(1f).clickable {onPhoto(photo)}) {
                            MediaPreview(photo,height=200.dp)
                        }
                    }
                }
                repeat(3-rowPhotos.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(18.dp))
        }
        OutlinedTextField(
            value=block.note,
            onValueChange={note -> update(currentBlock.copy(note=note))},
            label={Text("Примітка до виробу")},
            placeholder={Text("Примітка для всіх медіа виробу")},
            modifier=Modifier.fillMaxWidth().finishEditingOnOutsideTouch(), minLines=3
        )
    }
}

@Composable
private fun MediaPreview(media: PhotoEntry, height: androidx.compose.ui.unit.Dp) {
    if(media.mimeType.startsWith("image/")) PhotoPreview(media.path, height)
    else Column(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp))
            .background(CanvasColor).padding(16.dp),
        verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Text(when {
            media.mimeType.startsWith("video/") -> "▶ Відео"
            media.mimeType.startsWith("audio/") -> "♫ Аудіо"
            else -> "Файл"
        }, color=Teal, fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(media.displayName, color=Ink, maxLines=3, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun FileViewer(media: PhotoEntry, onClose: () -> Unit, onDelete: () -> Unit) {
    val context=LocalContext.current
    var error by remember(media.id) {mutableStateOf<String?>(null)}
    AlertDialog(
        onDismissRequest=onClose,
        title={Text(media.displayName)},
        text={Column {
            Text("Відкрити файл у відповідному застосунку на пристрої.")
            error?.let {Text(it,color=Red)}
        }},
        confirmButton={TextButton(onClick={
            try {
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.photos",File(media.path))
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri,media.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Відкрити медіа"
                ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch(_: Exception) {error="Не вдалося відкрити файл. Перевірте наявність відповідного застосунку."}
        }) {Text("Відкрити")}},
        dismissButton={Row {
            TextButton(onClick={
                try {onDelete()} catch(_: Exception) {error="Не вдалося видалити файл. Спробуйте ще раз."}
            }) {Text("Видалити",color=Red)}
            TextButton(onClick=onClose) {Text("Закрити")}
        }}
    )
}

@Composable
private fun PhotoPreview(path: String, height: androidx.compose.ui.unit.Dp = 260.dp) {
    PhotoImage(path, Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp)).background(CanvasColor))
}

@Composable
private fun PhotoImage(path: String, modifier: Modifier, maxDimension: Int = 1600) {
    var bitmap by remember(path) {mutableStateOf<android.graphics.Bitmap?>(null)}
    var failed by remember(path) {mutableStateOf(false)}
    LaunchedEffect(path, maxDimension) {
        bitmap=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(path,bounds)
                var sample=1
                while(bounds.outWidth/sample>maxDimension || bounds.outHeight/sample>maxDimension) sample*=2
                BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=sample})
            }.getOrNull()
        }
        failed=bitmap==null
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it.asImageBitmap(),contentDescription="Фото об'єкта",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
            ?: Text(if(failed) "Не вдалося відкрити фото" else "Завантаження фото…",color=Muted)
    }
}

@Composable
private fun PhotoViewer(photo: PhotoEntry, onClose: () -> Unit, onReplace: (String) -> Unit, onDelete: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val replace by rememberUpdatedState(onReplace)
    var pendingPath by rememberSaveable {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(pendingPath!=null)}
    var error by remember {mutableStateOf<String?>(null)}
    var chooseSource by remember {mutableStateOf(false)}
    fun acceptReplacement(path: String) {
        try {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,bounds)
            check(bounds.outWidth>0 && bounds.outHeight>0)
            replace(path)
            error=null
        } catch(_: Exception) {
            File(path).delete()
            error="Не вдалося замінити фото. Спробуйте ще раз."
        }
    }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingPath?.let {path -> if(success) acceptReplacement(path) else File(path).delete()}
        pendingPath=null
        busy=false
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) {
            busy=true
            error=null
            scope.launch {
                var copiedPath: String? = null
                var committed=false
                try {
                    val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg")
                    copiedPath=file.absolutePath
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        file.parentFile?.mkdirs()
                        try {
                            context.contentResolver.openInputStream(uri)?.use {input ->
                                file.outputStream().use {output -> input.copyTo(output)}
                            } ?: error("Фото недоступне")
                            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                            BitmapFactory.decodeFile(file.absolutePath,bounds)
                            check(bounds.outWidth>0 && bounds.outHeight>0)
                            file.absolutePath
                        } catch(e: Exception) {file.delete();throw e}
                    }
                    replace(checkNotNull(copiedPath))
                    committed=true
                } catch(e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch(_: Exception) {
                    error="Не вдалося замінити фото. Спробуйте ще раз."
                } finally {
                    if(!committed) copiedPath?.let {File(it).delete()}
                    busy=false
                }
            }
        }
    }
    Dialog(onDismissRequest={if(!busy) onClose()}, properties=DialogProperties(
        usePlatformDefaultWidth=false, decorFitsSystemWindows=false,
        dismissOnBackPress=!busy, dismissOnClickOutside=false
    )) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            PhotoImage(photo.path, Modifier.fillMaxSize(), maxDimension=3200)
            IconButton(onClick=onClose, enabled=!busy,
                modifier=Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha=.7f),CircleShape)
                    .semantics {contentDescription="Закрити фото"}) {
                Canvas(Modifier.size(22.dp)) {
                    drawLine(Color.White,Offset(size.width*.2f,size.height*.2f),Offset(size.width*.8f,size.height*.8f),3.dp.toPx(),StrokeCap.Round)
                    drawLine(Color.White,Offset(size.width*.8f,size.height*.2f),Offset(size.width*.2f,size.height*.8f),3.dp.toPx(),StrokeCap.Round)
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box {
                    IconButton(onClick={chooseSource=true},enabled=!busy,
                        modifier=Modifier.background(Color.Black.copy(alpha=.7f),CircleShape).semantics {contentDescription="Змінити фото"}) {
                        PhotoActionGlyph(delete=false)
                    }
                    DropdownMenu(chooseSource,{chooseSource=false}) {
                        DropdownMenuItem(text={Text("Вибрати з галереї")},onClick={chooseSource=false;gallery.launch("image/*")})
                        DropdownMenuItem(text={Text("Сфотографувати")},onClick={
                            chooseSource=false
                            try {
                                val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg").also {it.parentFile?.mkdirs();it.createNewFile()}
                                pendingPath=file.absolutePath
                                busy=true
                                camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.photos",file))
                            } catch(_: Exception) {
                                pendingPath?.let {File(it).delete()};pendingPath=null;busy=false
                                error="Камера недоступна. Виберіть фото з галереї."
                            }
                        })
                    }
                }
                IconButton(onClick={
                    try {onDelete()} catch(_: Exception) {error="Не вдалося видалити фото. Спробуйте ще раз."}
                },enabled=!busy,modifier=Modifier.background(Color.Black.copy(alpha=.7f),CircleShape)
                    .semantics {contentDescription="Видалити фото"}) {
                    PhotoActionGlyph(delete=true)
                }
            }
            if(busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            error?.let {Text(it,color=Red,modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp)
                .background(Color.Black.copy(alpha=.8f),RoundedCornerShape(12.dp)).padding(12.dp))}
        }
    }
}

@Composable
private fun PhotoActionGlyph(delete: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val w=size.width
        val h=size.height
        val stroke=Stroke(width=2.dp.toPx(),cap=StrokeCap.Round)
        if(delete) {
            drawLine(Color.White,Offset(w*.18f,h*.25f),Offset(w*.82f,h*.25f),stroke.width,StrokeCap.Round)
            drawLine(Color.White,Offset(w*.38f,h*.12f),Offset(w*.62f,h*.12f),stroke.width,StrokeCap.Round)
            val outline=Path().apply {moveTo(w*.28f,h*.32f);lineTo(w*.32f,h*.85f);lineTo(w*.68f,h*.85f);lineTo(w*.72f,h*.32f)}
            drawPath(outline,Color.White,style=stroke)
            drawLine(Color.White,Offset(w*.43f,h*.42f),Offset(w*.44f,h*.73f),stroke.width,StrokeCap.Round)
            drawLine(Color.White,Offset(w*.57f,h*.42f),Offset(w*.56f,h*.73f),stroke.width,StrokeCap.Round)
        } else {
            val pencil=Path().apply {
                moveTo(w*.2f,h*.8f);lineTo(w*.25f,h*.59f);lineTo(w*.68f,h*.16f)
                lineTo(w*.84f,h*.32f);lineTo(w*.41f,h*.75f);close()
            }
            drawPath(pencil,Color.White,style=stroke)
            drawLine(Color.White,Offset(w*.59f,h*.25f),Offset(w*.75f,h*.41f),stroke.width,StrokeCap.Round)
        }
    }
}

@Composable private fun BackButton(onClick:()->Unit){Surface(Modifier.size(42.dp).clickable(onClick=onClick),shape=RoundedCornerShape(12.dp),color=Surface,border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Box(contentAlignment=Alignment.Center){Text("‹",fontSize=28.sp,color=Ink)}}}