package io.github.unfo17.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mewx.wenku8_flutter.SystemSpeechCore
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SpeechFailure(val code: String, message: String) : Exception(message)
data class SystemVoice(val id: String, val locale: String, val quality: Int, val latency: Int, val networkRequired: Boolean, val installed: Boolean)

/** Coroutine adapter for the original native engine/session, voice validation and cancellation. */
class SystemSpeechClient(private val context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val core = SystemSpeechCore(context.applicationContext)
    private var closed = false
    suspend fun request(method: String, arguments: Map<String, Any?> = emptyMap()): Any? = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "语音会话已关闭" }
        suspendCancellableCoroutine { continuation ->
            val requestId = arguments["requestId"] as? String
            continuation.invokeOnCancellation {
                if (requestId != null) main.post {
                    if (!closed) core.handle(SystemSpeechCore.Call("cancel", mapOf("requestId" to requestId)), IgnoredResult)
                }
            }
            core.handle(SystemSpeechCore.Call(method, arguments), object : SystemSpeechCore.Result {
                override fun success(value: Any?) { if (continuation.isActive) continuation.resume(value) }
                override fun error(code: String, message: String?, details: Any?) { if (continuation.isActive) continuation.resumeWithException(SpeechFailure(code, message ?: code)) }
                override fun notImplemented() { if (continuation.isActive) continuation.resumeWithException(SpeechFailure("TTS_METHOD", "不支持的语音操作")) }
            })
        }
    }
    suspend fun engines(): List<Pair<String, String>> = (withTimeoutOrNull(8000) { request("engines") } .let { it ?: throw SpeechFailure("TTS_TIMEOUT", "语音引擎检测超时，请重新检测") } as? List<*>)?.mapNotNull { row ->
        val map = row as? Map<*, *> ?: return@mapNotNull null
        (map["id"] as? String)?.let { it to (map["name"] as? String ?: it) }
    }.orEmpty()
    suspend fun voices(engine: String): List<SystemVoice> = (withTimeoutOrNull(25000) { request("voices", mapOf("engine" to engine)) }.let { it ?: throw SpeechFailure("TTS_TIMEOUT", "音色检测超时，请检查语音数据后重新检测") } as? List<*>)?.mapNotNull { row ->
        val map = row as? Map<*, *> ?: return@mapNotNull null
        val id = map["id"] as? String ?: return@mapNotNull null
        SystemVoice(id, map["locale"] as? String ?: "", (map["quality"] as? Number)?.toInt() ?: 0,
            (map["latency"] as? Number)?.toInt() ?: 0, map["networkRequired"] == true,
            !(map["features"] as? List<*>).orEmpty().contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
    }.orEmpty()
    suspend fun synthesize(text: String, engine: String, voice: String, locale: String?): File {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val directory = SpeechTemporaryFiles.directory(context)
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(requestId.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$name.wav.part")
        try {
            request("synthesize", mapOf("requestId" to requestId, "text" to text, "engine" to engine, "voice" to voice, "locale" to locale, "path" to file.path))
            check(file.isFile && file.length() >= 45) { "语音没有生成有效音频" }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true; core.close()
    }
    private object IgnoredResult : SystemSpeechCore.Result {
        override fun success(value: Any?) = Unit
        override fun error(code: String, message: String?, details: Any?) = Unit
        override fun notImplemented() = Unit
    }
}
