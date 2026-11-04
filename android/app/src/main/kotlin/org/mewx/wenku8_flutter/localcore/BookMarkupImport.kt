package org.mewx.wenku8_flutter

import android.util.Base64
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** Native adapter for the document preparation contract in lib/book_documents.dart.
 * The existing EPUB writer still owns resource limits, packaging and chapter identities.
 */
internal class BookMarkupImport(private val root: File, private val pdfReader: ((String, BookDocumentInput, BookEpubWriter) -> List<String>)? = null) {
    data class Result(val warnings: List<String> = emptyList())
    fun read(name: String, source: BookDocumentInput, writer: BookEpubWriter,
        resource: ((String) -> InputStream?)? = null): Result {
        require(source.length() in 1..BookArchiveImport.MAX_ARCHIVE) { "文档为空或超过 512MiB" }
        val extension = name.substringAfterLast('.').lowercase()
        if (extension == "pdf") return Result((pdfReader ?: error("PDF 转换器未初始化"))(name, source, writer))
        if (extension == "doc") return Result(DocBinaryImport.read(source, writer))
        if (extension == "rtf") return Result(RtfDocumentImport.read(source, writer))
        if (extension == "docx") return Result(DocxDocumentImport.read(source, writer))
        if (extension == "fb2") return Result(Fb2DocumentImport.read(source, writer))
        require(source.length() <= 64L * 1024 * 1024) { "HTML / Markdown 文档需要在 64MiB 以内" }
        val document = if (extension in listOf("md", "markdown")) {
            val markdown = source.inputStream().use { BookTextDecoder.decode(it.readBytes()) }
            Jsoup.parse(HtmlRenderer.builder().build().render(Parser.builder().build().parse(markdown)))
        } else source.inputStream().use { Jsoup.parse(it, null, "") }
        return html(document, writer, resource)
    }
    fun html(document: Document, writer: BookEpubWriter, resource: ((String) -> InputStream?)? = null): Result {
        val warnings = linkedSetOf<String>()
        if (document.title().isNotBlank()) writer.title = document.title().take(2048)
        document.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { writer.author = it.take(2048) }
        document.selectFirst("meta[name=description]")?.attr("content")?.let { writer.summary = it.take(4000) }
        document.selectFirst("meta[name=keywords]")?.attr("content")?.let { writer.tags = it.split(',', '，').map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }.distinct() }
        document.select("script,iframe,object,embed,form,input,button,base").remove()
        document.allElements.forEach { node -> node.attributes().asList().filter { it.key.startsWith("on", true) }.forEach { node.removeAttr(it.key) } }
        document.select("a[href]").forEach { link -> if (link.attr("href").trim().startsWith("javascript:", true)) link.removeAttr("href") }
        document.select("img").forEachIndexed { index, image ->
            val src = image.attr("src")
            val key = "document-image-$index"
            try {
                val input: InputStream?
                val type: String
                if (src.startsWith("data:image/", true)) {
                    val comma = src.indexOf(',')
                    require(comma > 0 && src.substring(0, comma).endsWith(";base64", true) && src.length <= 12 * 1024 * 1024) { "插图编码无效或过大" }
                    type = src.substring(5, comma).substringBefore(';')
                    input = ByteArrayInputStream(Base64.decode(src.substring(comma + 1), Base64.DEFAULT))
                } else {
                    type = when (src.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "" }
                    input = resource?.invoke(src.replace('\\', '/'))
                }
                require(input != null && type.isNotEmpty()) { "图片资源未授权或缺失" }
                input.use { writer.resource(key, type, it) }
                image.attr("src", writer.resourceHref(key))
            } catch (error: Exception) {
                warnings += "部分插图无法导入：${error.message}"
                image.replaceWith(org.jsoup.nodes.TextNode(image.attr("alt").ifBlank { "[插图缺失]" }))
            }
        }
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false)
        val styles = document.select("style").joinToString("") { it.outerHtml() }
        var stylePending = styles
        document.body().childNodes().toList().forEach { node ->
            if (node is org.jsoup.nodes.TextNode) { writer.text(node.text()); return@forEach }
            val element = node as? org.jsoup.nodes.Element ?: return@forEach
            if (element.tagName() in listOf("h1", "h2")) { writer.heading(element.text()); stylePending = styles }
            val markup = element.outerHtml()
            if (markup.length <= 1024 * 1024) { writer.markup(stylePending + markup); stylePending = "" }
            else { writer.text(element.text()); warnings += "超长段落已按纯文本分段导入" }
        }

        return Result(warnings.toList())
    }
}
