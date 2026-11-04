package org.mewx.wenku8_flutter

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Range
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

/** Legacy Word text and pictures use the same Apache POI dependency selected by the upstream app. */
internal object DocBinaryImport {
    fun read(source: BookDocumentInput, writer: BookEpubWriter): List<String> {
        return try { modern(source, writer) }
        catch (old: org.apache.poi.hwpf.OldWordFileFormatException) {
            source.inputStream().use { input -> org.apache.poi.poifs.filesystem.POIFSFileSystem(input).use { filesystem ->
                org.apache.poi.hwpf.HWPFOldDocument(filesystem).use { document ->
                    val range = document.range
                    repeat(range.numParagraphs()) { index ->
                        val text = range.getParagraph(index).text().filter { it >= ' ' || it in "\n\r\t" }
                        if (LocalTextPublication.isHeading(text.trim())) writer.heading(text.trim())
                        else writer.text(text)
                    }
                }
            } }
            emptyList()
        }
    }
    private fun modern(source: BookDocumentInput, writer: BookEpubWriter): List<String> {
        require(source.length() in 1..64L * 1024 * 1024) { "DOC 文档需要在 64 MiB 以内" }
        val warnings = linkedSetOf<String>()
        source.inputStream().use { input -> HWPFDocument(input).use { document ->
            document.summaryInformation?.let { metadata ->
                metadata.title?.takeIf { it.isNotBlank() }?.let { writer.title = it.take(2048) }
                writer.author = metadata.author.orEmpty().take(2048)
                writer.summary = metadata.subject.orEmpty().take(4000)
                writer.tags = metadata.keywords.orEmpty().split(',', ';', '，').map { it.trim() }.filter { it.isNotEmpty() }
            }
            val fields = mutableListOf<Boolean>()
            val images = mutableSetOf<String>()
            fun paragraph(paragraph: Paragraph): Pair<String, Boolean> {
                val body = StringBuilder()
                repeat(paragraph.numCharacterRuns()) { index ->
                    val run = paragraph.getCharacterRun(index)
                    if (document.picturesTable.hasPicture(run)) {
                        val picture = document.picturesTable.extractPicture(run, false)
                        val key = "doc-image-${picture.startOffset}"
                        if (picture.mimeType in listOf("image/png", "image/jpeg", "image/gif", "image/webp")) {
                            if (images.add(key)) { require(picture.size <= 8 * 1024 * 1024) { "DOC 插图过大" }; ByteArrayInputStream(picture.content).use { writer.resource(key, picture.mimeType, it) } }
                            body.append("<img src=\"${writer.resourceHref(key)}\" alt=\"${BookEpubWriter.escape(picture.description.orEmpty())}\"/>")
                        } else { warnings += "DOC 包含暂不支持的插图格式：${picture.mimeType}"; body.append("[插图格式不支持]") }
                    }
                    val text = StringBuilder()
                    for ((offset, char) in run.text().withIndex()) when (char.code) {
                        19 -> fields += true
                        20 -> if (fields.isNotEmpty()) fields[fields.lastIndex] = false
                        21 -> if (fields.isNotEmpty()) fields.removeAt(fields.lastIndex)
                        1, 5 -> Unit
                        2 -> {
                            val position = run.startOffset + offset
                            val footnote = document.footnotes.getNoteIndexByAnchorPosition(position)
                            val endnote = document.endnotes.getNoteIndexByAnchorPosition(position)
                            val id = if (footnote >= 0) "fn-$footnote" else if (endnote >= 0) "en-$endnote" else null
                            if (id != null) text.append("\\u0000$id\\u0000")
                        }
                        7, 13 -> Unit
                        11 -> if (fields.none { it }) text.append('\n')
                        else -> if (fields.none { it } && (char >= ' ' || char == '\t')) text.append(char)
                    }
                    var html = BookEpubWriter.escape(text.toString()).replace("\n", "<br/>").replace("\t", "<span style=\"white-space:pre\">&#9;</span>")
                    html = html.replace(Regex("\\\\u0000((?:fn|en)-[0-9]+)\\\\u0000")) { match -> "<a epub:type=\"noteref\" href=\"#${match.groupValues[1]}\">[注]</a>" }
                    if (run.isBold) html = "<strong>$html</strong>"
                    if (run.isItalic) html = "<em>$html</em>"
                    if (run.underlineCode.toInt() > 0) html = "<u>$html</u>"
                    if (run.isStrikeThrough) html = "<s>$html</s>"
                    body.append(html)
                    require(body.length <= 1024 * 1024) { "DOC 单个段落过大" }
                }
                val style = runCatching { document.styleSheet.getStyleDescription(paragraph.styleIndex.toInt()).name }.getOrDefault("")
                val title = style.matches(Regex("(?:heading|标题|標題)\\s*[1-9]", RegexOption.IGNORE_CASE))
                val tag = if (title) "h2" else "p"
                return "<$tag>$body</$tag>" to title
            }
            val range = document.range
            var index = 0
            while (index < range.numParagraphs()) {
                val current = range.getParagraph(index)
                if (current.isInTable) {
                    val table = range.getTable(current)
                    val markup = StringBuilder("<table>")
                    repeat(table.numRows()) { r ->
                        markup.append("<tr>")
                        val row = table.getRow(r)
                        repeat(row.numCells()) { c ->
                            val cell = row.getCell(c)
                            markup.append("<td>")
                            repeat(cell.numParagraphs()) { p -> markup.append(paragraph(cell.getParagraph(p)).first) }
                            markup.append("</td>")
                        }
                        markup.append("</tr>")
                        require(markup.length <= 1024 * 1024) { "DOC 单张表格过大" }
                    }
                    markup.append("</table>"); writer.markup(markup.toString())
                    while (index < range.numParagraphs() && range.getParagraph(index).startOffset < table.endOffset) index++
                } else {
                    val (markup, title) = paragraph(current)
                    if (title) writer.heading(Jsoup.parseBodyFragment(markup).text())
                    writer.markup(markup)
                    index++
                }
            }
            for ((notes, noteRange, prefix) in listOf(Triple(document.footnotes, document.footnoteRange, "fn"), Triple(document.endnotes, document.endnoteRange, "en"))) {
                require(notes.notesCount <= 10000) { "DOC 脚注过多" }
                repeat(notes.notesCount) { n ->
                    val start = noteRange.startOffset + notes.getNoteTextStartOffset(n)
                    val end = noteRange.startOffset + notes.getNoteTextEndOffset(n)
                    val text = Range(start, end, document).text().filter { it >= ' ' || it in "\n\r\t" }.replace("\r", "\n")
                    require(text.length <= 1024 * 1024) { "DOC 脚注过大" }
                    writer.markup("<aside id=\"$prefix-$n\" epub:type=\"footnote\"><p>${BookEpubWriter.escape(text).replace("\n", "<br/>")}</p></aside>")
                }
            }
        } }
        return warnings.toList()
    }
}
