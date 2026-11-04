package io.github.unfo17.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.mewx.wenku8_flutter.BookArchiveImport
import org.mewx.wenku8_flutter.BookDocumentInput
import org.mewx.wenku8_flutter.BookZipSources
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun originalDigest(input: InputStream): String = input.use { source ->
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = ByteArray(65536)
    while (true) { val n = source.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
suspend fun exportLibrary(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    val library = Library.get(context)
    val records = library.records()
    records.filterNot { it.removed }.forEach { library.withOriginal(it) { source -> require(source.length() > 0) { "原文件无法读取，请重新关联" } } }
    val manifest = records.map { book -> book.copy(file = "${book.id}.${File(book.file).extension}", sourceReference = null, sourceSize = 0, sourceModified = 0,
        bundleFile = if (book.archiveEntry != null && !book.removed) "archive-${book.bundleDigest}.zip" else null).json() }
    val manifestBytes = JSONArray(manifest).toString().toByteArray()
    require(manifestBytes.size <= 10 * 1024 * 1024) { "书库索引超过 10MiB，暂时无法完整备份；现有数据保留" }
    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入备份")
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("library.json"))
        zip.write(manifestBytes); zip.closeEntry()
        zip.putNextEntry(ZipEntry("organization.json")); zip.write(library.organization.snapshot().toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("series.json")); zip.write(library.series.snapshot().toByteArray()); zip.closeEntry()
        val archives = mutableSetOf<String>()
        records.filterNot { it.removed }.forEach { book ->
            ensureActive()
            if (book.archiveEntry == null) {
                zip.putNextEntry(ZipEntry("${book.id}.${File(book.file).extension}"))
                library.withOriginal(book) { source -> source.inputStream().use { it.copyTo(zip, 65536) } }; zip.closeEntry()
            } else if (archives.add(checkNotNull(book.bundleDigest))) {
                zip.putNextEntry(ZipEntry("archive-${book.bundleDigest}.zip"))
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                library.withArchive(book) { source -> source.inputStream().use { input ->
                    val bytes = ByteArray(65536)
                    while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n); zip.write(bytes, 0, n) }
                } }
                require(hash.digest().joinToString("") { "%02x".format(it) } == book.bundleDigest) { "ZIP 原包已变化，请重新关联后备份" }
                zip.closeEntry()
            }
            File(book.file + ".jpg").takeIf { it.exists() }?.let { cover ->
                zip.putNextEntry(ZipEntry("${book.id}.${File(book.file).extension}.jpg")); cover.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}

/** All records and archive members are checked before the single metadata commit. */
suspend fun restoreLibrary(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
    val stage = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
    val promoted = mutableListOf<File>()
    var committed = false
    try {
        var total = 0L
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份")
        ZipInputStream(input).use { zip ->
            val seen = mutableSetOf<String>()
            while (true) {
                ensureActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && name == File(name).name && name != "." && name != ".." && seen.add(name)) { "备份中包含非法路径或重复文件" }
                require(seen.size <= 10000) { "备份中的文件过多" }
                File(stage, name).outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        total += n; require(total <= 1024L * 1024 * 1024) { "备份超过 1GB，请分批导入原书" }
                        if (name == "library.json" || name == "organization.json" || name == "series.json") require(out.channel.position() + n <= 10 * 1024 * 1024) { "备份索引过大" }
                        out.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
        val manifest = JSONArray(File(stage, "library.json").readText())
        val incoming = List(manifest.length()) { Book.from(manifest.getJSONObject(it)) }
        require(incoming.map { it.id }.distinct().size == incoming.size) { "备份包含重复的书籍编号" }
        val library = Library.get(context)
        val organizationState = File(stage, "organization.json").takeIf { it.exists() }?.let { library.organization.merged(it.readText()) } ?: library.organization.mergedLegacy(incoming)
        val seriesState = File(stage, "series.json").takeIf { it.exists() }?.let { library.series.merged(it.readText()) }
        val localRecords = library.records().associateBy { it.id }
        val checkedArchives = mutableSetOf<String>()
        incoming.forEach { book ->
            require(localRecords[book.id]?.let { it.textImportRules == book.textImportRules } != false) { "同一原书的目录设置不同，已保留现有书库；请先备份双方记录后再迁移" }
            require(book.id.matches(Regex("[a-f0-9]{64}")) && book.file == File(book.file).name && BookArchiveImport.supported(book.file)) { "备份中的书籍信息不正确" }
            require(book.sourceReference == null) { "备份不应包含设备文件授权" }
            if (!book.removed) {
                if (book.archiveEntry == null) {
                    val file = File(stage, book.file)
                    require(file.isFile && originalDigest(file.inputStream()) == book.id) { "备份中的原书缺失或校验失败，现有书库未改动" }
                    require(book.bundleFile == null) { "备份中的原包信息不正确" }
                } else {
                    require(book.archiveCharset in listOf("UTF-8", "GB18030") && BookZipSources.safeName(book.archiveEntry) == book.archiveEntry && book.archiveEntrySize in 1..BookArchiveImport.limit(book.archiveEntry)) { "备份中的 ZIP 目录信息不正确" }
                    val digest = book.bundleDigest ?: error("备份缺少 ZIP 原包校验值")
                    require(digest.matches(Regex("[a-f0-9]{64}")) && book.bundleFile == "archive-$digest.zip") { "备份中的 ZIP 原包信息不正确" }
                    val archive = File(stage, checkNotNull(book.bundleFile))
                    if (checkedArchives.add(digest)) require(archive.isFile && originalDigest(archive.inputStream()) == digest) { "备份中的 ZIP 原包缺失或损坏" }
                    val member = BookZipSources.document(BookDocumentInput(archive), BookZipSources.Entry(book.archiveEntry, book.archiveEntrySize), book.archiveCharset)
                    val raw = originalDigest(member.inputStream())
                    require(raw == book.sourceDigest && library.archiveMemberId(raw, book.archiveEntry, digest) == book.id) { "备份中的 ZIP 书籍校验失败" }
                }
            }
        }
        val destination = File(context.filesDir, "books").apply { mkdirs() }
        val archivesRoot = File(context.filesDir, "managed-archives").apply { mkdirs() }
        val merged = library.records().associateBy { it.id }.toMutableMap()
        incoming.forEach { book ->
            val existing = merged[book.id]
            val content = if (existing != null && !existing.removed) existing else if (!book.removed) {
                val target = File(destination, "${UUID.randomUUID()}.${book.file.substringAfterLast('.')}")
                var bundle: String? = null
                if (book.archiveEntry == null) { promoted += target; File(stage, book.file).copyTo(target) }
                else {
                    val archive = File(archivesRoot, "${book.bundleDigest}.zip")
                    if (!archive.exists()) { promoted += archive; File(stage, checkNotNull(book.bundleFile)).copyTo(archive) }
                    else require(originalDigest(archive.inputStream()) == book.bundleDigest) { "本机 ZIP 原包损坏，请先修复或移除对应书籍" }
                    bundle = archive.path
                }
                File(stage, book.file + ".jpg").takeIf { it.exists() }?.let { cover ->
                    val targetCover = File(target.path + ".jpg"); promoted += targetCover; cover.copyTo(targetCover)
                }
                book.copy(file = target.path, sourceReference = null, sourceSize = 0, sourceModified = 0, bundleFile = bundle)
            } else existing ?: book.copy(file = File(destination, book.file).path, bundleFile = null)
            val newer = if (existing != null && existing.lastRead > book.lastRead) existing else book
            merged[book.id] = newer.copy(file = content.file, removed = content.removed, sourceReference = content.sourceReference,
                sourceSize = content.sourceSize, sourceModified = content.sourceModified, bundleFile = content.bundleFile, bundleDigest = content.bundleDigest,
                archiveEntry = content.archiveEntry, archiveEntrySize = content.archiveEntrySize, archiveCharset = content.archiveCharset, sourceDigest = content.sourceDigest,
                bookmarks = (existing?.bookmarks.orEmpty() + book.bookmarks).distinct(), annotations = ReadingMark.mergeLists(existing?.annotations.orEmpty(), book.annotations))
        }
        library.replaceRecords(merged.values.toList(), organizationState, seriesState)
        committed = true
        incoming.size
    } finally {
        stage.deleteRecursively()
        if (!committed) promoted.forEach { it.delete() }
    }
}
