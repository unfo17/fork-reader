package org.mewx.wenku8_flutter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** PDF pages retain their layout; alt text exposes extracted page text to Readium search/TTS. */
internal class BookPdfImport(private val context: Context, private val root: File) {
    fun read(name: String, source: BookDocumentInput, writer: BookEpubWriter): List<String> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val temporary = File(root, "pdf-temporary").apply { mkdirs() }
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(temporary)
        source.inputStream().use { input -> PDDocument.load(input, memory).use { document ->
            require(!document.isEncrypted && document.currentAccessPermission.canExtractContent()) { "PDF 文件受保护，无法转换" }
            require(document.numberOfPages in 1..2000) { "PDF 页数需要在 1 至 2000 页以内" }
            writer.title = document.documentInformation.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
            writer.author = document.documentInformation.author.orEmpty().take(2048)
            writer.summary = document.documentInformation.subject.orEmpty().take(4000)
            writer.tags = document.documentInformation.keywords.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
            document.documentCatalog.language?.takeIf { it.isNotBlank() }?.let { writer.language = it }
            val descriptor = source.file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) } ?: ParcelFileDescriptor.fromFd(source.descriptor!!)
            descriptor.use { fd -> PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount == document.numberOfPages) { "PDF 页面索引不一致，文件可能已变化" }
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                repeat(renderer.pageCount) { index ->
                    stripper.startPage = index + 1; stripper.endPage = index + 1
                    val text = stripper.getText(document)
                    require(text.length <= 1024 * 1024 / 6) { "PDF 单页文字过多" }
                    renderer.openPage(index).use { page ->
                        require(page.width > 0 && page.height > 0) { "PDF 页面尺寸无效" }
                        val scale = minOf(2.0, 1600.0 / maxOf(page.width, page.height))
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val image = File(root, "pdf-page-${java.util.UUID.randomUUID()}.jpg")
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, Matrix().apply { postScale(scale.toFloat(), scale.toFloat()) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "PDF 页面转换失败" } }
                            val key = "pdf-page-$index"
                            image.inputStream().use { writer.resource(key, "image/jpeg", it) }
                            if (index == 0) writer.cover = key
                            writer.heading("第 ${index + 1} 页")
                            writer.markup("<img src=\"${writer.resourceHref(key)}\" style=\"width:100%;height:auto\" alt=\"${BookEpubWriter.escape(text)}\"/>")
                        } finally { bitmap.recycle(); image.delete() }
                    }
                }
            } }
        } }
        return emptyList()
    }
}
