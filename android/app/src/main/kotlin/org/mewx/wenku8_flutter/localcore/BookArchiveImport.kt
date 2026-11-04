package org.mewx.wenku8_flutter

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Restores the missing native preparation boundary used by BookDocuments. */
internal class BookArchiveImport(private val root: File) {
    var documents = BookMarkupImport(root)
    companion object {
        const val MAX_ARCHIVE = 512L * 1024 * 1024
        private val formats = setOf("epub", "txt", "fb2", "docx", "rtf", "doc", "pdf", "html", "htm", "xhtml", "md", "markdown", "mobi", "azw", "azw3", "prc")
        fun supported(name: String) = name.substringAfterLast('.').lowercase() in formats
        fun limit(name: String) = if (name.endsWith(".txt", true)) BookTextDecoder.MAX_TEXT else MAX_ARCHIVE
    }
    fun prepare(name: String, source: File): List<Map<String, String>> = prepare(name, BookDocumentInput(source))
    fun prepare(name: String, source: BookDocumentInput): List<Map<String, String>> {
        val output = File(root.apply { mkdirs() }, "prepared-${UUID.randomUUID()}.epub")
        return try { convert(name, source, output); listOf(mapOf("name" to name.substringBeforeLast('.') + ".epub", "path" to output.path)) }
        catch (error: Exception) { output.delete(); listOf(mapOf("name" to name, "error" to (error.message ?: "文档转换失败"))) }
    }
    fun convert(name: String, source: BookDocumentInput, output: File): List<String> {
        require(supported(name)) { "不支持的文档格式" }
        require(source.length() in 1..limit(name)) { "原书为空或超过导入上限" }
        BookImportWorkspace(File(root, "document-conversion")).use { session ->
            val target = File(session.root, "prepared.epub")
            val extension = name.substringAfterLast('.').lowercase()
            val warnings = when (extension) {
                "epub" -> { source.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 65536) } }; emptyList() }
                "txt" -> { LocalTextPublication.prepare(source, target, name.substringBeforeLast('.'), File(session.root, "text")); emptyList() }
                "mobi", "azw", "azw3", "prc" -> { mobi(source, session.root, target); emptyList() }
                else -> {
                    val writer = BookEpubWriter(session.root, name.substringBeforeLast('.')).apply { identifier = "urn:local:${output.nameWithoutExtension}" }
                    val effective = if (extension == "pdf" && source.file == null && source.descriptor == null) {
                        val file = File(session.root, "source.pdf")
                        source.inputStream().use { input -> file.outputStream().use { input.copyTo(it, 65536) } }
                        BookDocumentInput(file)
                    } else source
                    val result = documents.read(name, effective, writer, source.relatedResource)
                    writer.write(target)
                    result.warnings
                }
            }
            output.parentFile?.mkdirs()
            val temporary = File(output.path + ".part")
            try { target.copyTo(temporary, overwrite = true); check(temporary.renameTo(output)) { "无法保存转换后的文档" } }
            finally { temporary.delete() }
            return warnings
        }
    }
    private fun mobi(source: BookDocumentInput, workspace: File, output: File) {
        val input = source.file ?: File(workspace, "original.mobi").also { file -> source.inputStream().use { stream -> file.outputStream().use { stream.copyTo(it, 65536) } } }
        val parts = File(workspace, "mobi-parts").apply { mkdirs() }
        when (MobiConverter.exportParts(input.path, parts.path)) {
            0 -> Unit
            1 -> error("这本 Kindle 书有 DRM 保护，无法导入")
            3 -> error("Kindle 正文或资源超过转换上限")
            4 -> error("Kindle 转换文件写入失败")
            else -> error("Kindle 文件损坏或格式不受支持")
        }
        require(File(parts, "content.opf").isFile) { "Kindle 转换结果缺少书籍清单" }
        parts.listFiles().orEmpty().filter { it.extension in listOf("html", "xhtml") }.forEach { file ->
            val document = Jsoup.parse(file, null)
            document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false)
            document.selectFirst("html")?.attr("xmlns", "http://www.w3.org/1999/xhtml")
            document.select("script,iframe,object,embed").remove()
            file.writeText(document.outerHtml())
        }
        ZipOutputStream(output.outputStream().buffered(65536)).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = java.util.zip.CRC32().apply { update(mime) }.value })
            zip.write(mime); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write("""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()); zip.closeEntry()
            var expanded = 0L
            val files = parts.listFiles().orEmpty().filter { it.isFile }
            require(files.size <= 12000) { "Kindle 转换资源过多" }
            files.forEach { file ->
                expanded += file.length(); require(expanded <= MAX_ARCHIVE) { "Kindle 转换资源过大" }
                zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip, 65536) }; zip.closeEntry()
            }
        }
    }
}
