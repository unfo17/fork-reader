package io.github.unfo17.reader

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mewx.wenku8_flutter.BookImageStore
import org.mewx.wenku8_flutter.QuoteCardImage
import java.io.File
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable fun QuotePreview(book: Book, mark: ReadingMark, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("quote-images", 0) }
    var theme by remember { mutableStateOf(QuoteTheme.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: QuoteTheme.EDITORIAL) }
    var watermark by remember { mutableStateOf(preferences.getBoolean("watermark", true)) }
    var page by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var staged by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val renderer by produceState<QuoteImages?>(null, theme) {
        value = null
        try { value = withContext(Dispatchers.IO) {
            val fontId = ReaderOptions.decode(book.readerOptions).fontId
            val font = FontStore(context).fonts().firstOrNull { it.id == fontId }?.let { runCatching { android.graphics.Typeface.createFromFile(it.file) }.getOrNull() }
            if (font == null) QuoteImages(context, book, mark, theme) else QuoteImages(context, book, mark, theme, font)
        }.also { it.pages } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "摘录排版失败" }
    }
    val count = remember(renderer) { runCatching { renderer?.pages?.size ?: 0 }.getOrElse { message = it.message; 0 } }
    val png by produceState<ByteArray?>(null, renderer, page, watermark) {
        value = null
        try { if (renderer != null && count > 0) value = withContext(Dispatchers.Default) { renderer!!.png(page.coerceIn(0, count - 1), watermark) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "摘录图片生成失败" }
    }
    val image = remember(png) { png?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    DisposableEffect(image) { onDispose { image?.recycle() } }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val file = staged; staged = null
        if (uri == null) file?.delete() else scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { checkNotNull(file); context.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("无法写入图片") }; message = "图片已保存" }
            catch (e: Exception) { message = e.message ?: "保存失败" }
            finally { file?.delete(); busy = false }
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = staged; staged = null
        if (uri == null) file?.delete() else scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { checkNotNull(file); context.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("无法写入摘录压缩包") }; message = "全部摘录图片已导出" }
            catch (e: Exception) { message = e.message ?: "导出失败" }
            finally { file?.delete(); busy = false }
        }
    }
    fun stage(bytes: ByteArray): File {
        val root = File(context.cacheDir, "quote-share").apply { mkdirs() }
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        return File(root, "${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.96f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text("摘录图片", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } }
                ChoiceMenu("主题", theme, QuoteTheme.entries.map { it to it.label }, enabled = !busy) { theme = it; page = 0; preferences.edit().putString("theme", it.name).apply() }
                Row { Checkbox(watermark, { watermark = it; preferences.edit().putBoolean("watermark", it).apply() }, enabled = !busy); Text("显示署名", Modifier.padding(top = 12.dp)) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (image != null) Image(image.asImageBitmap(), "第 ${page + 1} 张摘录预览", Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) else CircularProgressIndicator()
                }
                if (count > 1) Row {
                    TextButton(enabled = !busy && page > 0, onClick = { page-- }) { Text("上一张") }
                    Text("${page + 1} / $count", Modifier.padding(12.dp))
                    TextButton(enabled = !busy && page + 1 < count, onClick = { page++ }) { Text("下一张") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy && png != null, onClick = { scope.launch {
                        busy = true
                        try { staged = withContext(Dispatchers.IO) { stage(png!!) }; savePicker.launch(QuoteCardImage.fileName(book.title)) }
                        catch (e: Exception) { message = e.message ?: "准备图片失败" }
                        finally { busy = false }
                    } }) { Text("另存本张") }
                    if (Build.VERSION.SDK_INT >= 29) TextButton(enabled = !busy && png != null, onClick = { scope.launch {
                        busy = true
                        try { val bytes = png!!; withContext(Dispatchers.IO) { BookImageStore.save(context, BookImageStore.renderedImage(bytes, book.title)) }; message = "已保存到相册" }
                        catch (e: Exception) { message = e.message ?: "相册保存失败" }
                        finally { busy = false }
                    } }) { Text("保存到相册") }
                    TextButton(enabled = !busy && png != null, onClick = { scope.launch {
                        busy = true
                        try {
                            val file = withContext(Dispatchers.IO) { stage(png!!) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                            val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = android.content.ClipData.newUri(context.contentResolver, "摘录图片", uri) }
                            context.startActivity(Intent.createChooser(intent, "分享摘录图片"))
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "图片分享失败" }
                        finally { busy = false }
                    } }) { Text("分享本张") }
                    if (count > 1) TextButton(enabled = !busy && renderer != null, onClick = { scope.launch {
                        busy = true
                        var output: File? = null
                        try {
                            val snapshot = renderer!!; val signed = watermark
                            output = withContext(Dispatchers.IO) {
                                val target = File(context.cacheDir, "quote-share/${UUID.randomUUID()}.zip").apply { parentFile?.mkdirs() }
                                try { java.util.zip.ZipOutputStream(target.outputStream()).use { zip ->
                                    for (index in snapshot.pages.indices) {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        val bytes = snapshot.png(index, signed)
                                        require(target.length() + bytes.size <= 1024L * 1024 * 1024) { "摘录压缩包超过 1GiB，请逐张保存" }
                                        zip.putNextEntry(java.util.zip.ZipEntry("excerpt-${index + 1}.png")); zip.write(bytes); zip.closeEntry()
                                    }
                                }; target } catch (e: Exception) { target.delete(); throw e }
                            }
                            staged = output; zipPicker.launch("摘录图片.zip")
                        } catch (e: Exception) { output?.delete(); message = e.message ?: "导出失败" }
                        finally { busy = false }
                    } }) { Text("全部导出 ZIP") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
