@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.publication.services.content.Content
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DocumentImportTest {
    @Test fun markdownHtmlAndFictionBookPreserveTextStylesImagesAndNotes() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val pixel = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val buffer = java.io.ByteArrayOutputStream()
        pixel.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, buffer); pixel.recycle()
        val encoded = android.util.Base64.encodeToString(buffer.toByteArray(), android.util.Base64.NO_WRAP)
        val samples = mapOf(
            "document.md" to "# 第一章 Markdown\n\n保留 **粗体** 和 *斜体*。\n\n![插图](data:image/png;base64,$encoded)\n\n## 第二章\n\n正文结尾。",
            "document.html" to """<html><head><title>HTML 测试</title><meta name="author" content="测试作者"/></head><body>开头正文<h1 id="first">第一章</h1><p>保留<strong>粗体</strong>与<ruby>汉<rt>han</rt></ruby>字。</p><img src="data:image/png;base64,$encoded"/><p><a href="#note" epub:type="noteref">注释</a></p><h2>第二章</h2><aside id="note" epub:type="footnote">脚注内容 &amp; 正文结尾。</aside></body></html>""",
            "document.fb2" to """<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><genre>prose</genre><author><first-name>测试</first-name><last-name>作者</last-name></author><book-title>FB2 测试</book-title><coverpage><image l:href="#cover"/></coverpage><lang>zh</lang></title-info></description><body><section id="chapter"><title><p>第一章</p></title><p>保留<strong>粗体</strong>与<emphasis>斜体</emphasis>。</p><image l:href="#cover"/><p><a type="note" l:href="#note">注释</a></p></section></body><body name="notes"><section id="note"><title><p>注释</p></title><p>脚注内容 &amp; 正文结尾。</p></section></body><binary id="cover" content-type="image/png">$encoded</binary></FictionBook>"""
        )
        for ((name, text) in samples) {
            val source = File(context.cacheDir, name).apply { writeText(text) }
            val book = library.import(Uri.fromFile(source), copy = false)
            try {
                assertFalse(File(book.file).exists())
                val publication = library.openPublication(book)
                try {
                    assertTrue(publication.readingOrder.isNotEmpty())
                    assertTrue(publication.tableOfContents.isNotEmpty())
                    assertTrue(publication.positions().isNotEmpty())
                    publication.readingOrder.forEach { link -> assertNotNull(publication.get(link)?.read()?.getOrNull()) }
                } finally { publication.close() }
                ZipFile(library.publicationFile(book)).use { zip ->
                    val content = zip.entries().asSequence().filter { it.name.matches(Regex("c[0-9]+\\.xhtml")) }.joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                    assertTrue("$name must preserve semantic bold text", content.contains("<strong>"))
                    assertTrue(content.contains("正文结尾"))
                    assertTrue(zip.entries().asSequence().any { it.name.startsWith("image-") })
                    if (name.endsWith(".html")) { assertTrue(content.contains("开头正文")); assertTrue(content.contains("<ruby>")); val target = Regex("href=\"(c[0-9]+\\.xhtml)#note\"").find(content)?.groupValues?.get(1); assertNotNull(target); assertTrue(zip.getInputStream(zip.getEntry(target)).bufferedReader().use { it.readText() }.contains("id=\"note\"")) }
                    if (name.endsWith(".fb2")) { assertTrue(content.contains("epub:type=\"footnote\"")); assertTrue(File(book.file + ".jpg").isFile) }
                }
            } finally { library.remove(book); source.delete() }
        }
    }

    @Test fun nativeMobiExporterParsesRealUncompressedHuffdicAndNcxSamples() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val library = Library.get(context)
        for (name in listOf("sample-unicode-uncompressed.mobi", "sample-unicode-huffdic.mobi", "sample-ncx.mobi", "sample-cp1252.mobi", "sample-textread.mobi", "sample-obfuscated-fonts.mobi")) {
            val source = File(context.cacheDir, name)
            instrumentation.context.assets.open("mobi/$name").use { input -> source.outputStream().use { input.copyTo(it) } }
            val book = library.import(Uri.fromFile(source))
            try {
                val pub = library.openPublication(book)
                try {
                    assertTrue(pub.metadata.title?.isNotBlank() == true)
                    assertTrue(pub.readingOrder.isNotEmpty())
                    assertTrue(pub.positions().isNotEmpty())
                    pub.readingOrder.forEach { link -> assertTrue((pub.get(link)?.read()?.getOrNull()?.size ?: 0) > 0) }
                } finally { pub.close() }
            } finally { library.remove(book); source.delete() }
        }
    }

    @Test fun encryptedMobiIsRejectedWithoutChangingLibrary() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val library = Library.get(context)
        val before = library.records().map { it.json().toString() }
        val source = File(context.cacheDir, "sample-drm-v1.mobi")
        instrumentation.context.assets.open("mobi/sample-drm-v1.mobi").use { input -> source.outputStream().use { input.copyTo(it) } }
        try {
            val result = runCatching { library.import(Uri.fromFile(source)) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("DRM"))
            assertEquals(before, library.records().map { it.json().toString() })
        } finally { source.delete() }
    }
    @Test fun docxMetadataTablesBookmarksPicturesAndFootnotesBecomeReadableEpub() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "structured-document.docx")
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val image = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, image); bitmap.recycle()
        java.util.zip.ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(name: String, value: String) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
            entry("docProps/core.xml", """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>DOCX 原创测试</dc:title><dc:creator>测试作者</dc:creator><dc:description>简介测试</dc:description></cp:coreProperties>""")
            entry("word/_rels/document.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="picture" Target="media/image.png" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"/></Relationships>""")
            entry("word/document.xml", """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第一章</w:t></w:r></w:p><w:p><w:bookmarkStart w:name="position"/><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t>保留粗斜体</w:t></w:r><w:r><w:footnoteReference w:id="1"/></w:r><w:r><w:drawing><a:blip r:embed="picture"/></w:drawing></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格甲</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>表格乙</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第二章</w:t></w:r></w:p><w:p><w:hyperlink w:anchor="position"><w:r><w:t>返回正文</w:t></w:r></w:hyperlink></w:p></w:body></w:document>""")
            entry("word/footnotes.xml", """<w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:footnote w:id="1"><w:p><w:r><w:t>脚注内容。</w:t></w:r></w:p></w:footnote></w:footnotes>""")
            zip.putNextEntry(java.util.zip.ZipEntry("word/media/image.png")); zip.write(image.toByteArray()); zip.closeEntry()
        }
        val book = library.import(Uri.fromFile(source), copy = false)
        try {
            assertEquals("DOCX 原创测试", book.title)
            assertEquals("测试作者", book.author)
            assertEquals("简介测试", book.summary)
            val pub = library.openPublication(book)
            try { assertEquals(2, pub.tableOfContents.size); assertTrue(pub.positions().isNotEmpty()) } finally { pub.close() }
            ZipFile(library.publicationFile(book)).use { zip ->
                val html = zip.entries().asSequence().filter { it.name.matches(Regex("c[0-9]+\\.xhtml")) }.joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                assertTrue(html.contains("<strong>")); assertTrue(html.contains("<em>")); assertTrue(html.contains("<table>"))
                assertTrue(html.contains("表格甲")); assertTrue(html.contains("表格乙")); assertTrue(html.contains("epub:type=\"noteref\""))
                assertTrue(html.contains("id=\"fn-1\"")); assertTrue(html.contains("脚注内容")); assertTrue(html.contains("c0.xhtml#bookmark-position"))
                assertTrue(zip.entries().asSequence().any { it.name.startsWith("image-") })
            }
        } finally { library.remove(book); source.delete() }
    }

    @Test fun rtfUnicodeFallbackAnsiStylesAndPicturesArePreserved() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "rtf-document.rtf")
        fun unicode(value: String): String = value.map { char -> if (char.code < 128) char.toString() else "\\u${char.code.toShort()}?" }.joinToString("")
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle()
        val hex = bytes.toByteArray().joinToString("") { "%02x".format(it) }
        source.writeText("{\\rtf1\\ansi\\ansicpg1252\\uc1{\\info{\\title RTF Test}{\\author Test Author}}" +
            unicode("中文与😀") + " caf\\'e9 \\b " + unicode("粗体") + "\\b0 \\i italic\\i0{\\*\\ignored hidden-destination}\\par{\\pict\\pngblip $hex}\\par End}")
        val book = library.import(Uri.fromFile(source))
        try {
            assertEquals("RTF Test", book.title)
            assertEquals("Test Author", book.author)
            val pub = library.openPublication(book)
            try { assertTrue(pub.positions().isNotEmpty()) } finally { pub.close() }
            ZipFile(library.publicationFile(book)).use { zip ->
                val html = zip.entries().asSequence().filter { it.name.matches(Regex("c[0-9]+\\.xhtml")) }.joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                assertTrue(html.contains("中文与😀")); assertTrue(html.contains("café")); assertTrue(html.contains("<strong>粗体</strong>")); assertTrue(html.contains("<em>italic</em>"))
                assertFalse(html.contains("hidden-destination"))
                assertTrue(zip.entries().asSequence().any { it.name.startsWith("image-") })
            }
        } finally { library.remove(book); source.delete() }
    }

    @Test fun legacyDocSamplesPreserveTextTablesPicturesAndNotes() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val library = Library.get(context)
        for (name in listOf("simple.doc", "simple-table.doc", "footnote.doc", "Picture_Alternative_Text.doc")) {
            val source = File(context.cacheDir, name)
            instrumentation.context.assets.open("doc/$name").use { input -> source.outputStream().use { input.copyTo(it) } }
            val book = library.import(Uri.fromFile(source))
            try {
                val pub = library.openPublication(book)
                try { assertTrue(pub.readingOrder.isNotEmpty()); assertTrue(pub.positions().isNotEmpty()) } finally { pub.close() }
                ZipFile(library.publicationFile(book)).use { zip ->
                    val html = zip.entries().asSequence().filter { it.name.matches(Regex("c[0-9]+\\.xhtml")) }.joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                    assertTrue(html.length > 100)
                    if (name == "simple-table.doc") assertTrue(html.contains("<table>"))
                    if (name == "footnote.doc") assertTrue(html.contains("epub:type=\"footnote\""))
                    if (name == "Picture_Alternative_Text.doc") assertTrue(zip.entries().asSequence().any { it.name.startsWith("image-") })
                }
            } finally { library.remove(book); source.delete() }
        }
    }

    @Test fun pdfLayoutTextCoverAndPageContentsAreAvailableOffline() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "fixture.pdf")
        instrumentation.context.assets.open("fixture.pdf").use { input -> source.outputStream().use { input.copyTo(it) } }
        val book = library.import(Uri.fromFile(source), copy = false)
        try {
            assertEquals("Original PDF Fixture", book.title)
            assertEquals("Test Author", book.author)
            assertTrue(File(book.file + ".jpg").exists())
            val pub = library.openPublication(book)
            try {
                assertEquals(2, pub.readingOrder.size)
                assertEquals(2, pub.tableOfContents.size)
                assertTrue(pub.positions().isNotEmpty())
                val iterator = pub.content()?.iterator()
                val accessible = StringBuilder()
                while (true) { val element = iterator?.nextOrNull() ?: break; if (element is Content.TextualElement) accessible.append(element.text) }
                assertTrue("PDF extracted text must be exposed to the actual Readium search/TTS service", accessible.contains("Original PDF test page 1"))
            } finally { pub.close() }
            ZipFile(library.publicationFile(book)).use { zip ->
                val html = zip.entries().asSequence().filter { it.name.matches(Regex("c[0-9]+\\.xhtml")) }.joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                assertTrue(html.contains("Original PDF test page 1")); assertTrue(html.contains("Original PDF test page 2"))
                assertEquals(2, zip.entries().asSequence().count { it.name.startsWith("image-") })
            }
            assertTrue(source.exists())
        } finally { library.remove(book); source.delete() }
    }

}
