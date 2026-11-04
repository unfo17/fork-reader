package io.github.unfo17.reader

import java.util.Locale

/** Suggestions from the upstream title rules; merging still requires confirmation. */
data class SeriesHint(val title: String, val number: Double) {
    val key get() = title.lowercase(Locale.ROOT).replace(Regex("[\\s._\\-—·・:：~～《》「」『』【】\\[\\]（）()]+"), "")
    val volumeTitle get() = "第 ${if (number == number.toInt().toDouble()) number.toInt().toString() else number.toString()} 卷"
    companion object {
        private val patterns = listOf(
            Regex("^(.*?)\\s*第\\s*([零〇一二三四五六七八九十百千两0-9]+)\\s*[卷集册冊部](?:\\s.*)?$"),
            Regex("^(.*?)\\s+(?:vol\\.?|volume)\\s*([0-9]+(?:\\.[0-9]+)?)(?:\\s.*)?$", RegexOption.IGNORE_CASE),
            Regex("^(.*?)[\\s._\\-（(\\[]+([0-9]{1,3})[）)\\]]?$"))
        fun infer(title: String): SeriesHint? {
            val value = title.map { if (it.code in 0xff10..0xff19) (it.code - 0xfee0).toChar() else it }.joinToString("").replace(Regex("\\.(epub|txt)$", RegexOption.IGNORE_CASE), "").trim()
            patterns.forEach { pattern ->
                val match = pattern.matchEntire(value) ?: return@forEach
                val name = match.groupValues[1].replace(Regex("[\\s._\\-—·]+$"), "").trim()
                val number = volumeNumber(match.groupValues[2]) ?: return@forEach
                val hint = SeriesHint(name, number)
                if (hint.key.length >= 2) return hint
            }
            return null
        }
        private fun volumeNumber(value: String): Double? {
            value.toDoubleOrNull()?.let { return it.takeIf { number -> number > 0 && number < 10000 } }
            val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
            val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
            var result = 0; var digit = 0
            value.forEach { char -> if (char in digits) digit = digits.getValue(char) else if (char in units) { result += (if (digit == 0) 1 else digit) * units.getValue(char); digit = 0 } else return null }
            return (result + digit).toDouble().takeIf { it > 0 }
        }
    }
}
