package com.example.mounter

import android.os.Bundle
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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

data class Project(val name: String, val address: String, val done: Int, val total: Int, val color: Color)
data class Worker(val initials: String, val name: String, val role: String, val color: Color)
data class Product(val id: String, val name: String, val dimensions: String, val form: String, val status: WorkStatus)
enum class WorkStatus { DONE, IN_PROGRESS, CANCELLED, TODO }
enum class Screen { HOME, OBJECTS, TEAM, PROFILE, OBJECT_DETAIL, PRODUCT_DETAIL }

private val projects = listOf(
    Project("ЖК «Рив'єра»", "вул. Набережна, 12", 18, 24, Color(0xFF8AA9A3)),
    Project("БЦ «Горизонт»", "просп. Перемоги, 44", 8, 18, Color(0xFF907F71)),
    Project("Котедж Коваленків", "с. Козин, вул. Лісова, 7", 12, 12, Color(0xFF748B67))
)

private val workers = listOf(
    Worker("АК", "Андрій Коваленко", "Бригадир", Color(0xFF536E68)),
    Worker("МБ", "Максим Бондаренко", "Монтажник", Color(0xFF8C705F)),
    Worker("ОМ", "Олексій Мельник", "Монтажник", Color(0xFF697A95)),
    Worker("ДШ", "Дмитро Шевченко", "Помічник", Color(0xFF947865)),
    Worker("РТ", "Роман Ткаченко", "Монтажник", Color(0xFF6C836B))
)

private val products = listOf(
    Product("01", "Шафа кутова", "2400 × 900 × 600 мм", "Бланк №118", WorkStatus.DONE),
    Product("02", "Тумба під раковину", "850 × 1200 × 560 мм", "Бланк №119", WorkStatus.IN_PROGRESS),
    Product("03", "Пенал з нішею", "2200 × 600 × 450 мм", "Бланк №120", WorkStatus.TODO),
    Product("04", "Комод низький", "720 × 1800 × 480 мм", "Бланк №121", WorkStatus.CANCELLED)
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
        typography = Typography(defaultFontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif),
        content = content
    )
}

@Composable
fun MounterApp() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selectedProject by remember { mutableStateOf(projects.first()) }
    var selectedProduct by remember { mutableStateOf(products.first()) }
    Row(Modifier.fillMaxSize().background(CanvasColor)) {
        NavigationRail(screen) { screen = it }
        AnimatedContent(targetState = screen, label = "screen", modifier = Modifier.weight(1f)) { current ->
            when (current) {
                Screen.HOME -> HomeScreen(
                    onProject = { selectedProject = it; screen = Screen.OBJECT_DETAIL },
                    onObjects = { screen = Screen.OBJECTS },
                    onTeam = { screen = Screen.TEAM }
                )
                Screen.OBJECTS -> ObjectsScreen { selectedProject = it; screen = Screen.OBJECT_DETAIL }
                Screen.TEAM -> TeamScreen()
                Screen.OBJECT_DETAIL -> ObjectDetail(selectedProject, { screen = Screen.OBJECTS }) { selectedProduct = it; screen = Screen.PRODUCT_DETAIL }
                Screen.PRODUCT_DETAIL -> ProductDetail(selectedProduct) { screen = Screen.OBJECT_DETAIL }
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
            val selected = active == screen || (screen == Screen.OBJECTS && active in listOf(Screen.OBJECT_DETAIL, Screen.PRODUCT_DETAIL))
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
            Screen.OBJECTS, Screen.OBJECT_DETAIL, Screen.PRODUCT_DETAIL -> { drawRoundRect(color, Offset(s*.13f,s*.1f), Size(s*.74f,s*.8f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f),style=Stroke(2.5f)); repeat(2){r->repeat(2){c->drawRect(color,Offset(s*(.26f+c*.31f),s*(.27f+r*.29f)),Size(s*.16f,s*.14f))}} }
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
private fun HomeScreen(onProject: (Project) -> Unit, onObjects: () -> Unit, onTeam: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp, 22.dp, 28.dp, 18.dp)) {
        PageHeader("Добрий ранок, Андрію!", "Понеділок, 5 жовтня  •  Заплановано 4 об'єкти") {
            Surface(shape = RoundedCornerShape(13.dp), color = Surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(14.dp, 9.dp), verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(Teal)); Spacer(Modifier.width(8.dp)); Text("Синхронізовано", color=Muted, fontSize=12.sp) } }
        }
        Spacer(Modifier.height(19.dp))
        Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatCard("Виконано", "18", "+6 сьогодні", Teal, Modifier.weight(1f))
            StatCard("В процесі", "7", "на 3 об'єктах", Orange, Modifier.weight(1f))
            StatCard("Скасовано", "2", "потребують уваги", Red, Modifier.weight(1f))
            ProgressCard(Modifier.weight(1.25f))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            SurfaceCard(Modifier.weight(1.65f).fillMaxHeight()) {
                SectionTitle("Активні об'єкти", "Всі об'єкти", onObjects)
                Spacer(Modifier.height(10.dp))
                projects.take(2).forEach { ProjectRow(it) { onProject(it) }; if (it != projects[1]) HorizontalDivider(color=Border) }
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

@Composable private fun ProgressCard(modifier:Modifier) { Surface(modifier.fillMaxHeight(),shape=RoundedCornerShape(18.dp),color=Ink){Row(Modifier.padding(17.dp),verticalAlignment=Alignment.CenterVertically){Box(contentAlignment=Alignment.Center){Canvas(Modifier.size(73.dp)){drawArc(Color(0xFF3C4B4E),-90f,360f,false,style=Stroke(8f));drawArc(Color(0xFF5CC3B5),-90f,270f,false,style=Stroke(8f,cap=StrokeCap.Round))};Text("75%",color=Color.White,fontWeight=FontWeight.Bold,fontSize=16.sp)};Spacer(Modifier.width(15.dp));Column{Text("Прогрес на сьогодні",color=Color(0xFFAAB6B7),fontSize=11.sp);Spacer(Modifier.height(6.dp));Text("24 з 32 робіт",color=Color.White,fontWeight=FontWeight.Bold,fontSize=17.sp);Text("Ще 8 до плану",color=Color(0xFF7FD0C4),fontSize=11.sp)}}} }

@Composable private fun SectionTitle(title:String, action:String, click:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,fontWeight=FontWeight.Bold,fontSize=17.sp,modifier=Modifier.weight(1f));Text(action,color=Teal,fontWeight=FontWeight.SemiBold,fontSize=12.sp,modifier=Modifier.clickable(onClick=click).padding(6.dp))}}

