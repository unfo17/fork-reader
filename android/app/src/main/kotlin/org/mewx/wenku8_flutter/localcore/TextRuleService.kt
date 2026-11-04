package org.mewx.wenku8_flutter

import android.app.Service
import android.content.Intent
import android.os.*
import java.util.regex.Pattern

/** One isolated UID, no document access. Only line matching is timed, never file I/O. */
class TextRuleService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var work: Handler
    private val main = Handler(Looper.getMainLooper())
    private var chapter: Pattern? = null
    private var volume: Pattern? = null
    override fun onCreate() { super.onCreate(); worker = HandlerThread("reader-toc-rules").apply { start() }; work = Handler(worker.looper) }
    override fun onBind(intent: Intent): IBinder = Messenger(Handler(Looper.getMainLooper()) { request ->
        if (request.what == STOP) { Process.killProcess(Process.myPid()); return@Handler true }
        val reply = request.replyTo ?: return@Handler true
        val code = request.what; val identity = request.arg1; val data = Bundle(request.data)
        val timeout = Runnable { Process.killProcess(Process.myPid()) }
        main.postDelayed(timeout, 2000)
        work.post {
            val result = Bundle()
            try {
                when (code) {
                    INITIALIZE -> {
                        val options = TextImportRules.decode(data.getString("options"))
                        chapter = Pattern.compile(if (options.customToc) options.chapterRule else TextImportRules.DEFAULT_CHAPTER, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
                        val rule = if (options.customToc) options.volumeRule else TextImportRules.DEFAULT_VOLUME
                        volume = rule.takeIf { it.isNotEmpty() }?.let { Pattern.compile(it, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }
                    }
                    CLASSIFY -> {
                        check(chapter != null) { "目录规则尚未准备好" }
                        val lines = data.getStringArray("lines") ?: error("目录行缺失")
                        require(lines.size <= 128 && lines.all { it.length <= 90 }) { "目录预览行超过限制" }
                        result.putIntArray("matches", lines.map { line ->
                            if (line.isEmpty()) 0 else if (volume?.matcher(line)?.find() == true) 1 else if (chapter!!.matcher(line).find()) 2 else 0
                        }.toIntArray())
                    }
                    else -> error("未知目录请求")
                }
            } catch (e: Exception) { result.putString("error", e.message?.take(300) ?: "目录表达式格式有误") }
            catch (_: StackOverflowError) { result.putString("error", "目录表达式过于复杂，请简化后重试") }
            finally { main.removeCallbacks(timeout) }
            runCatching { reply.send(Message.obtain(null, REPLY, identity, 0).apply { this.data = result }) }
        }
        true
    }).binder
    override fun onDestroy() { worker.quitSafely(); super.onDestroy() }
    companion object { const val INITIALIZE = 1; const val CLASSIFY = 2; const val STOP = 3; const val REPLY = 4 }
}
