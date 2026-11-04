package org.mewx.wenku8_flutter

import java.io.File

/** Adapts the existing decoder and EPUB writer to Readium.
 * Heading semantics mirror lib/local_import_options.dart and local_text_rules.dart.
 * The original TXT stays authoritative; the generated EPUB is a disposable reading resource.
 */
internal object LocalTextPublication {
    private val chapter = Regex("^(第[零〇一二三四五六七八九十百千万萬两兩0-9０-９]+[章节節回幕话話]|chapter\\s+\\d+|序章|序言|楔子|尾声|尾聲|后记|後記|番外)", RegexOption.IGNORE_CASE)
    private val volume = Regex("^(第[零〇一二三四五六七八九十百千万萬两兩0-9０-９]+[卷部]|volume\\s+\\d+)", RegexOption.IGNORE_CASE)
    private val decorated = Regex("^(={2,})\\s*(.*?)\\s*\\1$")

    fun isHeading(line: String): Boolean {
        if (line.isEmpty() || line.length > 90) return false
        val match = decorated.matchEntire(line)
        val candidate = match?.groupValues?.get(2)?.trim()?.takeUnless { it.startsWith('=') || it.endsWith('=') } ?: line
        return volume.containsMatchIn(candidate) || chapter.containsMatchIn(candidate)
    }

    fun prepare(source: File, output: File, title: String, workspace: File, encoding: String = "auto") {
        prepare(BookDocumentInput(source), output, title, workspace, encoding)
    }

    fun prepare(source: BookDocumentInput, output: File, title: String, workspace: File, encoding: String = "auto") {
        BookImportWorkspace(workspace).use { session ->
            val normalized = File(session.root, "normalized.txt")
            BookTextDecoder.normalize(source, normalized, encoding)
            val writer = BookEpubWriter(session.root, title)
            normalized.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val line = StringBuilder()
                var continuation = false
                fun flushLine() {
                    val value = line.toString().trim()
                    if (value.isNotEmpty()) {
                        if (!continuation && isHeading(value)) writer.heading(value) else writer.text(value)
                    }
                    line.setLength(0)
                }
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    for (i in 0 until count) {
                        val char = buffer[i]
                        if (char == '\n' || char == '\r') { flushLine(); continuation = false }
                        else {
                            line.append(char)
                            if (line.length >= 4000 && !char.isHighSurrogate()) { flushLine(); continuation = true }
                        }
                    }
                }
                flushLine()
            }
            val staged = File(session.root, "reading.epub")
            writer.write(staged)
            // Keep the previous valid cache until the conversion succeeds.
            output.parentFile?.mkdirs()
            val temporary = File(output.path + ".part")
            try {
                staged.copyTo(temporary, overwrite = true)
                check(temporary.renameTo(output)) { "无法保存阅读资源" }
            } finally { temporary.delete() }
        }
    }
}
