package io.github.unfo17.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mewx.wenku8_flutter.UserFontValidation
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

data class ReaderFont(val id: String, val name: String, val file: String, val downloaded: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("name", name).put("file", File(file).name).put("downloaded", downloaded)
}
data class FontCatalogEntry(val id: String, val name: String, val fileName: String, val bytes: Long, val transferBytes: Long, val archiveEntry: String?, val description: String, val urls: List<String>)

class FontStore(private val context: Context) {
    private val root = File(context.filesDir, "user-fonts").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("user-fonts", Context.MODE_PRIVATE)
    private val mutex get() = mutationMutex
    companion object { private val mutationMutex = kotlinx.coroutines.sync.Mutex() }
    fun fonts(): List<ReaderFont> = JSONArray(preferences.getString("fonts", "[]")).let { list ->
        List(list.length()) { index -> val j = list.getJSONObject(index); ReaderFont(j.getString("id"), j.getString("name"), File(root, j.getString("file")).path, j.optBoolean("downloaded")) }
    }
    private fun save(fonts: List<ReaderFont>) { check(preferences.edit().putString("fonts", JSONArray(fonts.map { it.json() }).toString()).commit()) { "字体记录保存失败" } }
    fun catalogue(): List<FontCatalogEntry> = context.assets.open("font-catalog.json").bufferedReader().use { reader -> JSONArray(reader.readText()) }.let { array ->
        List(array.length()) { index -> val j = array.getJSONObject(index); val urls = j.getJSONArray("urls")
            FontCatalogEntry(j.getString("id"), j.getString("name"), j.getString("fileName"), j.getLong("bytes"), j.getLong("transferBytes"), j.optString("archiveEntry").takeUnless { it == "null" || it.isEmpty() }, j.getString("description"), List(urls.length()) { urls.getString(it) }) }
    }
    private suspend fun boundedCopy(input: InputStream, target: File, maximum: Long, progress: (Long) -> Unit = {}) {
        target.outputStream().use { output ->
            val buffer = ByteArray(65536)
            var count = 0L
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val n = input.read(buffer); if (n < 0) break
                count += n; require(count <= maximum) { "字体文件超过大小限制" }
                output.write(buffer, 0, n); progress(count)
            }
            require(count > 0) { "字体文件为空" }
        }
    }
    private fun install(staged: File, name: String, identity: String? = null): ReaderFont {
        UserFontValidation.validate(staged)
        android.graphics.Typeface.createFromFile(staged) // Also ask the platform to validate before recording.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        staged.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        val id = identity ?: "font_" + digest.digest().joinToString("") { "%02x".format(it) }
        fonts().firstOrNull { it.id == id && File(it.file).isFile }?.let { return it }
        require(identity != null || fonts().count { !it.downloaded } < 20) { "最多保留 20 款导入字体，请先移除不用的字体" }
        require(id.matches(Regex("[a-zA-Z0-9_]{1,96}"))) { "字体编号无效" }
        val target = File(root, "$id.${if (staged.extension.equals("otf", true)) "otf" else "ttf"}")
        staged.copyTo(target, overwrite = true)
        val font = ReaderFont(id, name.take(160), target.path, identity != null)
        try { save(fonts().filterNot { it.id == id } + font) } catch (error: Exception) { target.delete(); throw error }
        return font
    }
    suspend fun import(uri: Uri): ReaderFont = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "font.ttf"
        val staged = File(root, "import-${UUID.randomUUID()}.${name.substringAfterLast('.').lowercase()}")
        mutex.lock()
        try {
            require(name.substringAfterLast('.').lowercase() in listOf("ttf", "otf")) { "请选择 TTF 或 OTF 字体" }
            context.contentResolver.openInputStream(uri)?.use { boundedCopy(it, staged, UserFontValidation.MAX_BYTES) } ?: error("无法读取字体")
            install(staged, name.substringBeforeLast('.'))
        } finally { staged.delete(); mutex.unlock() }
    }
    suspend fun download(entry: FontCatalogEntry, progress: (Long, Long) -> Unit): ReaderFont = withContext(Dispatchers.IO) {
        mutex.lock()
        val downloaded = File(root, "download-${UUID.randomUUID()}.part")
        val staged = File(root, "download-${UUID.randomUUID()}.${entry.fileName.substringAfterLast('.')}")
        try {
            var lastError: Exception? = null
            for (source in entry.urls) {
                kotlin.coroutines.coroutineContext.ensureActive()
                try {
                    var url = URL(source)
                    var connection: HttpURLConnection? = null
                    try {
                        var redirects = 0
                        while (true) {
                            check(url.protocol == "https") { "字体下载地址必须使用 HTTPS" }
                            connection?.disconnect()
                            val opened = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false }
                            connection = opened
                            if (opened.responseCode in 300..399) {
                                require(++redirects <= 5) { "字体重定向次数过多" }
                                url = URL(url, opened.getHeaderField("Location") ?: error("字体重定向无效"))
                            } else break
                        }
                        val active = checkNotNull(connection)
                        require(active.responseCode == 200) { "字体下载失败：HTTP ${active.responseCode}" }
                        active.inputStream.use { boundedCopy(it, downloaded, maxOf(entry.transferBytes, entry.bytes) + 1024 * 1024) { n -> progress(n, entry.transferBytes) } }
                    } finally { connection?.disconnect() }
                    if (entry.archiveEntry == null) downloaded.copyTo(staged, overwrite = true)
                    else ZipInputStream(downloaded.inputStream()).use { zip ->
                        var found = false
                        var expanded = 0L
                        var entries = 0
                        val skip = ByteArray(65536)
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val item = zip.nextEntry ?: break
                            require(++entries <= 10000) { "字体压缩包文件过多" }
                            if (item.name == entry.archiveEntry) { boundedCopy(zip, staged, UserFontValidation.MAX_BYTES); found = true; break }
                            while (true) {
                                val n = zip.read(skip); if (n < 0) break
                                expanded += n
                                require(expanded <= 64L * 1024 * 1024) { "字体压缩包展开内容过大" }
                            }
                            zip.closeEntry()
                        }
                        require(found) { "字体压缩包缺少指定文件" }
                    }
                    require(staged.length() == entry.bytes) { "字体文件大小校验失败" }
                    return@withContext install(staged, entry.name, entry.id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { lastError = error; downloaded.delete(); staged.delete() }
            }
            throw lastError ?: IllegalStateException("字体下载失败")
        } finally { downloaded.delete(); staged.delete(); mutex.unlock() }
    }
    suspend fun remove(font: ReaderFont) = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            check(!File(font.file).exists() || File(font.file).delete()) { "字体删除失败" }
            save(fonts().filterNot { it.id == font.id })
            val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            if (appearance.getString("fontId", "") == font.id) appearance.edit().putString("fontId", "").apply()
            val library = Library.get(context)
            library.replaceRecords(library.records().map { book ->
                val options = ReaderOptions.decode(book.readerOptions)
                if (options.fontId == font.id) book.copy(readerOptions = options.copy(fontId = "").json()) else book
            })
        } finally { mutex.unlock() }
    }
}
