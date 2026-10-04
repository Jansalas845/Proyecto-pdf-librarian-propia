@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.personal.pdflibrary.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.personal.pdflibrary.data.DocE
import com.personal.pdflibrary.data.FolderE

private fun pct(d: DocE) = if (d.pageCount > 0 && d.lastPage > 0) (d.lastPage * 100 / d.pageCount) else 0

private fun sortDocs(l: List<DocE>, s: Sort): List<DocE> = when (s) {
    Sort.NAME -> l.sortedBy { it.name.lowercase() }
    Sort.ADDED -> l.sortedByDescending { it.addedAt }
    Sort.MODIFIED -> l.sortedByDescending { it.modifiedAt }
    Sort.LAST_READ -> l.sortedByDescending { it.progressAt }
    Sort.PROGRESS -> l.sortedByDescending { pct(it) }
    Sort.RECENT -> l.sortedByDescending { maxOf(it.addedAt, it.modifiedAt, it.progressAt) }
}

private fun flatten(all: List<FolderE>, parent: String?, depth: Int, ids: Set<String>): List<Pair<FolderE, Int>> =
    all.filter { (if (it.parentId != null && it.parentId !in ids) null else it.parentId) == parent }
        .sortedBy { it.name.lowercase() }
        .flatMap { listOf(it to depth) + flatten(all, it.id, depth + 1, ids) }

private fun pathOf(id: String?, byId: Map<String, FolderE>): String {
    val parts = mutableListOf<String>()
    var cur = id; var guard = 0
    while (cur != null && guard++ < 20) { val f = byId[cur] ?: break; parts.add(0, f.name); cur = f.parentId }
    return parts.joinToString(" / ")
}

