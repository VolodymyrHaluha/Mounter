package com.example.mounter

import android.os.Bundle
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
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
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
private val Orange = Color(0xFFF0A442)
private val Red = Color(0xFFD96C67)
private val Border = Color(0xFF537265)

data class PhotoEntry(val path: String, val note: String = "", val id: String = UUID.randomUUID().toString())
data class WorkBlock(
    val id: String = UUID.randomUUID().toString(),
    val photos: List<PhotoEntry> = emptyList(),
    val name: String = ""
)
data class Project(
    val name: String, val customer: String,
    val id: String = UUID.randomUUID().toString(),
    val status: WorkStatus = WorkStatus.TODO,
    val blocks: List<WorkBlock> = listOf(WorkBlock())
) {
    // Keep the old name field readable for previously saved objects.
    val displayName: String get() = customer.ifBlank { name }
}
data class Worker(val initials: String, val name: String, val role: String, val color: Color)
enum class WorkStatus { DONE, IN_PROGRESS, CANCELLED, TODO }
enum class Screen { HOME, OBJECTS, TEAM, PROFILE, OBJECT_DETAIL }

private class ProjectStore(private val context: Context) {
    private val file get() = File(context.filesDir, "objects.json")
    fun load(): List<Project> {
        if (!file.exists()) return emptyList()
        val data = JSONArray(file.readText())
        return (0 until data.length()).map { i ->
            val p = data.getJSONObject(i)
            val blocks = p.getJSONArray("blocks")
            Project(p.getString("name"), p.getString("customer"), p.getString("id"),
                WorkStatus.valueOf(p.getString("status")), (0 until blocks.length()).map { j ->
                    val b = blocks.getJSONObject(j)
                    val photos = b.getJSONArray("photos")
                    WorkBlock(b.getString("id"), (0 until photos.length()).map { k ->
                        val photo = photos.getJSONObject(k)
                        PhotoEntry(photo.getString("path"), photo.getString("note"), photo.getString("id"))
                    }, name = b.optString("name", ""))
                })
        }
    }
    fun save(projects: List<Project>) {
        val data = JSONArray()
        projects.forEach { p ->
            val blocks = JSONArray()
            p.blocks.forEach { b ->
                val photos = JSONArray()
                b.photos.forEach { photo -> photos.put(JSONObject().put("id", photo.id).put("path", photo.path).put("note", photo.note)) }
                blocks.put(JSONObject().put("id", b.id).put("name", b.name).put("photos", photos))
            }
            data.put(JSONObject().put("id", p.id).put("name", p.name).put("customer", p.customer).put("status", p.status.name).put("blocks", blocks).put("customer_id", p.customerId ?: JSONObject.NULL))
        }
        val temporary = File(context.filesDir, "objects.tmp")
        temporary.writeText(data.toString())
        check(temporary.renameTo(file)) { "Не вдалося зберегти об'єкти" }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MounterTheme { MounterApp() } }
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
        content = { CompositionLocalProvider(LocalContentColor provides Ink) { content() } }
    )
}

@Composable
fun MounterApp() {
    val context = LocalContext.current
    val store = remember { ProjectStore(context) }
    var projects by remember { mutableStateOf(store.load()) }
    val teamPreferences = remember { context.getSharedPreferences("team", Context.MODE_PRIVATE) }
    var teamMembers by remember {
        mutableStateOf(
            teamPreferences.getStringSet("members", setOf("0", "1", "2", "3"))
                .orEmpty().mapNotNull { it.toIntOrNull() }
                .filter { it in workers.indices }.sorted()
        )
    }
    fun saveTeam(members: List<Int>) {
        teamPreferences.edit().putStringSet("members", members.map { it.toString() }.toSet()).apply()
        teamMembers = members.sorted()
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
        else block.photos.map { if (it.id == oldPhoto.id) it.copy(path = newPath) else it }
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
                    Screen.HOME -> HomeScreen(projects, teamMembers.map { workers[it] }, ::open, { screen = Screen.OBJECTS }, { screen = Screen.TEAM })
                    Screen.OBJECTS -> ObjectsScreen(projects, ::open, ::openPhoto) { project ->
                        val updated = projects + project
                        store.save(updated)
                        projects = updated
                        open(project)
                    }
                    Screen.TEAM -> TeamScreen(teamMembers, ::saveTeam)
                    Screen.OBJECT_DETAIL -> projects.find { it.id == selectedId }?.let { project ->
                        ObjectDetail(project, { screen = Screen.OBJECTS }, { block, photo -> openPhoto(project, block, photo) }, ::update)
                    }
                    Screen.PROFILE -> ProfileScreen()
                }
            }
        }
    }
    photoSelection?.let { selection ->
        val photo = projects.find { it.id == selection[0] }?.blocks
            ?.find { it.id == selection[1] }?.photos?.find { it.id == selection[2] }
        if (photo != null) key(selection) {
            PhotoViewer(photo, onClose = { photoSelection = null },
                onReplace = { changePhoto(it) }, onDelete = { changePhoto(null) })
        }
    }
}

