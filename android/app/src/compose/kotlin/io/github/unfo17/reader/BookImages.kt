package io.github.unfo17.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User cover images keep their original pixels on disk; UI decoding stays bounded. */
suspend fun loadCover(book: Book): Bitmap? = withContext(Dispatchers.IO) {
    val path = book.file + ".jpg"
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    var sample = 1
    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 768) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

val Book.readingState: String get() = if (status != "auto") status else when {
    progress >= 0.995 -> "finished"
    lastRead > 0 -> "reading"
    else -> "unread"
}
fun readingStateLabel(state: String): String = when (state) {
    "auto" -> "根据进度"
    "unread" -> "未读"
    "reading" -> "在读"
    "finished" -> "已读"
    "paused" -> "搁置"
    else -> "全部状态"
}
