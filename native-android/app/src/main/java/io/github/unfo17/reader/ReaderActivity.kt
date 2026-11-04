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
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File

class ReaderActivity : FragmentActivity() {
    private lateinit var library: Library
    private lateinit var book: Book
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var started = 0L
    private val containerId = View.generateViewId()
    private var loading by mutableStateOf(true)
    private var failure by mutableStateOf<String?>(null)
    private var txt by mutableStateOf<String?>(null)
    private var toc by mutableStateOf(false)
    private var marks by mutableStateOf(false)
    private var settings by mutableStateOf(false)
    private var fontSize by mutableFloatStateOf(100f)
    private var night by mutableStateOf(false)
    private var scroll by mutableStateOf(false)
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var speaking by mutableStateOf(false)
    private var search by mutableStateOf(false)
    private var textJump by mutableStateOf<Double?>(null)
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
                speech?.language = Locale.SIMPLIFIED_CHINESE
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
        scroll = preferences.getBoolean("scroll", false)
        setContent { ReaderTheme { ReaderScreen() } }
        lifecycleScope.launch {
            try {
                if (book.file.endsWith(".txt")) {
                    txt = withContext(Dispatchers.IO) { val bytes = File(book.file).readBytes(); val utf8 = bytes.toString(Charsets.UTF_8); if ('\uFFFD' in utf8) bytes.toString(charset("GB18030")) else utf8 }
                } else {
                    val pub = withContext(Dispatchers.IO) { library.engine.open(File(book.file)) }
                    publication = pub
                    supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
                        initialLocator = book.locator?.let { Locator.fromJSON(JSONObject(it)) },
                        initialPreferences = readerPreferences()
                    )
                }
                loading = false
            } catch (e: Exception) { failure = e.message ?: "无法打开书籍"; loading = false }
        }
    }

    private fun readerPreferences() = EpubPreferences(fontSize = fontSize.toDouble(), theme = if (night) Theme.DARK else Theme.LIGHT, scroll = scroll)
    private fun applySettings() {
        navigator?.submitPreferences(readerPreferences())
        getSharedPreferences("reader-${book.id}", MODE_PRIVATE).edit().putFloat("fontSize", fontSize).putBoolean("night", night).putBoolean("scroll", scroll).apply()
    }
    private fun currentBook() = library.books().first { it.id == book.id }
    private fun toggleSpeech() {
        if (speaking) { speech?.stop(); speaking = false; return }
        if (!speechReady) { notice = "系统朗读引擎未就绪，请安装中文语音"; return }
        lifecycleScope.launch {
            try {
                val text = txt?.let { it.substring((it.length * progression).toInt().coerceIn(0, it.length)).take(3000) }
                    ?: withContext(Dispatchers.IO) {
                        val iterator = publication?.content(navigator?.currentLocator?.value)?.iterator()
                        buildString { while (length < 3000) { ensureActive(); val element = iterator?.nextOrNull() ?: break; if (element is Content.TextualElement) append(element.text.orEmpty()).append("\n") } }.take(3000)
                    }
                if (text.isBlank()) { notice = "当前位置没有可朗读的文字"; return@launch }
                speaking = speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reading") == TextToSpeech.SUCCESS
            } catch (e: Exception) { notice = e.message ?: "无法朗读" }
        }
    }
    private fun mark() {
        val locator = navigator?.currentLocator?.value?.toJSON()?.toString() ?: JSONObject().put("txtProgress", progression).toString()
        val current = currentBook()
        if (locator !in current.bookmarks) library.update(current.copy(bookmarks = current.bookmarks + locator))
        notice = "书签已保存"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun ReaderScreen() {
        Scaffold(topBar = { TopAppBar(title = { Text(book.title, maxLines = 1) }, navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = {
            IconButton(onClick = { mark() }) { Icon(Icons.Outlined.BookmarkAdd, "添加书签") }
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
                    txt != null -> {
                        val state = rememberScrollState()
                        var restored by remember { mutableStateOf(false) }
                        LaunchedEffect(state.maxValue) { if (state.maxValue > 0 && !restored) { state.scrollTo((book.progress * state.maxValue).toInt()); restored = true } }
                        LaunchedEffect(textJump) { textJump?.let { state.scrollTo((it * state.maxValue).toInt()); textJump = null } }
                        LaunchedEffect(state.value) { progression = if (state.maxValue > 0) state.value.toDouble() / state.maxValue else 0.0 }
                        Surface(color = if (night) androidx.compose.ui.graphics.Color(0xFF191919) else MaterialTheme.colorScheme.surface) {
                            Text(txt!!, Modifier.fillMaxSize().verticalScroll(state).padding(24.dp), fontSize = (18 * fontSize / 100).sp, lineHeight = (30 * fontSize / 100).sp, color = if (night) androidx.compose.ui.graphics.Color(0xFFE0E0E0) else MaterialTheme.colorScheme.onSurface)
                        }
                    }
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
                                if (txt != null) {
                                    var offset = txt!!.indexOf(query, ignoreCase = true)
                                    while (offset >= 0 && matches.size < 100) { matches += ("$offset:" + txt!!.substring(offset, (offset + 100).coerceAtMost(txt!!.length))) to null; offset = txt!!.indexOf(query, offset + query.length, true) }
                                } else {
                                    val iterator = publication?.content()?.iterator()
                                    while (matches.size < 100) { ensureActive(); val element = iterator?.nextOrNull() ?: break; if (element is Content.TextualElement && element.text.orEmpty().contains(query, true)) matches += element.text.orEmpty().take(180) to element.locator }
                                }
                                matches
                            }
                        } catch (e: Exception) { notice = e.message ?: "搜索失败" }
                        finally { searching = false }
                    }
                }) { Text(if (searching) "搜索中…" else "搜索（最多 100 条）") }
                results.forEach { (text, locator) -> TextButton(onClick = { if (locator != null) navigator?.go(locator) else textJump = text.substringBefore(':').toDouble() / txt!!.length; search = false }) { Text(text.substringAfter(':', text)) } }
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
                    val locator = runCatching { Locator.fromJSON(JSONObject(raw)) }.getOrNull()
                    Row {
                        TextButton(onClick = { if (locator != null) navigator?.go(locator) else textJump = JSONObject(raw).optDouble("txtProgress"); marks = false }, modifier = Modifier.weight(1f)) { Text(locator?.title ?: "书签 ${index + 1} · TXT ${(JSONObject(raw).optDouble("txtProgress") * 100).toInt()}%") }
                        IconButton(onClick = { all = all - raw; library.update(currentBook().copy(bookmarks = all)) }) { Icon(Icons.Outlined.DeleteOutline, "删除书签") }
                    }
                }
            }
        }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.titleLarge)
                Text("字号 ${(fontSize).toInt()}%")
                Slider(fontSize, { fontSize = it; applySettings() }, valueRange = 75f..200f)
                Row { Text("夜间阅读", Modifier.weight(1f)); Switch(night, { night = it; applySettings() }) }
                if (publication != null) Row { Text("滚动阅读", Modifier.weight(1f)); Switch(scroll, { scroll = it; applySettings() }) }
            }
        }
    }
    override fun onStart() { super.onStart(); started = System.currentTimeMillis() }
    override fun onStop() {
        if (::book.isInitialized) {
            val current = currentBook()
            library.update(current.copy(progress = progression, lastRead = System.currentTimeMillis(), minutes = current.minutes + (System.currentTimeMillis() - started) / 1000))
        }
        speech?.stop(); speaking = false
        super.onStop()
    }
    override fun onDestroy() { super.onDestroy(); speech?.shutdown(); publication?.close() }
}
