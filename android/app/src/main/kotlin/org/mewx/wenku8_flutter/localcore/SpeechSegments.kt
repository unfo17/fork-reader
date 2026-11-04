package org.mewx.wenku8_flutter

/** Native port of lib/listening/models.dart; offsets always refer to the original UTF-16 text. */
internal object SpeechSegments {
    data class Segment(val start: Int, val end: Int, val text: String)
    private val letters = Regex("[\\p{L}\\p{N}]")
    private val sentences = Regex("[。！？!?；;\\n]")
    private val clauses = Regex("[，,：:、\\s]")
    fun clean(text: String): String = buildString {
        var at = 0
        while (at < text.length) {
            val code = text.codePointAt(at)
            val count = Character.charCount(code)
            val xml = code == 9 || code == 10 || code == 13 || code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
            val invisible = code in 0x7F..0x9F || code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0x2060..0x2069 || code == 0xFEFF || code == 0xFFFD || code in 0xFDD0..0xFDEF || (code and 0xFFFF) >= 0xFFFE
            if (!xml || invisible) repeat(count) { append(' ') } else appendCodePoint(code)
            at += count
        }
    }
    fun split(text: String, maximum: Int = 240): Sequence<Segment> = sequence {
        require(maximum in 20..1000)
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "[插图]" || trimmed.startsWith("<!--image-->") && trimmed.endsWith("<!--image-->")) return@sequence
        var offset = 0
        while (offset < text.length) {
            while (offset < text.length && text[offset].isWhitespace()) offset++
            if (offset >= text.length) break
            var end = minOf(offset + maximum, text.length)
            if (end < text.length) {
                if (Character.isHighSurrogate(text[end - 1])) end--
                val window = text.substring(offset, end)
                val sentence = sentences.findAll(window).lastOrNull()
                if (sentence != null && sentence.range.last + 1 >= maximum / 3) end = offset + sentence.range.last + 1
                else {
                    val clause = clauses.findAll(window).lastOrNull { match ->
                        val at = offset + match.range.first
                        val comma = text[at] == ',' || text[at] == '，'
                        match.range.last + 1 >= maximum / 2 && (!comma || !(text.getOrNull(at - 1) in '0'..'9' && text.getOrNull(at + 1) in '0'..'9'))
                    }
                    if (clause != null) end = offset + clause.range.last + 1
                }
                while (end < text.length && end < offset + maximum + 4 && text[end] in "」』”’") end++
            }
            val fragment = clean(text.substring(offset, end))
            if (letters.containsMatchIn(fragment)) yield(Segment(offset, end, fragment))
            offset = end
        }
    }
}
