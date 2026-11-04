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
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.file.FileResource
import android.content.Intent
import org.mewx.wenku8_flutter.LocalTextPublication
import org.mewx.wenku8_flutter.BookDocumentImportSession
import org.mewx.wenku8_flutter.BookTextDecoder
import org.mewx.wenku8_flutter.BookArchiveImport
import org.mewx.wenku8_flutter.BookZipSources

class ReadiumEngine(context: Context) {
    private val http = DefaultHttpClient()
    private val assets = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient = http, assetRetriever = assets, pdfFactory = null))
    suspend fun open(file: File, font: File? = null): Publication {
        return open(file.toUrl(), font)
    }
    suspend fun open(url: AbsoluteUrl, font: File? = null): Publication {
        val asset = assets.retrieve(url).getOrElse { error("无法读取文件：$it") }
        return opener.open(asset, allowUserInteraction = false, onCreatePublication = {
            val html = manifest.readingOrder.filter { it.mediaType?.isHtml == true }.associate { it.href.resolve() to it.mediaType!! }
            val original = container
            container = object : Container<Resource> by original {
                override fun get(url: Url): Resource? = original[url]?.let { resource ->
                    html[url]?.let { ReaderTypography.resource(resource, it) } ?: resource
                }
            }
            if (font != null) {
                val original = container
                val fontHref = Url.fromDecodedPath("__reader_fonts__/selected.${font.extension.lowercase()}")!!
                container = object : Container<Resource> by original {
                    override val entries: Set<Url> = original.entries + fontHref
                    override fun get(url: Url): Resource? = if (url == fontHref || url == fontUrl(font)) FileResource(font) else original[url]
                }
            }
        }).getOrElse { asset.close(); error("无法解析 EPUB：$it") }
    }
    companion object {
        // Match the local publication origin used by Readium 3.1.2's WebViewServer.
        fun fontUrl(font: File): AbsoluteUrl = AbsoluteUrl("https://readium/publication/__reader_fonts__/selected.${font.extension.lowercase()}")!!
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
    val addedAt: Long = System.currentTimeMillis(), val readerOptions: String? = null, val originalName: String = "", val warnings: List<String> = emptyList(),
    val archiveEntry: String? = null, val archiveEntrySize: Long = 0, val archiveCharset: String = "UTF-8",
    val bundleFile: String? = null, val bundleDigest: String? = null, val sourceDigest: String? = null, val annotations: List<ReadingMark> = emptyList(), val textImportRules: String? = null
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("author", author).put("file", file)
        .put("textImportRules", textImportRules).put("annotations", JSONArray(annotations.map { it.json() })).put("category", category).put("locator", locator).put("progress", progress).put("lastRead", lastRead)
        .put("bookmarks", JSONArray(bookmarks)).put("minutes", minutes)
        .put("archiveEntry", archiveEntry).put("archiveEntrySize", archiveEntrySize).put("archiveCharset", archiveCharset).put("bundleFile", bundleFile).put("bundleDigest", bundleDigest).put("sourceDigest", sourceDigest)
        .put("originalName", originalName).put("warnings", JSONArray(warnings)).put("readerOptions", readerOptions).put("summary", summary).put("tags", JSONArray(tags)).put("status", status).put("addedAt", addedAt).put("coverRevision", coverRevision)
        .put("sourceReference", sourceReference).put("sourceSize", sourceSize).put("sourceModified", sourceModified).put("removed", removed)
    companion object {
        fun from(j: JSONObject) = Book(j.getString("id"), j.getString("title"), j.optString("author"), j.getString("file"),
            j.optString("category", "未分类"), j.optString("locator").takeIf { it.isNotBlank() && it != "null" },
            j.optDouble("progress", 0.0), j.optLong("lastRead"),
            j.optJSONArray("bookmarks")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(), j.optLong("minutes"),
            j.optString("sourceReference").takeIf { it.isNotBlank() && it != "null" }, j.optLong("sourceSize"), j.optLong("sourceModified"), j.optBoolean("removed"), j.optLong("coverRevision"),
            j.optString("summary"), j.optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            j.optString("status", "auto"), j.optLong("addedAt"), j.optString("readerOptions").takeIf { it.isNotEmpty() && it != "null" }, j.optString("originalName"), j.optJSONArray("warnings")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            j.optString("archiveEntry").takeUnless { it.isBlank() || it == "null" }, j.optLong("archiveEntrySize"), j.optString("archiveCharset", "UTF-8"),
            j.optString("bundleFile").takeUnless { it.isBlank() || it == "null" }, j.optString("bundleDigest").takeUnless { it.isBlank() || it == "null" }, j.optString("sourceDigest").takeUnless { it.isBlank() || it == "null" },
            j.optJSONArray("annotations")?.let { a -> require(a.length() <= 10000) { "批注记录过多" }; List(a.length()) { ReadingMark.from(a.getJSONObject(it)) }.also { marks -> require(marks.map { it.id }.distinct().size == marks.size) { "批注编号重复" } } } ?: emptyList(), j.optString("textImportRules").takeUnless { it.isBlank() || it == "null" }?.also { org.mewx.wenku8_flutter.TextImportRules.decode(it) })
    }
}

