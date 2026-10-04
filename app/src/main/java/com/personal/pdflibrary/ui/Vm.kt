package com.personal.pdflibrary.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.pdflibrary.App
import com.personal.pdflibrary.data.DocE
import com.personal.pdflibrary.data.FolderE
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Section { LIBRARY, FAVORITES, RECENT }
enum class Sort(val label: String) {
    NAME("Nombre"), ADDED("Fecha de incorporación"), MODIFIED("Última modificación"),
    LAST_READ("Última lectura"), PROGRESS("Progreso"), RECENT("Más recientes")
}

sealed interface Dlg {
    data class NewFolder(val parent: String?) : Dlg
    data class RenameFolder(val f: FolderE) : Dlg
    data class DeleteFolder(val f: FolderE) : Dlg
    data class RenameDoc(val d: DocE) : Dlg
    data class EditDesc(val d: DocE) : Dlg
    data class MoveDoc(val d: DocE) : Dlg
    data class DeleteDoc(val d: DocE) : Dlg
}

class LibVm(app: Application) : AndroidViewModel(app) {
    val repo = (app as App).repo
    val folders = repo.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val docs = repo.docs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var section by mutableStateOf(Section.LIBRARY)
    var folderId by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var sort by mutableStateOf(Sort.NAME)
    var openDocId by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<Dlg?>(null)
    var message by mutableStateOf<String?>(null)
    var coverTarget: String? = null

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { message = e.message ?: "Error" }
    }

    fun importPdfs(uris: List<Uri>) = run {
        val target = if (section == Section.LIBRARY) folderId else null
        for (u in uris) try { repo.importPdf(u, target) } catch (e: Exception) { message = e.message }
    }
    fun setCover(uri: Uri?) { val id = coverTarget; if (uri != null && id != null) run { repo.setCover(id, uri) } }

    fun openDoc(id: String) { openDocId = id }
    fun closeDoc() { openDocId = null }
    fun saveProgress(id: String, page: Int) { viewModelScope.launch { try { repo.saveProgress(id, page) } catch (_: Exception) {} } }

    fun toggleFav(d: DocE) = run { repo.setFavorite(d.id, !d.favorite) }
    fun rename(d: DocE, n: String) = run { repo.rename(d.id, n) }
    fun describe(d: DocE, n: String) = run { repo.setDescription(d.id, n) }
    fun move(d: DocE, to: String?) = run { repo.moveDoc(d.id, to) }
    fun deleteDoc(d: DocE) = run { repo.deleteDoc(d.id) }
    fun newFolder(n: String, parent: String?) = run { repo.createFolder(n, parent) }
    fun renameFolder(f: FolderE, n: String) = run { repo.renameFolder(f.id, n) }
    fun deleteFolder(f: FolderE) = run {
        repo.deleteFolder(f.id)
        if (folderId == f.id) folderId = f.parentId
    }
}
