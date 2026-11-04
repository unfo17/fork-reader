package org.mewx.wenku8_flutter

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

/** Streaming FictionBook adapter; paragraph styles and note identities stay in EPUB resources. */
internal object Fb2DocumentImport {
    fun read(source: BookDocumentInput, writer: BookEpubWriter): List<String> {
        val warnings = linkedSetOf<String>()
        source.inputStream().use { input ->
            val xml = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(input, null) }
            var titleInfo = -1
            var author = -1
            var coverPage = -1
            var body = -1
            var notes = false
            val names = mutableListOf<String>()
            val authors = mutableListOf<String>()
            val genres = mutableListOf<String>()
            val sections = mutableMapOf<Int, String>()
            var captureDepth = -1
            var captureTag = ""
            var capture = StringBuilder()
            var sectionAnchor = ""
            fun href(): String = xml.getAttributeValue("http://www.w3.org/1999/xlink", "href") ?: xml.getAttributeValue(null, "href").orEmpty()
            fun escape(text: String) = BookEpubWriter.escape(text)
            fun opening(name: String, rootNote: Boolean = false): String {
                val tag = when (name) { "emphasis" -> "em"; "strikethrough" -> "s"; "subtitle" -> "h3"; "v" -> "p"; "cite" -> "blockquote"; "poem", "stanza" -> "div"; "section" -> if (rootNote) "aside" else "div"; "title" -> "h3"; else -> name.takeIf { it in setOf("p", "strong", "code", "sub", "sup", "a") } ?: "span" }
                if (name == "empty-line") return "<br/>"
                if (name == "image") return "<img src=\"${writer.resourceHref(href().removePrefix("#"))}\"/>"
                val id = xml.getAttributeValue(null, "id")?.let { " id=\"${escape(it)}\"" }.orEmpty()
                val reference = if (name == "a") " href=\"${escape(href())}\"" + if (xml.getAttributeValue(null, "type") == "note") " epub:type=\"noteref\"" else "" else ""
                return "<$tag$id$reference${if (rootNote) " epub:type=\"footnote\"" else ""}>"
            }
            val captureTags = mutableListOf<String>()
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                require(xml.depth <= 256) { "FB2 嵌套层数过多" }
                when (xml.eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = xml.name
                        if (captureDepth >= 0) {
                            val opening = opening(name)
                            capture.append(opening)
                            captureTags += if (opening.endsWith("/>")) "" else opening.substringAfter('<').substringBefore(' ').substringBefore('>')
                        } else when {
                            name == "title-info" -> titleInfo = xml.depth
                            name == "author" && titleInfo >= 0 -> { author = xml.depth; names.clear() }
                            author >= 0 && name in listOf("first-name", "middle-name", "last-name", "nickname") -> names += xml.nextText().take(2048)
                            titleInfo >= 0 && name == "book-title" -> writer.title = xml.nextText().take(2048)
                            titleInfo >= 0 && name == "lang" -> writer.language = xml.nextText().trim().take(32)
                            titleInfo >= 0 && name == "genre" -> genres += xml.nextText().trim().take(160)
                            coverPage >= 0 && name == "image" && body < 0 -> writer.cover = href().removePrefix("#")
                            name == "coverpage" && titleInfo >= 0 -> coverPage = xml.depth
                            name == "binary" -> {
                                val id = xml.getAttributeValue(null, "id").orEmpty()
                                val type = xml.getAttributeValue(null, "content-type").orEmpty()
                                val encoded = xml.nextText()
                                require(encoded.length <= 12 * 1024 * 1024) { "FB2 单张插图超过大小限制" }
                                if (type in listOf("image/jpeg", "image/png", "image/gif", "image/webp")) {
                                    ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT)).use { writer.resource(id, type, it) }
                                } else warnings += "FB2 包含暂不支持的图片类型：$type"
                            }
                            name == "body" -> { body = xml.depth; notes = xml.getAttributeValue(null, "name")?.lowercase() in listOf("notes", "comments") }
                            body >= 0 && name == "section" && !notes -> sections[xml.depth] = xml.getAttributeValue(null, "id").orEmpty()
                            (titleInfo >= 0 && name == "annotation") || body >= 0 && (name in listOf("title", "p", "subtitle", "cite", "poem", "image", "empty-line") || notes && name == "section") -> {
                                captureDepth = xml.depth; captureTag = name; capture = StringBuilder(); captureTags.clear()
                                sectionAnchor = sections.toSortedMap().values.lastOrNull().orEmpty()
                                val opening = opening(name, rootNote = notes && name == "section")
                                capture.append(opening)
                                captureTags += if (opening.endsWith("/>")) "" else opening.substringAfter('<').substringBefore(' ').substringBefore('>')
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (captureDepth >= 0) capture.append(escape(xml.text ?: error("FB2 包含无法解析的 XML 实体")))
                    XmlPullParser.END_TAG -> {
                        val name = xml.name
                        if (captureDepth >= 0) {
                            captureTags.removeAt(captureTags.lastIndex).takeIf { it.isNotEmpty() }?.let { capture.append("</$it>") }
                            if (xml.depth == captureDepth) {
                                val markup = capture.toString()
                                if (captureTag == "annotation") writer.summary = Jsoup.parseBodyFragment(markup).text().take(4000)
                                else if (captureTag == "title" && !notes) {
                                    val heading = Jsoup.parseBodyFragment(markup).text()
                                    writer.heading(heading)
                                    writer.markup("<h2${if (sectionAnchor.isEmpty()) "" else " id=\"${escape(sectionAnchor)}\""}>${escape(heading)}</h2>")
                                } else writer.markup(markup)
                                captureDepth = -1
                            }
                        } else {
                            if (name == "author" && xml.depth == author) { authors += names.filter { it.isNotBlank() }.joinToString(" "); author = -1 }
                            if (name == "title-info" && xml.depth == titleInfo) titleInfo = -1
                            if (name == "coverpage" && xml.depth == coverPage) coverPage = -1
                            if (name == "body" && xml.depth == body) { body = -1; notes = false }
                            if (name == "section") sections.remove(xml.depth)
                        }
                    }
                }
                require(capture.length <= 1024 * 1024) { "FB2 单个段落或脚注超过大小限制" }
                xml.nextToken()
            }
            writer.author = authors.distinct().joinToString("、")
            writer.tags = genres.distinct()
        }
        return warnings.toList()
    }
}
