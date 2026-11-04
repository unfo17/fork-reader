package org.mewx.wenku8_flutter

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Paths

/** Bounded OOXML conversion; originals and unrelated ZIP entries are never expanded to files. */
internal object DocxDocumentImport {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private data class Relationship(val target: String, val external: Boolean)
    private fun attribute(parser: XmlPullParser, key: String): String = parser.getAttributeValue(W, key) ?: parser.getAttributeValue(null, key).orEmpty()
    private fun <T> xml(input: InputStream, maximum: Long = 32L * 1024 * 1024, action: (XmlPullParser) -> T): T {
        var consumed = 0L
        val bounded = object : FilterInputStream(input) {
            override fun read(): Int { val value = `in`.read(); if (value >= 0) { consumed++; require(consumed <= maximum) { "DOCX XML 内容过大" } }; return value }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { val n = `in`.read(bytes, offset, length); if (n > 0) { consumed += n; require(consumed <= maximum) { "DOCX XML 内容过大" } }; return n }
        }
        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(bounded, null) }
        return action(parser)
    }
    private fun part(target: String): String {
        val decoded = URI(target).path ?: error("DOCX 资源地址无效")
        val result = Paths.get(if (decoded.startsWith('/')) decoded.removePrefix("/") else "word/$decoded").normalize().toString().replace('\\', '/')
        require(!result.startsWith("../") && !result.startsWith('/') && '\u0000' !in result) { "DOCX 资源地址越界" }
        return result
    }
    fun read(source: BookDocumentInput, writer: BookEpubWriter): List<String> {
        val warnings = linkedSetOf<String>()
        source.zip().use { zip ->
            require(zip.entries.size <= 12000) { "DOCX 压缩包文件过多" }
            val document = zip.getEntry("word/document.xml") ?: error("DOCX 缺少正文")
            val relationships = mutableMapOf<String, Relationship>()
            val usedImages = linkedMapOf<String, String>()
            val usedNotes = mutableSetOf<String>()
            zip.getEntry("docProps/core.xml")?.let { entry -> zip.getInputStream(entry).use { input -> xml(input, 1024 * 1024) { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                        "title" -> parser.nextText().takeIf { it.isNotBlank() }?.let { writer.title = it.take(2048) }
                        "creator" -> writer.author = parser.nextText().take(2048)
                        "description" -> writer.summary = parser.nextText().take(4000)
                        "keywords" -> writer.tags = parser.nextText().split(',', ';', '，').map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    }
                    parser.nextToken()
                }
            } } }
            zip.getEntry("word/_rels/document.xml.rels")?.let { entry -> zip.getInputStream(entry).use { input -> xml(input, 4L * 1024 * 1024) { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                        require(relationships.size < 12000) { "DOCX 资源引用过多" }
                        relationships[parser.getAttributeValue(null, "Id").orEmpty()] = Relationship(parser.getAttributeValue(null, "Target").orEmpty(), parser.getAttributeValue(null, "TargetMode") == "External")
                    }
                    parser.nextToken()
                }
            } } }
            val headingStyles = mutableSetOf<String>()
            zip.getEntry("word/styles.xml")?.let { entry -> zip.getInputStream(entry).use { input -> xml(input) { parser ->
                var current = ""
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                        "style" -> current = attribute(parser, "styleId")
                        "outlineLvl" -> if (attribute(parser, "val").toIntOrNull() in 0..8) headingStyles += current
                        "name" -> if (attribute(parser, "val").matches(Regex("heading [1-9]", RegexOption.IGNORE_CASE))) headingStyles += current
                    }
                    parser.nextToken()
                }
            } } }
            fun paragraph(parser: XmlPullParser): Pair<String, String?> {
                val start = parser.depth
                val body = StringBuilder()
                val plain = StringBuilder()
                var bold = false; var italic = false; var strike = false; var underline = false
                var alignment = ""
                var style = ""
                var hyperlink = false
                while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
                    require(parser.depth <= 256 && body.length <= 1024 * 1024) { "DOCX 段落过大或嵌套过深" }
                    if (parser.eventType == XmlPullParser.END_TAG && parser.depth == start) break
                    if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                        "r" -> { bold = false; italic = false; strike = false; underline = false }
                        "b" -> bold = attribute(parser, "val") !in listOf("0", "false", "off")
                        "i" -> italic = attribute(parser, "val") !in listOf("0", "false", "off")
                        "strike" -> strike = attribute(parser, "val") !in listOf("0", "false", "off")
                        "u" -> underline = attribute(parser, "val") != "none"
                        "pStyle" -> style = attribute(parser, "val")
                        "jc" -> alignment = when (attribute(parser, "val")) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; else -> "left" }
                        "t", "delText" -> {
                            if (parser.name == "delText") { parser.nextText(); continue }
                            val text = parser.nextText()
                            plain.append(text)
                            var html = BookEpubWriter.escape(text)
                            if (bold) html = "<strong>$html</strong>"
                            if (italic) html = "<em>$html</em>"
                            if (strike) html = "<s>$html</s>"
                            if (underline) html = "<u>$html</u>"
                            body.append(html)
                        }
                        "tab" -> { body.append("<span style=\"white-space:pre\">&#9;</span>"); plain.append('\t') }
                        "br", "cr" -> { body.append("<br/>"); plain.append('\n') }
                        "bookmarkStart" -> attribute(parser, "name").takeIf { it.isNotBlank() }?.let { body.append("<span id=\"bookmark-${BookEpubWriter.escape(it)}\"></span>") }
                        "hyperlink" -> {
                            val anchor = attribute(parser, "anchor")
                            val target = if (anchor.isNotBlank()) "#bookmark-$anchor" else relationships[parser.getAttributeValue(R, "id")]?.target.orEmpty()
                            if (target.startsWith('#') || target.startsWith("https://") || target.startsWith("http://")) {
                                body.append("<a href=\"${BookEpubWriter.escape(target)}\">"); hyperlink = true
                            }
                        }
                        "blip", "imagedata" -> {
                            val id = parser.getAttributeValue(R, "embed") ?: parser.getAttributeValue(R, "id")
                            val reference = relationships[id]
                            if (reference == null || reference.external) { warnings += "DOCX 部分插图引用缺失或为外部链接"; continue }
                            val path = part(reference.target)
                            val key = "docx-image-$id"
                            usedImages[path] = key
                            body.append("<img src=\"${writer.resourceHref(key)}\"/>")
                        }
                        "footnoteReference", "endnoteReference" -> {
                            val prefix = if (parser.name == "footnoteReference") "fn" else "en"
                            val id = "$prefix-${attribute(parser, "id")}"
                            usedNotes += id
                            body.append("<a href=\"#$id\" epub:type=\"noteref\">[${BookEpubWriter.escape(attribute(parser, "id"))}]</a>")
                        }
                    }
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "hyperlink" && hyperlink) { body.append("</a>"); hyperlink = false }
                }
                if (hyperlink) body.append("</a>")
                val heading = style.matches(Regex("Heading[1-9]", RegexOption.IGNORE_CASE)) || style in headingStyles
                val tag = if (heading) "h2" else "p"
                return "<$tag${if (alignment.isNotEmpty()) " style=\"text-align:$alignment\"" else ""}>$body</$tag>" to plain.toString().takeIf { heading && it.isNotBlank() }
            }
            zip.getInputStream(document).use { input -> xml(input) { parser ->
                var table: StringBuilder? = null
                var tableDepth = -1
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    require(parser.depth <= 256) { "DOCX 嵌套过深" }
                    if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                        "tbl" -> { if (table == null) { table = StringBuilder("<table>"); tableDepth = parser.depth } else table!!.append("<table>") }
                        "tr" -> table?.append("<tr>")
                        "tc" -> table?.append("<td>")
                        "p" -> {
                            val (markup, title) = paragraph(parser)
                            if (table != null) table!!.append(markup)
                            else { if (title != null) writer.heading(title); writer.markup(markup) }
                        }
                    }
                    if (parser.eventType == XmlPullParser.END_TAG) when (parser.name) {
                        "tr" -> table?.append("</tr>")
                        "tc" -> table?.append("</td>")
                        "tbl" -> {
                            table?.append("</table>")
                            if (parser.depth == tableDepth) { table?.toString()?.let(writer::markup); table = null; tableDepth = -1 }
                        }
                    }
                    require((table?.length ?: 0) <= 1024 * 1024) { "DOCX 单张表格过大" }
                    parser.nextToken()
                }
            } }
            for ((path, prefix, noteTag) in listOf(Triple("word/footnotes.xml", "fn", "footnote"), Triple("word/endnotes.xml", "en", "endnote"))) {
                zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { input -> xml(input) { parser ->
                    var current: String? = null
                    var note = StringBuilder()
                    var noteDepth = -1
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG) {
                            if (parser.name == noteTag) {
                                current = "$prefix-${attribute(parser, "id")}".takeIf { it in usedNotes }
                                noteDepth = parser.depth
                                note = StringBuilder()
                            } else if (parser.name == "p" && current != null) note.append(paragraph(parser).first)
                        }
                        if (parser.eventType == XmlPullParser.END_TAG && parser.depth == noteDepth && current != null) {
                            writer.markup("<aside id=\"${BookEpubWriter.escape(current!!)}\" epub:type=\"footnote\">$note</aside>"); current = null
                        }
                        require(note.length <= 1024 * 1024) { "DOCX 脚注过大" }
                        parser.nextToken()
                    }
                } } }
            }
            val selectedImages = usedImages.keys.mapNotNull { zip.getEntry(it) }
            zip.readEntries(selectedImages) { entry, stream ->
                val type = when (entry.name.substringAfterLast('.').lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> null }
                if (type != null) writer.resource(usedImages.getValue(entry.name), type, stream)
                else warnings += "DOCX 含暂不支持的插图格式：${entry.name.substringAfterLast('.')}"
            }
            if (selectedImages.size != usedImages.size) warnings += "DOCX 部分插图文件缺失"
        }
        return warnings.toList()
    }
}
