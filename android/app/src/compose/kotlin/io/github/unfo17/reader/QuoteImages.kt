package io.github.unfo17.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.mewx.wenku8_flutter.BookImageStore
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Same stable identities, palettes and current artwork as reading_visual_theme.dart. */
enum class QuoteTheme(val label: String, val background: Int, val ink: Int, val muted: Int) {
    ARCHIVE("阅读档案", 0xfff7f8f6.toInt(), 0xff18232c.toInt(), 0xff66727b.toInt()),
    SHELF("书架索引", 0xffeef4f0.toInt(), 0xff183a30.toInt(), 0xff5c6c63.toInt()),
    EDITORIAL("阅读年表", 0xfffaf6e9.toInt(), 0xff353126.toInt(), 0xff6f6854.toInt()),
    CONSTELLATION("星港夜航", 0xff101629.toInt(), 0xffe9edf7.toInt(), 0xffa5b0cd.toInt()),
    SIGNAL("行动档案", 0xfff0f1e8.toInt(), 0xff1c292a.toInt(), 0xff5f6f69.toInt()),
    SCORE("青春频率", 0xfffff4f3.toInt(), 0xff352f44.toInt(), 0xff786275.toInt()),
    INK("云间书信", 0xfff6f4e9.toInt(), 0xff233e3b.toInt(), 0xff607167.toInt()),
    FESTIVAL("晴空来信", 0xfff2f8fb.toInt(), 0xff1b3d54.toInt(), 0xff566f7d.toInt());
    val asset get() = "reading-art/${name.lowercase(java.util.Locale.ROOT)}-117.webp"
}

/** Preview and PNG share this exact layout; pagination preserves all source text. */
class QuoteImages(private val context: Context, val book: Book, val mark: ReadingMark, val theme: QuoteTheme, private val typeface: Typeface = if (theme == QuoteTheme.EDITORIAL || theme == QuoteTheme.INK) Typeface.SERIF else Typeface.DEFAULT) {
    data class Page(val text: String)
    private val bodyWidth = 384
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; this.typeface = if (bold) Typeface.create(typeface, Typeface.BOLD) else typeface }
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, bodyWidth).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.85f).setIncludePad(false).build()
    }
    val pages: List<Page> by lazy {
        val quote = mark.quote.trim()
        require(quote.isNotEmpty() && quote.length <= 1000000) { "此书签没有可导出的摘录文字" }
        val layout = text(quote, 22f, theme.ink)
        val result = mutableListOf<Page>(); var firstLine = 0
        while (firstLine < layout.lineCount) {
            var lastLine = firstLine
            while (lastLine + 1 < layout.lineCount && layout.getLineBottom(lastLine + 1) - layout.getLineTop(firstLine) <= 880) lastLine++
            result += Page(quote.substring(layout.getLineStart(firstLine), layout.getLineEnd(lastLine)))
            firstLine = lastLine + 1
        }
        result
    }
    fun render(index: Int, watermark: Boolean = true): Bitmap {
        val body = text(pages[index].text, 22f, theme.ink)
        val title = text("《${book.title}》", 15f, theme.ink, true)
        val chapter = org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(mark.locator))?.title.orEmpty()
        val source = text(listOf(book.author, chapter).filter { it.isNotBlank() }.joinToString(" · "), 11.5f, theme.muted)
        val date = Instant.ofEpochMilli(mark.createdAt).atOffset(ZoneOffset.ofTotalSeconds(mark.createdOffsetMinutes * 60)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX"))
        val footer = text(date + if (pages.size > 1) " · ${index + 1} / ${pages.size}" else "", 10f, theme.muted)
        val height = 360 + body.height + 92 + title.height + source.height + footer.height + 88
        require(height <= 4096) { "书名或来源过长，无法生成摘录图片" }
        val bitmap = Bitmap.createBitmap(960, height * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.scale(2f, 2f); canvas.drawColor(theme.background)
        val art = context.assets.open(theme.asset).use { BitmapFactory.decodeStream(it) } ?: error("摘录插画无法读取")
        try { canvas.drawBitmap(art, null, RectF(0f, 0f, 480f, 320f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) } finally { art.recycle() }
        fun draw(layout: StaticLayout, y: Float) { canvas.save(); canvas.translate(48f, y); layout.draw(canvas); canvas.restore() }
        draw(body, 360f)
        var y = 360f + body.height + 70f
        canvas.drawLine(48f, y - 20, 80f, y - 20, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.muted; strokeWidth = 1.2f })
        draw(title, y); y += title.height + 5
        draw(source, y); y += source.height + 12
        draw(footer, y)
        if (watermark) canvas.drawText("本地书房", 48f, height - 24f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.muted; textSize = 9f })
        return bitmap
    }
    fun png(index: Int, watermark: Boolean = true): ByteArray {
        val bitmap = render(index, watermark)
        return try { ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray().also { BookImageStore.renderedImage(it, "摘录") } } } finally { bitmap.recycle() }
    }
}
