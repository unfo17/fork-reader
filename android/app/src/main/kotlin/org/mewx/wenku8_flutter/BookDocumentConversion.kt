package org.mewx.wenku8_flutter

import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.commonmark.parser.Parser as MarkdownParser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

internal object MobiConverter {
    init { System.loadLibrary("wenku_mobi") }
    external fun exportParts(input: String, output: String): Int
}

internal class BookDocumentConversion(private val directory: File) {
    companion object {
        private const val MAX_MARKUP = 32 * 1024 * 1024
        private val htmlBlocks = setOf("p", "li", "pre", "blockquote", "td", "th", "figcaption")
        private val headings = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    }

    fun convert(name: String, source: File, output: File,
                resource: ((String) -> InputStream?)? = null) {
        val writer = BookEpubWriter(directory, name.substringBeforeLast('.'))
        when (BookArchiveImport.extension(name)) {
            "html", "htm", "xhtml" -> html(Jsoup.parse(source, null).body(), writer, resource)
            "md", "markdown" -> {
                val markdown = MarkdownParser.builder().build().parse(source.readText())
                val page = HtmlRenderer.builder().escapeHtml(false).sanitizeUrls(true).build().render(markdown)
                html(Jsoup.parseBodyFragment(page).body(), writer, resource)
            }
            "fb2" -> fb2(source, writer)
            "docx" -> docx(source, writer)
            "doc" -> source.inputStream().use { input ->
                HWPFDocument(input).use { document ->
                    document.summaryInformation?.let {
                        writer.title = it.title?.trim()?.takeIf(String::isNotEmpty) ?: writer.title
                        writer.author = it.author.orEmpty()
                    }
                    WordExtractor(document).paragraphText.forEach(writer::text)
                    for (picture in document.picturesTable.allPictures) {
                        val key = writer.inlineResourceKey()
                        ByteArrayInputStream(picture.content).use {
                            writer.resource(key, picture.mimeType, it)
                        }
                        writer.image(key)
                    }
                }
            }
            "pdf" -> pdf(source, writer)
            "mobi", "azw", "azw3", "prc" -> kindle(source, writer)
            "rtf" -> rtf(source, writer)
            else -> error("暂不支持此文档格式")
        }
        writer.write(output)
    }

    private fun html(root: Element, writer: BookEpubWriter, resource: ((String) -> InputStream?)?) {
        root.select("script,style,link,object,iframe,embed,template,form").remove()
        fun visit(element: Element) {
            val tag = element.normalName()
            when {
                tag in headings -> writer.heading(element.text())
                tag == "img" -> {
                    val name = element.attr("src")
                    val ext = name.substringAfterLast('.').lowercase()
                    if (ext in setOf("jpg", "jpeg", "png", "gif", "webp")) {
                        resource?.invoke(name)?.use {
                            writer.resource(name, ext, it)
                            writer.image(name)
                        }
                    }
                }
                tag in htmlBlocks -> {
                    writer.text(if (tag == "pre") element.wholeText() else element.text())
                    element.select("img").forEach { visit(it) }
                }
                else -> {
                    writer.text(element.ownText())
                    element.children().forEach(::visit)
                }
            }
        }
        visit(root)
    }

