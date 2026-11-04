@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.os.Bundle
import android.view.View
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import org.readium.r2.navigator.DecorableNavigator
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
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
import kotlin.math.roundToInt
import org.readium.r2.shared.publication.services.positions

class ReaderActivity : FragmentActivity() {
    private lateinit var library: Library
    private lateinit var book: Book
    private var series: BookSeries? = null
    private var publication: Publication? = null
    private var navigator by mutableStateOf<EpubNavigatorFragment?>(null)
    private var started = 0L
    private val containerId = View.generateViewId()
    private var loading by mutableStateOf(true)
    private var failure by mutableStateOf<String?>(null)
    private val annotationSession by viewModels<AnnotationDraftSession>()
    private var annotationDraft: ReadingMark?
        get() = annotationSession.mark.takeUnless { annotationSession.selectingRange }
        set(value) { annotationSession.mark = value }
    private var annotationExpected: Long?
        get() = annotationSession.expectedRevision
        set(value) { annotationSession.expectedRevision = value }
    private val pendingRange get() = annotationSession.mark.takeIf { annotationSession.selectingRange }
    private var annotations by mutableStateOf(false)
    private var decorationJob: kotlinx.coroutines.Job? = null
    private var toc by mutableStateOf(false)
    private var marks by mutableStateOf(false)
    private var fonts by mutableStateOf(false)
    private var selectedFontFile: File? = null
    private var settings by mutableStateOf(false)
    private var options by mutableStateOf(ReaderOptions())
    private var fontSize by mutableFloatStateOf(100f)
    private var night by mutableStateOf(false)
    private var scroll by mutableStateOf(false)
    private val speechClientSession = lazy { SystemSpeechClient(this) }
    private val speechAudioSession = lazy { SpeechAudioOutput(this) { speechEpoch++; speechJob?.cancel(); speaking = false } }
    private var speechJob: kotlinx.coroutines.Job? = null
    private var speechEpoch = 0L
    private var speechSettings by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private var search by mutableStateOf(false)
    private var searchOrigin by mutableStateOf<Locator?>(null)
    private var notice by mutableStateOf<String?>(null)
    private var progression by mutableDoubleStateOf(0.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The publication must be reopened after process death before creating its navigator.
        super.onCreate(null)
        library = Library.get(this)
        val found = library.books().firstOrNull { it.id == intent.getStringExtra("book") }
        if (found == null) { finish(); return }
        book = found
        series = library.series.get(intent.getStringExtra("series").orEmpty())?.takeIf { !it.deleted && it.volumes.any { part -> part.bookId == book.id } }
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
                val initial = (intent.getStringExtra("jumpLocator") ?: book.locator)?.let { Locator.fromJSON(JSONObject(it)) }
                    ?: if (book.progress > 0) withContext(Dispatchers.IO) {
                        val positions = pub.positions()
                        positions.getOrNull(((positions.size - 1) * book.progress).toInt().coerceAtLeast(0))
                    } else null
                supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
                    initialLocator = initial,
                    initialPreferences = readerPreferences(),
                    configuration = EpubNavigatorFragment.Configuration {
                        decorationTemplates = annotationTemplates()
                        selectionActionModeCallback = annotationSelectionMenu()

                        selectedFontFile?.let { file -> addFontFamilyDeclaration(FontFamily(options.fontId)) {
                            addFontFace { addSource(ReadiumEngine.fontUrl(file)); setFontWeight(100..900) }
                        } }
                    }
                )
                loading = false
            } catch (e: Exception) { failure = e.message ?: "无法打开书籍"; loading = false }
        }
    }

    private fun annotationSelectionMenu() = object : ActionMode.Callback {
        private fun add(menu: Menu) {
            (0..4).forEach { menu.removeItem(0x46524b00 + it) }
            if (pendingRange != null) { menu.add(Menu.NONE, 0x46524b04, 0, "使用此范围"); return }
            listOf(MarkStyle.UNDERLINE, MarkStyle.HIGHLIGHT, MarkStyle.OUTLINE, MarkStyle.NONE).forEachIndexed { index, style -> if (menu.findItem(0x46524b00 + index) == null) menu.add(Menu.NONE, 0x46524b00 + index, index, if (style == MarkStyle.NONE) "批注" else style.label) } }
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { add(menu); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { add(menu); return false }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val index = item.itemId - 0x46524b00
            if (index == 4 && pendingRange != null) { annotateSelection(pendingRange!!.style) { mode.finish() }; return true }
            if (index !in 0..3) return false
            annotateSelection(listOf(MarkStyle.UNDERLINE, MarkStyle.HIGHLIGHT, MarkStyle.OUTLINE, MarkStyle.NONE)[index]) { mode.finish() }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) { }
    }
    internal fun annotateSelection(style: MarkStyle, captured: () -> Unit = {}) {
        lifecycleScope.launch {
            try {
                val selection = navigator?.currentSelection() ?: run { notice = "请先长按正文选择文字"; return@launch }
                require(selection.locator.text.highlight.orEmpty().length in 1..12000) { "请选择 1 到 12000 字的原文" }
                val anchor = navigator?.evaluateJavascript("""
                    (function(){var s=window.getSelection();if(!s||!s.rangeCount)return null;var r=s.getRangeAt(0);
                    function path(e){var p=[];while(e&&e.nodeType===1){var n=1,t=e.previousElementSibling;while(t){if(t.tagName===e.tagName)n++;t=t.previousElementSibling;}p.unshift(e.tagName.toLowerCase()+':nth-of-type('+n+')');e=e.parentElement;}return p.join(' > ');}
                    function point(n,o){return {selector:path(n.nodeType===1?n:n.parentElement),childNodeIndex:n.nodeType===1?-1:Array.prototype.indexOf.call(n.parentNode.childNodes,n),offset:o,nodeType:n.nodeType};}
                    var e=r.commonAncestorContainer;if(e.nodeType!==1)e=e.parentElement;
                    return {cssSelector:path(e),forkSourceRange:{start:point(r.startContainer,r.startOffset),end:point(r.endContainer,r.endOffset)}};})()
                """.trimIndent())?.let { runCatching { JSONObject(it) }.getOrNull() }
                val json = selection.locator.toJSON()
                if (anchor != null) {
                    val locations = json.optJSONObject("locations") ?: JSONObject().also { json.put("locations", it) }
                    anchor.keys().forEach { locations.put(it, anchor.get(it)) }
                }
                val draft = ReadingMark.create(Locator.fromJSON(json) ?: selection.locator, style)
                val pending = pendingRange
                if (pending != null) {
                    require(library.books().any { it.id == book.id }) { "原书已移除，请重新导入后调整范围" }
                    val revised = pending.copy(locator = draft.locator, quote = draft.quote,
                        rangeUpdatedAt = if (pending.sameAnchor(draft)) pending.rangeUpdatedAt else maxOf(System.currentTimeMillis(), pending.revision + 1))
                    annotationSession.selectingRange = false
                    annotationDraft = revised
                } else {
                    val existing = currentBook().annotations.firstOrNull { !it.deleted && it.sameAnchor(draft) }
                    annotationExpected = existing?.revision
                    annotationDraft = existing ?: draft
                }
                captured(); navigator?.clearSelection()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "无法获取选区" }
        }
    }
    private fun adjustAnnotationRange(draft: ReadingMark) {
        require(library.books().any { it.id == book.id }) { "原书已移除，请重新导入后调整范围" }
        annotationSession.mark = draft
        annotationSession.selectingRange = true
        annotations = false; marks = false
        navigator?.clearSelection()
        Locator.fromJSON(JSONObject(draft.locator))?.let { navigator?.go(it) }
    }
    private fun cancelRangeAdjustment() {
        navigator?.clearSelection()
        annotationSession.selectingRange = false
    }
    private fun refreshDecorations() {
        val fragment = navigator ?: return
        val ink = parseColor(options.textColor)?.int ?: if (night) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY
        val decorations = currentBook().annotations.mapNotNull { it.decoration(ink) }
        decorationJob?.cancel()
        decorationJob = lifecycleScope.launch { fragment.applyDecorations(decorations, "annotations") }
    }
    private fun openVolume(bookId: String, locator: Locator? = null) {
        if (bookId != book.id && pendingRange != null) { notice = "请先完成或取消当前原书的范围调整"; return }
        if (bookId == book.id) { locator?.let { navigator?.go(it) }; toc = false; marks = false; return }
        val collection = series ?: return
        if (library.books().none { it.id == bookId }) { notice = "该卷原书已移除，请重新导入"; return }
        startActivity(android.content.Intent(this, ReaderActivity::class.java).putExtra("book", bookId).putExtra("series", collection.id).putExtra("jumpLocator", locator?.toJSON()?.toString()))
        finish()
    }
    private fun readerPreferences() = EpubPreferences(
        fontSize = fontSize.toDouble(), theme = if (night) Theme.DARK else Theme.LIGHT, scroll = scroll,
        lineHeight = options.lineHeight.toDouble(), letterSpacing = ReaderTypography.letterSpacingPreference(options.letterSpacing),
        paragraphSpacing = options.paragraphGap.toDouble() / 16.0, pageMargins = 0.0,
        paragraphIndent = if (options.indent) 2.0 else 0.0,
        textAlign = if (options.justify) TextAlign.JUSTIFY else TextAlign.START,
        publisherStyles = options.publisherStyles, verticalText = options.vertical,
        columnCount = if (options.twoPages) ColumnCount.TWO else ColumnCount.ONE,
        fontWeight = options.weight.toDouble(),
        textColor = parseColor(options.textColor), backgroundColor = Color(readerBackground()),
        fontFamily = options.fontId.takeIf { it.isNotEmpty() && selectedFontFile != null }?.let { FontFamily(it) }
    )
    private fun readerBackground() = runCatching { android.graphics.Color.parseColor(options.backgroundColor) }.getOrNull()
        ?: if (night) android.graphics.Color.rgb(18, 18, 18) else android.graphics.Color.WHITE
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
        refreshDecorations()
        library.updateReading(book.id) { it.copy(readerOptions = options.json()) }
        getSharedPreferences("reader-${book.id}", MODE_PRIVATE).edit().putFloat("fontSize", fontSize).putBoolean("night", night).putBoolean("scroll", scroll).apply()
    }
    private fun currentBook() = library.records().firstOrNull { it.id == book.id } ?: book
    private fun toggleSpeech() {
        if (speaking) {
            speechEpoch++; speechJob?.cancel()
            if (speechAudioSession.isInitialized()) speechAudioSession.value.stop()
            speaking = false; return
        }
        val epoch = ++speechEpoch
        speaking = true
        speechJob = lifecycleScope.launch {
            try {
                val iterator = withContext(Dispatchers.IO) { publication?.content(navigator?.currentLocator?.value)?.iterator() }
                var any = false
                while (true) {
                    ensureActive()
                    val element = withContext(Dispatchers.IO) { iterator?.nextOrNull() } ?: break
                    if (element !is Content.TextualElement) continue
                    for (segment in org.mewx.wenku8_flutter.SpeechSegments.split(element.text.orEmpty())) {
                        ensureActive()
                        val preferences = SystemSpeechSettings.load(this@ReaderActivity)
                        val file = speechClientSession.value.synthesize(segment.text, preferences.engine, preferences.voice, preferences.locale)
                        try { any = true; speechAudioSession.value.play(file, preferences.speed, keepFocus = true) }
                        finally { file.delete() }
                    }
                }
                if (!any) notice = "当前位置没有可朗读的文字"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { coroutineContext.ensureActive(); notice = e.message ?: "无法朗读，请检查系统语音设置" }
            finally {
                if (epoch == speechEpoch) {
                    if (speechAudioSession.isInitialized()) speechAudioSession.value.stop()
                    speaking = false
                }
            }
        }
    }
    internal suspend fun captureSearchLocator(): Locator? {
        val fragment = navigator ?: return null
        val before = fragment.currentLocator.value ?: return null
        val anchor = fragment.evaluateJavascript("""
            (function(){
              function path(e){var p=[];while(e&&e.nodeType===1){var i=1,n=e.previousElementSibling;while(n){if(n.tagName===e.tagName)i++;n=n.previousElementSibling;}p.unshift(e.tagName.toLowerCase()+':nth-of-type('+i+')');e=e.parentElement;}return p.join(' > ');}
              var style=getComputedStyle(document.documentElement),right=style.writingMode.indexOf('vertical')>=0||style.direction==='rtl';
              var x=right?innerWidth-2:2,ys=[2,8,24,48];
              for(var i=0;i<ys.length;i++){
                var r=document.caretRangeFromPoint(x,ys[i]);if(!r)continue;
                var n=r.startContainer;if(n.nodeType!==3||!n.textContent.trim())continue;
                var offset=Math.min(r.startOffset,n.length-1),probe=document.createRange();probe.setStart(n,offset);probe.setEnd(n,offset+1);
                var b=probe.getBoundingClientRect();if(b.bottom<=0||b.top>=innerHeight||b.right<=0||b.left>=innerWidth)continue;
                return {resource:location.href,cssSelector:path(n.parentElement),forkSourceRange:{start:{selector:path(n.parentElement),childNodeIndex:Array.prototype.indexOf.call(n.parentNode.childNodes,n),offset:offset,nodeType:3}}};
              }
              return {resource:location.href};
            })()
        """.trimIndent())?.let { runCatching { JSONObject(it) }.getOrNull() }
        val after = fragment.currentLocator.value ?: before
        if (anchor == null) return after
        // Readium emits currentLocator asynchronously after a turn. Use the visible document's
        // resource identity so a fast search cannot pair the new page's source point with the old href.
        val uri = android.net.Uri.parse(anchor.optString("resource"))
        val path = uri.encodedPath?.takeIf { uri.scheme == "https" && uri.host == "readium" && it.startsWith("/publication/") }?.removePrefix("/publication/")
        val visibleLink = publication?.readingOrder?.firstOrNull { link ->
            path != null && android.net.Uri.decode(ChapterSearchWindow.resource(link.href.toString()).removePrefix("/")) == android.net.Uri.decode(path)
        }
        val source = if (visibleLink != null && ChapterSearchWindow.resource(after.href.toString()) != ChapterSearchWindow.resource(visibleLink.href.toString())) publication?.locatorFromLink(visibleLink) ?: after else after
        val json = source.toJSON()
        val locations = json.optJSONObject("locations") ?: JSONObject().also { json.put("locations", it) }
        listOf("cssSelector", "forkSourceRange").forEach { key -> if (anchor.has(key)) locations.put(key, anchor.get(key)) }
        return Locator.fromJSON(json) ?: source
    }
    private fun openSearch() {
        lifecycleScope.launch {
            try { searchOrigin = captureSearchLocator(); search = true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "正文尚未定位，请稍后重试" }
        }
    }
    private fun mark() {
        val locator = navigator?.currentLocator?.value ?: return
        try {
            val mark = ReadingMark.create(locator, MarkStyle.NONE)
            if (currentBook().annotations.none { !it.deleted && it.locator == mark.locator }) library.saveAnnotation(book.id, mark)
            notice = "书签已保存，可在书签列表编辑分类和笔记"
        } catch (e: Exception) { notice = e.message ?: "书签保存失败" }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun ReaderScreen() {
        val compactControls = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 400 || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        BackHandler(enabled = pendingRange != null) { cancelRangeAdjustment() }
        Scaffold(topBar = { TopAppBar(title = { Text(series?.let { "${it.title} · ${it.volumes.firstOrNull { part -> part.bookId == book.id }?.title ?: book.title}" } ?: book.title, maxLines = 1) }, navigationIcon = { IconButton(onClick = { if (pendingRange != null) cancelRangeAdjustment() else finish() }) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = {
            IconButton(onClick = { mark() }, enabled = navigator != null && !loading && pendingRange == null) { Icon(Icons.Outlined.BookmarkAdd, "添加书签") }
            IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "阅读设置") }
        }) }, bottomBar = { Column {
            pendingRange?.let { pending -> Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("长按原文重新选择批注范围", style = MaterialTheme.typography.labelLarge)
                    Row {
                        TextButton(onClick = { annotateSelection(pending.style) }) { Text("使用此范围") }
                        TextButton(onClick = { cancelRangeAdjustment() }) { Text("取消调整") }
                    }
                }
            } }
            BottomAppBar(windowInsets = if (compactControls) WindowInsets(0, 0, 0, 0) else BottomAppBarDefaults.windowInsets) {
            if (compactControls) {
                IconButton(onClick = { toc = true }, enabled = publication != null) { Icon(Icons.Outlined.FormatListBulleted, "目录") }
                IconButton(onClick = { marks = true }, enabled = pendingRange == null) { Icon(Icons.Outlined.Bookmarks, "书签") }
            } else {
                TextButton(onClick = { toc = true }, enabled = publication != null) { Text("目录") }
                TextButton(onClick = { marks = true }, enabled = pendingRange == null) { Text("书签") }
            }
            IconButton(onClick = { openSearch() }, enabled = !loading && failure == null) { Icon(Icons.Outlined.Search, "全文搜索") }
            IconButton(onClick = { toggleSpeech() }, enabled = !loading && failure == null) { Icon(if (speaking) Icons.Outlined.Stop else Icons.Outlined.VolumeUp, if (speaking) "停止朗读" else "朗读") }
            Spacer(Modifier.weight(1f))
            Text("${(progression * 100).toInt()}%", Modifier.padding(12.dp))
            if (!compactControls) IconButton(onClick = { navigator?.goBackward() }, enabled = navigator != null) { Icon(Icons.Outlined.ChevronLeft, "上一页") }
            if (!compactControls) IconButton(onClick = { navigator?.goForward() }, enabled = navigator != null) { Icon(Icons.Outlined.ChevronRight, "下一页") }
        }
        if (compactControls) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { navigator?.goBackward() }, enabled = navigator != null) { Text("上一页") }
                TextButton(onClick = { navigator?.goForward() }, enabled = navigator != null) { Text("下一页") }
            }
        }
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
                            doOnAttach {
                                supportFragmentManager.beginTransaction().replace(id, fragment, "epub").commitNow()
                                fragment.addDecorationListener("annotations", object : DecorableNavigator.Listener {
                                    override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                                        val mark = currentBook().annotations.firstOrNull { !it.deleted && it.id == event.decoration.id } ?: return false
                                        if (pendingRange != null) return false
                                        annotationSession.open(mark, mark.revision); return true
                                    }
                                })
                                refreshDecorations()
                            }
                            lifecycleScope.launch { fragment.currentLocator.collect { locator ->
                                progression = locator.locations.totalProgression ?: locator.locations.progression ?: 0.0
                                library.updateReading(book.id) { it.copy(locator = locator.toJSON().toString(), progress = progression, lastRead = System.currentTimeMillis()) }
                            } }
                        }
                    }, update = { view ->
                        val density = view.resources.displayMetrics.density
                        view.setPadding((options.leftMargin * density).roundToInt(), (options.marginTop * density).roundToInt(), (options.rightMargin * density).roundToInt(), (options.marginBottom * density).roundToInt())
                        view.setBackgroundColor(readerBackground())
                    }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        notice?.let { message -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("确定") } }) }
        annotationDraft?.let { draft -> AnnotationEditor(draft, { annotationSession.clear() }, { updated ->
            library.saveAnnotation(book.id, updated, annotationExpected); annotationSession.clear(); refreshDecorations()
        }, onDelete = annotationExpected?.let { expected -> { library.deleteAnnotation(book.id, draft.id, expected); annotationSession.clear(); refreshDecorations() } }, book = currentBook(),
            onAdjustRange = { adjustAnnotationRange(it) }, onDraftChange = { annotationSession.mark = it }) }
        if (annotations || marks) AnnotationsPanel(library, series?.volumes?.map { it.bookId }?.toSet() ?: setOf(book.id), { annotations = false; marks = false }, { refreshDecorations() }, rangeBookId = book.id, onAdjustRange = { draft, expected ->
            annotationExpected = expected; adjustAnnotationRange(draft)
        }, onNavigate = { original, mark ->
            Locator.fromJSON(JSONObject(mark.locator))?.let { openVolume(original.id, it) }; annotations = false; marks = false
        }, onNavigateLegacy = { original, raw -> lifecycleScope.launch {
            try {
                val json = JSONObject(raw)
                val locator = Locator.fromJSON(json)
                if (locator != null) openVolume(original.id, locator)
                else {
                    val pub = if (original.id == book.id) publication else library.openPublication(original)
                    try {
                        val positions = withContext(Dispatchers.IO) { pub?.positions().orEmpty() }
                        positions.getOrNull(((positions.size - 1) * json.optDouble("txtProgress", 0.0).coerceIn(0.0, 1.0)).toInt().coerceAtLeast(0))?.let { openVolume(original.id, it) }
                    } finally { if (original.id != book.id) pub?.close() }
                }
                annotations = false; marks = false
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "书签定位失败" }
        } })
        if (search) ReaderSearch(library, currentBook(), series, searchOrigin, { search = false }, { hit -> openVolume(hit.bookId, hit.locator); search = false })
        if (toc) ModalBottomSheet(onDismissRequest = { toc = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("目录", style = MaterialTheme.typography.titleLarge)
                @Composable fun entries(links: List<Link>, depth: Int = 0) {
                    links.forEach { link ->
                        TextButton(onClick = { navigator?.go(link); toc = false }, modifier = Modifier.padding(start = (depth * 12).dp)) { Text(link.title ?: link.href.toString()) }
                        entries(link.children, depth + 1)
                    }
                }
                series?.let { collection ->
                    Text("分卷", style = MaterialTheme.typography.titleMedium)
                    collection.volumes.forEach { volume ->
                        var expanded by remember(volume.bookId) { mutableStateOf(volume.bookId == book.id) }
                        Row {
                            TextButton(onClick = { openVolume(volume.bookId) }, modifier = Modifier.weight(1f)) { Text((if (volume.bookId == book.id) "✓ " else "") + volume.title) }
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "章节") }
                        }
                        if (expanded && volume.bookId == book.id) entries(publication?.tableOfContents.orEmpty())
                        if (expanded && volume.bookId != book.id) {
                            val chapters by produceState<List<Pair<String, Locator?>>?>(null, volume.bookId) {
                                value = try {
                                    val original = library.books().firstOrNull { it.id == volume.bookId } ?: error("原书已移除")
                                    val pub = library.openPublication(original)
                                    try {
                                        fun flattened(links: List<Link>, depth: Int = 0): List<Pair<String, Locator?>> = links.flatMap { link -> listOf(("　".repeat(depth) + (link.title ?: link.href.toString())) to pub.locatorFromLink(link)) + flattened(link.children, depth + 1) }
                                        flattened(pub.tableOfContents).ifEmpty { pub.readingOrder.map { (it.title ?: it.href.toString()) to pub.locatorFromLink(it) } }
                                    } finally { pub.close() }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { notice = e.message ?: "分卷目录读取失败"; emptyList() }
                            }
                            if (chapters == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            chapters.orEmpty().forEach { (title, locator) -> TextButton(enabled = locator != null, onClick = { openVolume(volume.bookId, locator) }) { Text(title) } }
                        }
                    }
                }
                if (series == null) entries(publication?.tableOfContents.orEmpty())
            }
        }
        if (fonts) FontManager(onDismiss = { fonts = false }, selected = options.fontId, onSelect = { identity ->
            options = options.copy(fontId = identity, publisherStyles = if (identity.isEmpty()) options.publisherStyles else false)
            applySettings()
            fonts = false
            recreate()
        })
        if (speechSettings) SystemSpeechSettingsPanel { speechSettings = false }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(24.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { settings = false; speechSettings = true }) { Text("系统语音与音色") }
                OutlinedButton(onClick = { settings = false; fonts = true }) { Text("字体管理与下载") }
                Text("字号 ${(fontSize).toInt()}%")
                Slider(fontSize, { fontSize = it; applySettings() }, valueRange = 50f..300f)
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
                toggle("首行缩进", options.indent) { options.copy(indent = it, publisherStyles = false) }
                toggle("两端对齐", options.justify) { options.copy(justify = it, publisherStyles = false) }
                control("行高", options.lineHeight, 1f..3f) { options.copy(lineHeight = it, publisherStyles = false) }
                control("字距", options.letterSpacing, -.5f..3f) { options.copy(letterSpacing = it, publisherStyles = false) }
                control("段距", options.paragraphGap, 0f..32f) { options.copy(paragraphGap = it, publisherStyles = false) }
                control("左边距", options.leftMargin, 0f..80f) { options.copy(marginLeft = it) }
                control("右边距", options.rightMargin, 0f..80f) { options.copy(marginRight = it) }
                control("上边距", options.marginTop, 0f..80f) { options.copy(marginTop = it) }
                control("下边距", options.marginBottom, 0f..80f) { options.copy(marginBottom = it) }
                val weights = listOf(1f to "常规", 1.25f to "适中", 1.5f to "加粗").let { presets -> if (presets.any { it.first == options.weight }) presets else presets + (options.weight to "自定义 ${ (options.weight * 400).toInt() }") }
                ChoiceMenu("字重", options.weight, weights) { options = options.copy(weight = it, publisherStyles = false); applySettings() }
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
            library.updateReading(book.id) { current -> current.copy(progress = progression, lastRead = System.currentTimeMillis(), minutes = current.minutes + ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0)) }
        }
        speechEpoch++; speechJob?.cancel(); if (speechAudioSession.isInitialized()) speechAudioSession.value.stop(); speaking = false
        super.onStop()
    }
    override fun onDestroy() {
        super.onDestroy()
        if (speechAudioSession.isInitialized()) speechAudioSession.value.close()
        if (speechClientSession.isInitialized()) speechClientSession.value.close()
        publication?.close()
    }
}
