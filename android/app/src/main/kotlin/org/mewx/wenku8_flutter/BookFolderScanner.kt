package org.mewx.wenku8_flutter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

/** Explicitly authorized SAF trees and optional shared storage only. */
internal class BookFolderScanner(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("readium_scan", 0)
    private data class Entry(val uri: Uri, val name: String, val size: Long, val modified: Long, val grant: Uri?)
    private val pending = linkedMapOf<String, Entry>()
    fun formats(): Set<String> = prefs.getStringSet("formats", setOf("txt", "epub", "zip"))!!.toSet()
    fun setFormats(values: List<String>) { check(prefs.edit().putStringSet("formats", values.filter { it in setOf("txt", "epub", "zip") }.toSet()).commit()) }
    fun setAutomatic(value: Boolean) { check(prefs.edit().putBoolean("automatic", value).commit()) }
    fun canReadAll() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else
        activity.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun select(uri: Uri, flags: Int): Map<String, Any> {
        activity.contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(prefs.edit().putString("tree", uri.toString()).putBoolean("all", false).commit())
        return info()!!
    }
    fun selectAll(): Map<String, Any> {
        check(canReadAll()) { "尚未授予共享存储读取权限" }
        check(prefs.edit().remove("tree").putBoolean("all", true).commit()); return info()!!
    }
    fun disconnect() { prefs.getString("tree", null)?.let {
        try { activity.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
    }; check(prefs.edit().clear().commit()); pending.clear() }
    fun info(): Map<String, Any>? {
        val tree = prefs.getString("tree", null)
        val all = prefs.getBoolean("all", false)
        if (tree == null && !all) return null
        val readable = if (all) canReadAll() else activity.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission }
        return mapOf("name" to (if (all) "共享存储" else "所选文件夹"), "automatic" to prefs.getBoolean("automatic", false), "readable" to readable, "all" to all)
    }
    fun scan(): Map<String, Any> {
        check(info()?.get("readable") == true) { "请先选择并授权扫描文件夹" }
        pending.clear(); var total = 0; var unchanged = 0; var examined = 0
        val warnings = mutableListOf<String>()
        fun add(entry: Entry) {
            if (!formats().contains(entry.name.substringAfterLast('.').lowercase())) return
            if (!BookArchiveImport.supported(entry.name)) return
            total++
            val signature = "${entry.size}:${entry.modified}"
            if (entry.modified > 0 && prefs.getString("done:${entry.uri}", null) == signature) { unchanged++; return }
            pending[UUID.randomUUID().toString()] = entry
        }
        val tree = prefs.getString("tree", null)?.let(Uri::parse)
        if (tree != null) {
            val queue = java.util.ArrayDeque<Pair<String, Int>>()
            queue.add(DocumentsContract.getTreeDocumentId(tree) to 0)
            while (!queue.isEmpty() && examined < 12000) {
                val (id, depth) = queue.removeFirst()
                if (depth > 24) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                activity.contentResolver.query(children, arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified"), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext() && examined++ < 12000) {
                        val child = cursor.getString(0); val name = cursor.getString(1) ?: continue
                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(child to depth + 1)
                        else add(Entry(DocumentsContract.buildDocumentUriUsingTree(tree, child), name, cursor.getLong(3), cursor.getLong(4), tree))
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION") val root = Environment.getExternalStorageDirectory()
            root.walkTopDown().maxDepth(24).onEnter { !it.name.startsWith('.') && it.name != "Android" }.take(12000).forEach { file ->
                examined++; if (file.isFile && file.canRead()) add(Entry(Uri.fromFile(file), file.name, file.length(), file.lastModified(), null))
            }
        }
        if (examined >= 12000) warnings += "扫描达到 12000 项上限，请选择较小的文件夹"
        return mapOf("books" to pending.map { (token, entry) -> mapOf("token" to token, "name" to entry.name, "size" to entry.size, "folder" to "") }, "total" to total, "unchanged" to unchanged, "warnings" to warnings)
    }
    fun stage(token: String, root: File, copy: (Uri, File, Long) -> Unit): Map<String, String> {
        val entry = checkNotNull(pending[token]) { "扫描结果已过期" }; root.mkdirs()
        val file = File.createTempFile("scan-", ".source", root)
        try { copy(entry.uri, file, BookArchiveImport.limit(entry.name)); return mapOf("name" to entry.name, "path" to file.path) }
        catch (error: Exception) { file.delete(); throw error }
    }
    fun link(token: String, links: BookTextLinks, root: File, copy: (Uri, File, Long) -> Unit): Map<String, String> {
        val entry = checkNotNull(pending[token]) { "扫描结果已过期" }
        return mapOf("name" to entry.name, "reference" to links.remember(entry.uri, entry.grant))
    }
    fun markImported(token: String) {
        val entry = checkNotNull(pending[token]) { "扫描结果已过期" }
        check(prefs.edit().putString("done:${entry.uri}", "${entry.size}:${entry.modified}").commit())
    }
    fun finish() { pending.clear() }
}
