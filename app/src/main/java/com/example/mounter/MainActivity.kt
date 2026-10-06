package com.example.mounter

import android.os.Bundle
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
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

private val Ink = Color(0xFF172326)
private val Muted = Color(0xFF718084)
private val Teal = Color(0xFF0D8B7F)
private val TealDark = Color(0xFF08766D)
private val Mint = Color(0xFFE5F3EF)
private val Surface = Color.White
private val CanvasColor = Color(0xFFF4F7F8)
private val Orange = Color(0xFFF0A442)
private val Red = Color(0xFFD96C67)
private val Border = Color(0xFFE3E9EA)

data class PhotoEntry(val path: String, val note: String = "", val id: String = UUID.randomUUID().toString())
data class WorkBlock(val id: String = UUID.randomUUID().toString(), val photos: List<PhotoEntry> = emptyList())
data class Project(
    val name: String, val customer: String,
    val id: String = UUID.randomUUID().toString(),
    val status: WorkStatus = WorkStatus.TODO,
    val blocks: List<WorkBlock> = listOf(WorkBlock())
)
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
                    })
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
                blocks.put(JSONObject().put("id", b.id).put("photos", photos))
            }
            data.put(JSONObject().put("id", p.id).put("name", p.name).put("customer", p.customer).put("status", p.status.name).put("blocks", blocks))
        }
        val temporary = File(context.filesDir, "objects.tmp")
        temporary.writeText(data.toString())
        check(temporary.renameTo(file)) { "Не вдалося зберегти об'єкти" }
    }
}

private val workers = listOf(
    Worker("АК", "Андрій Коваленко", "Бригадир", Color(0xFF536E68)),
    Worker("МБ", "Максим Бондаренко", "Монтажник", Color(0xFF8C705F)),
    Worker("ОМ", "Олексій Мельник", "Монтажник", Color(0xFF697A95)),
    Worker("ДШ", "Дмитро Шевченко", "Помічник", Color(0xFF947865)),
    Worker("РТ", "Роман Ткаченко", "Монтажник", Color(0xFF6C836B))
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MounterTheme { MounterApp() } }
    }
}

@Composable
fun MounterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Teal, background = CanvasColor, surface = Surface, onSurface = Ink),
        typography = Typography(),
        content = content
    )
}

@Composable
fun MounterApp() {
    val context = LocalContext.current
    val store = remember { ProjectStore(context) }
    var projects by remember { mutableStateOf(store.load()) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    fun update(project: Project) {
        val updated = projects.map { if (it.id == project.id) project else it }
        store.save(updated)
        projects = updated
    }
    fun open(project: Project) { selectedId = project.id; screen = Screen.OBJECT_DETAIL }
    Row(Modifier.fillMaxSize().background(CanvasColor)) {
        NavigationRail(screen) { screen = it }
        AnimatedContent(targetState = screen, label = "screen", modifier = Modifier.weight(1f)) { current ->
            when (current) {
                Screen.HOME -> HomeScreen(projects, ::open, { screen = Screen.OBJECTS }, { screen = Screen.TEAM })
                Screen.OBJECTS -> ObjectsScreen(projects, ::open) { project ->
                    val updated = projects + project
                    store.save(updated)
                    projects = updated
                    open(project)
                }
                Screen.TEAM -> TeamScreen()
                Screen.OBJECT_DETAIL -> projects.find { it.id == selectedId }?.let { project ->
                    ObjectDetail(project, { screen = Screen.OBJECTS }, ::update)
                }
                Screen.PROFILE -> ProfileScreen()
            }
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
            Text("М", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
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
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFD1E1DC)).clickable { onSelect(Screen.PROFILE) }, contentAlignment = Alignment.Center) {
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
private fun HomeScreen(projects: List<Project>, onProject: (Project) -> Unit, onObjects: () -> Unit, onTeam: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp, 22.dp, 28.dp, 18.dp)) {
        PageHeader("Добрий ранок, Андрію!", "Об’єктів: ${projects.size}") {
            Surface(shape = RoundedCornerShape(13.dp), color = Surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(14.dp, 9.dp), verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(Teal)); Spacer(Modifier.width(8.dp)); Text("Синхронізовано", color=Muted, fontSize=12.sp) } }
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
                Text("4 людини • бригада №3", color=Muted, fontSize=12.sp)
                Spacer(Modifier.height(15.dp))
                workers.take(4).forEach { WorkerCompact(it) }
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
            Text(project.name, fontWeight=FontWeight.Bold, fontSize=16.sp)
            Text("Замовник: ${project.customer}", color=Muted, fontSize=12.sp)
            Text("Блоків: ${project.blocks.size}", color=Muted, fontSize=12.sp)
        }
        StatusPill(project.status)
        Text(" ›", fontSize=25.sp, color=Muted)
    }
}