@Composable
private fun NavigationRail(active: Screen, onSelect: (Screen) -> Unit) {
    val items = listOf(Screen.HOME to "Головна", Screen.OBJECTS to "Об'єкти", Screen.TEAM to "Бригада")
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
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(48.dp).clip(CircleShape).background(Mint).clickable { onSelect(Screen.PROFILE) }, contentAlignment = Alignment.Center) {
            Text("АК", color = TealDark, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp)); Text("Андрій", fontSize = 11.sp, color = Ink, fontWeight = FontWeight.SemiBold)
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(Border))
}

@Composable
private fun NavGlyph(screen: Screen, color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val s = size.minDimension
        when (screen) {
            Screen.HOME -> { drawRoundRect(color, Offset(s*.15f,s*.42f), Size(s*.7f,s*.48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)); val p=Path().apply{moveTo(s*.1f,s*.46f);lineTo(s*.5f,s*.1f);lineTo(s*.9f,s*.46f)};drawPath(p,color,style=Stroke(2.5f,cap=StrokeCap.Round)) }
            Screen.OBJECTS, Screen.OBJECT_DETAIL -> { drawRoundRect(color, Offset(s*.13f,s*.1f), Size(s*.74f,s*.8f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f),style=Stroke(2.5f)); repeat(2){r->repeat(2){c->drawRect(color,Offset(s*(.26f+c*.31f),s*(.27f+r*.29f)),Size(s*.16f,s*.14f))}} }
            else -> { drawCircle(color,s*.17f,Offset(s*.5f,s*.3f));drawArc(color,200f,140f,false,Offset(s*.13f,s*.43f),Size(s*.74f,s*.52f),style=Stroke(3f,cap=StrokeCap.Round)) }
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
private fun HomeScreen(projects: List<Project>, team: List<Worker>, onProject: (Project) -> Unit, onObjects: () -> Unit, onTeam: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp, 22.dp, 28.dp, 18.dp)) {
        PageHeader("Добрий ранок, Андрію!", "Об’єктів: ${projects.size}") {
            Surface(modifier=Modifier.clickable(onClick=onConnection),shape=RoundedCornerShape(13.dp),color=Surface,border=androidx.compose.foundation.BorderStroke(1.dp,Border)) {
                Row(Modifier.padding(14.dp,9.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(when(databaseStatus) {
                        DatabaseStatus.CONNECTED -> Teal
                        DatabaseStatus.CHECKING -> Orange
                        DatabaseStatus.ERROR -> Red
                    }))
                    Spacer(Modifier.width(8.dp))
                    Text(when(databaseStatus) {
                        DatabaseStatus.CONNECTED -> "БД підключено"
                        DatabaseStatus.CHECKING -> "Підключення до БД…"
                        DatabaseStatus.ERROR -> "Помилка підключення до БД"
                    },color=Muted,fontSize=12.sp)
                }
            }
        }
        Spacer(Modifier.height(19.dp))
        Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatCard("Виконано", projects.count {it.status==WorkStatus.DONE}.toString(), "об’єктів", Teal, Modifier.weight(1f))
            StatCard("В процесі", projects.count {it.status==WorkStatus.IN_PROGRESS}.toString(), "об’єктів", Orange, Modifier.weight(1f))
            StatCard("Скасовано", projects.count {it.status==WorkStatus.CANCELLED}.toString(), "об’єктів", Red, Modifier.weight(1f))
            SurfaceCard(Modifier.weight(1.25f).fillMaxHeight()) { Text("Очікують роботи", color=Muted, fontSize=12.sp); Text(projects.count {it.status==WorkStatus.TODO}.toString(), fontSize=30.sp, fontWeight=FontWeight.Bold) }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            SurfaceCard(Modifier.weight(1.65f).fillMaxHeight()) {
                SectionTitle("Активні об'єкти", "Всі об'єкти", onObjects)
                Spacer(Modifier.height(10.dp))
                if (projects.isEmpty()) Text("Додайте об’єкт у вкладці «Об’єкти»",color=Muted)
                projects.take(2).forEach { ProjectRow(it) { onProject(it) }; if (it != projects.take(2).last()) HorizontalDivider(color=Border) }
            }
            SurfaceCard(Modifier.weight(1f).fillMaxHeight()) {
                SectionTitle("Моя бригада", "Керувати", onTeam)
                Text("У складі: ${team.size}", color=Muted, fontSize=12.sp)
                Spacer(Modifier.height(15.dp))
                if (team.isEmpty()) {
                    Text("Склад бригади не обрано. Додайте співробітників у вкладці «Бригада».", color=Muted, fontSize=12.sp)
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(team, key={it.initials}) { WorkerCompact(it) }
                    }
                }
            }
        }
    }
}

