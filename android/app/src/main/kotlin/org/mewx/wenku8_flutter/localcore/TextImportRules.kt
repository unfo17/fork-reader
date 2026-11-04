package org.mewx.wenku8_flutter

import org.json.JSONObject

/** Persisted per original, following lib/local_import_options.dart. */
data class TextImportRules(
    val customToc: Boolean = false,
    val chapterRule: String = DEFAULT_CHAPTER,
    val volumeRule: String = DEFAULT_VOLUME,
    val autoTitles: Boolean = true,
    val encoding: String = "auto"
) {
    fun json(): String = JSONObject().put("version", 1).put("customToc", customToc).put("chapterRule", chapterRule).put("volumeRule", volumeRule).put("autoTitles", autoTitles).put("encoding", encoding).toString()
    fun validate(): TextImportRules {
        require(chapterRule.length <= 1024 && volumeRule.length <= 1024 && (!customToc || chapterRule.isNotBlank())) { "请填写章节表达式，每条最多 1024 个字符" }
        require(encoding in ENCODINGS) { "不支持的文本编码" }; return this
    }
    companion object {
        const val DEFAULT_CHAPTER = "^(第[零〇一二三四五六七八九十百千万萬两兩0-9０-９]+[章节節回幕话話]|chapter\\s+\\d+|序章|序言|楔子|尾声|尾聲|后记|後記|番外)"
        const val DEFAULT_VOLUME = "^(第[零〇一二三四五六七八九十百千万萬两兩0-9０-９]+[卷部]|volume\\s+\\d+)"
        val ENCODINGS = listOf("auto", "UTF-8", "GB18030", "Big5", "Big5-HKSCS", "UTF-16LE", "UTF-16BE")
        fun decode(raw: String?): TextImportRules {
            if (raw == null) return TextImportRules()
            val j = JSONObject(raw); require(j.getInt("version") == 1) { "TXT 导入设置版本不支持" }
            return TextImportRules(j.getBoolean("customToc"), j.getString("chapterRule"), j.getString("volumeRule"), j.getBoolean("autoTitles"), j.optString("encoding", "auto")).validate()
        }
    }
}
