@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.core.view.doOnAttach
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.publication.services.content.Content
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Color
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File
import org.readium.r2.shared.publication.services.positions
import org.mewx.wenku8_flutter.SpeechLocalePolicy

class ReaderActivity : FragmentActivity() {
    private lateinit var library: Library
    private lateinit var book: Book
    private var publication: Publication? = null
    private var navigator by mutableStateOf<EpubNavigatorFragment?>(null)
    private var started = 0L
    private val containerId = View.generateViewId()
    private var loading by mutableStateOf(true)
    private var failure by mutableStateOf<String?>(null)
    private var toc by mutableStateOf(false)
    private var marks by mutableStateOf(false)
    private var fonts by mutableStateOf(false)
    private var selectedFontFile: File? = null
    private var settings by mutableStateOf(false)
    private var options by mutableStateOf(ReaderOptions())
    private var fontSize by mutableFloatStateOf(100f)
    private var night by mutableStateOf(false)
    private var scroll by mutableStateOf(false)
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var speaking by mutableStateOf(false)
    private var search by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private var progression by mutableDoubleStateOf(0.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The publication must be reopened after process death before creating its navigator.
        super.onCreate(null)
        library = Library.get(this)
        val found = library.books().firstOrNull { it.id == intent.getStringExtra("book") }
        if (found == null) { finish(); return }
        book = found
        speech = TextToSpeech(this) { status ->
            speechReady = status == TextToSpeech.SUCCESS
            if (speechReady) {
                speech?.language = SpeechLocalePolicy.requestedLocale("zh-CN") ?: Locale.SIMPLIFIED_CHINESE
                speech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) { }
                    override fun onDone(id: String?) { runOnUiThread { speaking = false } }
                    @Deprecated("Legacy TTS callback") override fun onError(id: String?) { runOnUiThread { speaking = false; notice = "朗读失败，请检查系统语音引擎" } }
                })
            }
        }
        progression = book.progress
        val preferences = getSharedPreferences("reader-${book.id}", MODE_PRIVATE)
        fontSize = preferences.getFloat("fontSize", 100f)
        night = preferences.getBoolean("night", false)
        scroll = preferences.getBoolean("scroll", true)
        options = ReaderOptions.decode(book.readerOptions)
        if (book.readerOptions != null) { fontSize = options.fontSize; night = options.night; scroll = options.scroll }
        applyWindowOptions()
        setContent { ReaderTheme { ReaderScreen() } }
        lifecycleScope.launch {
            try {
                selectedFontFile = withContext(Dispatchers.IO) {
                    FontStore(this@ReaderActivity).fonts().firstOrNull { it.id == options.fontId }?.let { File(it.file).takeIf { file -> file.isFile } }
                }
                val pub = withContext(Dispatchers.IO) { library.openPublication(book, selectedFontFile) }
                publication = pub
                val initial = book.locator?.let { Locator.fromJSON(JSONObject(it)) }
                    ?: if (book.progress > 0) withContext(Dispatchers.IO) {
                        val positions = pub.positions()
                        positions.getOrNull(((positions.size - 1) * book.progress).toInt().coerceAtLeast(0))
                    } else null
                supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
                    initialLocator = initial,
                    initialPreferences = readerPreferences(),
                    configuration = EpubNavigatorFragment.Configuration {
                        selectedFontFile?.let { file -> addFontFamilyDeclaration(FontFamily(options.fontId)) {
                            addFontFace { addSource(ReadiumEngine.fontUrl(file)); setFontWeight(100..900) }
                        } }
                    }
                )
                loading = false
            } catch (e: Exception) { failure = e.message ?: "无法打开书籍"; loading = false }
        }
    }

    private fun readerPreferences() = EpubPreferences(
        fontSize = fontSize.toDouble(), theme = if (night) Theme.DARK else Theme.LIGHT, scroll = scroll,
        lineHeight = options.lineHeight.toDouble(), letterSpacing = options.letterSpacing.toDouble(),
        paragraphSpacing = options.paragraphGap.toDouble() / 16.0, pageMargins = options.margin.toDouble() / 26.0,
        paragraphIndent = if (options.indent) 2.0 else 0.0,
        textAlign = if (options.justify) TextAlign.JUSTIFY else TextAlign.START,
        publisherStyles = options.publisherStyles, verticalText = options.vertical,
        columnCount = if (options.twoPages) ColumnCount.TWO else ColumnCount.ONE,
        fontWeight = options.weight.toDouble(),
        textColor = parseColor(options.textColor), backgroundColor = parseColor(options.backgroundColor),
        fontFamily = options.fontId.takeIf { it.isNotEmpty() && selectedFontFile != null }?.let { FontFamily(it) }
    )
    private fun parseColor(value: String): Color? = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()
    private fun applyWindowOptions() {
        if (options.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (options.hideSystemStatus) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        window.attributes = window.attributes.apply { screenBrightness = options.brightness }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (options.volumePaging && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event?.repeatCount == 0) { if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) navigator?.goForward() else navigator?.goBackward() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (options.volumePaging && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
    private fun applySettings() {
        options = options.copy(fontSize = fontSize, night = night, scroll = scroll)
        navigator?.submitPreferences(readerPreferences())
        applyWindowOptions()
        library.update(currentBook().copy(readerOptions = options.json()))
        getSharedPreferences("reader-${book.id}", MODE_PRIVATE).edit().putFloat("fontSize", fontSize).putBoolean("night", night).putBoolean("scroll", scroll).apply()
    }
    private fun currentBook() = library.books().first { it.id == book.id }
    private fun toggleSpeech() {
        if (speaking) { speech?.stop(); speaking = false; return }
        if (!speechReady) { notice = "系统朗读引擎未就绪，请安装中文语音"; return }
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val iterator = publication?.content(navigator?.currentLocator?.value)?.iterator()
                    buildString {
                        while (length < 3000) {
                            ensureActive()
                            val element = iterator?.nextOrNull() ?: break
                            if (element is Content.TextualElement) append(element.text.orEmpty()).append("\n")
                        }
                    }.take(3000)
                }
                if (text.isBlank()) { notice = "当前位置没有可朗读的文字"; return@launch }
                speaking = speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reading") == TextToSpeech.SUCCESS
            } catch (e: Exception) { notice = e.message ?: "无法朗读" }
        }
    }
    private fun mark() {
        val locator = navigator?.currentLocator?.value?.toJSON()?.toString() ?: return
        val current = currentBook()
        if (locator !in current.bookmarks) library.update(current.copy(bookmarks = current.bookmarks + locator))
        notice = "书签已保存"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun ReaderScreen() {
        Scaffold(topBar = { TopAppBar(title = { Text(book.title, maxLines = 1) }, navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = {
            IconButton(onClick = { mark() }, enabled = navigator != null && !loading) { Icon(Icons.Outlined.BookmarkAdd, "添加书签") }
            IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "阅读设置") }
        }) }, bottomBar = { BottomAppBar {
            TextButton(onClick = { toc = true }, enabled = publication != null) { Text("目录") }
            TextButton(onClick = { marks = true }) { Text("书签") }
            IconButton(onClick = { search = true }, enabled = !loading && failure == null) { Icon(Icons.Outlined.Search, "全文搜索") }
            IconButton(onClick = { toggleSpeech() }, enabled = !loading && failure == null) { Icon(if (speaking) Icons.Outlined.Stop else Icons.Outlined.VolumeUp, if (speaking) "停止朗读" else "朗读") }
            Spacer(Modifier.weight(1f))
            Text("${(progression * 100).toInt()}%", Modifier.padding(12.dp))
            IconButton(onClick = { navigator?.goBackward() }, enabled = navigator != null) { Icon(Icons.Outlined.ChevronLeft, "上一页") }
            IconButton(onClick = { navigator?.goForward() }, enabled = navigator != null) { Icon(Icons.Outlined.ChevronRight, "下一页") }
        } }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                    failure != null -> Text(failure!!, Modifier.padding(24.dp))
                    publication != null -> AndroidView(factory = { context ->
                        FragmentContainerView(context).apply {
                            id = containerId
                            val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
                            navigator = fragment
                            doOnAttach { supportFragmentManager.beginTransaction().replace(id, fragment, "epub").commitNow() }
                            lifecycleScope.launch { fragment.currentLocator.collect { locator ->
                                progression = locator.locations.totalProgression ?: locator.locations.progression ?: 0.0
                                val current = currentBook()
                                library.update(current.copy(locator = locator.toJSON().toString(), progress = progression, lastRead = System.currentTimeMillis()))
                            } }
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        notice?.let { message -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("确定") } }) }
        if (search) ModalBottomSheet(onDismissRequest = { search = false }) {
            var query by remember { mutableStateOf("") }
            var results by remember { mutableStateOf<List<Pair<String, Locator?>>>(emptyList()) }
            var searching by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索书中内容") }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = query.isNotBlank() && !searching, onClick = {
                    scope.launch {
                        searching = true
                        try {
                            results = withContext(Dispatchers.IO) {
                                val matches = mutableListOf<Pair<String, Locator?>>()
                                val iterator = publication?.content()?.iterator()
                                while (matches.size < 100) {
                                    ensureActive()
                                    val element = iterator?.nextOrNull() ?: break
                                    if (element is Content.TextualElement && element.text.orEmpty().contains(query, true)) matches += element.text.orEmpty().take(180) to element.locator
                                }
                                matches
                            }
                        } catch (e: Exception) { notice = e.message ?: "搜索失败" }
                        finally { searching = false }
                    }
                }) { Text(if (searching) "搜索中…" else "搜索（最多 100 条）") }
                results.forEach { (text, locator) -> TextButton(onClick = { locator?.let { navigator?.go(it) }; search = false }) { Text(text) } }
            }
        }
        if (toc) ModalBottomSheet(onDismissRequest = { toc = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("目录", style = MaterialTheme.typography.titleLarge)
                @Composable fun entries(links: List<Link>, depth: Int = 0) {
                    links.forEach { link ->
                        TextButton(onClick = { navigator?.go(link); toc = false }, modifier = Modifier.padding(start = (depth * 12).dp)) { Text(link.title ?: link.href.toString()) }
                        entries(link.children, depth + 1)
                    }
                }
                entries(publication?.tableOfContents.orEmpty())
            }
        }
        if (marks) ModalBottomSheet(onDismissRequest = { marks = false }) {
            var all by remember { mutableStateOf(currentBook().bookmarks) }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("书签", style = MaterialTheme.typography.titleLarge)
                if (all.isEmpty()) Text("还没有书签。点击阅读器右上角添加。", Modifier.padding(16.dp))
                all.forEachIndexed { index, raw ->
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    val locator = json?.let { Locator.fromJSON(it) }
                    val legacyProgress = json?.optDouble("txtProgress", 0.0)?.coerceIn(0.0, 1.0) ?: 0.0
                    Row {
                        TextButton(onClick = { if (locator != null) navigator?.go(locator) else lifecycleScope.launch {
                            val positions = withContext(Dispatchers.IO) { publication?.positions().orEmpty() }
                            positions.getOrNull(((positions.size - 1) * legacyProgress).toInt().coerceAtLeast(0))?.let { navigator?.go(it) }
                        }; marks = false }, modifier = Modifier.weight(1f)) { Text(locator?.title ?: "书签 ${index + 1} · ${(legacyProgress * 100).toInt()}%") }
                        IconButton(onClick = { all = all - raw; library.update(currentBook().copy(bookmarks = all)) }) { Icon(Icons.Outlined.DeleteOutline, "删除书签") }
                    }
                }
            }
        }
        if (fonts) FontManager(onDismiss = { fonts = false }, selected = options.fontId, onSelect = { identity ->
            options = options.copy(fontId = identity, publisherStyles = if (identity.isEmpty()) options.publisherStyles else false)
            applySettings()
            fonts = false
            recreate()
        })
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
            Column(Modifier.padding(24.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { settings = false; fonts = true }) { Text("字体管理与下载") }
                Text("字号 ${(fontSize).toInt()}%")
                Slider(fontSize, { fontSize = it; applySettings() }, valueRange = 75f..200f)
                Row { Text("夜间阅读", Modifier.weight(1f)); Switch(night, { night = it; applySettings() }) }
                Row { Text("滚动阅读", Modifier.weight(1f)); Switch(scroll, { scroll = it; applySettings() }) }
                @Composable fun toggle(label: String, value: Boolean, update: (Boolean) -> ReaderOptions) {
                    Row { Text(label, Modifier.weight(1f)); Switch(value, { options = update(it); applySettings() }) }
                }
                @Composable fun control(label: String, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> ReaderOptions) {
                    Text("$label ${"%.1f".format(value)}")
                    Slider(value, { options = update(it); navigator?.submitPreferences(readerPreferences()) }, valueRange = range, onValueChangeFinished = { applySettings() })
                }
                toggle("保留原书样式", options.publisherStyles) { options.copy(publisherStyles = it) }
                toggle("竖排", options.vertical) { options.copy(vertical = it) }
                toggle("双页", options.twoPages) { options.copy(twoPages = it) }
                toggle("首行缩进", options.indent) { options.copy(indent = it) }
                toggle("两端对齐", options.justify) { options.copy(justify = it) }
                control("行高", options.lineHeight, 1f..3f) { options.copy(lineHeight = it) }
                control("字距", options.letterSpacing, 0f..3f) { options.copy(letterSpacing = it) }
                control("段距", options.paragraphGap, 0f..32f) { options.copy(paragraphGap = it) }
                control("页边距", options.margin, 0f..80f) { options.copy(margin = it) }
                control("字重", options.weight, .5f..2f) { options.copy(weight = it) }
                toggle("屏幕常亮", options.keepScreenOn) { options.copy(keepScreenOn = it) }
                toggle("音量键翻页", options.volumePaging) { options.copy(volumePaging = it) }
                toggle("沉浸阅读", options.hideSystemStatus) { options.copy(hideSystemStatus = it) }
                TextButton(onClick = { options = options.copy(brightness = -1f); applySettings() }) { Text("亮度跟随系统") }
                control("亮度", options.brightness.coerceAtLeast(0f), 0f..1f) { options.copy(brightness = it) }
                var textColor by remember { mutableStateOf(options.textColor) }
                var backgroundColor by remember { mutableStateOf(options.backgroundColor) }
                OutlinedTextField(textColor, { textColor = it }, label = { Text("文字颜色，例如 #222222（留空为默认）") })
                OutlinedTextField(backgroundColor, { backgroundColor = it }, label = { Text("背景颜色，例如 #F5EBD8") })
                TextButton(onClick = {
                    if ((textColor.isBlank() || parseColor(textColor) != null) && (backgroundColor.isBlank() || parseColor(backgroundColor) != null)) {
                        options = options.copy(textColor = textColor, backgroundColor = backgroundColor); applySettings()
                    } else notice = "请输入有效的颜色值"
                }) { Text("应用颜色") }
                TextButton(onClick = { options = ReaderOptions(); fontSize = options.fontSize; night = options.night; scroll = options.scroll; applySettings() }) { Text("恢复默认阅读设置") }
            }
        }
    }
    override fun onStart() { super.onStart(); started = System.currentTimeMillis() }
    override fun onStop() {
        if (::book.isInitialized && !loading && failure == null) {
            val current = currentBook()
            library.update(current.copy(progress = progression, lastRead = System.currentTimeMillis(), minutes = current.minutes + (System.currentTimeMillis() - started) / 1000))
        }
        speech?.stop(); speaking = false
        super.onStop()
    }
    override fun onDestroy() { super.onDestroy(); speech?.shutdown(); publication?.close() }
}
