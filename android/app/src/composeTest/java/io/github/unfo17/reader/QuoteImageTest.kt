package io.github.unfo17.reader

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mewx.wenku8_flutter.BookImageStore
import org.mewx.wenku8_flutter.QuoteCardImage
import org.mewx.wenku8_flutter.commitGalleryImage

@RunWith(AndroidJUnit4::class)
class QuoteImageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val book = Book("a".repeat(64), "摘录图片验收", "作者", "unused.epub")
    private fun mark(quote: String) = ReadingMark.from(JSONObject().put("id", "image-test").put("locator", JSONObject().put("href", "chapter.xhtml").put("type", "application/xhtml+xml").put("title", "第一章"))
        .put("quote", quote).put("style", "NONE").put("color", "INK").put("note", "").put("category", "")
        .put("createdAt", 1770000000000L).put("createdOffsetMinutes", 480).put("updatedAt", 1770000000000L).put("categoryAt", 1770000000000L).put("rangeUpdatedAt", 1770000000000L).put("stateAt", 1770000000000L).put("deleted", false))
    @Test fun longExcerptsPaginateWithoutDroppingTextOrSplittingSurrogatePairs() {
        val quote = (1..100).joinToString("\n") { "第 $it 段：摘录保留每一个字符 😀 café العربية，日本語と中文。" }
        val cards = QuoteImages(context, book, mark(quote), QuoteTheme.EDITORIAL)
        assertTrue(cards.pages.size > 1)
        assertEquals(quote, cards.pages.joinToString("") { it.text })
        cards.pages.forEach { assertFalse(Character.isHighSurrogate(it.text.last())); assertFalse(Character.isLowSurrogate(it.text.first())) }
        assertTrue(QuoteCardImage.valid(cards.png(cards.pages.lastIndex)))
    }
    @Test fun allEightUpstreamThemesRenderBoundedOriginalArtwork() {
        QuoteTheme.entries.forEach { theme ->
            val cards = QuoteImages(context, book, mark("预览与导出使用相同排版。\n文字和来源完整显示。"), theme)
            val bytes = cards.png(0)
            assertTrue("${theme.name} should use a valid bounded PNG", QuoteCardImage.valid(bytes))
            val image = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { assertEquals(960, image.width); assertTrue(image.height <= 8192); assertEquals(theme.background, image.getPixel(0, image.height - 1)) } finally { image.recycle() }
        }
    }
    @Test fun galleryPublishesTheExactPngAndFailedPublicationRollsBack() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val name = "摘录测试-${System.nanoTime()}"
        val bytes = QuoteImages(context, book, mark("相册中保留完整 PNG 原图。"), QuoteTheme.ARCHIVE).png(0)
        BookImageStore.save(context, BookImageStore.renderedImage(bytes, name))
        val resolver = context.contentResolver
        val uri = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.IS_PENDING), "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf("$name.png"), null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(bytes.size.toLong(), cursor.getLong(1)); assertEquals(0, cursor.getInt(2))
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
        }
        try { assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() }) } finally { resolver.delete(uri, null, null) }
        var rolledBack = false
        assertTrue(runCatching { commitGalleryImage(create = { "temporary" }, write = { }, publish = { error("publication failed") }, rollback = { rolledBack = true }) }.isFailure)
        assertTrue(rolledBack)
    }
}
