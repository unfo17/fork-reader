package io.github.unfo17.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

suspend fun exportLibrary(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    val library = Library.get(context)
    val records = library.records()
    // Validate linked grants first. A failed export never claims to have created a complete backup.
    records.filterNot { it.removed }.forEach { library.withOriginal(it) { source -> require(source.length() > 0) { "原文件无法读取，请重新关联" } } }
    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入备份")
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("library.json"))
        val manifest = records.map { book -> book.copy(file = "${book.id}.${File(book.file).extension}", sourceReference = null, sourceSize = 0, sourceModified = 0).json() }
        zip.write(JSONArray(manifest).toString().toByteArray())
        zip.closeEntry()
        records.filterNot { it.removed }.forEach { book ->
            ensureActive()
            zip.putNextEntry(ZipEntry("${book.id}.${File(book.file).extension}"))
            library.withOriginal(book) { source -> source.inputStream().use { it.copyTo(zip, 65536) } }
            zip.closeEntry()
            File(book.file + ".jpg").takeIf { it.exists() }?.let { cover ->
                zip.putNextEntry(ZipEntry("${book.id}.${File(book.file).extension}.jpg"))
                cover.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}

/** Stage and validate all originals before a single atomic metadata commit. */
suspend fun restoreLibrary(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
    val stage = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
    val promoted = mutableListOf<File>()
    var committed = false
    try {
        var total = 0L
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份")
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            val seen = mutableSetOf<String>()
            while (entry != null) {
                ensureActive()
                val name = entry.name
                require(!entry.isDirectory && name == File(name).name && name != "." && name != ".." && seen.add(name)) { "备份中包含非法路径或重复文件" }
                require(seen.size <= 10000) { "备份中的文件过多" }
                File(stage, name).outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        total += n; require(total <= 1024L * 1024 * 1024) { "备份超过 1GB，请分批导入原书" }
                        if (name == "library.json") require(out.channel.position() + n <= 10 * 1024 * 1024) { "备份索引过大" }
                        out.write(buffer, 0, n)
                    }
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        val manifest = JSONArray(File(stage, "library.json").readText())
        val incoming = List(manifest.length()) { Book.from(manifest.getJSONObject(it)) }
        require(incoming.map { it.id }.distinct().size == incoming.size) { "备份包含重复的书籍编号" }
        incoming.forEach { book ->
            require(book.id.matches(Regex("[a-f0-9]{64}")) && book.file == File(book.file).name && (book.file.endsWith(".epub") || book.file.endsWith(".txt"))) { "备份中的书籍信息不正确" }
            require(book.sourceReference == null) { "备份不应包含设备文件授权" }
            if (!book.removed) {
                val file = File(stage, book.file)
                require(file.isFile) { "备份缺少原始书籍" }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
                require(digest.digest().joinToString("") { "%02x".format(it) } == book.id) { "备份中的原书校验失败，现有书库未改动" }
            }
        }
        val library = Library.get(context)
        val destination = File(context.filesDir, "books").apply { mkdirs() }
        // Build the complete replacement first; retain existing originals when already present.
        val merged = library.records().associateBy { it.id }.toMutableMap()
        incoming.forEach { book ->
            val existing = merged[book.id]
            val content = if (existing != null && !existing.removed) existing else if (!book.removed) {
                val target = File(destination, "${UUID.randomUUID()}.${book.file.substringAfterLast('.')}")
                promoted += target; File(stage, book.file).copyTo(target)
                File(stage, book.file + ".jpg").takeIf { it.exists() }?.let { cover ->
                    val targetCover = File(target.path + ".jpg"); promoted += targetCover; cover.copyTo(targetCover)
                }
                book.copy(file = target.path, sourceReference = null, sourceSize = 0, sourceModified = 0)
            } else existing ?: book.copy(file = File(destination, book.file).path)
            val newer = if (existing != null && existing.lastRead > book.lastRead) existing else book
            merged[book.id] = newer.copy(file = content.file, removed = content.removed, sourceReference = content.sourceReference,
                sourceSize = content.sourceSize, sourceModified = content.sourceModified,
                bookmarks = (existing?.bookmarks.orEmpty() + book.bookmarks).distinct())
        }
        library.replaceRecords(merged.values.toList())
        committed = true
        incoming.size
    } finally {
        stage.deleteRecursively()
        if (!committed) promoted.forEach { it.delete() }
    }
}
