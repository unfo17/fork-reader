package org.mewx.wenku8_flutter

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** RTF group-state reader: ANSI codepages, signed Unicode controls, styles and embedded pictures. */
internal object RtfDocumentImport {
    private data class State(
        var skip: Boolean = false, var unicodeFallback: Int = 1, var codepage: String = "windows-1252",
        var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var strike: Boolean = false,
        var align: String = "left", var destination: String = "", var metadata: StringBuilder? = null,
        var picture: Picture? = null, var pictureRoot: Boolean = false
    )
    private class Picture { var type = ""; val hex = StringBuilder(); val binary = ByteArrayOutputStream() }
    fun read(source: BookDocumentInput, writer: BookEpubWriter): List<String> {
        require(source.length() in 1..64L * 1024 * 1024) { "RTF 文档需要在 64 MiB 以内" }
        val bytes = source.inputStream().use { it.readBytes() }
        require(bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "{\\rtf") { "不是有效的 RTF 文档" }
        var state = State()
        val groups = mutableListOf<State>()
        val raw = ByteArrayOutputStream()
        val text = StringBuilder()
        val paragraph = StringBuilder()
        val warnings = linkedSetOf<String>()
        var fallback = 0
        var pictureCount = 0
        fun decodeRaw() {
            if (raw.size() > 0) { text.append(String(raw.toByteArray(), Charset.forName(state.codepage))); raw.reset() }
        }
        fun flushRun() {
            decodeRaw()
            if (text.isEmpty()) return
            if (state.metadata != null) state.metadata!!.append(text)
            else if (!state.skip) {
                var html = BookEpubWriter.escape(text.toString())
                if (state.bold) html = "<strong>$html</strong>"
                if (state.italic) html = "<em>$html</em>"
                if (state.underline) html = "<u>$html</u>"
                if (state.strike) html = "<s>$html</s>"
                paragraph.append(html)
            }
            text.setLength(0)
        }
        fun flushParagraph() {
            flushRun()
            if (paragraph.isNotBlank()) writer.markup("<p style=\"text-align:${state.align}\">$paragraph</p>")
            paragraph.setLength(0)
        }
        fun appendByte(value: Int) {
            if (fallback > 0) fallback--
            else if (!state.skip || state.metadata != null) raw.write(value)
        }
        fun appendCharacter(value: Char) {
            if (fallback > 0) fallback--
            else if (!state.skip || state.metadata != null) { decodeRaw(); text.append(value) }
        }
        val destinations = setOf("fonttbl", "colortbl", "stylesheet", "info", "generator", "listtable", "listoverridetable", "revtbl", "rsidtbl", "xmlnstbl", "datastore", "themedata", "colorschememapping", "fldinst", "header", "footer", "headerl", "headerr", "footerl", "footerr", "nonshppict")
        var at = 0
        while (at < bytes.size) {
            val byte = bytes[at].toInt() and 255
            when (byte) {
                123 -> {
                    flushRun(); require(groups.size < 256) { "RTF 分组嵌套过深" }
                    groups += state
                    state = state.copy(pictureRoot = false)
                    at++
                }
                125 -> {
                    flushRun()
                    if (state.pictureRoot) {
                        val picture = checkNotNull(state.picture)
                        val image = if (picture.binary.size() > 0) picture.binary.toByteArray() else {
                            require(picture.hex.length % 2 == 0) { "RTF 图片数据不完整" }
                            ByteArray(picture.hex.length / 2) { i -> picture.hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                        }
                        if (picture.type.isNotBlank()) {
                            val key = "rtf-image-${pictureCount++}"
                            ByteArrayInputStream(image).use { writer.resource(key, picture.type, it) }
                            paragraph.append("<img src=\"${writer.resourceHref(key)}\"/>")
                        } else warnings += "RTF 含暂不支持的矢量插图，已保留正文"
                    }
                    if (state.destination.isNotEmpty() && state.metadata !== groups.lastOrNull()?.metadata) {
                        val value = state.metadata?.toString().orEmpty().trim()
                        when (state.destination) { "title" -> if (value.isNotBlank()) writer.title = value.take(2048); "author" -> writer.author = value.take(2048); "subject" -> writer.summary = value.take(4000); "keywords" -> writer.tags = value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() } }
                    }
                    require(groups.isNotEmpty()) { "RTF 分组不完整" }
                    state = groups.removeAt(groups.lastIndex)
                    at++
                }
                92 -> {
                    at++; require(at < bytes.size) { "RTF 控制符不完整" }
                    val next = bytes[at].toInt() and 255
                    when {
                        next == 39 -> {
                            require(at + 2 < bytes.size) { "RTF 字符编码不完整" }
                            val value = String(bytes, at + 1, 2, Charsets.US_ASCII).toIntOrNull(16) ?: error("RTF 十六进制字符无效")
                            appendByte(value); at += 3
                        }
                        next in listOf(92, 123, 125) -> { appendByte(next); at++ }
                        next == 42 -> { flushRun(); state.skip = true; at++ }
                        next == 126 -> { appendCharacter('\u00a0'); at++ }
                        next == 95 -> { appendCharacter('\u2011'); at++ }
                        next == 45 -> { appendCharacter('\u00ad'); at++ }
                        next in 65..90 || next in 97..122 -> {
                            val start = at
                            while (at < bytes.size && (bytes[at].toInt() in 65..90 || bytes[at].toInt() in 97..122)) at++
                            val word = String(bytes, start, at - start, Charsets.US_ASCII)
                            val numberStart = at
                            if (at < bytes.size && bytes[at].toInt() == 45) at++
                            while (at < bytes.size && bytes[at].toInt() in 48..57) at++
                            val number = String(bytes, numberStart, at - numberStart, Charsets.US_ASCII).toIntOrNull()
                            if (at < bytes.size && bytes[at].toInt() == 32) at++
                            if (word in destinations) { flushRun(); state.skip = true }
                            else when (word) {
                                "title", "author", "subject", "keywords" -> { flushRun(); state.destination = word; state.metadata = StringBuilder() }
                                "pict" -> { flushRun(); state.picture = Picture(); state.pictureRoot = true; state.skip = true }
                                "pngblip" -> state.picture?.type = "image/png"
                                "jpegblip" -> state.picture?.type = "image/jpeg"
                                "bin" -> {
                                    val count = number ?: error("RTF 二进制长度无效")
                                    require(count in 0..8 * 1024 * 1024 && at.toLong() + count <= bytes.size) { "RTF 二进制数据过大或不完整" }
                                    state.picture?.binary?.write(bytes, at, count)
                                    at += count
                                }
                                "uc" -> { require(number != null && number in 0..16) { "RTF Unicode 回退长度无效" }; state.unicodeFallback = number }
                                "u" -> { decodeRaw(); if (!state.skip || state.metadata != null) text.append(((number ?: 0) and 0xffff).toChar()); fallback = state.unicodeFallback }
                                "ansicpg" -> {
                                    flushRun()
                                    val name = when (number) { 65001 -> "UTF-8"; 936 -> "GB18030"; 950 -> "Big5-HKSCS"; 932 -> "Shift_JIS"; 949 -> "EUC-KR"; else -> "windows-${number ?: 1252}" }
                                    require(Charset.isSupported(name)) { "RTF 文本编码不受支持" }; state.codepage = name
                                }
                                "b", "i", "ul", "ulnone", "strike" -> {
                                    flushRun(); val enabled = number != 0
                                    when (word) { "b" -> state.bold = enabled; "i" -> state.italic = enabled; "ul" -> state.underline = enabled; "ulnone" -> state.underline = false; "strike" -> state.strike = enabled }
                                }
                                "plain" -> { flushRun(); state.bold = false; state.italic = false; state.underline = false; state.strike = false }
                                "pard" -> { flushParagraph(); state.align = "left" }
                                "ql", "qr", "qc", "qj" -> { flushRun(); state.align = when (word) { "qc" -> "center"; "qr" -> "right"; "qj" -> "justify"; else -> "left" } }
                                "par", "page", "row" -> if (!state.skip && state.metadata == null) flushParagraph()
                                "line" -> if (!state.skip && state.metadata == null) { flushRun(); paragraph.append("<br/>") }
                                "tab", "cell" -> if (!state.skip && state.metadata == null) { flushRun(); paragraph.append("<span style=\"white-space:pre\">&#9;</span>") }
                                "emdash" -> appendCharacter('\u2014')
                                "endash" -> appendCharacter('\u2013')
                                "lquote" -> appendCharacter('\u2018')
                                "rquote" -> appendCharacter('\u2019')
                                "ldblquote" -> appendCharacter('\u201c')
                                "rdblquote" -> appendCharacter('\u201d')
                                "bullet" -> appendCharacter('\u2022')
                            }
                        }
                        else -> at++
                    }
                }
                else -> {
                    val picture = state.picture
                    if (picture != null) {
                        if (byte.toChar().digitToIntOrNull(16) != null) picture.hex.append(byte.toChar())
                        require(picture.hex.length <= 16 * 1024 * 1024 && picture.binary.size() <= 8 * 1024 * 1024) { "RTF 插图过大" }
                    } else if (byte !in listOf(10, 13)) appendByte(byte)
                    at++
                }
            }
            if (paragraph.length + text.length + raw.size() > 128 * 1024 && !state.skip && state.metadata == null) flushParagraph()
        }
        require(groups.isEmpty()) { "RTF 文档分组不完整" }
        flushParagraph()
        return warnings.toList()
    }
}