@Composable private fun WorkerCompact(worker:Worker){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Avatar(worker,38);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(worker.name,fontWeight=FontWeight.SemiBold,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(worker.role,color=Muted,fontSize=10.sp)};Box(Modifier.size(7.dp).clip(CircleShape).background(Teal))}}

@Composable private fun Avatar(worker:Worker,size:Int){Box(Modifier.size(size.dp).clip(CircleShape).background(worker.color.copy(alpha=.18f)),contentAlignment=Alignment.Center){Text(worker.initials,color=worker.color,fontWeight=FontWeight.Bold,fontSize=(size*.32).sp)}}

@Composable private fun ProductSketch(color:Color,modifier:Modifier=Modifier){Canvas(modifier.padding(10.dp)){drawRoundRect(color.copy(alpha=.28f),Offset(size.width*.12f,size.height*.12f),Size(size.width*.76f,size.height*.76f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(5f));drawRect(color,Offset(size.width*.2f,size.height*.24f),Size(size.width*.6f,size.height*.58f));drawLine(Color.White.copy(alpha=.7f),Offset(size.width*.5f,size.height*.25f),Offset(size.width*.5f,size.height*.81f),2f);drawCircle(Color.White,size.width*.025f,Offset(size.width*.46f,size.height*.52f));drawCircle(Color.White,size.width*.025f,Offset(size.width*.54f,size.height*.52f))}}

@Composable
private fun ObjectsScreen(projects: List<Project>, onProject: (Project) -> Unit, onCreate: (Project) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var customer by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp,22.dp)) {
        PageHeader("Об'єкти", "${projects.size} об'єктів • фото та примітки") {
            Button(onClick={ creating=true }) { Text("Створити об'єкт") }
        }
        Spacer(Modifier.height(20.dp))
        if (projects.isEmpty()) Text("Створіть перший об'єкт: вкажіть назву та замовника.", color=Muted)
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(projects, key={it.id}) { project -> SurfaceCard(Modifier.fillMaxWidth()) { ProjectRow(project) { onProject(project) } } }
        }
    }
    if (creating) AlertDialog(
        onDismissRequest={creating=false}, title={Text("Новий об'єкт")},
        text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, {name=it}, label={Text("Назва об'єкта")}, singleLine=true)
            OutlinedTextField(customer, {customer=it}, label={Text("Замовник")}, singleLine=true)
        } },
        confirmButton={ TextButton(enabled=name.isNotBlank() && customer.isNotBlank(), onClick={
            onCreate(Project(name.trim(),customer.trim())); name=""; customer=""; creating=false
        }) {Text("Створити")} },
        dismissButton={TextButton(onClick={creating=false}) {Text("Скасувати")}}
    )
}

@Composable
private fun TeamScreen() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("team", Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf(preferences.getStringSet("members", setOf("0","1","2","3"))!!.map { it.toInt() }.toSet()) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp,22.dp)) {
        PageHeader("Моя бригада", if(saved) "Склад збережено" else "Оберіть співробітників, які працюють з вами сьогодні") {
            Button(onClick={preferences.edit().putStringSet("members",selected.map {it.toString()}.toSet()).apply();saved=true}) { Text("Зберегти склад") }
        }
        Spacer(Modifier.height(20.dp))
        SurfaceCard(Modifier.fillMaxSize()) {
            Text("Співробітники",fontWeight=FontWeight.Bold,fontSize=17.sp)
            LazyColumn {
                items(workers.indices.toList()) { index ->
                    val w=workers[index]
                    Row(Modifier.fillMaxWidth().clickable {selected=if(index in selected)selected-index else selected+index;saved=false}.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                        Avatar(w,46); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {Text(w.name,fontWeight=FontWeight.SemiBold);Text(w.role,color=Muted,fontSize=11.sp)}
                        Checkbox(checked=index in selected,onCheckedChange={checked->selected=if(checked)selected+index else selected-index;saved=false})
                    }
                    HorizontalDivider(color=Border)
                }
            }
        }
    }
}

