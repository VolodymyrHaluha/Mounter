package com.example.mounter

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

private data class RenderedPdfPage(val bitmap: Bitmap, val pageCount: Int)

// Open and close the renderer within each background operation, including failures.
private fun renderPdfPage(path: String, index: Int): RenderedPdfPage {
    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF не містить сторінок." }
            renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                // Bound both dimensions and total pixels for large engineering drawings.
                val scale = minOf(2400f / maxOf(page.width, page.height),
                    sqrt(6_000_000f / (page.width.toFloat() * page.height)))
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return RenderedPdfPage(bitmap, renderer.pageCount)
                } catch (failure: Throwable) { bitmap.recycle(); throw failure }
            }
        }
    }
}

@Composable
internal fun PdfViewer(drawing: DrawingEntry, onClose: () -> Unit) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var rendered by remember { mutableStateOf<RenderedPdfPage?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var zoom by remember(pageIndex) { mutableFloatStateOf(1f) }
    var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(drawing.path, pageIndex, retry) {
        rendered = null
        failure = null
        loading = true
        try {
            val result = withContext(Dispatchers.IO) { renderPdfPage(drawing.path, pageIndex) }
            pageCount = result.pageCount
            rendered = result
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            failure = "Не вдалося відкрити PDF. Файл може бути пошкоджений, захищений паролем або недоступний."
        } finally { loading = false }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(drawing.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onClose) { Text("Закрити") }
                }
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color(0xFF303030))
                    .onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
                    rendered?.let { page ->
                        Image(page.bitmap.asImageBitmap(), contentDescription = "Креслення, сторінка ${pageIndex + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().pointerInput(pageIndex, page.bitmap) {
                                detectTransformGestures { centroid, pan, zoomChange, _ ->
                                    val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                    val proposed = (offset + center - centroid) * (newZoom / zoom) + centroid - center + pan
                                    val limitX = viewport.width * (newZoom - 1f) / 2f
                                    val limitY = viewport.height * (newZoom - 1f) / 2f
                                    offset = Offset(proposed.x.coerceIn(-limitX, limitX), proposed.y.coerceIn(-limitY, limitY))
                                    zoom = newZoom
                                }
                            }.graphicsLayer {
                                scaleX = zoom; scaleY = zoom
                                translationX = offset.x; translationY = offset.y
                                clip = true
                            })
                    }
                    if (loading) CircularProgressIndicator()
                    failure?.let { message ->
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(message, color = Color.White)
                            TextButton(onClick = { retry++ }) { Text("Спробувати ще раз") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !loading && failure == null && pageIndex > 0, onClick = { pageIndex-- }) { Text("Попередня") }
                    Text(if (pageCount > 0) "${pageIndex + 1} / $pageCount" else "PDF")
                    TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("Скинути масштаб") }
                    TextButton(enabled = !loading && failure == null && pageIndex + 1 < pageCount,
                        onClick = { pageIndex++ }) { Text("Наступна") }
                }
            }
        }
    }
}