@Composable
fun LibraryScreen(vm: LibVm) {
    val folders by vm.folders.collectAsState()
    val docs by vm.docs.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importPdfs(it) }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.setCover(it) }

    val ids = remember(folders) { folders.map { it.id }.toSet() }
    val byId = remember(folders) { folders.associateBy { it.id } }
    val q = vm.query.trim()
    val atRoot = vm.section == Section.LIBRARY && vm.folderId == null

    val shownFolders = remember(folders, q, vm.section, vm.folderId) {
        when {
            q.isNotEmpty() -> folders.filter { it.name.contains(q, true) }
            vm.section == Section.LIBRARY -> folders.filter { (if (it.parentId != null && it.parentId !in ids) null else it.parentId) == vm.folderId }
                .sortedBy { it.name.lowercase() }
            else -> emptyList()
        }
    }
    val shownDocs = remember(docs, folders, q, vm.section, vm.folderId, vm.sort) {
        val base = when {
            q.isNotEmpty() -> docs.filter { d ->
                listOf(d.name, d.originalName, d.description, byId[d.folderId]?.name ?: "").any { it.contains(q, true) }
            }
            vm.section == Section.FAVORITES -> docs.filter { it.favorite }
            vm.section == Section.RECENT -> docs.filter { it.progressAt > 0 }.sortedByDescending { it.progressAt }.take(30)
            else -> docs.filter { (if (it.folderId != null && it.folderId !in ids) null else it.folderId) == vm.folderId }
        }
        if (vm.section == Section.RECENT && q.isEmpty()) base else sortDocs(base, vm.sort)
    }

    BackHandler(enabled = q.isNotEmpty() || !atRoot) {
        when {
            q.isNotEmpty() -> vm.query = ""
            vm.section != Section.LIBRARY -> vm.section = Section.LIBRARY
            else -> vm.folderId = byId[vm.folderId]?.parentId?.takeIf { it in ids }
        }
    }

    val title = when {
        vm.section == Section.FAVORITES -> "Favoritos"
        vm.section == Section.RECENT -> "Recientes"
        vm.folderId != null -> byId[vm.folderId]?.name ?: "Biblioteca"
        else -> "Biblioteca"
    }

    BoxWithConstraints {
        val wide = maxWidth >= 720.dp
        var fabMenu by remember { mutableStateOf(false) }
        var sortMenu by remember { mutableStateOf(false) }

        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            if (!wide && !atRoot) IconButton(onClick = {
                                if (vm.section != Section.LIBRARY) vm.section = Section.LIBRARY
                                else vm.folderId = byId[vm.folderId]?.parentId?.takeIf { it in ids }
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Ordenar") }
                                DropdownMenu(sortMenu, { sortMenu = false }) {
                                    Sort.values().forEach { s ->
                                        DropdownMenuItem(
                                            text = { Text(s.label) },
                                            leadingIcon = { if (vm.sort == s) Icon(Icons.Default.Check, null) },
                                            onClick = { vm.sort = s; sortMenu = false })
                                    }
                                }
                            }
                        })
                    OutlinedTextField(
                        value = vm.query, onValueChange = { vm.query = it },
                        placeholder = { Text("Buscar nombre, carpeta, descripción…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Close, "Limpiar") } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
                }
            },
            bottomBar = {
                if (!wide) NavigationBar {
                    NavigationBarItem(vm.section == Section.LIBRARY, { vm.section = Section.LIBRARY }, { Icon(Icons.Default.LibraryBooks, null) }, label = { Text("Biblioteca") })
                    NavigationBarItem(vm.section == Section.FAVORITES, { vm.section = Section.FAVORITES }, { Icon(Icons.Default.Star, null) }, label = { Text("Favoritos") })
                    NavigationBarItem(vm.section == Section.RECENT, { vm.section = Section.RECENT }, { Icon(Icons.Default.History, null) }, label = { Text("Recientes") })
                }
            },
            floatingActionButton = {
                Box {
                    FloatingActionButton(onClick = { fabMenu = true }) { Icon(Icons.Default.Add, "Agregar") }
                    DropdownMenu(fabMenu, { fabMenu = false }) {
                        DropdownMenuItem(text = { Text("Importar PDF") }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                            onClick = { fabMenu = false; importLauncher.launch(arrayOf("application/pdf")) })
                        DropdownMenuItem(text = { Text("Nueva carpeta") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { fabMenu = false; vm.dialog = Dlg.NewFolder(if (vm.section == Section.LIBRARY) vm.folderId else null) })
                    }
                }
            },
        ) { inner ->
            Row(Modifier.padding(inner).fillMaxSize()) {
                if (wide) {
                    LazyColumn(Modifier.width(260.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                        item { NavRow("Favoritos", Icons.Default.Star, 0, vm.section == Section.FAVORITES) { vm.section = Section.FAVORITES } }
                        item { NavRow("Recientes", Icons.Default.History, 0, vm.section == Section.RECENT) { vm.section = Section.RECENT } }
                        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                        item { NavRow("Biblioteca", Icons.Default.LibraryBooks, 0, atRoot) { vm.section = Section.LIBRARY; vm.folderId = null } }
                        items(flatten(folders, null, 1, ids), key = { it.first.id }) { (f, depth) ->
                            NavRow(f.name, Icons.Default.Folder, depth, vm.section == Section.LIBRARY && vm.folderId == f.id) {
                                vm.section = Section.LIBRARY; vm.folderId = f.id
                            }
                        }
                    }
                }
                if (shownDocs.isEmpty() && shownFolders.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), Alignment.Center) {
                        Text(if (q.isNotEmpty()) "Sin resultados" else "Aquí no hay nada todavía.\nUsa + para importar un PDF o crear una carpeta.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                } else LazyVerticalGrid(
                    GridCells.Adaptive(150.dp), Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shownFolders, key = { "f" + it.id }) { f -> FolderCard(f, vm) }
                    items(shownDocs, key = { "d" + it.id }) { d ->
                        DocCard(d, vm, coverLauncher = {
                            vm.coverTarget = d.id
                            coverLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                    }
                }
            }
        }
        DialogHost(vm, folders, byId)
    }
}

@Composable
private fun NavRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, depth: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(start = (12 + depth * 16).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FolderCard(f: FolderE, vm: LibVm) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = { vm.query = ""; vm.section = Section.LIBRARY; vm.folderId = f.id }, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Text(f.name, Modifier.weight(1f).padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; vm.dialog = Dlg.RenameFolder(f) })
                    DropdownMenuItem(text = { Text("Nueva subcarpeta") }, onClick = { menu = false; vm.dialog = Dlg.NewFolder(f.id) })
                    DropdownMenuItem(text = { Text("Eliminar carpeta") }, onClick = { menu = false; vm.dialog = Dlg.DeleteFolder(f) })
                }
            }
        }
    }
}