    private fun xml(input: InputStream): Element = input.use {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_MARKUP) { "文档正文超过 32 MiB" }
            bytes.write(buffer, 0, count)
        }
        Jsoup.parse(ByteArrayInputStream(bytes.toByteArray()), null, "", Parser.xmlParser())
    }

    private fun fb2(source: File, writer: BookEpubWriter) {
        val book = xml(source.inputStream())
        val info = book.getElementsByTag("title-info").first()
        info?.getElementsByTag("book-title")?.first()?.text()?.takeIf(String::isNotBlank)?.let {
            writer.title = it
        }
        writer.author = info?.getElementsByTag("author")?.joinToString("、") { it.text() }.orEmpty()
        for (binary in book.getElementsByTag("binary")) {
            val identifier = binary.id()
            val type = binary.attr("content-type")
            if (identifier.isNotEmpty() && type.startsWith("image/")) {
                android.util.Base64InputStream(
                    ByteArrayInputStream(binary.text().toByteArray(Charsets.US_ASCII)), android.util.Base64.DEFAULT
                ).use { writer.resource(identifier, type, it) }
            }
        }
        for (body in book.getElementsByTag("body")) {
            for (item in body.select("title > p, section p, image")) {
                when {
                    item.normalName() == "image" -> {
                        val id = (item.attr("l:href").ifEmpty { item.attr("xlink:href") }).removePrefix("#")
                        if (id.isNotEmpty()) writer.image(id)
                    }
                    item.parent()?.normalName() == "title" -> writer.heading(item.text())
                    else -> writer.text(item.text())
                }
            }
        }
    }

    private fun docx(source: File, writer: BookEpubWriter) = BookDocumentInput(source).zip().use { zip ->
        val body = xml(zip.getInputStream(requireNotNull(zip.getEntry("word/document.xml")) { "DOCX 缺少正文" }))
        val relationships = zip.getEntry("word/_rels/document.xml.rels")?.let { xml(zip.getInputStream(it)) }
            ?.getElementsByTag("Relationship")?.associate {
                it.attr("Id") to it.attr("Target")
            }.orEmpty()
        zip.getEntry("docProps/core.xml")?.let {
            val metadata = xml(zip.getInputStream(it))
            val title = metadata.getElementsByTag("dc:title").first()?.text()
            if (!title.isNullOrBlank()) writer.title = title
            writer.author = metadata.getElementsByTag("dc:creator").first()?.text().orEmpty()
        }
        for (p in body.getElementsByTag("w:p")) {
            val style = p.getElementsByTag("w:pStyle").first()?.attr("w:val").orEmpty()
            val text = p.getElementsByTag("w:t").joinToString("") { it.wholeText() }
            if (style.startsWith("heading", true) || style.startsWith("标题")) writer.heading(text)
            else writer.text(text)
            for (drawing in p.getElementsByTag("a:blip")) {
                val target = relationships[drawing.attr("r:embed")] ?: continue
                val name = target.removePrefix("/")
                val entry = zip.getEntry(if (name.startsWith("word/")) name else "word/$name") ?: continue
                zip.getInputStream(entry).use { writer.resource(name, name.substringAfterLast('.'), it) }
                writer.image(name)
            }
        }
    }

    private fun pdf(source: File, writer: BookEpubWriter) {
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly().setTempDir(directory)).use { document ->
            require(!document.isEncrypted && document.currentAccessPermission.canExtractContent()) { "PDF 已加密或禁止提取正文" }
            require(document.numberOfPages in 1..2000) { "PDF 页数过多，请拆分后导入" }
            val metadata = document.documentInformation
            writer.title = metadata.title?.takeIf(String::isNotBlank) ?: writer.title
            writer.author = metadata.author.orEmpty()
            val stripper = PDFTextStripper()
            val renderer = PDFRenderer(document)
            for (index in 0 until document.numberOfPages) {
                val page = index + 1
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(document).trim()
                writer.heading("第 $page 页")
                if (text.isNotEmpty()) {
                    text.lineSequence().forEach(writer::text)
                } else {
                    val bounds = document.getPage(index).cropBox
                    val scale = (1400f / maxOf(1f, bounds.width, bounds.height)).coerceAtMost(1.6f)
                    val bitmap = renderer.renderImage(index, scale, ImageType.RGB)
                    val image = File.createTempFile("pdf-page-", ".jpg", directory)
                    try {
                        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)) }
                        val key = writer.inlineResourceKey()
                        image.inputStream().use { writer.resource(key, "jpeg", it) }
                        writer.image(key)
                    } finally {
                        bitmap.recycle()
                        image.delete()
                    }
                }
            }
        }
    }

    private fun kindle(source: File, writer: BookEpubWriter) {
        val parts = File(directory, "kindle").apply { mkdir() }
        when (MobiConverter.exportParts(source.path, parts.path)) {
            0 -> Unit
            1 -> error("这本 Kindle 书籍受 DRM 保护")
            3 -> error("Kindle 文档超过转换大小限制")
            else -> error("无法解析 Kindle 书籍")
        }
        val manifest = File(parts, "content.opf")
        if (manifest.isFile) {
            val metadata = xml(manifest.inputStream())
            fun value(name: String) = metadata.getElementsByTag(name).first()?.text()
            writer.title = value("dc:title")?.takeIf(String::isNotBlank) ?: writer.title
            writer.author = value("dc:creator").orEmpty()
        }
        val files = parts.listFiles().orEmpty().associateBy { it.name }
        for (part in files.values.filter { it.name.startsWith("part") }.sortedBy { it.name }) {
            html(Jsoup.parse(part, null).body(), writer) { path ->
                files[path.substringAfterLast('/')]?.inputStream()
            }
        }
    }

    private fun rtf(source: File, writer: BookEpubWriter) {
        require(source.length() <= MAX_MARKUP) { "RTF 正文超过 32 MiB" }
        val input = source.readText(Charsets.ISO_8859_1)
        require(input.startsWith("{\\rtf")) { "无法解析 RTF 文件" }
        data class Group(var hidden: Boolean = false, var fallback: Int = 1)
        val stack = ArrayDeque<Group>()
        var group = Group()
        var skip = 0
        var charset = Charset.forName("windows-1252")
        val text = StringBuilder()
        fun emit(value: Char) {
            if (skip > 0) skip-- else if (!group.hidden) text.append(value)
        }
        fun newline() {
            writer.text(text.toString())
            text.clear()
        }
        var i = 0
        while (i < input.length) {
            when (val ch = input[i++]) {
                '{' -> {
                    require(stack.size < 256) { "RTF 嵌套过深" }
                    stack.addLast(group)
                    group = group.copy()
                }
                '}' -> if (stack.isNotEmpty()) group = stack.removeLast()
                '\n', '\r' -> Unit
                '\\' -> {
                    if (i >= input.length) break
                    val code = input[i]
                    if (!code.isLetter()) {
                        i++
                        when (code) {
                            '*', '~' -> if (code == '*') group.hidden = true else emit(' ')
                            '\\', '{', '}' -> emit(code)
                            '\'' -> if (i + 1 < input.length) {
                                val bytes = arrayListOf<Byte>()
                                fun appendByte() {
                                    input.substring(i, i + 2).toIntOrNull(16)?.let { bytes += it.toByte() }
                                    i += 2
                                }
                                appendByte()
                                while (i + 3 < input.length && input[i] == '\\' && input[i + 1] == '\'') {
                                    i += 2
                                    appendByte()
                                }
                                bytes.toByteArray().toString(charset).forEach(::emit)
                            }
                        }
                        continue
                    }
                    val start = i
                    while (i < input.length && input[i].isLetter()) i++
                    val word = input.substring(start, i)
                    val numberStart = i
                    if (i < input.length && input[i] == '-') i++
                    while (i < input.length && input[i].isDigit()) i++
                    val number = input.substring(numberStart, i).toIntOrNull()
                    if (i < input.length && input[i] == ' ') i++
                    when (word) {
                        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer" ->
                            group.hidden = true
                        "uc" -> group.fallback = number?.coerceIn(0, 32) ?: 1
                        "ansicpg" -> if (number != null) {
                            charset = runCatching {
                                Charset.forName(when (number) {
                                    936 -> "GBK"; 950 -> "Big5"; 65001 -> "UTF-8"
                                    else -> "windows-$number"
                                })
                            }.getOrDefault(charset)
                        }
                        "u" -> if (!group.hidden && number != null) {
                            text.append(number.toChar())
                            skip = group.fallback
                        }
                        "par", "line" -> if (!group.hidden) newline()
                        "tab" -> emit(' ')
                        "bin" -> i += (number ?: 0).coerceIn(0, input.length - i)
                    }
                }
                else -> emit(ch)
            }
            if (text.length >= 8000 && !text.last().isHighSurrogate()) newline()
        }
        newline()
    }
}