@Composable private fun ProjectRow(project:Project,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){ProductSketch(project.color,Modifier.size(76.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFF0F2F0)));Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(project.name,fontWeight=FontWeight.Bold,fontSize=15.sp);Spacer(Modifier.height(4.dp));Text(project.address,color=Muted,fontSize=11.sp);Spacer(Modifier.height(9.dp));LinearProgressIndicator(progress={project.done.toFloat()/project.total},modifier=Modifier.fillMaxWidth(.75f).height(5.dp).clip(CircleShape),color=Teal,trackColor=Mint)};Column(horizontalAlignment=Alignment.End){Text("${project.done}/${project.total}",fontWeight=FontWeight.Bold,color=Ink);Text("виробів",color=Muted,fontSize=10.sp)};Spacer(Modifier.width(9.dp));Text("›",fontSize=25.sp,color=Muted)} }

@Composable private fun WorkerCompact(worker:Worker){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Avatar(worker,38);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(worker.name,fontWeight=FontWeight.SemiBold,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(worker.role,color=Muted,fontSize=10.sp)};Box(Modifier.size(7.dp).clip(CircleShape).background(Teal))}}

@Composable private fun Avatar(worker:Worker,size:Int){Box(Modifier.size(size.dp).clip(CircleShape).background(worker.color.copy(alpha=.18f)),contentAlignment=Alignment.Center){Text(worker.initials,color=worker.color,fontWeight=FontWeight.Bold,fontSize=(size*.32).sp)}}

@Composable private fun ProductSketch(color:Color,modifier:Modifier=Modifier){Canvas(modifier.padding(10.dp)){drawRoundRect(color.copy(alpha=.28f),Offset(size.width*.12f,size.height*.12f),Size(size.width*.76f,size.height*.76f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(5f));drawRect(color,Offset(size.width*.2f,size.height*.24f),Size(size.width*.6f,size.height*.58f));drawLine(Color.White.copy(alpha=.7f),Offset(size.width*.5f,size.height*.25f),Offset(size.width*.5f,size.height*.81f),2f);drawCircle(Color.White,size.width*.025f,Offset(size.width*.46f,size.height*.52f));drawCircle(Color.White,size.width*.025f,Offset(size.width*.54f,size.height*.52f))}}

@Composable
private fun ObjectsScreen(onProject:(Project)->Unit){Column(Modifier.fillMaxSize().padding(28.dp,22.dp)){PageHeader("Об'єкти", "Оберіть замовлення для перегляду виробів") { FilledTonalButton(onClick={},colors=ButtonDefaults.filledTonalButtonColors(containerColor=Mint,contentColor=TealDark)){Text("Усі  •  4",fontWeight=FontWeight.Bold)} };Spacer(Modifier.height(22.dp));LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp)){items(projects){project->Surface(Modifier.width(290.dp).fillParentMaxHeight(),shape=RoundedCornerShape(20.dp),color=Surface,border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Column(Modifier.clickable{onProject(project)}.padding(18.dp)){ProductSketch(project.color,Modifier.fillMaxWidth().height(135.dp).clip(RoundedCornerShape(15.dp)).background(project.color.copy(alpha=.1f)));Spacer(Modifier.height(17.dp));Text(project.name,fontWeight=FontWeight.Bold,fontSize=18.sp);Text(project.address,color=Muted,fontSize=12.sp);Spacer(Modifier.weight(1f));Row(verticalAlignment=Alignment.CenterVertically){LinearProgressIndicator(progress={project.done.toFloat()/project.total},modifier=Modifier.weight(1f).height(7.dp).clip(CircleShape),color=Teal,trackColor=Mint);Spacer(Modifier.width(12.dp));Text("${project.done}/${project.total}",fontWeight=FontWeight.Bold)};Spacer(Modifier.height(12.dp));Button(onClick={onProject(project)},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Teal),shape=RoundedCornerShape(12.dp)){Text("Відкрити об'єкт")}}}}}}