@Composable
private fun DocCard(d: DocE, vm: LibVm, coverLauncher: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cover = vm.repo.coverFile(d)?.takeIf { it.exists() }
    Card(
        onClick = { vm.openDoc(d.id) }, Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (cover != null) AsyncImage(cover, d.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Default.PictureAsPdf, null, Modifier.align(Alignment.Center).size(48.dp))
                IconButton(onClick = { vm.toggleFav(d) }, Modifier.align(Alignment.TopEnd)) {
                    Icon(if (d.favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorito",
                        tint = if (d.favorite) androidx.compose.ui.graphics.Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface)
                }
            }
            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (d.pageCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator({ pct(d) / 100f }, Modifier.fillMaxWidth())
                        Text(if (d.lastPage > 0) "Pág. ${d.lastPage} / ${d.pageCount} · ${pct(d)}%" else "${d.pageCount} páginas",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; vm.dialog = Dlg.RenameDoc(d) })
                        DropdownMenuItem(text = { Text("Descripción") }, onClick = { menu = false; vm.dialog = Dlg.EditDesc(d) })
                        DropdownMenuItem(text = { Text("Cambiar portada") }, onClick = { menu = false; coverLauncher() })
                        DropdownMenuItem(text = { Text("Mover a carpeta") }, onClick = { menu = false; vm.dialog = Dlg.MoveDoc(d) })
                        DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menu = false; vm.dialog = Dlg.DeleteDoc(d) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHost(vm: LibVm, folders: List<FolderE>, byId: Map<String, FolderE>) {
    val dlg = vm.dialog ?: return
    val close = { vm.dialog = null }
    when (dlg) {
        is Dlg.NewFolder -> TextDialog("Nueva carpeta", "", "Nombre", true, close) { vm.newFolder(it, dlg.parent) }
        is Dlg.RenameFolder -> TextDialog("Renombrar carpeta", dlg.f.name, "Nombre", true, close) { vm.renameFolder(dlg.f, it) }
        is Dlg.RenameDoc -> TextDialog("Renombrar documento", dlg.d.name, "Nombre", true, close) { vm.rename(dlg.d, it) }
        is Dlg.EditDesc -> TextDialog("Descripción", dlg.d.description, "Descripción", false, close) { vm.describe(dlg.d, it) }
        is Dlg.DeleteFolder -> ConfirmDialog("Eliminar carpeta", "Se eliminará «${dlg.f.name}». Su contenido NO se borra: sube un nivel.", close) { vm.deleteFolder(dlg.f) }
        is Dlg.DeleteDoc -> ConfirmDialog("Eliminar documento", "«${dlg.d.name}» se eliminará de este dispositivo.", close) { vm.deleteDoc(dlg.d) }
        is Dlg.MoveDoc -> AlertDialog(
            onDismissRequest = close, title = { Text("Mover a…") },
            text = {
                LazyColumn {
                    item { Text("Biblioteca (raíz)", Modifier.fillMaxWidth().clickable { vm.move(dlg.d, null); close() }.padding(12.dp)) }
                    items(flatten(folders, null, 0, byId.keys), key = { it.first.id }) { (f, depth) ->
                        Text(pathOf(f.id, byId), Modifier.fillMaxWidth().clickable { vm.move(dlg.d, f.id); close() }.padding(start = (12 + depth * 8).dp, top = 12.dp, bottom = 12.dp, end = 12.dp))
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(close) { Text("Cancelar") } })
    }
}

@Composable
fun TextDialog(title: String, initial: String, label: String, single: Boolean, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = single, minLines = if (single) 1 else 3) },
        confirmButton = { TextButton(onClick = { if (single && text.isBlank()) return@TextButton; onOk(text); onDismiss() }) { Text("Aceptar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

@Composable
fun ConfirmDialog(title: String, msg: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(msg) },
        confirmButton = { TextButton(onClick = { onOk(); onDismiss() }) { Text("Aceptar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}