@Composable
private fun ObjectDetail(project: Project, onBack: () -> Unit, onUpdate: (Project) -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp,20.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            BackButton(onBack); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {Text(project.name,fontWeight=FontWeight.Bold,fontSize=25.sp);Text("Замовник: ${project.customer}",color=Muted,fontSize=12.sp)}
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
                PhotoBlock(block, project.blocks.indexOf(block)+1) { updated ->
                    onUpdate(project.copy(blocks=project.blocks.map {if(it.id==block.id)updated else it}))
                }
            }
        }
    }
}

@Composable
private fun PhotoBlock(block: WorkBlock, number: Int, onUpdate: (WorkBlock) -> Unit) {
    val context=LocalContext.current
    val currentBlock by rememberUpdatedState(block)
    val update by rememberUpdatedState(onUpdate)
    var pendingPath by rememberSaveable {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingPath?.let {path ->
            if(success) update(currentBlock.copy(photos=currentBlock.photos+PhotoEntry(path))) else File(path).delete()
        }
        pendingPath=null
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) {
            var target: File? = null
            try {
                target=File(context.filesDir,"photos/${UUID.randomUUID()}.jpg").also {it.parentFile?.mkdirs()}
                context.contentResolver.openInputStream(uri)?.use {input -> target.outputStream().use {input.copyTo(it)} } ?: error("Фото недоступне")
                update(currentBlock.copy(photos=currentBlock.photos+PhotoEntry(target.absolutePath)))
            } catch (e: Exception) {target?.delete();error="Не вдалося додати фото. Спробуйте ще раз."}
        }
    }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Блок $number",fontWeight=FontWeight.Bold,fontSize=18.sp,modifier=Modifier.weight(1f))
            OutlinedButton(onClick={gallery.launch("image/*")}) {Text("Додати фото")}
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
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).background(CanvasColor),contentAlignment=Alignment.Center) {
                Text("Сфотографуйте або додайте фото",color=Muted)
            }
        }
        block.photos.forEach {photo ->
            key(photo.id) {
                PhotoPreview(photo.path)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value=photo.note,
                    onValueChange={note -> update(currentBlock.copy(photos=currentBlock.photos.map {if(it.id==photo.id)it.copy(note=note) else it}))},
                    label={Text("Примітки до фото")}, placeholder={Text("Характеристики та короткий опис")},
                    modifier=Modifier.fillMaxWidth(), minLines=2
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun PhotoPreview(path: String) {
    var bitmap by remember(path) {mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(path) {
        bitmap=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,bounds)
            var sample=1
            while(bounds.outWidth/sample>1600 || bounds.outHeight/sample>1600) sample*=2
            BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=sample})
        }
    }
    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)).background(CanvasColor),contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it.asImageBitmap(),contentDescription="Фото об'єкта",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
            ?: Text("Завантаження фото…",color=Muted)
    }
}

@Composable private fun StatusPill(status:WorkStatus){val data=when(status){WorkStatus.DONE->Triple("Виконано",Mint,TealDark);WorkStatus.IN_PROGRESS->Triple("В процесі",Color(0xFFFFF1DC),Color(0xFFB16B0D));WorkStatus.CANCELLED->Triple("Скасовано",Color(0xFFFBE9E7),Color(0xFFB54C47));WorkStatus.TODO->Triple("Очікує",CanvasColor,Muted)};Box(Modifier.clip(CircleShape).background(data.second).padding(10.dp,6.dp)){Text(data.first,color=data.third,fontSize=10.sp,fontWeight=FontWeight.Bold)}}

@Composable private fun BackButton(onClick:()->Unit){Surface(Modifier.size(42.dp).clickable(onClick=onClick),shape=RoundedCornerShape(12.dp),color=Surface,border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Box(contentAlignment=Alignment.Center){Text("‹",fontSize=28.sp,color=Ink)}}}
@Composable private fun ProfileScreen(){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){SurfaceCard(Modifier.width(380.dp)){Row(verticalAlignment=Alignment.CenterVertically){Avatar(workers.first(),70);Spacer(Modifier.width(16.dp));Column{Text(workers.first().name,fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Бригадир • бригада №3",color=Muted)}}}}}