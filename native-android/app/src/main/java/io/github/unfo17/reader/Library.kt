package io.github.unfo17.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.services.coverFitting
import android.util.Size
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.util.UUID

class ReadiumEngine(context: Context) {
    private val http = DefaultHttpClient()
    private val assets = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient = http, assetRetriever = assets, pdfFactory = null))
    suspend fun open(file: File): Publication {
        val asset = assets.retrieve(file.toUrl()).getOrElse { error("无法读取文件：$it") }
        return opener.open(asset, allowUserInteraction = false).getOrElse { asset.close(); error("无法解析 EPUB：$it") }
    }
}

data class Book(
    val id: String, val title: String, val author: String, val file: String,
    val category: String = "未分类", val locator: String? = null,
    val progress: Double = 0.0, val lastRead: Long = 0, val bookmarks: List<String> = emptyList(),
    val minutes: Long = 0
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("author", author).put("file", file)
        .put("category", category).put("locator", locator).put("progress", progress).put("lastRead", lastRead)
        .put("bookmarks", JSONArray(bookmarks)).put("minutes", minutes)
    companion object {
        fun from(j: JSONObject) = Book(j.getString("id"), j.getString("title"), j.optString("author"), j.getString("file"),
            j.optString("category", "未分类"), j.optString("locator").takeIf { it.isNotBlank() && it != "null" },
            j.optDouble("progress", 0.0), j.optLong("lastRead"),
            j.optJSONArray("bookmarks")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(), j.optLong("minutes"))
    }
}

/** A single process-wide store prevents reader and library edits from overwriting one another. */
class Library private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    val engine = ReadiumEngine(context)
    @Synchronized fun books(): List<Book> = JSONArray(prefs.getString("books", "[]")).let { a -> List(a.length()) { Book.from(a.getJSONObject(it)) } }
    @Synchronized fun update(book: Book) { save(books().filterNot { it.id == book.id } + book) }
    private fun save(books: List<Book>) { check(prefs.edit().putString("books", JSONArray(books.map { it.json() }).toString()).commit()) }
    @Synchronized fun remove(book: Book) { save(books().filterNot { it.id == book.id }); File(book.file).delete(); File(book.file + ".jpg").delete() }
    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "导入的书籍"
        require(name.endsWith(".epub", true) || name.endsWith(".txt", true)) { "当前版本支持 EPUB 和 TXT" }
        val extension = if (name.endsWith(".epub", true)) "epub" else "txt"
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("无法读取所选文件")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buf = ByteArray(8192); var n = input.read(buf); while (n > 0) { digest.update(buf, 0, n); n = input.read(buf) } }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            books().firstOrNull { it.id == id }?.let { file.delete(); return@withContext it }
            val book = if (extension == "epub") {
                val publication = engine.open(file)
                try {
                    publication.coverFitting(Size(240, 360))?.let { cover -> File(file.path + ".jpg").outputStream().use { cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }; cover.recycle() }
                    Book(id, publication.metadata.title ?: name.removeSuffix(".epub"), publication.metadata.authors.joinToString { it.name.toString() }, file.path) }
                finally { publication.close() }
            } else Book(id, name.substringBeforeLast('.'), "", file.path)
            update(book)
            book
        } catch (e: Exception) { file.delete(); File(file.path + ".jpg").delete(); throw e }
    }
    companion object {
        @Volatile private var instance: Library? = null
        fun get(context: Context): Library = instance ?: synchronized(this) { instance ?: Library(context.applicationContext).also { instance = it } }
    }
}

/** Uses only the granted document tree; never asks for broad storage permissions. */
suspend fun scanFolder(context: Context, tree: Uri): List<Uri> = withContext(Dispatchers.IO) {
    val result = mutableListOf<Uri>()
    val visited = mutableSetOf<String>()
    suspend fun visit(id: String) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!visited.add(id)) return
        require(visited.size <= 10000) { "目录过大，请选择较小的子目录" }
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        context.contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val child = cursor.getString(0)
                if (cursor.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) visit(child)
                else if (cursor.getString(1).let { it.endsWith(".epub", true) || it.endsWith(".txt", true) }) result += android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, child)
            }
        }
    }
    visit(android.provider.DocumentsContract.getTreeDocumentId(tree))
    result
}