@Composable
private fun TeamScreen(){var selected by remember{mutableStateOf(setOf(0,1,2,3))};Column(Modifier.fillMaxSize().padding(28.dp,22.dp)){PageHeader("Моя бригада", "Оберіть співробітників, які працюють з вами сьогодні") { Button(onClick={},colors=ButtonDefaults.buttonColors(containerColor=Teal),shape=RoundedCornerShape(12.dp)){Text("Зберегти склад")} };Spacer(Modifier.height(20.dp));Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(18.dp)){SurfaceCard(Modifier.weight(1.8f).fillMaxHeight()){Text("Співробітники",fontWeight=FontWeight.Bold,fontSize=17.sp);Spacer(Modifier.height(8.dp));LazyColumn{items(workers.indices.toList()){index->val w=workers[index];Row(Modifier.fillMaxWidth().clickable{selected=if(index in selected)selected-index else selected+index}.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically){Avatar(w,46);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(w.name,fontWeight=FontWeight.SemiBold);Text(w.role,color=Muted,fontSize=11.sp)};Checkbox(checked=index in selected,onCheckedChange={checked->selected=if(checked)selected+index else selected-index},colors=CheckboxDefaults.colors(checkedColor=Teal))};HorizontalDivider(color=Border)}}};SurfaceCard(Modifier.weight(1f).fillMaxHeight()){Text("Бригада №3",fontWeight=FontWeight.Bold,fontSize=18.sp);Text("${selected.size} людини обрано",color=Muted,fontSize=12.sp);Spacer(Modifier.height(20.dp));Box(Modifier.fillMaxWidth().height(94.dp).clip(RoundedCornerShape(16.dp)).background(Mint),contentAlignment=Alignment.Center){Text("На зміні\n08:00 — 18:00",color=TealDark,fontWeight=FontWeight.Bold,fontSize=17.sp)};Spacer(Modifier.height(14.dp));selected.sorted().forEach{WorkerCompact(workers[it])}}}}}

@Composable
private fun ObjectDetail(project:Project,onBack:()->Unit,onProduct:(Product)->Unit){Column(Modifier.fillMaxSize().padding(28.dp,20.dp)){Row(verticalAlignment=Alignment.CenterVertically){BackButton(onBack);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(project.name,fontWeight=FontWeight.Bold,fontSize=25.sp);Text(project.address,color=Muted,fontSize=12.sp)};StatusPill(WorkStatus.IN_PROGRESS)};Spacer(Modifier.height(18.dp));Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(18.dp)){SurfaceCard(Modifier.width(245.dp).fillMaxHeight()){ProductSketch(project.color,Modifier.fillMaxWidth().height(145.dp).clip(RoundedCornerShape(16.dp)).background(project.color.copy(alpha=.12f)));Spacer(Modifier.height(16.dp));Text("Прогрес об'єкта",color=Muted,fontSize=12.sp);Text("${project.done} з ${project.total}",fontWeight=FontWeight.Bold,fontSize=25.sp);Spacer(Modifier.height(8.dp));LinearProgressIndicator(progress={project.done.toFloat()/project.total},modifier=Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),color=Teal,trackColor=Mint);Spacer(Modifier.height(18.dp));Text("Замовник",color=Muted,fontSize=11.sp);Text("Олександр Коваленко",fontWeight=FontWeight.SemiBold,fontSize=13.sp)};SurfaceCard(Modifier.weight(1f).fillMaxHeight()){SectionTitle("Вироби", "${products.size} позиції",{});Spacer(Modifier.height(5.dp));LazyColumn{items(products){p->ProductRow(p){onProduct(p)};HorizontalDivider(color=Border)}}}}}}

