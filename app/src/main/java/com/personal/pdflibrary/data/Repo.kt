package com.personal.pdflibrary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Todo vive en el almacenamiento interno de la app: Room (datos) + filesDir (PDFs y portadas). */
class Repo(private val ctx: Context) {
    private val db = Room.databaseBuilder(ctx, LibDb::class.java, "library.db").build()
    private val dao = db.dao()
    private val lock = Mutex()

    val pdfDir = File(ctx.filesDir, "pdfs").apply { mkdirs() }
    val coverDir = File(ctx.filesDir, "covers").apply { mkdirs() }

    val folders = dao.folders()
    val docs = dao.docs()

    fun pdfFile(id: String) = File(pdfDir, "$id.pdf")
    fun coverFile(d: DocE): File? = if (d.coverAt > 0) File(coverDir, "${d.id}_${d.coverAt}.jpg") else null
    suspend fun getDoc(id: String) = dao.doc(id)
    private fun now() = System.currentTimeMillis()

    private suspend fun editDoc(id: String, f: (DocE, Long) -> DocE) {
        lock.withLock { val d = dao.doc(id) ?: return; dao.putDoc(f(d, now())) }
    }

    // ---------------- Documentos ----------------
    suspend fun rename(id: String, name: String) =
        editDoc(id) { d, t -> d.copy(name = name.trim().ifEmpty { d.name }, modifiedAt = t) }
    suspend fun setDescription(id: String, text: String) =
        editDoc(id) { d, t -> d.copy(description = text.trim(), modifiedAt = t) }
    suspend fun setFavorite(id: String, fav: Boolean) =
        editDoc(id) { d, t -> d.copy(favorite = fav, modifiedAt = t) }
    suspend fun moveDoc(id: String, folderId: String?) =
        editDoc(id) { d, t -> d.copy(folderId = folderId, modifiedAt = t) }
    suspend fun saveProgress(id: String, page: Int) =
        editDoc(id) { d, t -> d.copy(lastPage = page.coerceIn(1, maxOf(d.pageCount, 1)), progressAt = t) }

    suspend fun deleteDoc(id: String) = withContext(Dispatchers.IO) {
        lock.withLock { dao.deleteDoc(id) }
        pdfFile(id).delete()
        coverDir.listFiles { f -> f.name.startsWith("${id}_") }?.forEach { it.delete() }
    }

    suspend fun importPdf(uri: Uri, folderId: String?) = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dest = pdfFile(id)
        var display = "documento.pdf"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) display = it.getString(0) ?: display
        }
        // Copia exacta, byte a byte: el PDF no se modifica ni se comprime.
        (ctx.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo leer el archivo")).use { i ->
            dest.outputStream().use { o -> i.copyTo(o) }
        }
        var pages = 0
        var thumb: Bitmap? = null
        try {
            val pfd = ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(pfd)
            try {
                pages = r.pageCount
                val p = r.openPage(0)
                try {
                    val w = 600
                    val bmp = Bitmap.createBitmap(w, (w * p.height.toFloat() / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    thumb = bmp
                } finally { p.close() }
            } finally { r.close(); pfd.close() }
        } catch (e: Exception) {
            dest.delete()
            throw IOException("No se pudo abrir el PDF (¿dañado o con contraseña?)")
        }
        val t = now()
        File(coverDir, "${id}_$t.jpg").outputStream().use { thumb!!.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        lock.withLock {
            dao.putDoc(DocE(
                id = id, name = display.removeSuffix(".pdf").removeSuffix(".PDF"), originalName = display,
                folderId = folderId, description = "", favorite = false, coverAt = t, addedAt = t, modifiedAt = t,
                pageCount = pages, lastPage = 0, progressAt = 0, size = dest.length()))
        }
    }

    /** Portada desde la galería. Se guarda aparte; el PDF no se toca. */
    suspend fun setCover(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / s > 1400) s *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: throw IOException("Imagen no válida")
        val t = now()
        File(coverDir, "${id}_$t.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        editDoc(id) { d, _ -> d.copy(coverAt = t, modifiedAt = t) }
        coverDir.listFiles { f -> f.name.startsWith("${id}_") && f.name != "${id}_$t.jpg" }?.forEach { it.delete() }
    }

    // ---------------- Carpetas ----------------
    suspend fun createFolder(name: String, parentId: String?) {
        lock.withLock { dao.putFolder(FolderE(UUID.randomUUID().toString(), name.trim(), parentId)) }
    }

    suspend fun renameFolder(id: String, name: String) {
        lock.withLock {
            val f = dao.folder(id) ?: return
            dao.putFolder(f.copy(name = name.trim().ifEmpty { f.name }))
        }
    }

    /** Borrar una carpeta NO borra su contenido: documentos y subcarpetas suben al nivel superior. */
    suspend fun deleteFolder(id: String) {
        lock.withLock {
            val f = dao.folder(id) ?: return
            val t = now()
            dao.putFolders(dao.allFolders().filter { it.parentId == id }.map { it.copy(parentId = f.parentId) })
            dao.putDocs(dao.allDocs().filter { it.folderId == id }.map { it.copy(folderId = f.parentId, modifiedAt = t) })
            dao.deleteFolder(id)
        }
    }
}
