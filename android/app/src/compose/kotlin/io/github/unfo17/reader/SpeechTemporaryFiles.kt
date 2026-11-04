package io.github.unfo17.reader

import android.content.Context
import java.io.File

/** Interrupted processes may leave a partial WAV; active clients in this process share ownership. */
internal object SpeechTemporaryFiles {
    private val initialized = mutableSetOf<String>()
    @Synchronized fun directory(context: Context): File {
        val root = File(context.cacheDir, "system-speech")
        check(root.isDirectory || root.mkdirs()) { "无法创建语音缓存，请检查可用空间" }
        if (root.absolutePath !in initialized) {
            root.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.wav\\.part")) }?.forEach { it.delete() }
            initialized += root.absolutePath
        }
        return root
    }
}