@Composable private fun ProductRow(product:Product,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){ProductSketch(Color(0xFF798D87),Modifier.size(66.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF0F3F2)));Spacer(Modifier.width(13.dp));Box(Modifier.size(28.dp).clip(CircleShape).background(CanvasColor),contentAlignment=Alignment.Center){Text(product.id,fontSize=11.sp,fontWeight=FontWeight.Bold)};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(product.name,fontWeight=FontWeight.Bold,fontSize=14.sp);Text("${product.dimensions}  •  ${product.form}",color=Muted,fontSize=11.sp)};StatusPill(product.status);Spacer(Modifier.width(10.dp));Text("›",fontSize=25.sp,color=Muted)}}

@Composable private fun StatusPill(status:WorkStatus){val data=when(status){WorkStatus.DONE->Triple("Виконано",Mint,TealDark);WorkStatus.IN_PROGRESS->Triple("В процесі",Color(0xFFFFF1DC),Color(0xFFB16B0D));WorkStatus.CANCELLED->Triple("Скасовано",Color(0xFFFBE9E7),Color(0xFFB54C47));WorkStatus.TODO->Triple("Очікує",CanvasColor,Muted)};Box(Modifier.clip(CircleShape).background(data.second).padding(10.dp,6.dp)){Text(data.first,color=data.third,fontSize=10.sp,fontWeight=FontWeight.Bold)}}

@Composable
private fun ProductDetail(product:Product,onBack:()->Unit){var status by remember{mutableStateOf(product.status)};var hasPhoto by remember{mutableStateOf(false)};val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){bitmap->hasPhoto=bitmap!=null;if(bitmap!=null)status=WorkStatus.DONE};Column(Modifier.fillMaxSize().padding(28.dp,20.dp)){Row(verticalAlignment=Alignment.CenterVertically){BackButton(onBack);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text("Виріб ${product.id}",color=Muted,fontSize=12.sp);Text(product.name,fontWeight=FontWeight.Bold,fontSize=24.sp)};StatusPill(status)};Spacer(Modifier.height(17.dp));Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(18.dp)){SurfaceCard(Modifier.weight(1.1f).fillMaxHeight()){ProductSketch(Color(0xFF718A83),Modifier.fillMaxWidth().height(185.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xFFE9EFED)));Spacer(Modifier.height(16.dp));Text("Характеристики",fontWeight=FontWeight.Bold,fontSize=16.sp);Spacer(Modifier.height(10.dp));InfoLine("Розмір",product.dimensions);InfoLine("Номер",product.form);InfoLine("Матеріал","МДФ, дуб натуральний")};SurfaceCard(Modifier.weight(1f).fillMaxHeight()){Text("Підтвердження монтажу",fontWeight=FontWeight.Bold,fontSize=18.sp);Text("Додайте фото встановленого виробу",color=Muted,fontSize=12.sp);Spacer(Modifier.height(14.dp));Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(if(hasPhoto)Mint else CanvasColor).border(1.dp,if(hasPhoto)Teal else Border,RoundedCornerShape(16.dp)).clickable{camera.launch(null)},contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(56.dp).clip(CircleShape).background(if(hasPhoto)Teal else Color.White),contentAlignment=Alignment.Center){Text(if(hasPhoto)"✓" else "◉",fontSize=25.sp,color=if(hasPhoto)Color.White else Teal,fontWeight=FontWeight.Bold)};Spacer(Modifier.height(10.dp));Text(if(hasPhoto)"Фото додано" else "Сфотографувати",fontWeight=FontWeight.Bold);Text(if(hasPhoto)"Роботу позначено як виконану" else "Камера відкриється автоматично",color=Muted,fontSize=10.sp)}};Spacer(Modifier.height(13.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onClick={status=WorkStatus.CANCELLED},modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Red)){Text("Скасувати")};Button(onClick={camera.launch(null)},modifier=Modifier.weight(1.35f),shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.buttonColors(containerColor=Teal)){Text(if(hasPhoto)"Замінити фото" else "Додати фото")}}}}}}

@Composable private fun InfoLine(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text(label,color=Muted,fontSize=11.sp,modifier=Modifier.width(75.dp));Text(value,fontWeight=FontWeight.SemiBold,fontSize=11.sp)}}
@Composable private fun BackButton(onClick:()->Unit){Surface(Modifier.size(42.dp).clickable(onClick=onClick),shape=RoundedCornerShape(12.dp),color=Surface,border=androidx.compose.foundation.BorderStroke(1.dp,Border)){Box(contentAlignment=Alignment.Center){Text("‹",fontSize=28.sp,color=Ink)}}}
@Composable private fun ProfileScreen(){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){SurfaceCard(Modifier.width(380.dp)){Row(verticalAlignment=Alignment.CenterVertically){Avatar(workers.first(),70);Spacer(Modifier.width(16.dp));Column{Text(workers.first().name,fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Бригадир • бригада №3",color=Muted)}}}}}
