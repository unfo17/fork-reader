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
import org.mewx.wenku8_flutter.BookTextLinks
import org.mewx.wenku8_flutter.BookDocumentInput
import org.mewx.wenku8_flutter.retainBookOpenReadGrant
import org.readium.r2.shared.util.AbsoluteUrl
import android.content.Intent
import org.mewx.wenku8_flutter.LocalTextPublication
import org.mewx.wenku8_flutter.BookDocumentImportSession
import org.mewx.wenku8_flutter.BookTextDecoder

class ReadiumEngine(context: Context) {
    private val http = DefaultHttpClient()
    private val assets = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient = http, assetRetriever = assets, pdfFactory = null))
    suspend fun open(file: File): Publication {
        return open(file.toUrl())
    }
    suspend fun open(url: AbsoluteUrl): Publication {
        val asset = assets.retrieve(url).getOrElse { error("无法读取文件：$it") }
        return opener.open(asset, allowUserInteraction = false).getOrElse { asset.close(); error("无法解析 EPUB：$it") }
    }
}

data class Book(
    val id: String, val title: String, val author: String, val file: String,
    val category: String = "未分类", val locator: String? = null,
    val progress: Double = 0.0, val lastRead: Long = 0, val bookmarks: List<String> = emptyList(),
    val minutes: Long = 0,
    val sourceReference: String? = null, val sourceSize: Long = 0, val sourceModified: Long = 0,
    val removed: Boolean = false, val coverRevision: Long = 0,
    val summary: String = "", val tags: List<String> = emptyList(), val status: String = "auto",
    val addedAt: Long = System.currentTimeMillis(), val readerOptions: String? = null
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("author", author).put("file", file)
        .put("category", category).put("locator", locator).put("progress", progress).put("lastRead", lastRead)
        .put("bookmarks", JSONArray(bookmarks)).put("minutes", minutes)
        .put("readerOptions", readerOptions).put("summary", summary).put("tags", JSONArray(tags)).put("status", status).put("addedAt", addedAt).put("coverRevision", coverRevision)
        .put("sourceReference", sourceReference).put("sourceSize", sourceSize).put("sourceModified", sourceModified).put("removed", removed)
    companion object {
        fun from(j: JSONObject) = Book(j.getString("id"), j.getString("title"), j.optString("author"), j.getString("file"),
            j.optString("category", "未分类"), j.optString("locator").takeIf { it.isNotBlank() && it != "null" },
            j.optDouble("progress", 0.0), j.optLong("lastRead"),
            j.optJSONArray("bookmarks")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(), j.optLong("minutes"),
            j.optString("sourceReference").takeIf { it.isNotBlank() && it != "null" }, j.optLong("sourceSize"), j.optLong("sourceModified"), j.optBoolean("removed"), j.optLong("coverRevision"),
            j.optString("summary"), j.optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            j.optString("status", "auto"), j.optLong("addedAt"), j.optString("readerOptions").takeIf { it.isNotEmpty() && it != "null" })
    }
}