/** A single process-wide store prevents reader and library edits from overwriting one another. */
class Library private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    val organization = LibraryOrganization({ prefs.getString("organization", null) }, { prefs.edit().putString("organization", it).commit() })
    init { organization.migrateLegacy(JSONArray(prefs.getString("books", "[]")).let { a -> List(a.length()) { Book.from(a.getJSONObject(it)) } }) }
    val series = SeriesStore({ prefs.getString("series", null) }, { prefs.edit().putString("series", it).commit() })
    val engine = ReadiumEngine(context)
    private val links = BookTextLinks(context)
    private val externalRequests = org.mewx.wenku8_flutter.BookOpenRequests(context)
    val externalRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Synchronized fun offerExternal(intent: Intent?): Boolean = externalRequests.offer(intent).also { if (it) externalRevision.value++ }
    @Synchronized fun restoreExternal(intent: Intent): Boolean = externalRequests.restore(intent).also { if (it) externalRevision.value++ }
    fun pendingExternalIntents() = externalRequests.pendingIntents()
    internal suspend fun pendingExternal(): ExternalBookRequest? = withContext(Dispatchers.IO) {
        externalRequests.pending()?.let { raw -> ExternalBookRequest(raw.getValue("id") as String, raw.getValue("name") as String, raw["canLink"] == true, raw["canLocate"] == true, raw["canMove"] == true, raw["sourceName"] as? String, raw["sourceLocation"] as? String, raw["error"] as? String) }
    }
    @Synchronized fun finishExternal(id: String) { externalRequests.finish(id, links); externalRevision.value++ }
    internal suspend fun previewExternal(request: ExternalBookRequest, copy: Boolean, move: Boolean = false): ExternalBookPreview = withContext(Dispatchers.IO) {
        require(!move || request.canMove) { "来源不支持移动，原书保留" }
        val (uri, grant) = externalRequests.source(request.id, copy || move)
        ExternalBookPreview(request.id, preview(uri, request.name), grant, copy || move, move)
    }
    internal suspend fun moveExternal(id: String, book: Book): Book = withContext(Dispatchers.IO) {
        val saved = ensureManagedCopy(book)
        java.io.RandomAccessFile(saved.file, "rw").use { it.fd.sync() }
        for (directory in listOf(File(saved.file).parentFile, File(context.applicationInfo.dataDir, "shared_prefs"))) {
            val descriptor = android.system.Os.open(checkNotNull(directory).path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        externalRequests.deleteSource(id, saved.file) { file -> books().any { it.id == saved.id && it.sourceReference == null && File(it.file).canonicalFile == file } }
        saved
    }
    private suspend fun ensureManagedCopy(book: Book): Book = withContext(Dispatchers.IO) {
        importMutex.lock()
        var target: File? = null
        var committed = false
        try {
            val current = books().firstOrNull { it.id == book.id } ?: error("书籍已移除，原文件保留")
            if (current.sourceReference == null) return@withContext current
            require(current.archiveEntry == null && File(current.file).extension.lowercase() in listOf("epub", "txt")) { "此格式不支持移动原文件" }
            val copy = File(context.filesDir, "books/${UUID.randomUUID()}.${File(current.file).extension}").apply { parentFile?.mkdirs() }
            target = copy
            withOriginal(current) { source -> source.inputStream().use { input -> copy.outputStream().use { output -> input.copyTo(output, 65536) } } }
            require(digest(BookDocumentInput(copy)) == current.id) { "原书已变化，原文件保留" }
            File(current.file + ".jpg").takeIf { it.isFile }?.copyTo(File(copy.path + ".jpg"))
            val saved = synchronized(this@Library) {
                val latest = books().firstOrNull { it.id == current.id } ?: error("书籍已移除，原文件保留")
                require(latest.sourceReference == current.sourceReference && latest.file == current.file) { "原书位置已变化，原文件保留" }
                latest.copy(file = copy.path, sourceReference = null, sourceSize = 0, sourceModified = 0).also { update(it) }
            }
            committed = true
            current.sourceReference?.let(::releaseReference)
            File(current.file + ".jpg").delete()
            saved
        } finally { if (!committed) target?.let { it.delete(); File(it.path + ".jpg").delete() }; importMutex.unlock() }
    }
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
    internal fun <T> withArchive(book: Book, action: (BookDocumentInput) -> T): T {
        val reference = book.sourceReference
        if (reference == null) return action(BookDocumentInput(File(book.bundleFile ?: book.file)))
        val lease = links.openDocument(reference, book.sourceSize, book.sourceModified, if (book.archiveEntry == null && book.file.endsWith(".txt")) "text-index" else null)
        return try { action(BookDocumentInput(lease.getValue("descriptor") as Int, lease.getValue("size") as Long, lease["offset"] as? Long ?: 0L)) }
        finally { links.closeDocument(lease.getValue("lease") as String) }
    }
    internal fun <T> withOriginal(book: Book, action: (BookDocumentInput) -> T): T = withArchive(book) { original ->
        if (book.archiveEntry == null) action(original)
        else action(BookZipSources.document(original, BookZipSources.Entry(book.archiveEntry, book.archiveEntrySize), book.archiveCharset))
    }
    private fun releaseReference(reference: String) {
        if (books().none { it.sourceReference == reference }) links.release(reference)
    }
    suspend fun openPublication(book: Book, font: File? = null): Publication = withContext(Dispatchers.IO) {
        check(!book.removed) { "本机文件已删除，请重新导入原书；阅读记录仍保留" }
        if (book.sourceReference != null && book.archiveEntry == null && book.file.endsWith(".epub")) {
            withOriginal(book) { Unit } // Validate the upstream descriptor lease before Readium opens its own resource.
            engine.open(AbsoluteUrl(links.source(book.sourceReference).toString()) ?: error("原文件位置无效"), font)
        } else engine.open(publicationFile(book), font)
    }
    private fun publicationCache(book: Book): File = if (book.file.endsWith(".txt", true)) {
        val version = book.textImportRules?.let { "v3-" + java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(12).joinToString("") { byte -> "%02x".format(byte) } } ?: "v2"
        File(context.filesDir, "text-publications/${book.id}.$version.epub")
    }
        else File(context.filesDir, "document-publications/${book.id}.v1.epub")
    private fun conversionWarnings(book: Book): List<String> = runCatching {
        val file = File(publicationCache(book).path + ".warnings.json")
        if (!file.isFile) emptyList() else JSONArray(file.readText()).let { array -> List(array.length()) { array.getString(it) } }
    }.getOrDefault(emptyList())
    suspend fun publicationFile(book: Book): File = withContext(Dispatchers.IO) {
        if (book.file.endsWith(".epub", true) && book.archiveEntry == null) return@withContext File(book.file)
        val target = publicationCache(book)
        textMutex.lock()
        try {
            withOriginal(book) { source ->
                if (!target.isFile) {
                    val warnings = if (book.archiveEntry != null && book.file.endsWith(".epub", true)) {
                        target.parentFile?.mkdirs()
                        val temporary = File(target.path + ".part")
                        try { source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it, 65536) } }; check(temporary.renameTo(target)) { "无法保存压缩包阅读缓存" } }
                        finally { temporary.delete() }
                        emptyList()
                    } else if (book.file.endsWith(".txt", true)) {
                        LocalTextPublication.prepare(source, target, book.title, File(context.noBackupFilesDir, "text-conversion"), options = book.textImportRules?.let { org.mewx.wenku8_flutter.TextImportRules.decode(it) }, context = context); emptyList()
                    } else {
                        val root = File(context.noBackupFilesDir, "format-conversion").apply { mkdirs() }
                        BookArchiveImport(root).apply { documents = org.mewx.wenku8_flutter.BookMarkupImport(root, org.mewx.wenku8_flutter.BookPdfImport(context, root)::read) }
                            .convert(book.originalName.ifBlank { book.title + "." + File(book.file).extension }, source, target)
                    }
                    File(target.path + ".warnings.json").writeText(JSONArray(warnings).toString())
                }
            }
            target
        } finally { textMutex.unlock() }
    }
    @Synchronized fun records(): List<Book> {
        val assignments = organization.resolvedGroups(); val groups = organization.groups()
        return JSONArray(prefs.getString("books", "[]")).let { a -> List(a.length()) { Book.from(a.getJSONObject(it)).let { book -> book.copy(category = groups[assignments[book.id]] ?: "未分类") } } }
    }
    fun books(): List<Book> = records().filterNot { it.removed }
    fun collectionBooks(): List<Book> {
        val originals = books(); val byId = originals.associateBy { it.id }
        val collections = series.definitions().filter { !it.deleted && it.volumes.all { part -> part.bookId in byId } }
        val hidden = collections.flatMap { it.volumes.map { volume -> volume.bookId } }.toSet()
        return originals.filterNot { it.id in hidden } + collections.map { collection ->
            val members = collection.volumes.map { byId.getValue(it.bookId) }
            val first = members.first()
            val chapters = collection.volumes.sumOf { it.chapters.size }.coerceAtLeast(1)
            first.copy(id = collection.id, title = collection.title, author = collection.author, summary = collection.summary, tags = collection.tags,
                category = organization.nameOf(organization.groupOf(collection.id)), addedAt = members.minOf { it.addedAt }, lastRead = members.maxOf { it.lastRead },
                progress = collection.volumes.sumOf { volume -> byId.getValue(volume.bookId).progress * volume.chapters.size } / chapters,
                minutes = members.sumOf { it.minutes }, bookmarks = members.flatMap { it.bookmarks }, annotations = members.flatMap { it.annotations }, status = "auto")
        }
    }
    suspend fun saveSeries(id: String?, title: String, volumes: List<Pair<String, String>>, expectedAt: Long?, author: String? = null, summary: String? = null, tags: List<String>? = null): BookSeries {
        val parts = volumes.map { (bookId, label) ->
            val original = books().firstOrNull { it.id == bookId } ?: error("分卷原书已移除")
            val publication = openPublication(original)
            try { Triple(original, label, publication.readingOrder.map { it.href.toString() }) } finally { publication.close() }
        }
        return synchronized(this@Library) {
            val active = books().map { it.id }.toSet()
            require(parts.all { it.first.id in active }) { "分卷原书已变化，请重新选择" }
            series.save(id, title, parts, expectedAt, author, summary, tags)
        }
    }
    @Synchronized fun saveAnnotation(bookId: String, mark: ReadingMark, expectedRevision: Long? = null) {
        ReadingMark.from(mark.json())
        val current = records().firstOrNull { it.id == bookId } ?: error("书籍记录已不存在")
        val previous = current.annotations.firstOrNull { it.id == mark.id }
        require(previous?.revision == expectedRevision && previous?.deleted != true) { "批注已变化，请重新打开" }
        require(current.annotations.size < 10000 || previous != null) { "批注数量已达上限" }
        update(current.copy(annotations = current.annotations.filterNot { it.id == mark.id } + mark))
    }
    @Synchronized fun deleteAnnotation(bookId: String, markId: String, expectedRevision: Long) {
        val current = records().firstOrNull { it.id == bookId } ?: return
        val previous = current.annotations.firstOrNull { it.id == markId } ?: return
        require(previous.revision == expectedRevision) { "批注已变化，请重新打开" }
        val removed = previous.copy(deleted = true, stateAt = maxOf(System.currentTimeMillis(), previous.revision + 1))
        update(current.copy(annotations = current.annotations.map { if (it.id == markId) removed else it }))
    }
    @Synchronized fun updateReading(id: String, change: (Book) -> Book) {
        val current = records(); val record = current.firstOrNull { it.id == id } ?: return
        val changed = change(record)
        require(changed.id == id && changed.removed == record.removed && changed.file == record.file && changed.sourceReference == record.sourceReference) { "阅读记录不能更改原书状态" }
        save(current.map { if (it.id == id) changed.copy(annotations = ReadingMark.mergeLists(record.annotations, changed.annotations)) else it })
    }
    @Synchronized fun update(book: Book) {
        val current = records()
        val existing = current.firstOrNull { it.id == book.id }
        save(current.filterNot { it.id == book.id } + book.copy(annotations = ReadingMark.mergeLists(existing?.annotations.orEmpty(), book.annotations)))
    }
    @Synchronized fun replaceRecords(books: List<Book>, organizationState: String? = null, seriesState: String? = null) {
        val latest = records().associateBy { it.id }
        val retained = books.map { book -> book.copy(annotations = ReadingMark.mergeLists(latest[book.id]?.annotations.orEmpty(), book.annotations)) }
        val edit = prefs.edit().putString("books", JSONArray(retained.map { it.json() }).toString())
        if (organizationState != null) edit.putString("organization", organization.merged(organizationState))
        if (seriesState != null) edit.putString("series", series.merged(seriesState))
        check(edit.commit()) { "书库保存失败" }
        if (organizationState != null) organization.invalidateAssignments()
    }
    private fun save(books: List<Book>) { check(prefs.edit().putString("books", JSONArray(books.map { it.json() }).toString()).commit()) }
    @Synchronized fun remove(book: Book) {
        series.get(book.id)?.takeIf { !it.deleted }?.let { collection ->
            collection.volumes.forEach { part -> books().firstOrNull { it.id == part.bookId }?.let(::remove) }
            return
        }
        // Match upstream local_book_removal.dart: delete local content, retain history and bookmarks.
        File(book.file).takeIf { book.sourceReference == null }?.let { check(!it.exists() || it.delete()) { "本机文件删除失败，书籍和记录已保留" } }
        update(book.copy(removed = true, sourceReference = null, sourceSize = 0, sourceModified = 0, bundleFile = null))
        book.sourceReference?.let(::releaseReference)
        book.bundleFile?.takeIf { path -> books().none { it.bundleFile == path } }?.let { File(it).delete() }
        File(book.file + ".jpg").delete()
        File(context.filesDir, "text-publications/${book.id}.epub").delete()
        publicationCache(book).delete()
        File(publicationCache(book).path + ".warnings.json").delete()
    }
    suspend fun import(uri: Uri, copy: Boolean = true, treeGrant: Uri? = null, nameHint: String? = null): Book {
        importMutex.lock()
        try { return if (copy) importSelected(uri, nameHint) else importLinked(uri, treeGrant, nameHint) }
        finally { importMutex.unlock() }
    }
    private fun textRulesFor(id: String): String? {
        records().firstOrNull { it.id == id }?.let { return it.textImportRules }
        return context.getSharedPreferences("import-options", Context.MODE_PRIVATE).getString("textRules", org.mewx.wenku8_flutter.TextImportRules().json())
    }
    private fun restoredRecord(book: Book): Book {
        val old = records().firstOrNull { it.id == book.id } ?: return book
        return old.copy(file = book.file, removed = false, sourceReference = book.sourceReference, sourceSize = book.sourceSize, sourceModified = book.sourceModified, originalName = book.originalName, warnings = book.warnings, archiveEntry = book.archiveEntry, archiveEntrySize = book.archiveEntrySize, archiveCharset = book.archiveCharset, bundleFile = book.bundleFile, bundleDigest = book.bundleDigest, sourceDigest = book.sourceDigest)
    }
    internal suspend fun preview(uri: Uri, nameHint: String? = null): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val name = nameHint ?: displayName(uri)
        if (!name.endsWith(".zip", true)) { require(BookArchiveImport.supported(name)) { "不支持的书籍格式：$name" }; return@withContext listOf(ImportCandidate(uri, name)) }
        val hash = context.contentResolver.openInputStream(uri)?.use { stream ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var count = 0L
            while (true) { ensureActive(); val n = stream.read(buffer); if (n < 0) break; count += n; require(count <= BookArchiveImport.MAX_ARCHIVE) { "ZIP 原包超过 512MiB" }; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } ?: error("无法读取压缩包")
        val inspection = BookZipSources.inspect { context.contentResolver.openInputStream(uri) ?: error("无法读取压缩包") }
        require(inspection.entries.isNotEmpty()) { "压缩包中没有支持的电子书；嵌套 ZIP 需先单独解压" }
        inspection.entries.map { ImportCandidate(uri, name, it, inspection.charset, hash) }
    }
    internal suspend fun importCandidate(candidate: ImportCandidate, copy: Boolean, tree: Uri?): Book {
        if (candidate.entry == null) return import(candidate.uri, copy, tree, candidate.name)
        importMutex.lock()
        try { return importArchiveMember(candidate, copy, tree) } finally { importMutex.unlock() }
    }
    internal fun archiveMemberId(raw: String, name: String, parentDigest: String): String = if (name.substringAfterLast('.').lowercase() in listOf("html", "htm", "xhtml", "md", "markdown")) {
        java.security.MessageDigest.getInstance("SHA-256").digest((raw + ":" + parentDigest).toByteArray()).joinToString("") { "%02x".format(it) }
    } else raw
    private suspend fun importArchiveMember(candidate: ImportCandidate, copy: Boolean, tree: Uri?): Book = withContext(Dispatchers.IO) {
        val entry = checkNotNull(candidate.entry)
        val parentDigest = checkNotNull(candidate.archiveDigest)
        var reference: String? = null
        var bundle: File? = null
        var created: Book? = null
        try {
            val size: Long
            val modified: Long
            if (copy) {
                val root = File(context.filesDir, "managed-archives").apply { mkdirs() }
                bundle = File(root, "$parentDigest.zip")
                if (!bundle!!.isFile) {
                    val temporary = File(bundle!!.path + ".part")
                    try {
                        context.contentResolver.openInputStream(candidate.uri)?.use { input -> temporary.outputStream().use { output ->
                            val bytes = ByteArray(65536); var total = 0L
                            while (true) { ensureActive(); val n = input.read(bytes); if (n < 0) break; total += n; require(total <= BookArchiveImport.MAX_ARCHIVE) { "ZIP 原包超过大小限制" }; output.write(bytes, 0, n) }
                        } } ?: error("无法读取压缩包")
                        require(digest(BookDocumentInput(temporary)) == parentDigest) { "压缩包在预览后发生变化，请重新选择" }
                        check(temporary.renameTo(bundle!!)) { "无法保存 ZIP 原包" }
                    } finally { temporary.delete() }
                }
                size = bundle!!.length(); modified = bundle!!.lastModified()
            } else {
                reference = links.remember(candidate.uri, retainGrant(candidate.uri, tree))
                val lease = links.openDocument(reference!!)
                try {
                    size = lease.getValue("size") as Long; modified = lease.getValue("modified") as Long
                    require(digest(BookDocumentInput(lease.getValue("descriptor") as Int, size)) == parentDigest) { "压缩包在预览后发生变化，请重新选择" }
                } finally { links.closeDocument(lease.getValue("lease") as String) }
            }
            val anchor = File(context.filesDir, "books/${UUID.randomUUID()}.${entry.name.substringAfterLast('.').lowercase()}").apply { parentFile?.mkdirs() }
            val provisional = Book("", entry.name.substringAfterLast('/').substringBeforeLast('.'), "", anchor.path,
                sourceReference = reference, sourceSize = size, sourceModified = modified, originalName = entry.name,
                archiveEntry = entry.name, archiveEntrySize = entry.size, archiveCharset = candidate.charset, bundleFile = bundle?.path, bundleDigest = parentDigest)
            val raw = withOriginal(provisional) { digest(it) }
            val id = archiveMemberId(raw, entry.name, parentDigest)
            books().firstOrNull { it.id == id }?.let { existing -> reference?.let(::releaseReference); return@withContext existing }
            val identified = provisional.copy(id = id, sourceDigest = raw, textImportRules = if (entry.name.endsWith(".txt", true)) textRulesFor(id) else null)
            created = identified
            val publication = openPublication(identified)
            val book = try {
                publication.coverFitting(Size(240, 360))?.let { cover -> File(anchor.path + ".jpg").outputStream().use { cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }; cover.recycle() }
                restoredRecord(identified.copy(title = publication.metadata.title ?: identified.title, author = publication.metadata.authors.joinToString { it.name.toString() },
                    summary = publication.metadata.description.orEmpty(), tags = publication.metadata.subjects.map { it.name }, warnings = conversionWarnings(identified)))
            } finally { publication.close() }
            update(book)
            book
        } catch (error: Exception) {
            created?.let { File(it.file + ".jpg").delete(); publicationCache(it).delete() }
            reference?.let(::releaseReference)
            throw error
        } finally {
            bundle?.takeIf { file -> books().none { it.bundleFile == file.path } }?.delete()
        }
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
    private suspend fun importLinked(uri: Uri, tree: Uri?, nameHint: String? = null): Book = withContext(Dispatchers.IO) {
        val name = nameHint ?: displayName(uri)
        require(BookArchiveImport.supported(name)) { "暂不支持所选文档格式" }
        val reference = links.remember(uri, retainGrant(uri, tree))
        var created: Book? = null
        try {
            val lease = links.openDocument(reference, purpose = if (name.endsWith(".txt", true)) "text-index" else null)
            val size = lease.getValue("size") as Long
            val modified = lease.getValue("modified") as Long
            val id = try { digest(BookDocumentInput(lease.getValue("descriptor") as Int, size, lease["offset"] as? Long ?: 0L)) }
            finally { links.closeDocument(lease.getValue("lease") as String) }
            books().firstOrNull { it.id == id }?.let {
                if (it.sourceReference != reference) releaseReference(reference)
                return@withContext it
            }
            val anchor = File(context.filesDir, "books/${UUID.randomUUID()}.${name.substringAfterLast('.').lowercase()}")
            anchor.parentFile?.mkdirs()
            val provisional = Book(id, name.substringBeforeLast('.'), "", anchor.path, sourceReference = reference, sourceSize = size, sourceModified = modified, originalName = name, textImportRules = if (name.endsWith(".txt", true)) textRulesFor(id) else null)
            created = provisional
            val publication = openPublication(provisional)
            val book = try {
                publication.coverFitting(Size(240, 360))?.let { cover -> File(anchor.path + ".jpg").outputStream().use { cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }; cover.recycle() }
                restoredRecord(provisional.copy(title = publication.metadata.title ?: provisional.title, author = publication.metadata.authors.joinToString { it.name.toString() }, summary = publication.metadata.description.orEmpty(), tags = publication.metadata.subjects.map { it.name }, warnings = conversionWarnings(provisional)))
            } finally { publication.close() }
            update(book)
            book
        } catch (error: Exception) {
            created?.let { File(it.file + ".jpg").delete(); publicationCache(it).delete() }
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
                var raw = ""
                var parentDigest: String? = null
                val id = try {
                    val original = BookDocumentInput(lease.getValue("descriptor") as Int, size, lease["offset"] as? Long ?: 0L)
                    if (book.archiveEntry == null) digest(original)
                    else {
                        parentDigest = digest(original)
                        raw = digest(BookZipSources.document(original, BookZipSources.Entry(book.archiveEntry, book.archiveEntrySize), book.archiveCharset))
                        archiveMemberId(raw, book.archiveEntry, parentDigest!!)
                    }
                } finally { links.closeDocument(lease.getValue("lease") as String) }
                require(id == book.id) { "所选文件与原书内容不一致，请选择原来的文件；现有进度与书签已保留" }
                val replacement = book.copy(sourceReference = reference, sourceSize = size, sourceModified = modified, removed = false,
                    bundleFile = null, bundleDigest = parentDigest ?: book.bundleDigest, sourceDigest = raw.takeIf { it.isNotEmpty() } ?: book.sourceDigest)
                update(replacement)
                if (book.sourceReference != reference) book.sourceReference?.let(::releaseReference)
                if (book.sourceReference == null && book.bundleFile == null) File(book.file).delete()
                book.bundleFile?.takeIf { path -> books().none { it.bundleFile == path } }?.let { File(it).delete() }
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
    private fun displayName(uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
        return org.mewx.wenku8_flutter.BookOpenNames.resolve(name, runCatching { context.contentResolver.getType(uri) }.getOrNull())
    }
    private fun digest(source: BookDocumentInput): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun importSelected(uri: Uri, nameHint: String? = null): Book = withContext(Dispatchers.IO) {
        val name = nameHint ?: displayName(uri)
        require(BookArchiveImport.supported(name)) { "暂不支持所选文档格式" }
        val extension = name.substringAfterLast('.').lowercase()
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
            val provisional = Book(id, name.substringBeforeLast('.'), "", file.path, originalName = name, textImportRules = if (name.endsWith(".txt", true)) textRulesFor(id) else null)
            val reading = publicationFile(provisional)
            if (reading != file) generated = reading
            val publication = engine.open(reading)
            val book = try {
                publication.coverFitting(Size(240, 360))?.let { cover -> File(file.path + ".jpg").outputStream().use { cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }; cover.recycle() }
                restoredRecord(provisional.copy(title = publication.metadata.title ?: provisional.title,
                    author = publication.metadata.authors.joinToString { it.name.toString() }, summary = publication.metadata.description.orEmpty(), tags = publication.metadata.subjects.map { it.name }, warnings = conversionWarnings(provisional)))
            } finally { publication.close() }
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
                else if (cursor.getString(1).let { (BookArchiveImport.supported(it) || it.endsWith(".zip", true)) }) result += android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, child)
            }
        }
    }
    visit(android.provider.DocumentsContract.getTreeDocumentId(tree))
    result
}
