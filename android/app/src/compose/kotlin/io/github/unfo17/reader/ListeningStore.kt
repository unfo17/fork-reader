@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.content.Context
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.content.Content
import java.io.File
import java.security.MessageDigest

/** Native counterpart of lib/listening/store.dart: listening never overwrites reading anchors. */
data class ListeningCursor(
    val bookId: String, val seriesId: String?, val title: String, val href: String,
    val paragraphKey: String, val start: Int, val speechHash: String, val audioHash: String,
    val positionMillis: Long, val locator: String, val updatedAt: Long, val completed: Boolean = false,
) {
    val ownerId get() = seriesId ?: bookId
    fun json(): JSONObject = JSONObject().put("version", 1).put("bookId", bookId).put("seriesId", seriesId).put("title", title).put("href", href)
        .put("paragraphKey", paragraphKey).put("start", start).put("speechHash", speechHash).put("audioHash", audioHash)
        .put("positionMillis", positionMillis).put("locator", JSONObject(locator)).put("updatedAt", updatedAt).put("completed", completed)
    companion object {
        fun from(json: JSONObject): ListeningCursor {
            require(json.getInt("version") == 1)
            val cursor = ListeningCursor(json.getString("bookId"), json.optString("seriesId").takeIf { it.isNotBlank() && it != "null" }, json.getString("title"), json.getString("href"),
                json.getString("paragraphKey"), json.getInt("start"), json.getString("speechHash"), json.getString("audioHash"), json.getLong("positionMillis"),
                json.getJSONObject("locator").toString(), json.getLong("updatedAt"), json.optBoolean("completed"))
            require(cursor.bookId.length in 1..128 && (cursor.seriesId == null || cursor.seriesId.length in 1..128) && cursor.title.length <= 512 && cursor.href.length in 1..8192 &&
                cursor.start >= 0 && cursor.positionMillis in 0..86400000L && cursor.updatedAt in 0..1000000000000000L && cursor.locator.length <= 16384 &&
                Locator.fromJSON(JSONObject(cursor.locator)) != null && listOf(cursor.paragraphKey, cursor.speechHash, cursor.audioHash).all { it.matches(Regex("[a-f0-9]{64}")) }) { "续听记录不正确" }
            return cursor
        }
        fun paragraphKey(element: Content.TextualElement): String = fingerprint(listOf("readium-content-v1", ChapterSearchWindow.resource(element.locator.href.toString()),
            element.locator.toJSON().optJSONObject("locations")?.optString("cssSelector").orEmpty(), element.locator.locations.progression.toString(), element.text.orEmpty()))
        fun speechHash(settings: SystemSpeechSettings, text: String) = fingerprint(listOf("system-speech-v1", settings.engine, settings.voice, settings.locale, text))
        private fun fingerprint(parts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { digest.update(it.toByteArray()); digest.update(0.toByte()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun audioHash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class ListeningStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun cursor(ownerId: String): ListeningCursor? = runCatching {
        val raw = prefs.getString(PREFIX + ownerId, null) ?: return null
        require(raw.length <= 32768)
        ListeningCursor.from(JSONObject(raw)).takeIf { it.ownerId == ownerId }
    }.getOrNull()
    fun cursors(): List<ListeningCursor> = prefs.all.keys.filter { it.startsWith(PREFIX) }.mapNotNull { cursor(it.removePrefix(PREFIX)) }.sortedByDescending { it.updatedAt }
    fun save(cursor: ListeningCursor) {
        val json = cursor.json(); ListeningCursor.from(json)
        val raw = json.toString(); require(raw.length <= 32768) { "续听记录超过限制" }
        check(prefs.edit().putString(PREFIX + cursor.ownerId, raw).commit()) { "续听位置保存失败" }
    }
    fun remove(ownerId: String) { check(prefs.edit().remove(PREFIX + ownerId).commit()) { "续听记录删除失败" } }
    fun observe(listener: () -> Unit): AutoCloseable {
        val changed = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key?.startsWith(PREFIX) == true) listener() }
        prefs.registerOnSharedPreferenceChangeListener(changed)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(changed) }
    }
    companion object { const val PREFERENCES = "listening-progress"; const val PREFIX = "cursor.v1." }
}