/** A single process-wide store prevents reader and library edits from overwriting one another. */
class Library private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    val engine = ReadiumEngine(context)
    private val links = BookTextLinks(context)
    private val importSession = BookDocumentImportSession<Uri>(File(context.noBackupFilesDir, "book-import-staging")) { uri, target, limit ->
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val bytes = ByteArray(65536)
                var total = 0L
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "所选书籍超过大小限制" }
                    output.write(bytes, 0, count)
                }
                require(total > 0) { "所选书籍为空" }
            }
        } ?: error("无法读取所选文件")
    }
    private val importMutex = kotlinx.coroutines.sync.Mutex()
    private val textMutex = kotlinx.coroutines.sync.Mutex()
    internal fun <T> withOriginal(book: Book, action: (BookDocumentInput) -> T): T {
        val reference = book.sourceReference ?: return action(BookDocumentInput(File(book.file)))
        val lease = links.openDocument(reference, book.sourceSize, book.sourceModified, if (book.file.endsWith(".txt")) "text-index" else null)
        return try { action(BookDocumentInput(lease.getValue("descriptor") as Int, lease.getValue("size") as Long, lease["offset"] as? Long ?: 0L)) }
        finally { links.closeDocument(lease.getValue("lease") as String) }
    }
    suspend fun openPublication(book: Book): Publication = withContext(Dispatchers.IO) {
        check(!book.removed) { "本机文件已删除，请重新导入原书；阅读记录仍保留" }
        if (book.sourceReference != null && book.file.endsWith(".epub")) {
            withOriginal(book) { Unit } // Validate the upstream descriptor lease before Readium opens its own resource.
            engine.open(AbsoluteUrl(links.source(book.sourceReference).toString()) ?: error("原文件位置无效"))
        } else engine.open(publicationFile(book))
    }
    suspend fun publicationFile(book: Book): File = withContext(Dispatchers.IO) {
        if (!book.file.endsWith(".txt", true)) return@withContext File(book.file)
        val target = File(context.filesDir, "text-publications/${book.id}.epub")
        textMutex.lock()
        try {
            withOriginal(book) { source ->
                if (!target.isFile) LocalTextPublication.prepare(source, target, book.title, File(context.noBackupFilesDir, "text-conversion"))
            }
            target
        } finally { textMutex.unlock() }
    }
    @Synchronized fun records(): List<Book> = JSONArray(prefs.getString("books", "[]")).let { a -> List(a.length()) { Book.from(a.getJSONObject(it)) } }
    fun books(): List<Book> = records().filterNot { it.removed }
    @Synchronized fun update(book: Book) { save(records().filterNot { it.id == book.id } + book) }
    @Synchronized fun replaceRecords(books: List<Book>) { save(books) }
    private fun save(books: List<Book>) { check(prefs.edit().putString("books", JSONArray(books.map { it.json() }).toString()).commit()) }
    @Synchronized fun remove(book: Book) {
        // Match upstream local_book_removal.dart: delete local content, retain history and bookmarks.
        File(book.file).takeIf { book.sourceReference == null }?.let { check(!it.exists() || it.delete()) { "本机文件删除失败，书籍和记录已保留" } }
        update(book.copy(removed = true, sourceReference = null, sourceSize = 0, sourceModified = 0))
        book.sourceReference?.let(links::release)
        File(book.file + ".jpg").delete()
        File(context.filesDir, "text-publications/${book.id}.epub").delete()
    }
    suspend fun import(uri: Uri, copy: Boolean = true, treeGrant: Uri? = null): Book {
        importMutex.lock()
        try { return if (copy) importSelected(uri) else importLinked(uri, treeGrant) }
        finally { importMutex.unlock() }
    }
    private fun restoredRecord(book: Book): Book {
        val old = records().firstOrNull { it.id == book.id } ?: return book
        return old.copy(file = book.file, removed = false, sourceReference = book.sourceReference, sourceSize = book.sourceSize, sourceModified = book.sourceModified)
    }
    private fun retainGrant(uri: Uri, tree: Uri?): Uri? {
        if (uri.scheme == "file") return null
        val grant = tree ?: uri
        val retained = retainBookOpenReadGrant(true,
            hasPersistedRead = { context.contentResolver.persistedUriPermissions.any { it.uri == grant && it.isReadPermission } },
            retainRead = { context.contentResolver.takePersistableUriPermission(grant, Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        check(retained) { "文件提供方无法保留读取权限，请改选「复制到应用」" }
        return grant
    }
    private suspend fun importLinked(uri: Uri, tree: Uri?): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        require(name.endsWith(".epub", true) || name.endsWith(".txt", true)) { "当前支持 EPUB 和 TXT" }
        val reference = links.remember(uri, retainGrant(uri, tree))
        var created: Book? = null
        try {
            val lease = links.openDocument(reference, purpose = if (name.endsWith(".txt", true)) "text-index" else null)
            val size = lease.getValue("size") as Long
            val modified = lease.getValue("modified") as Long
            val id = try { digest(BookDocumentInput(lease.getValue("descriptor") as Int, size, lease["offset"] as? Long ?: 0L)) }
            finally { links.closeDocument(lease.getValue("lease") as String) }
            books().firstOrNull { it.id == id }?.let {
                if (it.sourceReference != reference) links.release(reference)
                return@withContext it
            }
            val anchor = File(context.filesDir, "books/${UUID.randomUUID()}.${name.substringAfterLast('.').lowercase()}")
            anchor.parentFile?.mkdirs()
            val provisional = Book(id, name.substringBeforeLast('.'), "", anchor.path, sourceReference = reference, sourceSize = size, sourceModified = modified)
            created = provisional
            val publication = openPublication(provisional)
            val book = try {
                publication.coverFitting(Size(240, 360))?.let { cover -> File(anchor.path + ".jpg").outputStream().use { cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }; cover.recycle() }
                restoredRecord(provisional.copy(title = publication.metadata.title ?: provisional.title, author = publication.metadata.authors.joinToString { it.name.toString() }))
            } finally { publication.close() }
            update(book)
            book
        } catch (error: Exception) {
            created?.let { File(it.file + ".jpg").delete(); File(context.filesDir, "text-publications/${it.id}.epub").delete() }
            if (books().none { it.sourceReference == reference }) links.release(reference)
            throw error
        }
    }
    suspend fun relink(book: Book, uri: Uri, tree: Uri? = null): Book = withContext(Dispatchers.IO) {
        importMutex.lock()
        try {
            val reference = links.remember(uri, retainGrant(uri, tree))
            try {
                val lease = links.openDocument(reference, purpose = if (book.file.endsWith(".txt")) "text-index" else null)
                val size = lease.getValue("size") as Long
                val modified = lease.getValue("modified") as Long
                val id = try { digest(BookDocumentInput(lease.getValue("descriptor") as Int, size, lease["offset"] as? Long ?: 0L)) }
                finally { links.closeDocument(lease.getValue("lease") as String) }
                require(id == book.id) { "所选文件与原书内容不一致，请选择原来的文件；现有进度与书签已保留" }
                val replacement = book.copy(sourceReference = reference, sourceSize = size, sourceModified = modified, removed = false)
                update(replacement)
                if (book.sourceReference != reference) book.sourceReference?.let(links::release)
                replacement
            } catch (error: Exception) {
                if (records().none { it.sourceReference == reference }) links.release(reference)
                throw error
            }
        } finally { importMutex.unlock() }
    }
    suspend fun replaceCover(book: Book, uri: Uri): Book = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) { "封面图片超过 8MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取图片")
        val validated = org.mewx.wenku8_flutter.BookImageStore.image(bytes, book.title)
        val temporary = File(book.file + ".jpg.part")
        try {
            temporary.writeBytes(validated.bytes)
            check(temporary.renameTo(File(book.file + ".jpg"))) { "无法保存封面" }
        } finally { temporary.delete() }
        val updated = records().first { it.id == book.id }.copy(coverRevision = System.currentTimeMillis())
        update(updated)
        updated
    }
    suspend fun restoreCover(book: Book): Book = withContext(Dispatchers.IO) {
        val publication = openPublication(book)
        val cover = try { publication.coverFitting(Size(240, 360)) } finally { publication.close() }
        val target = File(book.file + ".jpg")
        if (cover == null) check(!target.exists() || target.delete()) { "无法移除自定义封面" }
        else {
            val temporary = File(target.path + ".part")
            try {
                temporary.outputStream().use { check(cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)) { "无法保存封面" } }
                check(temporary.renameTo(target)) { "无法保存封面" }
            } finally { temporary.delete(); cover.recycle() }
        }
        val updated = records().first { it.id == book.id }.copy(coverRevision = System.currentTimeMillis())
        update(updated)
        updated
    }
    private fun displayName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "导入的书籍"
    private fun digest(source: BookDocumentInput): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun importSelected(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "导入的书籍"
        require(name.endsWith(".epub", true) || name.endsWith(".txt", true)) { "当前版本支持 EPUB 和 TXT" }
        val extension = if (name.endsWith(".epub", true)) "epub" else "txt"
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        var generated: File? = null
        try {
            val selected = importSession.queue.select(uri, name, if (extension == "txt") BookTextDecoder.MAX_TEXT else 512L * 1024 * 1024)
            try {
                val staged = File(importSession.queue.stage(selected.getValue("token")).getValue("path"))
                try { check(staged.renameTo(file)) { "无法保存导入文件" } } finally { staged.delete() }
            } finally { importSession.queue.release(listOf(selected.getValue("token"))) }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buf = ByteArray(8192); var n = input.read(buf); while (n > 0) { digest.update(buf, 0, n); n = input.read(buf) } }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            books().firstOrNull { it.id == id }?.let { file.delete(); return@withContext it }
            val book = restoredRecord(if (extension == "epub") {
                val publication = engine.open(file)
                try {
                    publication.coverFitting(Size(240, 360))?.let { cover -> File(file.path + ".jpg").outputStream().use { cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }; cover.recycle() }
                    Book(id, publication.metadata.title ?: name.removeSuffix(".epub"), publication.metadata.authors.joinToString { it.name.toString() }, file.path) }
                finally { publication.close() }
            } else Book(id, name.substringBeforeLast('.'), "", file.path))
            if (extension == "txt") {
                val publication = engine.open(publicationFile(book).also { generated = it })
                publication.close()
            }
            update(book)
            book
        } catch (e: Exception) { file.delete(); File(file.path + ".jpg").delete(); generated?.delete(); throw e }
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
