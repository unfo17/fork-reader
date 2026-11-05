package io.github.unfo17.reader

/** Port of lib/listening/languages.dart; candidates belong to the selected engine only. */
internal object SpeechLanguages {
    fun code(value: String): String {
        val tag = value.trim().replace('_', '-')
        if (!tag.matches(Regex("[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,4}"))) return ""
        return when (val primary = tag.substringBefore('-').lowercase(java.util.Locale.ROOT)) {
            "chi", "zho" -> "zh"; "jpn" -> "ja"; "eng" -> "en"; "kor" -> "ko"; else -> primary
        }
    }
    fun paragraph(tag: String, text: String, fallback: String): String {
        val explicit = code(tag)
        if (explicit.isNotEmpty() && explicit != "und") return explicit
        val kana = text.count { it in '\u3041'..'\u3096' || it in '\u30a1'..'\u30fa' || it in '\uff66'..'\uff9f' }
        if (kana > 0) {
            val han = text.count { it in '\u3400'..'\u4dbf' || it in '\u4e00'..'\u9fff' || it in '\uf900'..'\ufaff' }
            if (han < 32 || kana * 10 >= han) return "ja"
        }
        if (text.any { it in '\uac00'..'\ud7a3' || it in '\u1100'..'\u11ff' }) return "ko"
        return code(fallback)
    }
    fun voice(language: String, preferred: String, voices: List<SystemVoice>, selected: String? = null): SystemVoice? {
        val code = code(language)
        if (code.isEmpty()) return null
        val matches = voices.filter { it.installed && it.id != "@default" && code(it.locale) == code }
        if (selected != null) return matches.firstOrNull { it.id == selected }
        return matches.sortedWith(compareByDescending<SystemVoice> { it.id == preferred }.thenBy { it.networkRequired }.thenByDescending { it.quality }.thenBy { it.id }).firstOrNull()
    }
}