@Composable private fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Surface(modifier, shape=RoundedCornerShape(20.dp), color=Surface, border=androidx.compose.foundation.BorderStroke(1.dp,Border)) { Column(Modifier.padding(18.dp), content=content) }

@Composable
private fun StatCard(label:String, value:String, note:String, color:Color, modifier:Modifier) {
    Surface(modifier.fillMaxHeight(), shape=RoundedCornerShape(18.dp), color=Surface, border=androidx.compose.foundation.BorderStroke(1.dp,Border)) {
        Column(Modifier.padding(16.dp)) { Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(9.dp).clip(CircleShape).background(color));Spacer(Modifier.width(8.dp));Text(label,color=Muted,fontSize=12.sp,fontWeight=FontWeight.Medium)};Spacer(Modifier.weight(1f));Row(verticalAlignment=Alignment.Bottom){Text(value,fontSize=30.sp,fontWeight=FontWeight.Bold,color=Ink);Spacer(Modifier.width(10.dp));Text(note,color=Muted,fontSize=11.sp,modifier=Modifier.padding(bottom=5.dp))} }
    }
}

@Composable private fun SectionTitle(title:String, action:String, click:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,fontWeight=FontWeight.Bold,fontSize=17.sp,modifier=Modifier.weight(1f));Text(action,color=Teal,fontWeight=FontWeight.SemiBold,fontSize=12.sp,modifier=Modifier.clickable(onClick=click).padding(6.dp))}}

@Composable private fun ProjectRow(project: Project, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=16.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(project.displayName, fontWeight=FontWeight.Bold, fontSize=16.sp)
            Text("Блоків: ${project.blocks.size}", color=Muted, fontSize=12.sp)
        }
        StatusPill(project.status)
        Text(" ›", fontSize=25.sp, color=Muted)
    }
}

@Composable private fun WorkerCompact(worker:Worker){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Avatar(worker,38);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(worker.name,fontWeight=FontWeight.SemiBold,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(worker.role,color=Muted,fontSize=10.sp)};Box(Modifier.size(7.dp).clip(CircleShape).background(Teal))}}

@Composable private fun Avatar(worker:Worker,size:Int){Box(Modifier.size(size.dp).clip(CircleShape).background(worker.color.copy(alpha=.18f)),contentAlignment=Alignment.Center){Text(worker.initials,color=Ink,fontWeight=FontWeight.Bold,fontSize=(size*.32).sp)}}

