package com.personal.pdflibrary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders")
data class FolderE(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
)

@Entity(tableName = "documents")
data class DocE(
    @PrimaryKey val id: String,
    val name: String,
    val originalName: String,
    val folderId: String?,
    val description: String,
    val favorite: Boolean,
    val coverAt: Long,        // versión de la portada (0 = sin portada); forma parte del nombre del archivo
    val addedAt: Long,
    val modifiedAt: Long,
    val pageCount: Int,
    val lastPage: Int,        // 0 = sin leer
    val progressAt: Long,     // última lectura
    val size: Long,
)

@Dao
interface LibDao {
    @Query("SELECT * FROM folders") fun folders(): Flow<List<FolderE>>
    @Query("SELECT * FROM documents") fun docs(): Flow<List<DocE>>
    @Query("SELECT * FROM folders") suspend fun allFolders(): List<FolderE>
    @Query("SELECT * FROM documents") suspend fun allDocs(): List<DocE>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun doc(id: String): DocE?
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): FolderE?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFolder(f: FolderE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDoc(d: DocE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFolders(l: List<FolderE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDocs(l: List<DocE>)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteDoc(id: String)
    @Query("DELETE FROM folders WHERE id = :id") suspend fun deleteFolder(id: String)
}

@Database(entities = [FolderE::class, DocE::class], version = 1, exportSchema = false)
abstract class LibDb : RoomDatabase() { abstract fun dao(): LibDao }
