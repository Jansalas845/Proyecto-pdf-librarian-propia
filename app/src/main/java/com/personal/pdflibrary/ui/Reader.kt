@file:OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)

package com.personal.pdflibrary.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.personal.pdflibrary.data.DocE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.ceil

/** PdfRenderer no admite acceso concurrente: todo el renderizado pasa por un Mutex. */
class PdfSession(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val r = PdfRenderer(pfd)
    private val mutex = Mutex()
    val count = r.pageCount
    /** alto/ancho de cada página */
    val ratios = FloatArray(count).also { a ->
        for (i in 0 until count) { val p = r.openPage(i); a[i] = p.height.toFloat() / p.width; p.close() }
    }
    val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun render(i: Int, width: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            val p = r.openPage(i)
            try {
                val h = (width * p.height.toFloat() / p.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally { p.close() }
        }
    }

    fun close() { try { r.close(); pfd.close() } catch (_: Exception) {} }
}

private fun Modifier.pinchZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size >= 2) {            // dos dedos = zoom; un dedo = scroll normal
                val z = event.calculateZoom()
                if (z != 1f) onZoom(z)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
fun ReaderScreen(vm: LibVm, id: String) {
    var doc by remember { mutableStateOf<DocE?>(null) }
    var session by remember { mutableStateOf<PdfSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        try {
            val d = vm.repo.getDoc(id) ?: throw Exception("Documento no encontrado")
            val s = withContext(Dispatchers.IO) { PdfSession(vm.repo.pdfFile(id)) }
            doc = d; session = s
        } catch (e: Exception) { error = "No se pudo abrir el PDF: ${e.message}" }
    }
    DisposableEffect(id) { onDispose { session?.close() } }
    BackHandler { vm.closeDoc() }

    val d = doc; val s = session
    if (d == null || s == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("Abriendo…") }, navigationIcon = {
            IconButton(onClick = { vm.closeDoc() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } }) }) { p ->
            Box(Modifier.padding(p).fillMaxSize(), Alignment.Center) {
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
            }
        }
        return
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (d.lastPage - 1).coerceIn(0, maxOf(s.count - 1, 0)))
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    var zoom by remember { mutableFloatStateOf(1f) }
    var goTo by remember { mutableStateOf(false) }
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(1200).collect { vm.saveProgress(id, it + 1) }
    }
    DisposableEffect(id) {
        onDispose { vm.saveProgress(id, listState.firstVisibleItemIndex + 1) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(d.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    Text("Página $page / ${s.count}", style = MaterialTheme.typography.labelMedium)
                } },
                navigationIcon = { IconButton(onClick = { vm.closeDoc() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    IconButton(onClick = { zoom = 1f }) { Icon(Icons.Default.ZoomOutMap, "Restablecer zoom") }
                    IconButton(onClick = { goTo = true }) { Icon(Icons.Default.FormatListNumbered, "Ir a página") }
                })
        },
    ) { inner ->
        BoxWithConstraints(Modifier.padding(inner).fillMaxSize().background(Color(0xFF3A3A3A))) {
            val density = LocalDensity.current
            val baseW = maxWidth
            val pageW = baseW * zoom
            // Se renderiza a resolución escalonada (1x,2x,3x) para no gastar memoria de más.
            val renderPx = with(density) { (baseW.toPx() * ceil(zoom).coerceIn(1f, 3f)).toInt() }
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll).pinchZoom { z -> zoom = (zoom * z).coerceIn(1f, 4f) }) {
                LazyColumn(Modifier.width(pageW).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(s.count) { i -> PageView(s, i, renderPx, pageW, s.ratios[i]) }
                }
            }
        }
    }

    if (goTo) {
        var text by remember { mutableStateOf(page.toString()) }
        AlertDialog(
            onDismissRequest = { goTo = false }, title = { Text("Ir a la página (1–${s.count})") },
            text = { OutlinedTextField(text, { text = it.filter(Char::isDigit) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
            confirmButton = { TextButton(onClick = {
                text.toIntOrNull()?.let { n -> scope.launch { listState.scrollToItem((n - 1).coerceIn(0, s.count - 1)) } }
                goTo = false
            }) { Text("Ir") } },
            dismissButton = { TextButton(onClick = { goTo = false }) { Text("Cancelar") } })
    }
}

@Composable
private fun PageView(s: PdfSession, index: Int, widthPx: Int, widthDp: Dp, ratio: Float) {
    var bmp by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        val key = "$index@$widthPx"
        bmp = s.cache.get(key) ?: s.render(index, widthPx).also { s.cache.put(key, it) }
    }
    Box(Modifier.width(widthDp).aspectRatio(1f / ratio).background(Color.White)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
    }
}