@Composable private fun ProductSketch(color:Color,modifier:Modifier=Modifier){Canvas(modifier.padding(10.dp)){drawRoundRect(color.copy(alpha=.28f),Offset(size.width*.12f,size.height*.12f),Size(size.width*.76f,size.height*.76f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(5f));drawRect(color,Offset(size.width*.2f,size.height*.24f),Size(size.width*.6f,size.height*.58f));drawLine(Color.White.copy(alpha=.7f),Offset(size.width*.5f,size.height*.25f),Offset(size.width*.5f,size.height*.81f),2f);drawCircle(Color.White,size.width*.025f,Offset(size.width*.46f,size.height*.52f));drawCircle(Color.White,size.width*.025f,Offset(size.width*.54f,size.height*.52f))}}

@Composable
private fun ObjectsScreen(projects: List<Project>, onProject: (Project) -> Unit, onPhoto: (Project, WorkBlock, PhotoEntry) -> Unit, onCreate: (Project) -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var customer by rememberSaveable { mutableStateOf("") }
    var chosenClient by remember { mutableStateOf<Client?>(null) }
    var clients by remember { mutableStateOf<List<Client>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(customer,creating,api) {
        clients=emptyList();searchError=null
        if(!creating) return@LaunchedEffect
        searching=true
        try {
            delay(300)
            clients=api.clients(customer)
        } catch(error: kotlinx.coroutines.CancellationException) {throw error}
        catch(error: Exception) {searchError=error.message ?: "Помилка пошуку замовника.";onError(searchError!!)}
        finally {searching=false}
    }
    Column(Modifier.fillMaxSize().padding(28.dp,22.dp)) {
        PageHeader("Об'єкти", "${projects.size} об'єктів • фото та примітки") {
            Button(onClick={ customer="";chosenClient=null;creating=true }) { Text("Створити об'єкт") }
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
        onDismissRequest={creating=false}, title={Text("Новий об'єкт")},
        text={ OutlinedTextField(customer, {customer=it}, label={Text("Замовник")}, singleLine=true) },
        confirmButton={ TextButton(enabled=customer.isNotBlank(), onClick={
            val value=customer.trim()
            onCreate(Project(name=value, customer=value)); customer=""; creating=false
        }) {Text("Створити")} },
        dismissButton={TextButton(onClick={creating=false}) {Text("Скасувати")}}
    )
}

@Composable
private fun ObjectCard(project: Project, modifier: Modifier, onPhoto: (WorkBlock, PhotoEntry) -> Unit, onOpen: () -> Unit) {
    val photos=project.blocks.flatMap { block -> block.photos.map { block to it } }
    SurfaceCard(modifier) {
        Text(project.displayName, fontWeight=FontWeight.Bold, fontSize=18.sp,
            modifier=Modifier.fillMaxWidth().clickable(onClick=onOpen))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
            Text("Блоків: ${project.blocks.size}", color=Muted, fontSize=12.sp, modifier=Modifier.weight(1f))
            StatusPill(project.status)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if (photos.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp))
                        .background(CanvasColor).clickable(onClick=onOpen), contentAlignment=Alignment.Center) {
                        Text("Фото ще не додані", color=Muted)
                    }
                }
            }
            items(photos, key={it.second.id}) { (block, photo) ->
                Column {
                    if (block.name.isNotBlank()) {
                        Text(block.name, fontWeight=FontWeight.SemiBold, fontSize=13.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    Box(Modifier.clickable { onPhoto(block, photo) }) { PhotoPreview(photo.path, height=160.dp) }
                    Spacer(Modifier.height(8.dp))
                    Text(photo.note.ifBlank { "Примітки ще не додані" },
                        color=if(photo.note.isBlank()) Muted else Ink, fontSize=12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick=onOpen, modifier=Modifier.fillMaxWidth()) { Text("Відкрити об'єкт") }
    }
}

@Composable
private fun TeamScreen(members: List<Int>, onSave: (List<Int>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(members) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp,22.dp)) {
        PageHeader("Моя бригада", if(saved) "Склад збережено" else "Оберіть співробітників, які працюють з вами сьогодні") {
            Button(onClick={onSave(selected);saved=true}) { Text("Зберегти склад") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if (photos.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp))
                        .background(CanvasColor).clickable(onClick=onOpen), contentAlignment=Alignment.Center) {
                        Text("Фото ще не додані", color=Muted)
                    }
                }
            }
            items(photos, key={it.second.id}) { (block, photo) ->
                Column {
                    if (block.name.isNotBlank()) {
                        Text(block.name, fontWeight=FontWeight.SemiBold, fontSize=13.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    Box(Modifier.clickable { onPhoto(block, photo) }) { PhotoPreview(photo.path, height=160.dp) }
                    Spacer(Modifier.height(8.dp))
                    Text(photo.note.ifBlank { "Примітки ще не додані" },
                        color=if(photo.note.isBlank()) Muted else Ink, fontSize=12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick=onOpen, modifier=Modifier.fillMaxWidth()) { Text("Відкрити об'єкт") }
    }
}

@Composable
private fun TeamScreen(members: List<Worker>, api: DirectoryApi, onError: (String) -> Unit, onSave: (List<Worker>) -> Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var results by remember {mutableStateOf<List<Worker>>(emptyList())}
    var searching by remember {mutableStateOf(false)}
    var searchError by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(query,api) {
        results=emptyList();searchError=null
        if(query.isBlank()) {searching=false;return@LaunchedEffect}
        searching=true
        try {
            delay(300)
            results=api.employees(query.trim())
        } catch(error: kotlinx.coroutines.CancellationException) {throw error}
        catch(error: Exception) {searchError=error.message ?: "Помилка пошуку співробітників.";onError(searchError!!)}
        finally {searching=false}
    }
    val available=results.filterNot {result -> members.any {it.id==result.id}}
    Column(Modifier.fillMaxSize().padding(28.dp,22.dp)) {
        PageHeader("Моя бригада", "У складі: ${members.size} • зміни зберігаються автоматично")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(query,{query=it},label={Text("Пошук співробітника за ПІБ")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        SurfaceCard(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn {
                if(query.isNotBlank()) {
                    item {
                        Text("Результати пошуку",fontWeight=FontWeight.Bold)
                        if(searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                        searchError?.let {Text(it,color=Red)}
                        if(!searching && available.isEmpty() && searchError==null) Text("Нових співробітників не знайдено",color=Muted)
                    }
                    items(available,key={"result-${it.id}"}) {worker ->
                        EmployeeChoice(worker,api,false) {checked -> if(checked) onSave((members+worker).distinctBy {it.id})}
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Обрані співробітники",fontWeight=FontWeight.Bold)
                    if(members.isEmpty()) Text("Знайдіть співробітника та позначте checkbox, щоб додати до бригади.",color=Muted)
                }
                items(members,key={"selected-${it.id}"}) {worker ->
                    EmployeeChoice(worker,api,true) {checked -> if(!checked) onSave(members.filterNot {it.id==worker.id})}
                }
            }
        }
    }
}

@Composable
private fun ObjectDetail(project: Project, onBack: () -> Unit, onPhoto: (WorkBlock, PhotoEntry) -> Unit, onUpdate: (Project) -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp,20.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            BackButton(onBack); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {Text(project.displayName,fontWeight=FontWeight.Bold,fontSize=25.sp);Text("Блоків: ${project.blocks.size}",color=Muted,fontSize=12.sp)}
            var expanded by remember {mutableStateOf(false)}
            Box {
                OutlinedButton(onClick={expanded=true}) {StatusPill(project.status);Text(" ▾")}
                DropdownMenu(expanded, {expanded=false}) {
                    WorkStatus.entries.forEach {status -> DropdownMenuItem(text={StatusPill(status)},onClick={onUpdate(project.copy(status=status));expanded=false})}
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick={onUpdate(project.copy(blocks=project.blocks+WorkBlock()))}) {Text("Додати блок")}
        }
        Spacer(Modifier.height(18.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(18.dp)) {
            items(project.blocks, key={it.id}) { block ->
                PhotoBlock(block, { photo -> onPhoto(block, photo) }) { updated ->
                    onUpdate(project.copy(blocks=project.blocks.map {if(it.id==block.id)updated else it}))
                }
            }
        }
    }
}

@Composable
private fun PhotoBlock(block: WorkBlock, onPhoto: (PhotoEntry) -> Unit, onUpdate: (WorkBlock) -> Unit) {
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
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if(uris.isNotEmpty()) {
            importing=true
            error=null
            scope.launch {
                try {
                    val imported=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        uris.mapNotNull { uri ->
                            var target: File? = null
                            try {
                                val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg")
                                    .also {it.parentFile?.mkdirs()}
                                target=file
                                context.contentResolver.openInputStream(uri)?.use {input ->
                                    file.outputStream().use {output -> input.copyTo(output)}
                                } ?: error("Фото недоступне")
                                PhotoEntry(file.absolutePath)
                            } catch(e: Exception) {
                                target?.delete()
                                null
                            }
                        }
                    }
                    if(imported.isNotEmpty()) update(currentBlock.copy(photos=currentBlock.photos+imported))
                    if(imported.size<uris.size) error="Не вдалося додати ${uris.size-imported.size} фото. Спробуйте ще раз."
                } finally {
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
                label={Text("Назва блоку")},
                placeholder={Text("Наприклад, кухня")},
                singleLine=true,
                modifier=Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(enabled=!importing, onClick={gallery.launch("image/*")}) {Text(if(importing) "Додаємо фото…" else "Додати фото")}
            Spacer(Modifier.width(10.dp))
            Button(onClick={
                try {
                    val file=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg").also {it.parentFile?.mkdirs();it.createNewFile()}
                    pendingPath=file.absolutePath
                    camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.photos",file))
                } catch(e: Exception) {pendingPath?.let {File(it).delete()};pendingPath=null;error="Камера недоступна. Додайте фото з галереї."}
            }) {Text("Сфотографувати")}
        }
        error?.let {Text(it,color=Red)}
        Spacer(Modifier.height(12.dp))
        if(block.photos.isEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                repeat(3) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp))
                            .background(CanvasColor).clickable(enabled=!importing) { gallery.launch("image/*") },
                            contentAlignment=Alignment.Center) {
                            Text("Додати фото", color=Muted)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Примітка з'явиться після додавання фото", color=Muted, fontSize=11.sp)
                    }
                }
            }
        }
        block.photos.chunked(3).forEach { rowPhotos ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                rowPhotos.forEach {photo ->
                    key(photo.id) {
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.clickable { onPhoto(photo) }) { PhotoPreview(photo.path, height=200.dp) }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value=photo.note,
                                onValueChange={note -> update(currentBlock.copy(photos=currentBlock.photos.map {if(it.id==photo.id)it.copy(note=note) else it}))},
                                label={Text("Примітки до фото")}, placeholder={Text("Характеристики та короткий опис")},
                                modifier=Modifier.fillMaxWidth(), minLines=2
                            )
                        }
                    }
                }
                repeat(3-rowPhotos.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
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
        } catch(e: Exception) {
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
                } catch(e: Exception) {
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
                            } catch(e: Exception) {
                                pendingPath?.let {File(it).delete()};pendingPath=null;busy=false
                                error="Камера недоступна. Виберіть фото з галереї."
                            }
                        })
                    }
                }
                IconButton(onClick={
                    try {onDelete()} catch(e: Exception) {error="Не вдалося видалити фото. Спробуйте ще раз."}
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

@Composable private fun StatusPill(status:WorkStatus){val data=when(status){WorkStatus.DONE->Triple("Виконано",Mint,TealDark);WorkStatus.IN_PROGRESS->Triple("В процесі",Color(0xFF4A381D),Color(0xFFFFCE88));WorkStatus.CANCELLED->Triple("Скасовано",Color(0xFF492B29),Color(0xFFFFB4AC));WorkStatus.TODO->Triple("Очікує",CanvasColor,Muted)};Box(Modifier.clip(CircleShape).background(data.second).padding(10.dp,6.dp)){Text(data.first,color=data.third,fontSize=10.sp,fontWeight=FontWeight.Bold)}}

@Composable private fun BackButton(onClick:()->Unit){Surface(Modifier.size(42.dp).clickable(onClick=onClick),shape=RoundedCornerShape(12.dp),color=Surface,border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Box(contentAlignment=Alignment.Center){Text("‹",fontSize=28.sp,color=Ink)}}}
@Composable private fun ProfileScreen() {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        SurfaceCard(Modifier.width(380.dp)) {
            Text("Монтажник",fontWeight=FontWeight.Bold,fontSize=20.sp)
            Text("Склад бригади обирається у вкладці «Бригада»",color=Muted)
        }
    }
}
