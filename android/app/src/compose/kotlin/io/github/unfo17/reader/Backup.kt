package io.github.unfo17.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

suspend fun exportLibrary(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    val books = Library.get(context).books()
    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入备份")
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("library.json"))
        zip.write(JSONArray(books.map { it.json().put("file", File(it.file).name) }).toString().toByteArray())
        zip.closeEntry()
        books.forEach { book ->
            listOf(File(book.file), File(book.file + ".jpg")).filter { it.exists() }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}

/** Stage and validate the entire archive before changing the existing library. */
suspend fun restoreLibrary(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
    val stage = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
    try {
        var total = 0L
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份")
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            val seen = mutableSetOf<String>()
            while (entry != null) {
                val name = entry.name
                require(!entry.isDirectory && name == File(name).name && name != "." && name != ".." && seen.add(name)) { "备份中包含非法路径或重复文件" }
                require(seen.size <= 10000) { "备份中的文件过多" }
                File(stage, name).outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var n = zip.read(buffer)
                    while (n > 0) { total += n; require(total <= 1024L * 1024 * 1024) { "备份超过 1GB，请分批导入原书" }; out.write(buffer, 0, n); n = zip.read(buffer) }
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        require(File(stage, "library.json").length() <= 10 * 1024 * 1024) { "备份索引过大" }
        val manifest = JSONArray(File(stage, "library.json").readText())
        val books = List(manifest.length()) { Book.from(manifest.getJSONObject(it)) }
        require(books.map { it.id }.distinct().size == books.size) { "备份包含重复的书籍编号" }
        books.forEach { book ->
            require(book.id.matches(Regex("[a-f0-9]{64}")) && book.file == File(book.file).name && (book.file.endsWith(".epub") || book.file.endsWith(".txt"))) { "备份中的书籍信息不正确" }
            require(File(stage, book.file).isFile) { "备份缺少原始书籍" }
        }
        val library = Library.get(context)
        val destination = File(context.filesDir, "books").apply { mkdirs() }
        books.forEach { book ->
            val existing = library.books().firstOrNull { it.id == book.id }
            if (existing == null) {
                val target = File(destination, "${UUID.randomUUID()}.${book.file.substringAfterLast('.')}")
                File(stage, book.file).copyTo(target)
                File(stage, book.file + ".jpg").takeIf { it.exists() }?.copyTo(File(target.path + ".jpg"))
                library.update(book.copy(file = target.path))
            } else {
                val newer = if (book.lastRead > existing.lastRead) book.copy(file = existing.file) else existing
                library.update(newer.copy(bookmarks = (existing.bookmarks + book.bookmarks).distinct()))
            }
        }
        books.size
    } finally { stage.deleteRecursively() }
}
