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

    fun headingCandidate(line: String): String {
        val match = decorated.matchEntire(line)
        return match?.groupValues?.get(2)?.trim()?.takeUnless { it.startsWith('=') || it.endsWith('=') } ?: line
    }
    fun defaultKind(line: String): Int {
        if (line.isEmpty() || line.length > 90) return 0
        val candidate = headingCandidate(line)
        return if (volume.containsMatchIn(candidate)) 1 else if (chapter.containsMatchIn(candidate)) 2 else 0
    }
    fun isHeading(line: String): Boolean = defaultKind(line) != 0

    fun prepare(source: File, output: File, title: String, workspace: File, encoding: String = "auto", options: TextImportRules? = null, context: android.content.Context? = null) {
        prepare(BookDocumentInput(source), output, title, workspace, encoding, options, context)
    }

    fun prepare(source: BookDocumentInput, output: File, title: String, workspace: File, encoding: String = "auto", options: TextImportRules? = null, context: android.content.Context? = null) {
        BookImportWorkspace(workspace).use { session ->
            val normalized = File(session.root, "normalized.txt")
            BookTextDecoder.normalize(source, normalized, options?.encoding ?: encoding)
            val writer = BookEpubWriter(session.root, title).apply { identifier = "urn:local:${output.nameWithoutExtension}" }
            if (options != null && !options.autoTitles) writer.heading("正文")
            val rules = options?.takeIf { it.customToc }?.let { TextRuleClient(checkNotNull(context), it) }
            try { normalized.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val line = StringBuilder()
                var continuation = false
                val pending = mutableListOf<Pair<String, Boolean>>()
                fun writeBatch() {
                    if (pending.isEmpty()) return
                    val candidates = pending.filter { it.second && it.first.length <= 90 }.map { it.first }
                    val classifications = rules?.classify(candidates)?.iterator()
                    pending.forEach { (original, eligible) ->
                        val value = if (options != null && !options.customToc) {
                            val match = decorated.matchEntire(original)
                            match?.groupValues?.get(2)?.trim()?.takeUnless { it.startsWith('=') || it.endsWith('=') } ?: original
                        } else original
                        val kind = if (!eligible || original.length > 90) 0 else if (classifications != null) classifications.next() else if (volume.containsMatchIn(value)) 1 else if (chapter.containsMatchIn(value)) 2 else 0
                        if (options == null) { if (eligible && isHeading(original)) writer.heading(original) else writer.text(original) }
                        else when (kind) { 1 -> writer.volume(value); 2 -> writer.heading(value); else -> writer.text(original) }
                    }
                    pending.clear()
                }
                fun flushLine() {
                    val value = line.toString().trim()
                    if (value.isNotEmpty()) {
                        pending += value to !continuation
                        if (pending.size >= 128) writeBatch()
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
                flushLine(); writeBatch()
            } } finally { rules?.close() }
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
