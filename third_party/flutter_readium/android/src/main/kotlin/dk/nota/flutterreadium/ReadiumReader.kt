package dk.nota.flutterreadium

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import dk.nota.flutterreadium.events.NarrationSyncEventChannel
import dk.nota.flutterreadium.events.ReadiumError
import dk.nota.flutterreadium.events.ReadiumErrorEventChannel
import dk.nota.flutterreadium.events.ReadiumReaderStatus
import dk.nota.flutterreadium.events.ReadiumReaderStatusEventChannel
import dk.nota.flutterreadium.events.TextLocatorEventChannel
import dk.nota.flutterreadium.events.TimedBasedStateEventChannel
import dk.nota.flutterreadium.models.ReadiumTimebasedState
import dk.nota.flutterreadium.navigators.AudioRecoveryPolicy
import dk.nota.flutterreadium.navigators.AudiobookNavigator
import dk.nota.flutterreadium.navigators.ComicNavigator
import dk.nota.flutterreadium.navigators.EpubNavigator
import dk.nota.flutterreadium.navigators.FlutterVisualNavigator
import dk.nota.flutterreadium.navigators.PageBreakSkippingContentIteratorFactory
import dk.nota.flutterreadium.navigators.PdfNavigator
import dk.nota.flutterreadium.navigators.SyncAudiobookNavigator
import dk.nota.flutterreadium.navigators.TTSNavigator
import dk.nota.flutterreadium.navigators.TimebasedNavigator
import io.flutter.plugin.common.BinaryMessenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.extensions.normalizeLocator
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.LocatorCollection
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.content.DefaultContentService
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.publication.services.content.contentServiceFactory
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Try.Companion.failure
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.PublicationOpener.OpenError
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ReadiumReader"

/** [DefaultHttpClient] has no timeouts by default - a blocked socket read would hang forever. */
private val HTTP_CONNECT_TIMEOUT = 10.seconds
private val HTTP_READ_TIMEOUT = 30.seconds

private val stateKey = "dk.nota.flutterreadium.ReadiumReaderState"

private val currentPublicationUrlKey = "currentPublicationUrl"
private val ttsEnabledKey = "ttsEnabled"
private val audioEnabledKey = "audioEnabled"
private val syncAudioEnabledKey = "syncAudioEnabled"

private val epubEnabledKey = "epubEnabled"
private val pdfEnabledKey = "pdfEnabled"
private val comicEnabledKey = "comicEnabled"
private val ttsNavigatorStateKey = "ttsState"
private val audioNavigatorStateKey = "audioState"
private val syncAudioNavigatorStateKey = "syncAudioState"
private val epubNavigatorStateKey = "epubState"
private val decorationStyleKey = "decorationStyle"

internal fun shouldInjectMOColumnBreakCss(
    isMOActive: Boolean,
    preventMOColumnBreaks: Boolean,
): Boolean = isMOActive && preventMOColumnBreaks

// TODO: Support custom headers and authentication header for content files.

@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class, DelicateReadiumApi::class)
object ReadiumReader :
    TimebasedNavigator.TimebasedListener,
    EpubNavigator.VisualListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) {
    // App-owned preferences are reapplied to each Readium resource, never to a separate renderer.
    var appReaderStyleScript: String? = null
    var appSelectionToolbar = false
    var appSelectionMode: android.view.ActionMode? = null
    var appSelectionDidEnd: (() -> Unit)? = null
    fun appIsFixedLayout(): Boolean = currentPublication?.metadata?.layout == org.readium.r2.shared.publication.Layout.FIXED

    private fun appVisibleWebView(): android.webkit.WebView? {
        val root = currentReaderWidget?.getView() ?: return null
        var selected: android.webkit.WebView? = null
        var largest = 0L
        fun visit(node: android.view.View) {
            if (node is android.webkit.WebView) {
                val rect = android.graphics.Rect()
                if (node.isShown && node.getGlobalVisibleRect(rect)) {
                    val area = rect.width().toLong()*rect.height()
                    if (area > largest) { largest=area; selected=node }
                }
            } else if (node is android.view.ViewGroup) for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        visit(root)
        return selected
    }
    private fun appVisualPageKey(): String? = appVisibleWebView()?.let { "${it.url}|${it.scrollX}|${it.scrollY}" }
    var appPageTurnCrossedResource = false
    private suspend fun appEvaluateVisible(script: String): String? {
        val view = appVisibleWebView() ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { c ->
            view.evaluateJavascript(script) { value -> if (c.isActive) c.resumeWith(Result.success(value)) }
        }
    }

    private fun appMatchesResource(view: android.webkit.WebView?, href: String): Boolean {
        val path = android.net.Uri.decode(href.substringBefore('#').substringBefore('?')).trimStart('/')
        return path.isNotEmpty() && view?.url?.let { android.net.Uri.parse(it).path?.endsWith("/$path") } == true
    }

    private val appGeometryScript = """
        function geometry(){
            var scroll=document.scrollingElement||document.documentElement;
            return {x:scrollX,y:scrollY,w:innerWidth,h:innerHeight,
                sw:scroll.scrollWidth,sh:scroll.scrollHeight,dpr:devicePixelRatio};
        }
    """.trimIndent()

    private fun appLocatorAtGeometry(raw: String, view: android.webkit.WebView, geometry: JSONObject): String {
        val json = JSONObject(raw)
        if (!appMatchesResource(view, json.getString("href"))) {
            val link = currentPublication?.readingOrder?.filter { appMatchesResource(view, it.href.resolve().toString()) }
                ?.maxByOrNull { it.href.resolve().toString().length } ?: return raw
            json.put("href", link.href.resolve().toString())
            json.put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
            json.remove("locations")
            json.remove("text")
        }
        if (!appIsFixedLayout()) {
            val locations = json.optJSONObject("locations") ?: JSONObject().also { json.put("locations", it) }
            val scrolling = epubNavigator?.preferences?.scroll == true
            val extent = geometry.optDouble(if (scrolling) "sh" else "sw", 0.0)
            val viewport = geometry.optDouble(if (scrolling) "h" else "w", 1.0).coerceAtLeast(1.0)
            val offset = kotlin.math.abs(geometry.optDouble(if (scrolling) "y" else "x", 0.0))
            val progress = if (scrolling) offset / (extent - viewport).coerceAtLeast(1.0) else offset / extent.coerceAtLeast(viewport)
            locations.put("progression", progress.coerceIn(0.0, 1.0))
            if (!scrolling) {
                val page = (offset / viewport).roundToInt()
                val count = (extent / viewport).roundToInt().coerceAtLeast(page + 1)
                locations.put("currentPage", page).put("totalPages", count)
            }
        }
        return json.toString()
    }

    suspend fun appJumpToPage(raw: String, seek: Boolean): Boolean {
        val locator = Locator.fromJSON(JSONObject(raw)) ?: return false
        if (seek && !appIsFixedLayout() && appMatchesResource(appVisibleWebView(), locator.href.toString())) {
            val value = locator.locations.progression?.coerceIn(0.0, 1.0) ?: 0.0
            val applied = appEvaluateVisible("(function(){ if(typeof readium==='undefined'||!readium.scrollToPosition)return false; readium.scrollToPosition($value); return true; })();")
            if (applied == "true") return true
        }
        val navigator = epubNavigator ?: return false
        val publication = currentPublication ?: return false
        val target = publication.normalizeLocator(locator)
        if (!navigator.go(target, false)) return false
        return kotlinx.coroutines.withTimeoutOrNull(5000L) {
            while (!appMatchesResource(appVisibleWebView(), target.href.toString())) kotlinx.coroutines.delay(16)
            true
        } == true
    }

    suspend fun appLandPage(anchor: String): Boolean {
        val json = JSONObject(anchor)
        val captured = json.getJSONObject("locator")
        // These anchors came from this publication's rendered page. Re-resolving
        // a DOM range/text quote and normalizing it again is unnecessary: exact
        // pixel positioning follows once Readium has the right resource loaded.
        val navigation = JSONObject().put("href", captured.getString("href"))
            .put("type", captured.optString("type", "application/xhtml+xml"))
            .put("locations", JSONObject().put("progression", captured.optJSONObject("locations")?.optDouble("progression", 0.0) ?: 0.0))
        val locator = Locator.fromJSON(navigation) ?: return false
        val path = android.net.Uri.decode(locator.href.toString().substringBefore('#').substringBefore('?')).trimStart('/')
        val exactPosition = json.has("x") && json.has("y") && !appIsFixedLayout()
        val alreadyVisible = appVisibleWebView()?.url?.let { android.net.Uri.parse(it).path?.endsWith("/$path") } == true
        if (alreadyVisible && exactPosition) return true
        if (appIsFixedLayout()) visualGoToLocator(locator, false)
        else if (epubNavigator?.go(locator, false) != true) return false
        return kotlinx.coroutines.withTimeoutOrNull(5000L) {
            while (appVisibleWebView()?.url?.let { android.net.Uri.parse(it).path?.endsWith("/$path") } != true || (!exactPosition && epubNavigator?.currentLocator?.value?.href?.isEquivalent(locator.href) != true)) kotlinx.coroutines.delay(16)
            true
        } == true
    }

    suspend fun appPositionPage(anchor: String) {
        if (appIsFixedLayout()) return
        val json = JSONObject(anchor)
        val geometry = json.optJSONObject("geometry")
        val x = json.optDouble("x", 0.0)
        val y = json.optDouble("y", 0.0)
        val progress = json.optJSONObject("locator")?.optJSONObject("locations")?.optDouble("progression", 0.0) ?: 0.0
        appEvaluateVisible("""
            (function(){
                $appGeometryScript
                var target=$geometry,g=geometry();
                var same=!target||['w','h','sw','sh','dpr'].every(function(key){return Math.abs(target[key]-g[key])<1;});
                if(!same){readium.scrollToPosition($progress);return;}
                if(Math.abs(g.x-$x)>.25||Math.abs(g.y-$y)>.25)
                    window.scrollTo({left:$x,top:$y,behavior:'instant'});
            })();
        """.trimIndent())
    }

    fun appVisualPageState(): Map<String, String>? {
        val view = appVisibleWebView() ?: return null
        return mapOf("key" to "${view.url}|${view.scrollX}|${view.scrollY}|${view.width}|${view.height}", "url" to (view.url ?: ""))
    }

    fun appReplayTap(x: Double, y: Double) {
        val root = currentReaderWidget?.getView() ?: return
        val time = android.os.SystemClock.uptimeMillis()
        for (action in intArrayOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, time + if (action == android.view.MotionEvent.ACTION_UP) 8 else 0,
                action, (x * root.width).toFloat(), (y * root.height).toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { root.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    var appPreviewVersion = 0
        private set
    fun appInterruptPreview() { appPreviewVersion++ }

    suspend fun appWaitForPageReady(
        style: String?, anchor: String?, previewVersion: Int? = null,
        stable: Boolean = false, waitForPaint: Boolean = true,
    ): Boolean {
        val fixed = appIsFixedLayout()
        val expected = if (fixed || style == null) "null" else JSONObject.quote(style)
        val saved = if (fixed || anchor == null) null else JSONObject(anchor)
        val targetGeometry = saved?.optJSONObject("geometry")
        val targetX = saved?.optDouble("x", 0.0) ?: 0.0
        val targetY = saved?.optDouble("y", 0.0) ?: 0.0
        val progression = saved?.optJSONObject("locator")?.optJSONObject("locations")
            ?.optDouble("progression", 0.0) ?: 0.0
        var needsPosition = saved != null
        val requireStable = stable || anchor != null
        val ready = kotlinx.coroutines.withTimeoutOrNull(3000L) {
            var last: String? = null
            while (previewVersion == null || previewVersion == appPreviewVersion) {
                val sample = appEvaluateVisible("""
                    (function(){
                        $appGeometryScript
                        var expected=$expected;
                        if(!document.body||document.readyState==='loading'||
                            (document.fonts&&document.fonts.status!=='loaded')||
                            (expected!==null&&window.__wenkuAppliedStyle!==expected))return null;
                        var g=geometry(),target=$targetGeometry;
                        var same=!target||['w','h','sw','sh','dpr'].every(function(key){
                            return Math.abs(target[key]-g[key])<1;
                        });
                        if($needsPosition&&!same){
                            readium.scrollToPosition($progression);
                            return 'positioned';
                        }
                        if(($needsPosition||target)&&same&&
                            (Math.abs(g.x-$targetX)>.75||Math.abs(g.y-$targetY)>.75)){
                            window.scrollTo({left:$targetX,top:$targetY,behavior:'instant'});
                            return 'positioned';
                        }
                        if(window.__wenkuPageReady&&!window.__wenkuPageReady())return null;
                        g.paper=getComputedStyle(document.documentElement).backgroundColor;
                        return JSON.stringify(g);
                    })();
                """.trimIndent())
                if (sample == "\"positioned\"") {
                    needsPosition = false
                    last = null
                } else if (sample != null && sample != "null") {
                    needsPosition = false
                    if (!requireStable || sample == last) {
                        if (!fixed) {
                            val paper = JSONObject(org.json.JSONTokener(sample).nextValue().toString()).optString("paper")
                            val values = Regex("[0-9.]+").findAll(paper).map { it.value.toDouble() }.toList()
                            if (values.size >= 3 && (values.size == 3 || values[3] > 0.0)) {
                                appSetPaper(android.graphics.Color.rgb(values[0].toInt(), values[1].toInt(), values[2].toInt()))
                            }
                        }
                        return@withTimeoutOrNull true
                    }
                    last = sample
                } else {
                    last = null
                }
                kotlinx.coroutines.delay(16)
            }
            false
        } == true
        if (!ready) return false
        if (!waitForPaint) return true
        val view = appVisibleWebView() ?: return false
        // The WebView may be fully covered by our retained Flutter page. A
        // compositor acknowledgement is optional there; DOM/font/image readiness
        // above is authoritative, and software capture forces the actual draw.
        return kotlinx.coroutines.withTimeoutOrNull(64L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { c ->
                view.postVisualStateCallback(System.nanoTime(), object : android.webkit.WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { if (c.isActive) c.resumeWith(Result.success(true)) }
                })
            }
        } ?: true
    }

    private suspend fun appPageEdge(direction: Int): Pair<Int, Boolean> {
        val publication = currentPublication ?: return -1 to false
        // A locator notification alone is not proof that an edge is the end of
        // the book. Navigate the reading order, including untitled/image items.
        val visiblePath = appVisibleWebView()?.url?.let { android.net.Uri.parse(it).path }
        val visibleIndex = publication.readingOrder.indices.filter { i ->
            val path=android.net.Uri.decode(publication.readingOrder[i].href.resolve().toString().substringBefore('#').substringBefore('?')).trimStart('/')
            !path.isEmpty() && visiblePath?.endsWith("/$path") == true
        }.maxByOrNull { publication.readingOrder[it].href.resolve().toString().length }
        val index = visibleIndex ?: epubNavigator?.currentLocator?.value?.let { at -> publication.readingOrder.indexOfFirst { it.href.resolve().isEquivalent(at.href) } } ?: -1
        val geometry = if (appIsFixedLayout()) null else runCatching {
            val verticalScroll = epubNavigator?.preferences?.scroll == true
            val raw = appEvaluateVisible("""
                (function(){var e=document.scrollingElement||document.documentElement,v=$verticalScroll;
                return JSON.stringify({position:Math.abs(v?scrollY:scrollX),maximum:Math.max(0,v?e.scrollHeight-innerHeight:e.scrollWidth-innerWidth)});})();
            """.trimIndent()) ?: return@runCatching null
            JSONObject(org.json.JSONTokener(raw).nextValue().toString())
        }.getOrNull()
        val atEdge = geometry?.let {
            if (direction > 0) it.optDouble("maximum")-it.optDouble("position") <= 2.0
            else it.optDouble("position") <= 2.0
        } == true
        return index to atEdge
    }

    suspend fun appCanTurnPage(direction: Int): Boolean {
        val publication = currentPublication ?: return false
        val (index, edge) = appPageEdge(direction)
        return !(edge || appIsFixedLayout()) || index < 0 || index + (if (direction > 0) 1 else -1) in publication.readingOrder.indices
    }

    suspend fun appTurnPage(direction: Int, animated: Boolean): Boolean {
        val navigator = epubNavigator ?: return false
        val publication = currentPublication ?: return false
        val previous = navigator.currentLocator?.value
        appPageTurnCrossedResource = false
        val previousVisual = appVisualPageKey()
        val previousUrl = appVisibleWebView()?.url
        val (index, atEdge) = appPageEdge(direction)
        if (atEdge && index >= 0) {
            val next = index + if (direction > 0) 1 else -1
            if (next !in publication.readingOrder.indices) return false
            val link = publication.readingOrder[next]
            val json = JSONObject().put("href", link.href.resolve().toString())
                .put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                .put("locations", JSONObject().put("progression", if (direction > 0) 0.0 else 1.0))
            val target = Locator.fromJSON(json) ?: return false
            appPageTurnCrossedResource = true
            return appLandPage(JSONObject().put("locator", target.toJSON()).toString())
        } else {
            if (direction > 0) visualGoForward(animated) else visualGoBackward(animated)
        }
        val changed = kotlinx.coroutines.withTimeoutOrNull(if (appPageTurnCrossedResource) 3000L else 1200L) {
            while (navigator.currentLocator?.value == previous && appVisualPageKey() == previousVisual) kotlinx.coroutines.delay(16)
            true
        } == true
        if (appVisibleWebView()?.url != previousUrl) appPageTurnCrossedResource = true
        if (changed || navigator.currentLocator?.value != previous || appVisualPageKey() != previousVisual) return true
        val (settledIndex, settledEdge) = appPageEdge(direction)
        if (settledEdge && settledIndex >= 0) {
            val adjacent = settledIndex + if (direction > 0) 1 else -1
            if (adjacent in publication.readingOrder.indices) {
                val link = publication.readingOrder[adjacent]
                val target = JSONObject().put("href", link.href.resolve().toString())
                    .put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", if (direction > 0) 0.0 else 1.0))
                appPageTurnCrossedResource = true
                return appLandPage(JSONObject().put("locator", target).toString())
            }
        }
        return false
    }
    suspend fun appApplyStyle(script: String): String? {
        appReaderStyleScript = script
        if (currentPublication?.metadata?.layout == org.readium.r2.shared.publication.Layout.FIXED) return null
        appEvaluateAllWebViews(script)
        return null
    }

    fun appEvaluateAllWebViews(script: String) {
        fun apply(node: android.view.View) {
            if (node is android.webkit.WebView) node.evaluateJavascript(script, null)
            else if (node is android.view.ViewGroup) for (i in 0 until node.childCount) apply(node.getChildAt(i))
        }
        currentReaderWidget?.getView()?.let { apply(it) }
    }

    fun appSetPaper(color: Int) {
        val root = currentReaderWidget?.getView() ?: return
        var view: android.view.View? = appVisibleWebView()
        while (view != null) {
            if ((view.background as? android.graphics.drawable.ColorDrawable)?.color != color)
                view.setBackgroundColor(color)
            if (view === root) return
            view = view.parent as? android.view.View
        }
        if ((root.background as? android.graphics.drawable.ColorDrawable)?.color != color)
            root.setBackgroundColor(color)
    }

    fun appSetViewportInsets(top: Double, bottom: Double, minimumStatus: Double) =
        currentReaderWidget?.setAppViewportInsets(top, bottom, minimumStatus)

    fun appContentTop(): Double {
        val root = currentReaderWidget?.getView() ?: return 0.0
        val page = appVisibleWebView() ?: return 0.0
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val pageLocation = IntArray(2).also(page::getLocationInWindow)
        return (pageLocation[1] - rootLocation[1]) / root.resources.displayMetrics.density.toDouble()
    }

    fun appOwnsPageGestures(): Boolean = appSelectionToolbar &&
        epubNavigator != null && epubNavigator?.preferences?.scroll != true &&
        appSelectionMode == null

    // Snapshot identity must not resolve DOM selectors or traverse the TOC.
    fun appPageKeyJson(): String? = epubNavigator?.currentLocator?.value?.toJSON()?.toString()

    suspend fun appCapturePage(
        includeBands: Boolean = false, vertical: Boolean = false,
        allowLateCapture: Boolean = false, fixedLayoutPaper: Int? = null,
    ): Map<String, Any>? {
        // Use the selected resource for DOM geometry, but draw its parent tree:
        // ViewGroup.drawChild supplies the WebView's native scroll translation.
        // Calling webView.draw directly loses that translation after TOC jumps.
        val root = currentReaderWidget?.getView() ?: return null
        val view = appVisibleWebView() ?: return null
        if (root.width <= 0 || root.height <= 0) return null
        val locator = appPageKeyJson() ?: return null
        val visualKey = appVisualPageKey()
        kotlinx.coroutines.withTimeoutOrNull(80) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
                view.postVisualStateCallback(System.nanoTime(), object : android.webkit.WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { if (c.isActive) c.resumeWith(Result.success(Unit)) }
                })
            }
        }
        val metadata = kotlinx.coroutines.withTimeoutOrNull(if (allowLateCapture) 500L else 200L) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { c ->
                view.evaluateJavascript("""
                    (function(){
                        if(document.fonts && document.fonts.status!=='loaded')return null;
                        if(window.__wenkuPageReady&&!window.__wenkuPageReady())return null;
                        $appGeometryScript
                        var w=innerWidth,h=innerHeight,v=$vertical,extent=v?w:h;
                        var imageOnly=!!document.querySelector('img,svg image') &&
                            !(document.body.innerText||'').trim() &&
                            !document.querySelector('svg text,canvas,video');
                        var hasIllustration=imageOnly||Array.from(document.querySelectorAll('img,svg image')).some(function(image){
                            var rect=image.getBoundingClientRect();
                            return rect.right>0&&rect.left<w&&rect.bottom>0&&rect.top<h&&rect.width>0&&rect.height>0;
                        });
                        var gaps=[];
                        if($includeBands&&!imageOnly){
                            var groups=new Map(), visible=new Map(), range=document.createRange();
                            var block='p,h1,h2,h3,h4,h5,h6,li,pre,blockquote,figcaption,td,th,div,figure,body';
                            function onscreen(r){return r.right>0&&r.left<w&&r.bottom>0&&r.top<h;}
                            function add(owner,rect){
                                if(!onscreen(rect))return;
                                var start=Math.max(0,v?rect.left:rect.top),end=Math.min(extent,v?rect.right:rect.bottom);
                                if(end<=start)return;
                                var group=groups.get(owner);
                                if(group){group[0]=Math.min(group[0],start);group[1]=Math.max(group[1],end);}
                                else groups.set(owner,[start,end]);
                            }
                            var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT), node;
                            while(node=walker.nextNode()){
                                var parent=node.parentElement;
                                if(!parent||!node.textContent.trim()||parent.closest('script,style,[data-readium]'))continue;
                                var owner=parent.closest(block)||parent;
                                var show=visible.get(parent);
                                if(show===undefined){
                                    show=Array.from(parent.getClientRects()).some(onscreen);
                                    visible.set(parent,show);
                                }
                                if(!show)continue;
                                range.selectNodeContents(node);
                                for(var rect of range.getClientRects())add(owner,rect);
                            }
                            for(var image of document.querySelectorAll('img,svg,table,video,canvas')){
                                var owner=image.closest('p,figcaption,figure')||image;
                                for(var rect of image.getClientRects())add(owner,rect);
                            }
                            var spans=Array.from(groups,function(entry){
                                var owner=entry[0], ink=entry[1];
                                if(owner.localName!=='p')return ink;
                                // The CSS block box is the paragraph boundary.
                                // Font ascent/descent can extend past that box,
                                // especially with a tight line height.
                                var start=extent,end=0;
                                for(var rect of owner.getClientRects()){
                                    if(!onscreen(rect))continue;
                                    start=Math.min(start,v?rect.left:rect.top);
                                    end=Math.max(end,v?rect.right:rect.bottom);
                                }
                                return end>start?[Math.max(0,start),Math.min(extent,end)]:ink;
                            }).sort(function(a,b){return a[0]-b[0];});
                            var previousEnd=-1;
                            for(var span of spans){
                                // CSS text boxes can touch or slightly overlap even
                                // when two distinct paragraphs have clear pixel rows.
                                if(previousEnd>=0&&previousEnd-span[0]<=4){
                                    var start=Math.min(previousEnd,span[0])-3;
                                    var end=Math.max(previousEnd,span[0])+3;
                                    gaps.push([Math.max(0,start)/extent,Math.min(extent,end)/extent]);
                                }
                                previousEnd=Math.max(previousEnd,span[1]);
                            }
                        }
                        var color=getComputedStyle(document.documentElement).backgroundColor.match(/[\d.]+/g)||[255,255,255];
                        return JSON.stringify({gaps:gaps,color:color.slice(0,3).map(Number),geometry:geometry(),
                            imageOnly:imageOnly,hasIllustration:hasIllustration});
                    })();
                """.trimIndent()) { value -> if (c.isActive) c.resumeWith(Result.success(value)) }
            }
        } ?: return null
        if (metadata == "null" || visualKey != appVisualPageKey()) return null
        val info = JSONObject(org.json.JSONTokener(metadata).nextValue().toString())
        val geometry = info.getJSONObject("geometry")
        val rootPosition = IntArray(2).also(root::getLocationInWindow)
        val pagePosition = IntArray(2).also(view::getLocationInWindow)
        val visibleLocator = appLocatorAtGeometry(locator, view, geometry)
        val color = info.getJSONArray("color")
        val paper = fixedLayoutPaper ?: android.graphics.Color.rgb(color.getInt(0), color.getInt(1), color.getInt(2))
        val imageOnly = info.optBoolean("imageOnly")
        // Preserve text at device resolution. Illustration transitions can
        // share smaller textures while Readium keeps the full-size live page.
        val scale = if (imageOnly) minOf(1.0, 1600.0 / maxOf(root.width, root.height)) else 1.0
        val bitmap = android.graphics.Bitmap.createBitmap(
            (root.width * scale).roundToInt().coerceAtLeast(1),
            (root.height * scale).roundToInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val axis = if (vertical) 0 else 1
        val bandOffset = pagePosition[axis] - rootPosition[axis]
        val bandExtent = if (vertical) view.width else view.height
        return try {
            val canvas = android.graphics.Canvas(bitmap)
            appSetPaper(paper)
            canvas.drawColor(paper)
            canvas.scale(bitmap.width.toFloat() / root.width, bitmap.height.toFloat() / root.height)
            root.draw(canvas)
            val identity = "${view.url}|${view.scrollX}|${view.scrollY}|${root.width}|${root.height}"
            withContext(Dispatchers.Default) {
                val width = bitmap.width
                val height = bitmap.height
                // Android's software ARGB_8888 buffer is RGBA byte order. Copy
                // once in native code rather than allocating an IntArray and
                // converting every pixel in a Kotlin loop.
                val rgba = ByteArray(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(rgba))
                val length = if (vertical) width else height
                val breadth = if (vertical) height else width
                val lineStride = if (vertical) 4 else width * 4
                val pixelStride = if (vertical) width * 4 else 4
                val empty = ByteArray(length) { -1 }
                fun differs(a: Int, b: Int): Boolean =
                    kotlin.math.abs((rgba[a].toInt() and 255) - (rgba[b].toInt() and 255)) > 12 ||
                    kotlin.math.abs((rgba[a+1].toInt() and 255) - (rgba[b+1].toInt() and 255)) > 12 ||
                    kotlin.math.abs((rgba[a+2].toInt() and 255) - (rgba[b+2].toInt() and 255)) > 12
                fun blank(line: Int): Boolean {
                    if (line !in 0 until length) return false
                    if (empty[line] >= 0) return empty[line].toInt() == 1
                    val first = line * lineStride
                    for (i in 1 until breadth) {
                        if (differs(first + i * pixelStride, first)) {
                            empty[line]=0; return false
                        }
                    }
                    empty[line]=1; return true
                }
                fun separatesInk(line: Int): Boolean {
                    if (blank(line - 1) || blank(line)) return true
                    val above = (line - 1) * lineStride
                    val below = line * lineStride
                    // A tight layout may leave no entirely blank row. Accept a
                    // DOM boundary only if no ink is connected across the cut.
                    for (i in 1 until breadth) {
                        if (!differs(above + i * pixelStride, above)) continue
                        for (j in maxOf(0, i - 1)..minOf(breadth - 1, i + 1)) {
                            if (differs(below + j * pixelStride, below)) return false
                        }
                    }
                    return true
                }
                val cuts = mutableListOf(0.0)
                val gaps = info.getJSONArray("gaps")
                for (i in 0 until gaps.length()) {
                    val gap = gaps.getJSONArray(i)
                    val low = kotlin.math.ceil(bandOffset + gap.getDouble(0)*bandExtent).toInt().coerceIn(1,length-1)
                    val high = kotlin.math.floor(bandOffset + gap.getDouble(1)*bandExtent).toInt().coerceIn(1,length-1)
                    if (low > high) continue
                    val middle = (low+high)/2
                    var found = -1
                    for (distance in 0..(high-low)) {
                        for (line in intArrayOf(middle-distance,middle+distance)) {
                            if (line in low..high && separatesInk(line)) { found=line; break }
                        }
                        if (found >= 0) break
                    }
                    // An uncertain line is kept intact with its neighbour.
                    // Never trust a stale DOM midpoint enough to slice ink.
                    if (found >= 0 && found.toDouble()/length > cuts.last()) cuts.add(found.toDouble()/length)
                }
                cuts.add(1.0)
                val bands = cuts.zipWithNext { a,b -> if (vertical) listOf(a,0.0,b,1.0) else listOf(0.0,a,1.0,b) }
                val returnBottoms = when {
                    imageOnly -> listOf(1.0)
                    includeBands && !vertical && cuts.size > 2 -> cuts.drop(1)
                    else -> appHorizontalCuts(rgba, width, height)
                }
                mapOf("width" to width, "height" to height, "pixels" to rgba,
                    "sampled" to (scale < 1.0),
                    "hasIllustration" to info.optBoolean("hasIllustration"),
                    "bands" to bands, "returnBottoms" to returnBottoms,
                    "color" to listOf(android.graphics.Color.red(paper), android.graphics.Color.green(paper), android.graphics.Color.blue(paper)),
                    "locator" to visibleLocator, "identity" to identity,
                    "anchor" to JSONObject().put("locator", JSONObject(visibleLocator)).put("geometry", geometry)
                        .put("x", geometry.getDouble("x")).put("y", geometry.getDouble("y")).toString())
            }
        } finally { bitmap.recycle() }
    }

    // Separate horizontal rows in the rendered bitmap for the foreground
    // animation when paragraph geometry was not captured. Validate the
    // sampled gaps across the full width before cutting the bitmap.
    private fun appHorizontalCuts(rgba: ByteArray, width: Int, height: Int): List<Double> {
        val stride = (width / 96).coerceAtLeast(1)
        fun quiet(row: Int, sampleSpacing: Int): Boolean {
            val first = row * width * 4
            val r = rgba[first].toInt() and 255
            val g = rgba[first + 1].toInt() and 255
            val b = rgba[first + 2].toInt() and 255
            for (x in sampleSpacing until width step sampleSpacing) {
                val pixel = first + x * 4
                if (kotlin.math.abs((rgba[pixel].toInt() and 255) - r) > 12 ||
                    kotlin.math.abs((rgba[pixel + 1].toInt() and 255) - g) > 12 ||
                    kotlin.math.abs((rgba[pixel + 2].toInt() and 255) - b) > 12) return false
            }
            return true
        }
        val bottoms = mutableListOf<Double>()
        var hadInk = false
        var gap = -1
        for (row in 0 until height) {
            if (quiet(row, stride)) {
                if (hadInk && gap < 0) gap = row
            } else {
                if (gap >= 0 && row - gap >= 3) {
                    val cut = (gap + row) / 2
                    if (quiet(cut - 1, 1) && quiet(cut, 1)) bottoms.add(cut.toDouble() / height)
                }
                hadInk = true
                gap = -1
            }
        }
        bottoms.add(1.0)
        return bottoms
    }

    private var appRef: WeakReference<Application>? = null

    private var activityRef: WeakReference<Activity>? = null

    /**
     * The host [FragmentActivity], if available. Sourced from the plugin's ActivityAware
     * binding rather than a PlatformView's view context, which is only the Activity under
     * Texture-Layer Hybrid Composition — under Hybrid Composition it is a non-Activity context.
     */
    val fragmentActivity: FragmentActivity?
        get() = activityRef?.get() as? FragmentActivity

    private var timedBasedStateEventChannel: TimedBasedStateEventChannel? = null

    private var textLocatorEventChannel: TextLocatorEventChannel? = null

    private var readiumReaderStatusEventChannel: ReadiumReaderStatusEventChannel? = null

    private var errorChannel: ReadiumErrorEventChannel? = null

    private var narrationSyncEventChannel: NarrationSyncEventChannel? = null

    /**
     * Runtime narration-sync flag. `true` means the visual reader follows the audio cue
     * automatically; `false` means the user has entered manual mode (visual stays, audio plays).
     * Seeded from [FlutterEpubPreferences.disableSynchronization]: enabled == !disableSynchronization.
     */
    private var narrationSyncEnabled: Boolean = true

    private var readerViewRef: WeakReference<ReadiumReaderWidget>? = null

    private var savedStateRef: WeakReference<SavedStateRegistry>? = null

    // in-memory cached state
    private val state = mutableMapOf<String, Any?>()

    private val currentReadiumTimebasedState =
        MutableStateFlow(ReadiumTimebasedState.none())

    private val currentTextLocator = MutableStateFlow<Locator?>(null)

    private var defaultHttpHeaders = mutableMapOf<String, String>()

    var decorationStyle: FlutterDecorationPreferences
        get() =
            state[decorationStyleKey] as? FlutterDecorationPreferences
                ?: FlutterDecorationPreferences()
        set(value) {
            state[decorationStyleKey] = value
        }

    private val httpClient by lazy {
        DefaultHttpClient(
            connectTimeout = HTTP_CONNECT_TIMEOUT,
            readTimeout = HTTP_READ_TIMEOUT,
            callback =
                object : DefaultHttpClient.Callback {
                    override suspend fun onStartRequest(request: HttpRequest): HttpTry<HttpRequest> {
                        val requestWithHeaders =
                            request.copy {
                                defaultHttpHeaders.toMap().forEach { (key, value) ->
                                    setHeader(key, value)
                                }
                            }
                        return Try.success(requestWithHeaders)
                    }
                },
        )
    }

    private var assetRetrieverCache: AssetRetriever? = null

    private val assetRetriever: AssetRetriever
        get() {
            if (assetRetrieverCache == null) {
                assetRetrieverCache = AssetRetriever(context.contentResolver, httpClient)
            }

            return assetRetrieverCache!!
        }

    private var publicationOpenerCache: PublicationOpener? = null

    private var ttsNavigator: TTSNavigator? = null

    private var audiobookNavigator: AudiobookNavigator? = null
    private var syncAudiobookNavigator: SyncAudiobookNavigator? = null

    /** TTS-only: handles page-break elements during content iteration. Not [preventMOColumnBreaksActive] (MO-only). */
    private var pageBreakIteratorFactory: PageBreakSkippingContentIteratorFactory? = null

    /**
     * Mirrors `EPUBPreferences.preventMOColumnBreaks`. Defaults to `true`.
     * Consumer can opt out via preferences.
     */
    private var preventMOColumnBreaksActive: Boolean = true

    /** True when a Media Overlay (sync-narration) navigator is active. */
    val isMOActive: Boolean
        get() = syncAudiobookNavigator != null

    /** Whether MO page-change reinjection should run for the current reader state. */
    internal val shouldInjectMOColumnBreakCssOnPageChange: Boolean
        get() = shouldInjectMOColumnBreakCss(isMOActive, preventMOColumnBreaksActive)

    private val timebasedNavigator: TimebasedNavigator<*>?
        get() = audiobookNavigator ?: syncAudiobookNavigator ?: ttsNavigator

    private var visualNavigator: FlutterVisualNavigator? = null

    /** True when the current visual navigator is a PDF navigator. */
    val isPdf: Boolean
        get() = visualNavigator is PdfNavigator

    /** True when the current visual navigator is a comic (CBZ / DiViNa) navigator. */
    val isComic: Boolean
        get() = visualNavigator is ComicNavigator

    /** Typed accessor for EPUB-specific operations. */
    private val epubNavigator: EpubNavigator?
        get() = visualNavigator as? EpubNavigator

    /** Typed accessor for PDF-specific operations. */
    private val pdfNavigator: PdfNavigator?
        get() = visualNavigator as? PdfNavigator

    /** Typed accessor for comic (CBZ / DiViNa) navigator. */
    private val comicNavigator: ComicNavigator?
        get() = visualNavigator as? ComicNavigator

    private var _audioPreferences: FlutterAudioPreferences = FlutterAudioPreferences()

    private var currentTimebasedPublicationDurationMs: Double? = null

    /** Current audio preferences (defaults if audio hasn't been enabled yet). */
    val audioPreferences: FlutterAudioPreferences
        get() = _audioPreferences

    /**
     * The PublicationFactory is used to open publications.
     */
    private val publicationOpener: PublicationOpener
        get() {
            if (publicationOpenerCache == null) {
                publicationOpenerCache =
                    PublicationOpener(
                        publicationParser =
                            DefaultPublicationParser(
                                context,
                                assetRetriever = assetRetriever,
                                httpClient = httpClient,
                                pdfFactory = PdfiumDocumentFactory(context),
                            ),
                    )
            }

            return publicationOpenerCache!!
        }

    // Initialize from plugin or anywhere you have an Application or Context.
    fun attach(
        activity: Activity,
        messenger: BinaryMessenger,
    ) {
        unwrapToApplication(activity)?.let { appRef = WeakReference(it) }
        activityRef = WeakReference(activity)

        timedBasedStateEventChannel?.dispose()
        timedBasedStateEventChannel = TimedBasedStateEventChannel(messenger)

        textLocatorEventChannel?.dispose()
        textLocatorEventChannel = TextLocatorEventChannel(messenger)

        readiumReaderStatusEventChannel?.dispose()
        readiumReaderStatusEventChannel = ReadiumReaderStatusEventChannel(messenger)

        errorChannel?.dispose()
        errorChannel = ReadiumErrorEventChannel(messenger)

        narrationSyncEventChannel?.dispose()
        narrationSyncEventChannel = NarrationSyncEventChannel(messenger)

        // store weak ref only
        (activity as? SavedStateRegistryOwner)?.savedStateRegistry?.let {
            savedStateRef = WeakReference(it)
            it.registerSavedStateProvider(stateKey) {
                storeState()
            }

            restoreState(it.consumeRestoredStateForKey(stateKey))
        }

        currentReadiumTimebasedState
            .onEach {
                PluginLog.d(
                    TAG,
                    "currentTimebasedReaderState: ${
                        jsonEncode(
                            it.toJSON(),
                        )
                    }",
                )

                timedBasedStateEventChannel?.sendEvent(it)
            }.launchIn(this)
    }

    private fun storeState(): Bundle {
        if (currentPublicationUrl == null) {
            // No current publication, no state.
            return Bundle()
        }

        return Bundle().apply {
            putString(currentPublicationUrlKey, currentPublicationUrl)
            putBoolean(epubEnabledKey, epubNavigator != null)
            putBundle(epubNavigatorStateKey, epubNavigator?.storeState())
            // PdfNavigatorFragment in kotlin-toolkit does not support
            // process-death restoration (`RestorationNotSupportedException` from
            // its onResume). We record the boolean for symmetry but skip the
            // serialised state bundle — the widget reopens fresh on restore.
            putBoolean(pdfEnabledKey, pdfNavigator != null)
            // ImageNavigatorFragment also throws RestorationNotSupportedException on
            // process-death restore — same treatment as PDF: record the flag only.
            putBoolean(comicEnabledKey, comicNavigator != null)
            putBoolean(ttsEnabledKey, ttsNavigator != null)
            putBundle(ttsNavigatorStateKey, ttsNavigator?.storeState())
            putBoolean(audioEnabledKey, audiobookNavigator != null)
            putBundle(audioNavigatorStateKey, audiobookNavigator?.storeState())
            putBoolean(syncAudioEnabledKey, syncAudiobookNavigator != null)
            putBundle(syncAudioNavigatorStateKey, syncAudiobookNavigator?.storeState())
            putParcelable(decorationStyleKey, decorationStyle)
        }
    }

    private fun restoreState(bundle: Bundle?) {
        if (bundle == null) {
            PluginLog.d(TAG, "::restoreState nothing to restore")
            return
        }

        PluginLog.d(TAG, "::restoreState $bundle")
        val pubUrl = bundle.getString(currentPublicationUrlKey)
        if (pubUrl == null) {
            PluginLog.d(TAG, "::storeState - currentPublicationUrl - not restored")
            return
        }

        PluginLog.d(TAG, "::restoreState - currentPublicationUrl - $pubUrl")
        launch {
            val pub =
                openPublication(pubUrl).getOrElse {
                    PluginLog.d(TAG, "::restoreState - failed to restore publication")
                    // TODO: Handle this somehow
                    return@launch
                }

            decorationStyle =
                bundle.getParcelable(decorationStyleKey) as? FlutterDecorationPreferences
                    ?: FlutterDecorationPreferences()

            if (bundle.getBoolean(epubEnabledKey)) {
                PluginLog.d(TAG, "::storeState - restore epub navigator")
                bundle.getBundle(epubNavigatorStateKey)?.let { state ->
                    visualNavigator =
                        EpubNavigator.restoreState(pub, this@ReadiumReader, state).apply {
                            initNavigator()
                            PluginLog.d(TAG, "::storeState - epubNavigator restored")
                            setDecorationStyle(decorationStyle)
                        }
                }
            }

            // We deliberately do not restore the PDF navigator across process
            // death — upstream PdfNavigatorFragment doesn't support it. The
            // widget will re-enable PDF on next attach using the locator that
            // Dart re-supplies via creation params.
            if (bundle.getBoolean(pdfEnabledKey)) {
                PluginLog.d(TAG, ":storeState - PDF was active; skipping restore (unsupported by PdfNavigatorFragment)")
            }

            // ImageNavigatorFragment also throws RestorationNotSupportedException on
            // process-death restore — same treatment as PDF: skip and let the widget
            // re-enable on next attach.
            if (bundle.getBoolean(comicEnabledKey)) {
                PluginLog.d(TAG, "::storeState - comic was active; skipping restore (unsupported by ImageNavigatorFragment)")
            }

            if (bundle.getBoolean(ttsEnabledKey)) {
                // Restore TTS navigator
                PluginLog.d(TAG, "::storeState - restore tts navigator")
                bundle.getBundle(ttsNavigatorStateKey)?.let { state ->
                    ttsNavigator =
                        TTSNavigator.restoreState(pub, this@ReadiumReader, state).apply {
                            initNavigator()
                            PluginLog.d(TAG, "::storeState - ttsNavigator restored")
                        }
                }
            }

            if (bundle.getBoolean(audioEnabledKey)) {
                // Restore Audio navigator
                PluginLog.d(TAG, "::storeState - restore audio navigator")
                bundle.getBundle(audioNavigatorStateKey)?.let { state ->
                    audiobookNavigator =
                        AudiobookNavigator.restoreState(pub, this@ReadiumReader, state).apply {
                            initNavigator()
                            PluginLog.d(TAG, "::storeState - audioNavigator restored")
                        }
                    currentTimebasedPublicationDurationMs = computePublicationDurationMs(pub.readingOrder.map { it.duration })
                }
            } else if (bundle.getBoolean(syncAudioEnabledKey)) {
                // Restore Sync Audio navigator
                PluginLog.d(TAG, "::storeState - restore sync audio navigator")
                val (ap, mediaOverlays) = pub.makeSyncAudiobook()
                if (mediaOverlays != null) {
                    bundle.getBundle(syncAudioNavigatorStateKey)?.let { state ->
                        syncAudiobookNavigator =
                            SyncAudiobookNavigator
                                .restoreState(
                                    ap,
                                    mediaOverlays,
                                    this@ReadiumReader,
                                    state,
                                ).apply {
                                    initNavigator()
                                    PluginLog.d(TAG, "::storeState - syncAudioNavigator restored")
                                }
                        currentTimebasedPublicationDurationMs = computePublicationDurationMs(ap.readingOrder.map { it.duration })
                    }
                } else {
                    PluginLog.e(TAG, "::storeState - no media overlays for sync audio navigator")
                }
            }

            PluginLog.d(TAG, "consumeRestoredStateForKey - 2 - $currentPublication")
        }
    }

    fun detach() {
        closePublication()

        appRef?.clear()
        appRef = null

        activityRef?.clear()
        activityRef = null

        savedStateRef?.clear()
        savedStateRef = null

        assetRetrieverCache = null
        publicationOpenerCache = null

        readerViewRef?.clear()
        readerViewRef = null

        timedBasedStateEventChannel?.dispose()
        timedBasedStateEventChannel = null

        textLocatorEventChannel?.dispose()
        textLocatorEventChannel = null

        readiumReaderStatusEventChannel?.dispose()
        readiumReaderStatusEventChannel = null

        errorChannel?.dispose()
        errorChannel = null

        narrationSyncEventChannel?.dispose()
        narrationSyncEventChannel = null

        coroutineContext.cancelChildren()
    }

    // Safe getter — returns applicationContext or throws if not available.
    val application: Application
        get() =
            appRef?.get()
                ?: throw IllegalStateException("Application not initialized. Call ReadiumReader.attach(...) first.")

    var currentReaderWidget: ReadiumReaderWidget?
        get() = readerViewRef?.get()
        set(value) {
            readerViewRef = value?.let { WeakReference(it) }
        }

    /** Selection actions configured from Dart. Used by EpubReaderFragment to build ActionMode menu. */
    var selectionActions: List<SelectionActionConfig> = emptyList()

    private val context: Context
        get() = application.applicationContext

    private var _currentPublication: Publication? = null
    val currentPublication: Publication?
        get() = _currentPublication
    var currentPublicationUrl
        get() = state[currentPublicationUrlKey] as String?
        set(value) {
            state[currentPublicationUrlKey] = value
        }

    /***
     * For EPUB profile, maps document [Url] to a list of all the cssSelectors in the document.
     *
     * This is used to find the current toc item.
     */
    private var currentPublicationCssSelectorMap: MutableMap<Url, List<String>>? = null

    /**
     * Sets the headers used in the HTTP requests for fetching publication resources, including
     * resources in already created `Publication` objects.
     *
     * @param headers a map of HTTP header key value pairs.
     */
    fun setDefaultHttpHeaders(headers: Map<String, String>) {
        defaultHttpHeaders.clear()
        defaultHttpHeaders.putAll(headers)
    }

    /**
     * Policy for the audio-stream error recovery loop (retry attempts, backoff, stall
     * detection). Read by [dk.nota.flutterreadium.navigators.AudiobookNavigator] at
     * construction time — applies to the next-opened publication and to any in-flight
     * recovery loop, not to an already-running attempt sequence.
     */
    var audioRecoveryPolicy: AudioRecoveryPolicy = AudioRecoveryPolicy()

    private suspend fun assetToPublication(
        asset: Asset,
        transformingContainerFactory: ((Container<Resource>) -> Container<Resource>)? = null,
    ): Try<Publication, OpenError> {
        val publication: Publication =
            publicationOpener
                .open(asset, allowUserInteraction = true, onCreatePublication = {
                    container = transformingContainerFactory?.let { it(container) } ?: container
                    if (manifest.conformsTo(Publication.Profile.EPUB)) {
                        val factory = PageBreakSkippingContentIteratorFactory()
                        pageBreakIteratorFactory = factory
                        servicesBuilder.contentServiceFactory =
                            DefaultContentService.createFactory(
                                listOf(factory),
                            )
                    }
                })
                .getOrElse { err: OpenError ->
                    fun unwrapCause(e: org.readium.r2.shared.util.Error?): String =
                        when (e) {
                            null -> "null"
                            is ThrowableError<*> -> "${e.message} | throwable: ${e.throwable}"
                            else -> "${e.message} | cause: ${unwrapCause(e.cause)}"
                        }
                    val detail =
                        when (err) {
                            is OpenError.Reading -> "Reading error: ${unwrapCause(err.cause)}"
                            is OpenError.FormatNotSupported -> "FormatNotSupported: ${unwrapCause(err.cause)}"
                            else -> err.toString()
                        }
                    PluginLog.e(TAG, "Error opening publication: $detail")
                    asset.close()
                    return failure(err)
                }
        PluginLog.d(TAG, "Open publication success: $publication")
        return Try.success(publication)
    }

    /**
     * Load a publication from a String url.
     * Note: Remember to close the publication to avoid leaks.
     */
    suspend fun loadPublication(pubUrl: String?): Try<Publication, PublicationError> {
        if (pubUrl == null) {
            return failure(
                PublicationError.Unexpected(
                    DebugError("missing argument"),
                ),
            )
        }

        return AbsoluteUrl.invoke(pubUrl)?.let { pubUrl -> loadPublication(pubUrl) } ?: failure(
            PublicationError.Unexpected(
                DebugError("Invalid Url"),
            ),
        )
    }

    /**
     * Load a publication from an AbsoluteUrl
     *
     * Note: Remember to close the publication to avoid leaks.
     */
    suspend fun loadPublication(
        pubUrl: AbsoluteUrl,
        transformingContainerFactory: ((Container<Resource>) -> Container<Resource>)? = null,
    ): Try<Publication, PublicationError> {
        if (currentPublicationUrl == pubUrl.toString()) {
            // Current publication is the same as the one we are trying to load, return it.
            currentPublication?.let {
                return Try.success(it)
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                // TODO: should client provide mediaType to assetRetriever?
                val asset: Asset =
                    assetRetriever
                        .retrieve(pubUrl)
                        .getOrElse { error: AssetRetriever.RetrieveUrlError ->
                            PluginLog.e(TAG, "Error retrieving asset: $error from url:$pubUrl")
                            return@withContext failure(PublicationError.invoke(error))
                        }
                val pub =
                    assetToPublication(
                        asset,
                        transformingContainerFactory,
                    ).getOrElse { error: OpenError ->
                        PluginLog.e(
                            TAG,
                            "Error loading asset to Publication object: $error from url:$pubUrl",
                        )
                        return@withContext failure(PublicationError.invoke(error))
                    }
                PluginLog.d(TAG, "Opened publication = ${pub.metadata.identifier} from url:$pubUrl")
                return@withContext Try.success(pub)
            } catch (e: Throwable) {
                return@withContext failure(PublicationError.Unexpected(ThrowableError(e)))
            }
        }
    }

    /**
     * Open a publication and set it as the current publication.
     */
    suspend fun openPublication(pubUrl: String?): Try<Publication, PublicationError> {
        if (pubUrl == null) {
            return failure(
                PublicationError.Unexpected(
                    DebugError("missing argument"),
                ),
            )
        }

        return AbsoluteUrl.invoke(pubUrl)?.let { pubUrl -> openPublication(pubUrl) } ?: failure(
            PublicationError.Unexpected(
                DebugError("Invalid Url"),
            ),
        )
    }

    /**
     * Open a publication and set it as the current publication.
     */
    suspend fun openPublication(pubUrl: AbsoluteUrl): Try<Publication, PublicationError> {
        if (currentPublicationUrl == pubUrl.toString()) {
            // Current publication is the same as the one we are trying to open, return it.
            // If you need to reload the publication, you need to close it first.
            currentPublication?.let {
                return Try.success(it)
            }
        }

        // Close previously opened publication to avoid leaks.
        closePublication()

        val transformingContainerFactory =
            fun(container: Container<Resource>): Container<Resource> {
                return TransformingContainer(container) { url: Url, resource: Resource ->
                    val publication = currentPublication ?: return@TransformingContainer resource
                    val navigator = epubNavigator ?: return@TransformingContainer resource

                    val tocIds =
                        publication.tableOfContents
                            .flattenChildren()
                            .mapNotNull { it.href.resolve().fragment }
                    val epubPreferences =
                        navigator.preferences?.effectiveForLayout(publication.metadata.layout)
                    if (url.extension?.value?.endsWith("html", ignoreCase = true) == true) {
                        resource.injectScriptsAndStyles(tocIds, epubPreferences)
                    } else {
                        resource
                    }
                }
            }

        val pub =
            loadPublication(
                pubUrl,
                transformingContainerFactory,
            ).getOrElse { e -> return failure(e) }

        _currentPublication = pub
        currentPublicationUrl = pubUrl.toString()

        return Try.success(pub)
    }

    /**
     * Load a publication from a URL
     * Note: Remember to close the publication to avoid leaks.
     */
    suspend fun loadPublicationFromUrl(urlStr: String): Try<Publication, PublicationError> {
        val pubUrl =
            resolvePubUrl(urlStr).getOrElse {
                return failure(PublicationError.InvalidPublicationUrl(urlStr))
            }

        PluginLog.d(TAG, "loadPublicationFromUrl: $pubUrl")

        return loadPublication(pubUrl)
    }

    /**
     * Open a publication from a URL.
     *
     * Note: This sets the publication as the current publication.
     */
    suspend fun openPublicationFromUrl(urlStr: String): Try<Publication, PublicationError> {
        val pubUrl =
            resolvePubUrl(urlStr).getOrElse {
                return failure(PublicationError.InvalidPublicationUrl(urlStr))
            }

        PluginLog.d(TAG, "openPublicationFromUrl: $pubUrl")

        return openPublication(pubUrl)
    }

    /**
     * Helper function for resolving a URL and make sure a file path is turned into a URL.
     */
    private fun resolvePubUrl(urlStr: String): Try<AbsoluteUrl, PublicationError> {
        var pubUrlStr = urlStr
        // If URL is neither http nor file, assume it is a local file reference.
        if (!pubUrlStr.startsWith("http") && !pubUrlStr.startsWith("file")) {
            pubUrlStr = "file://$pubUrlStr"
        }
        // Create AbsoluteUrl, return PublicationError.InvalidPublicationUrl if null
        val pubUrl =
            AbsoluteUrl(pubUrlStr) ?: return failure(
                PublicationError.InvalidPublicationUrl(pubUrlStr),
            )

        return Try.success(pubUrl)
    }

    fun closePublication() {
        appReaderStyleScript = null
        ttsNavigator?.dispose()
        ttsNavigator = null
        audiobookNavigator?.dispose()
        audiobookNavigator = null
        syncAudiobookNavigator?.dispose()
        syncAudiobookNavigator = null

        _audioPreferences = FlutterAudioPreferences()
        currentTimebasedPublicationDurationMs = null

        currentReadiumTimebasedState.value = ReadiumTimebasedState()
        currentTextLocator.value = null

        _currentPublication?.close()
        _currentPublication = null
        pageBreakIteratorFactory = null
        currentPublicationCssSelectorMap = null
        ResourceFileCache.purgeAll()

        state.clear()
    }

    override fun onTimebasedPlaybackStateChanged(timebasedState: TimebasedNavigator.TimebasedState) {
        PluginLog.d(TAG, "::onTimebasedPlaybackStateChanged $timebasedState")
        currentReadiumTimebasedState.value = currentReadiumTimebasedState.value.copyWith(state = timebasedState)
    }

    override fun onTimebasedBufferChanged(buffer: Duration?) {
        PluginLog.d(TAG, "::onTimebasedBufferChanged $buffer")
        currentReadiumTimebasedState.value = currentReadiumTimebasedState.value.copyWith(currentBuffered = buffer?.inWholeMilliseconds)
    }

    override fun onTimebasedPlaybackFailure(error: PublicationError) {
        PluginLog.e(TAG, "::onTimebasedPlaybackFailure $error")

        errorChannel?.sendEvent(ReadiumError.invoke(error))
    }

    @OptIn(InternalReadiumApi::class)
    override fun onTimebasedCurrentLocatorChanges(
        locator: Locator,
        currentReadingOrderLink: Link?,
    ) {
        val duration = currentReadingOrderLink?.duration
        val timeOffset = locator.locations.timeWithDuration(duration)

        PluginLog.d(TAG, "::onTimebasedCurrentLocatorChanges $locator, timeOffset=$timeOffset")

        currentReadiumTimebasedState.value =
            currentReadiumTimebasedState.value.copyWith(
                currentOffset = timeOffset?.inWholeMilliseconds?.toDouble(),
                currentDuration = duration?.let { it * 1000 },
                totalProgressDuration =
                    computeTotalProgressDurationMs(
                        locator.locations.totalProgression,
                        currentTimebasedPublicationDurationMs,
                    ),
                totalDuration = currentTimebasedPublicationDurationMs,
                currentLocator = locator,
            )
    }

    override fun onTimebasedLocationChanged(
        locator: Locator,
        isWordRange: Boolean,
    ) {
        PluginLog.d(TAG, "::onTimebasedLocationChanged $locator")

        // In scroll mode, skip fine-grained word-range syncs. Scrolling to each
        // spoken word re-pins the current paragraph to the top of the viewport,
        // causing constant snap-to-top jitter. The utterance-level sync and the
        // per-word highlight decoration keep the reader in the right place.
        // In pagination we DO follow the word range, so an utterance spanning a
        // page boundary turns the page to the word currently being spoken.
        if (isWordRange && epubNavigator?.preferences?.scroll == true) {
            PluginLog.d(TAG, "::onTimebasedLocationChanged skipped word-range sync in scroll mode")
            return
        }

        launch {
            syncVisualToLocator(locator, true)
        }
    }

    /**
     * Find the current table of content item from a locator.
     */
    suspend fun epubEnrichLocatorWithTocHref(locator: Locator): Locator {
        val publication =
            currentPublication ?: run {
                PluginLog.e(TAG, "::epubEnrichLocatorWithTocHref - no currentPublication")
                return locator
            }

        if (!publication.conformsTo(Publication.Profile.EPUB)) {
            PluginLog.w(TAG, "::epubEnrichLocatorWithTocHref - not an EPUB profile")
            return locator
        }

        val cleanHref = locator.href.cleanHref()
        val tocLinks = publication.tableOfContents.flattenChildren().filter {
            it.href.resolve().cleanHref().path == cleanHref.path
        }
        if (tocLinks.isEmpty()) return locator
        // A resource can have many TOC anchors and most paragraphs have no IDs.
        // Compare actual DOM order; an absent selector must never become index 0.
        val visible = epubFirstVisibleElementLocator()?.takeIf { it.href.cleanHref() == cleanHref }
        val selector = visible?.locations?.cssSelector ?: locator.locations.cssSelector ?: return locator
        val fragments = org.json.JSONArray(tocLinks.map { android.net.Uri.decode(it.href.resolve().fragment ?: "") })
        val raw = epubEvaluateJavascript(
            """(function(){
              var current=document.querySelector(${JSONObject.quote(selector)});
              if(!current)return -1;
              var fragments=$fragments,best=-1,bestNode=null;
              for(var i=0;i<fragments.length;i++){
                var node=fragments[i]?document.getElementById(fragments[i]):document.documentElement;
                if(!node)continue;
                var before=node===current||node.contains(current)||(node.compareDocumentPosition(current)&4);
                if(before&&(!bestNode||bestNode===node||bestNode.contains(node)||(bestNode.compareDocumentPosition(node)&4))){best=i;bestNode=node;}
              }
              return best;
            })()"""
        )
        val index = raw?.toIntOrNull() ?: return locator
        val tocItem = tocLinks.getOrNull(index) ?: return locator
        return locator.copy(title = tocItem.title).copyWithTocHref(tocItem)
    }

    @OptIn(InternalReadiumApi::class)
    suspend fun epubEnable(
        initialLocator: Locator?,
        initialPreferences: FlutterEpubPreferences,
        fragmentManager: FragmentManager,
        viewGroup: ViewGroup,
        readerWidget: ReadiumReaderWidget,
    ) {
        val pub = currentPublication ?: throw Exception("Publication not opened cannot enable epub")

        currentReaderWidget = readerWidget

        val isEpub = pub.conformsTo(Publication.Profile.EPUB)
        if (!isEpub) {
            throw Exception("Publication is not an EPUB, cannot enable epub navigator")
        }

        // Seed the runtime sync flag from the initial preference. If the preference
        // already disables synchronization, start in manual mode.
        narrationSyncEnabled = initialPreferences.disableSynchronization != true

        withMainContext {
            epubNavigator?.let {
                attachEpubNavigator(fragmentManager, viewGroup)
                return@withMainContext
            } // Already enabled - assume from restored state.

            EpubNavigator(pub, initialLocator, this@ReadiumReader, initialPreferences).apply {
                initNavigator()
                visualNavigator = this
                attachEpubNavigator(fragmentManager, viewGroup)
                setDecorationStyle(decorationStyle)
                return@withMainContext
            }
        }
    }

    suspend fun attachEpubNavigator(
        fragmentManager: FragmentManager?,
        viewGroup: ViewGroup?,
    ) {
        if (fragmentManager == null || viewGroup == null) {
            PluginLog.w(TAG, "::attachEpubNavigator: Missing fragmentManager or viewGroup")
            return
        }

        val navigator =
            visualNavigator ?: run {
                PluginLog.d(TAG, "::attachEpubNavigator: Tried to attach a non-existing epub navigator?")
                return
            }

        withMainContext {
            // Queue decorations to be applied when the epubNavigator is attached.
            decorationsUpdated()

            navigator.attachNavigator(fragmentManager, viewGroup)
        }
    }

    @OptIn(InternalReadiumApi::class)
    suspend fun pdfEnable(
        initialLocator: Locator?,
        fragmentManager: FragmentManager,
        viewGroup: ViewGroup,
        readerWidget: ReadiumReaderWidget,
    ) {
        val pub = currentPublication ?: throw Exception("Publication not opened cannot enable pdf")

        currentReaderWidget = readerWidget

        val isPdf =
            pub.conformsTo(Publication.Profile.PDF) ||
                pub.readingOrder
                    .firstOrNull()
                    ?.mediaType
                    ?.matches(MediaType.PDF) == true
        if (!isPdf) {
            throw Exception("Publication is not a PDF, cannot enable pdf navigator")
        }

        withMainContext {
            pdfNavigator?.let {
                attachPdfNavigator(fragmentManager, viewGroup)
                return@withMainContext
            }

            PdfNavigator(pub, initialLocator, this@ReadiumReader).apply {
                initNavigator()
                visualNavigator = this
                attachPdfNavigator(fragmentManager, viewGroup)
                return@withMainContext
            }
        }
    }

    suspend fun attachPdfNavigator(
        fragmentManager: FragmentManager?,
        viewGroup: ViewGroup?,
    ) {
        if (fragmentManager == null || viewGroup == null) {
            PluginLog.d(TAG, "::attachPdfNavigator: Missing fragmentManager or viewGroup")
            return
        }

        val navigator =
            pdfNavigator ?: run {
                PluginLog.d(TAG, "::attachPdfNavigator: Tried to attach a non-existing pdf navigator?")
                return
            }

        withMainContext {
            navigator.attachNavigator(fragmentManager, viewGroup)
        }
    }

    fun pdfClose() {
        currentReaderWidget = null
        visualNavigator?.dispose()
        visualNavigator = null
    }

    suspend fun pdfUpdatePreferences(preferences: FlutterPdfPreferences) {
        val navigator =
            pdfNavigator ?: run {
                PluginLog.e(TAG, "::pdfUpdatePreferences called without a pdfNavigator")
                return
            }
        navigator.updatePreferences(preferences)
    }

    fun pdfEnrichLocatorWithTocHref(locator: Locator): Locator {
        val publication = currentPublication ?: return locator
        val page = locator.locations.position ?: return locator

        // Find the last TOC entry whose "#page=N" fragment is ≤ the current page.
        val tocEntry =
            publication.tableOfContents
                .flattenChildren()
                .asSequence()
                .mapNotNull { link ->
                    val href = link.href.toString()
                    val fragment = href.substringAfterLast("#", "")
                    if (!fragment.startsWith("page=")) return@mapNotNull null
                    val tocPage = fragment.removePrefix("page=").toIntOrNull() ?: return@mapNotNull null
                    Pair(tocPage, link)
                }.filter { it.first <= page }
                .maxByOrNull { it.first }
                ?.second ?: return locator

        return locator
            .copy(title = tocEntry.title)
            .copyWithTocHref(tocEntry)
    }

    @OptIn(InternalReadiumApi::class)
    suspend fun comicEnable(
        initialLocator: Locator?,
        fragmentManager: FragmentManager,
        viewGroup: ViewGroup,
        readerWidget: ReadiumReaderWidget,
    ) {
        val pub = currentPublication ?: throw Exception("Publication not opened cannot enable comic")

        currentReaderWidget = readerWidget

        val isComic =
            pub.conformsTo(Publication.Profile.DIVINA) ||
                pub.readingOrder
                    .firstOrNull()
                    ?.mediaType
                    ?.matches(MediaType.CBZ) == true

        if (!isComic) {
            throw Exception("Publication is not a comic (CBZ/DiViNa), cannot enable comic navigator")
        }

        withMainContext {
            comicNavigator?.let {
                attachComicNavigator(fragmentManager, viewGroup)
                return@withMainContext
            }

            ComicNavigator(pub, initialLocator, this@ReadiumReader).apply {
                initNavigator()
                visualNavigator = this
                attachComicNavigator(fragmentManager, viewGroup)
                return@withMainContext
            }
        }
    }

    suspend fun attachComicNavigator(
        fragmentManager: FragmentManager?,
        viewGroup: ViewGroup?,
    ) {
        if (fragmentManager == null || viewGroup == null) {
            PluginLog.d(TAG, "::attachComicNavigator: Missing fragmentManager or viewGroup")
            return
        }

        val navigator =
            comicNavigator ?: run {
                PluginLog.d(TAG, "::attachComicNavigator: Tried to attach a non-existing comic navigator?")
                return
            }

        withMainContext {
            navigator.attachNavigator(fragmentManager, viewGroup)
        }
    }

    fun comicClose() {
        currentReaderWidget = null
        visualNavigator?.dispose()
        visualNavigator = null
    }

    fun epubClose() {
        currentReaderWidget = null
        visualNavigator?.dispose()
        visualNavigator = null
    }

    /** Close the active visual navigator, regardless of type (EPUB or PDF). */
    fun visualClose() {
        currentReaderWidget = null
        visualNavigator?.dispose()
        visualNavigator = null
    }

    /**
     * Enable the appropriate visual navigator (EPUB or PDF) based on the
     * current publication type. This replaces the separate `epubEnable` /
     * `pdfEnable` call sites in the widget so callers don't need to branch.
     */
    @OptIn(InternalReadiumApi::class)
    suspend fun visualEnable(
        initialLocator: Locator?,
        initialPreferences: FlutterEpubPreferences,
        fragmentManager: FragmentManager,
        viewGroup: ViewGroup,
        readerWidget: ReadiumReaderWidget,
    ) {
        val pub =
            currentPublication ?: throw Exception("Publication not opened cannot enable visual navigator")

        // Route to the appropriate navigator. Comic check comes first: CBZ publications may
        // lack the EPUB profile even though the streamer parsed them successfully, so we must
        // not let them fall through to epubEnable (which throws for non-EPUB).
        val isPdf =
            pub.conformsTo(Publication.Profile.PDF) ||
                pub.readingOrder
                    .firstOrNull()
                    ?.mediaType
                    ?.matches(MediaType.PDF) == true

        val isComic =
            pub.conformsTo(Publication.Profile.DIVINA) ||
                pub.readingOrder
                    .firstOrNull()
                    ?.mediaType
                    ?.matches(MediaType.CBZ) == true

        when {
            isPdf -> pdfEnable(initialLocator, fragmentManager, viewGroup, readerWidget)
            isComic -> comicEnable(initialLocator, fragmentManager, viewGroup, readerWidget)
            else -> epubEnable(initialLocator, initialPreferences, fragmentManager, viewGroup, readerWidget)
        }
    }

    /** Navigate backward in the active visual navigator. */
    suspend fun visualGoBackward(animated: Boolean) {
        val navigator =
            visualNavigator ?: run {
                PluginLog.d(TAG, "::visualGoBackward. Navigator not ready.")
                return
            }
        navigator.goBackward(animated)
    }

    /** Navigate forward in the active visual navigator. */
    suspend fun visualGoForward(animated: Boolean) {
        val navigator =
            visualNavigator ?: run {
                PluginLog.d(TAG, "::visualGoForward. Navigator not ready.")
                return
            }
        navigator.goForward(animated)
    }

    /** Go to the given locator in the active visual navigator. */
    suspend fun visualGoToLocator(
        locator: Locator,
        animated: Boolean,
    ) {
        val publication =
            currentPublication ?: run {
                PluginLog.e(TAG, "::visualGoToLocator called without an open publication")
                return
            }
        val toLocator = publication.normalizeLocator(locator)
        val navigator =
            visualNavigator ?: run {
                PluginLog.d(TAG, "::visualGoToLocator. Navigator not ready.")
                return
            }
        navigator.goToLocator(toLocator, animated)
    }

    suspend fun ttsEnable(ttsPrefs: FlutterTtsPreferences) {
        currentPublication?.let {
            applyPageBreakBehavior(ttsPrefs)
            ttsNavigator =
                TTSNavigator(it, this@ReadiumReader, currentTextLocator.value, ttsPrefs).apply {
                    initNavigator()
                }
        } ?: throw Exception("Publication not opened cannot enable tts")
        currentTimebasedPublicationDurationMs = null
    }

    suspend fun ttsSetPreferences(ttsPrefs: FlutterTtsPreferences) {
        applyPageBreakBehavior(ttsPrefs)
        ttsNavigator?.updatePreferences(ttsPrefs)
            ?: throw Exception("TTS is not enabled, can't set preferences")
    }

    suspend fun setDecorationStyle(style: FlutterDecorationPreferences) {
        decorationStyle = style

        decorationsUpdated()
    }

    suspend fun decorationsUpdated() {
        ttsNavigator?.decorationsUpdated()
        syncAudiobookNavigator?.decorationsUpdated()
    }

    /**
     * Cached list of android tts voices.
     */
    private var availableTtsVoices: Set<AndroidTtsEngine.Voice>? = null

    /**
     * Get available tts voices
     */
    suspend fun ttsGetAvailableVoices(): Set<AndroidTtsEngine.Voice> {
        // Already loaded, return existing list.
        availableTtsVoices?.takeIf { it.isNotEmpty() }?.let { return it }

        // Get the available voices from the TTS navigator.
        // If the TTS navigator hasn't been initialized, create a dummy AndroidTtsEngine.
        availableTtsVoices = ttsNavigator?.voices ?: AndroidTtsEngine
            .invoke(
                context,
                {
                    AndroidTtsSettings(
                        Language("C"),
                        false,
                        0.0,
                        0.0,
                        mapOf(),
                    )
                },
                { language, availableVoices -> null },
                AndroidTtsPreferences(),
            )?.voices

        return availableTtsVoices ?: setOf()
    }

    fun ttsGetPreferences(): FlutterTtsPreferences? = ttsNavigator?.preferences

    suspend fun ttsSetPreferredVoice(
        voiceId: String?,
        language: String?,
    ) {
        if (voiceId == null) {
            PluginLog.w(TAG, "::ttsSetPreferredVoice - missing voiceId")
            return
        }

        if (language == null) {
            PluginLog.w(TAG, "::ttsSetPreferredVoice - missing language")
            return
        }

        ttsNavigator?.setPreferredVoice(voiceId, language)
    }

    suspend fun play(locator: Locator?) {
        val fromLocator =
            locator ?: currentReadiumTimebasedState.value.currentLocator ?: currentTextLocator.value
                ?: epubFirstVisibleElementLocator()

        PluginLog.d(TAG, "::play($locator) - fromLocator:$fromLocator")

        timebasedNavigator?.play(fromLocator)
    }

    suspend fun pause() {
        timebasedNavigator?.pause()
    }

    suspend fun resume() {
        timebasedNavigator?.resume()
    }

    suspend fun stop() {
        audiobookNavigator?.apply {
            pause()
            dispose()

            audiobookNavigator = null
        }

        val wasMOActive = isMOActive
        syncAudiobookNavigator?.apply {
            pause()
            dispose()

            syncAudiobookNavigator = null
        }
        if (wasMOActive) {
            epubEvaluateJavascript("window.flutterReadium.removeMOBreakCSS()")
        }

        ttsNavigator?.apply {
            pause()
            dispose()

            ttsNavigator = null
        }

        currentReadiumTimebasedState.value = ReadiumTimebasedState.none()
        exitNarrationMode()
    }

    /**
     * Resets narration-sync state and returns the comic overlay to its full-page view.
     * Called on stop (not pause). Distinct from [setNarrationSyncEnabled] which re-pans
     * to the last narrated panel.
     */
    fun exitNarrationMode() {
        PluginLog.d(TAG, "::exitNarrationMode")
        narrationSyncEnabled = true
        emitNarrationSyncChanged(true)
        launch {
            epubNavigator?.exitNarrationMode()
            comicNavigator?.exitNarrationMode()
        }
    }

    /**
     * Skip backwards.
     */
    suspend fun previous() {
        timebasedNavigator?.goBackward()
    }

    /**
     * Skip forwards.
     */
    suspend fun next() {
        timebasedNavigator?.goForward()
    }

    /**
     * Go to a specific locator.
     */
    suspend fun goToLocator(locator: Locator) {
        val publication =
            currentPublication ?: run {
                PluginLog.e(TAG, "::goToLocator called without a current publication")
                return
            }
        val toLocator = publication.normalizeLocator(locator)
        timebasedNavigator?.let { navigator ->
            PluginLog.d(TAG, "::goToLocator - timebased $toLocator")
            val narrationLocator =
                toLocator.copy(
                    text = Locator.Text(),
                )
            navigator.goToLocator(narrationLocator)
            // navigator.goToLocator blocks until the seek lands (see AudiobookNavigator),
            // so reflect the confirmed position in state before returning. This is what a
            // subsequent play(null) reads to resume from, closing the seek→play race.
            currentReadiumTimebasedState.value =
                currentReadiumTimebasedState.value.copyWith(currentLocator = narrationLocator)

            return
        }

        // An explicit locator jump (TOC / bookmark / search) during active narration is
        // handled by the timebasedNavigator.goToLocator branch above (narration follows the
        // jump). This branch is only reached when narration is NOT active, so a jump must not
        // enter manual mode. Page-turns (epubGoForward/epubGoBackward) do enter manual mode.
        epubGoToLocator(toLocator, true)
    }

    /**
     * Go to a progression value between 0.0 and 1.0
     */
    suspend fun goToProgression(progression: Double) {
        timebasedNavigator?.let { timebasedNavigator ->
            PluginLog.d(TAG, "::goToProgression - timebased $progression")
            timebasedNavigator.seekToProgression(progression)

            return
        }

        epubGoToProgression(progression)
    }

    suspend fun searchInPublication(query: String): Try<List<LocatorCollection>, Error> {
        val pub =
            currentPublication ?: return failure(
                Error("no publication"),
            )
        val isPdf =
            pub.conformsTo(Publication.Profile.PDF) ||
                pub.readingOrder
                    .firstOrNull()
                    ?.mediaType
                    ?.matches(MediaType.PDF) == true
        if (isPdf) {
            return failure(
                Error("PDF search is not supported on Android: kotlin-toolkit does not ship a SearchService for PDF publications."),
            )
        }
        val resultIterator =
            pub.search(query, SearchService.Options()) ?: return failure(
                Error("SearchService unavailable"),
            )
        val results = mutableListOf<LocatorCollection>()
        while (true) {
            val result = resultIterator.next()
            if (result.isFailure) break
            val collection = result.getOrNull() ?: break
            results.add(collection)
        }
        return Try.success(results.toList())
    }

    /**
     * Seek to a specific [offset] in seconds from the current position. Can be negative or positive.
     */
    suspend fun audioSeek(offset: Double) {
        val navigator =
            timebasedNavigator ?: run {
                PluginLog.w(TAG, "::audioSeek - called without an active timebase navigator")
                return
            }

        navigator.seekTo(offset)
    }

    @OptIn(InternalReadiumApi::class)
    suspend fun audioEnable(
        locator: Locator?,
        preferences: FlutterAudioPreferences,
    ) {
        _audioPreferences = preferences

        val publication =
            currentPublication ?: run {
                throw Exception("Publication not opened")
            }

        val initialLocator = locator?.let { publication.normalizeLocator(it) }

        // Handle karaoke books - by creating a pseudo audio publication from the media overlays.
        val (ap, overlays) = publication.makeSyncAudiobook()

        audiobookNavigator?.dispose()
        syncAudiobookNavigator?.dispose()
        audiobookNavigator = null
        syncAudiobookNavigator = null

        if (overlays == null) {
            PluginLog.d(TAG, "::audioEnable - plain audiobook")

            audiobookNavigator =
                AudiobookNavigator(
                    ap,
                    this@ReadiumReader,
                    initialLocator,
                    preferences,
                ).apply {
                    initNavigator()
                }
            currentTimebasedPublicationDurationMs = computePublicationDurationMs(ap.readingOrder.map { it.duration })
        } else {
            PluginLog.d(TAG, "::audioEnable - media-overlay book")
            val ail = initialLocator ?: epubNavigator?.currentLocator?.value
            syncAudiobookNavigator =
                SyncAudiobookNavigator(
                    ap,
                    overlays,
                    this@ReadiumReader,
                    ail,
                    preferences,
                ).apply {
                    initNavigator()
                }
            if (preventMOColumnBreaksActive) {
                epubEvaluateJavascript("window.flutterReadium.injectMOBreakCSS()")
            }
            currentTimebasedPublicationDurationMs = computePublicationDurationMs(ap.readingOrder.map { it.duration })
        }
    }

    suspend fun audioUpdatePreferences(preferences: FlutterAudioPreferences) {
        _audioPreferences = preferences

        val navigator =
            audiobookNavigator ?: syncAudiobookNavigator ?: run {
                PluginLog.e(TAG, "::audioUpdatePreferences called without an active audiobook navigator")
                throw Exception("Audio not enabled, cannot update preferences")
            }

        navigator.updatePreferences(preferences)
    }

    suspend fun applyAppDecorations(values: List<Map<String, Any>>, group: String) {
        applyDecorations(values.mapNotNull { decorationFromMap(it) }, group)
    }

    suspend fun applyDecorations(
        decorations: List<Decoration>,
        group: String,
    ) {
        val navigator = epubNavigator ?: return

        navigator.applyDecorations(decorations, group)
    }

    override fun onPageLoaded() {
        currentReaderWidget?.onPageLoaded()
    }

    override fun onPageChanged(
        pageIndex: Int,
        totalPages: Int,
        locator: Locator,
    ) {
        currentReaderWidget?.onPageChanged(pageIndex, totalPages, locator)
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        currentReaderWidget?.onExternalLinkActivated(url)
    }

    override fun onVisualCurrentLocationChanged(locator: Locator) {
        currentReaderWidget?.onVisualCurrentLocationChanged(locator)
    }

    override fun onVisualReaderIsReady() {
        currentReaderWidget?.onVisualReaderIsReady()
    }

    suspend fun appCurrentLocatorJson(): String? {
        val visible = epubFirstVisibleElementLocator()
        val current = epubNavigator?.currentLocator?.value
        val locator = visible ?: current ?: return null
        val json = epubEnrichLocatorWithTocHref(locator).toJSON()
        val view = appVisibleWebView() ?: return json.toString()
        val raw = appEvaluateVisible("(function(){ $appGeometryScript; return JSON.stringify(geometry()); })();")
        val geometry = raw?.takeIf { it != "null" }?.let { JSONObject(org.json.JSONTokener(it).nextValue().toString()) }
            ?: return json.toString()
        return appLocatorAtGeometry(json.toString(), view, geometry)
    }

    suspend fun epubFirstVisibleElementLocator(): Locator? {
        val navigator =
            epubNavigator ?: run {
                PluginLog.d(TAG, "::epubFirstVisibleElementLocator called without a epubNavigator")
                return null
            }

        return navigator.firstVisibleElementLocator()
    }

    suspend fun epubEvaluateJavascript(script: String): String? {
        val navigator =
            epubNavigator ?: run {
                PluginLog.d(TAG, "::epubEvaluateJavascript called without a epubNavigator")
                return null
            }

        return navigator.evaluateJavascript(script)
    }

    /**
     * Update EPUB navigator preferences.
     */
    suspend fun epubUpdatePreferences(preferences: FlutterEpubPreferences) {
        val navigator =
            epubNavigator ?: run {
                PluginLog.d(TAG, "::epubUpdatePreferences called without a epubNavigator")
                return
            }

        val newPreventBreaks = preferences.preventMOColumnBreaks ?: true
        if (isMOActive && newPreventBreaks != preventMOColumnBreaksActive) {
            if (newPreventBreaks) {
                epubEvaluateJavascript("window.flutterReadium.injectMOBreakCSS()")
            } else {
                epubEvaluateJavascript("window.flutterReadium.removeMOBreakCSS()")
            }
        }
        preventMOColumnBreaksActive = newPreventBreaks

        navigator.updatePreferences(preferences)
    }

    /**
     * Navigate backward in the EPUB navigator.
     */
    suspend fun epubGoBackward(animated: Boolean) {
        val navigator =
            visualNavigator ?: run {
                PluginLog.d(TAG, "::epubGoBackward called without a visualNavigator")
                return
            }

        enterManualModeIfNarrating("epubGoBackward")
        navigator.goBackward(animated)
    }

    /**
     * Navigate forward in the EPUB navigator.
     */
    suspend fun epubGoForward(animated: Boolean) {
        val navigator =
            visualNavigator ?: run {
                PluginLog.d(TAG, "::epubGoForward called without a visualNavigator")
                return
            }

        enterManualModeIfNarrating("epubGoForward")
        navigator.goForward(animated)
    }

    /**
     * Go to a specific locator in the EPUB navigator, this scrolls to the locator position if needed.
     */
    suspend fun epubGoToLocator(
        locator: Locator,
        animated: Boolean,
    ) {
        val publication =
            currentPublication ?: run {
                PluginLog.e(TAG, "::epubGoToLocator called without an open publication")
                return
            }

        val toLocator = publication.normalizeLocator(locator)

        val navigator =
            visualNavigator ?: run {
                PluginLog.d(TAG, "::epubGoToLocator called without a visualNavigator")
                return
            }

        navigator.goToLocator(toLocator, animated)
    }

    suspend fun epubGoToProgression(progression: Double) {
        val navigator =
            visualNavigator ?: run {
                PluginLog.d(TAG, "::epubGoToProgression called without a visualNavigator")
                return
            }

        navigator.scrollToProgression(progression)
    }

    /**
     * Sync epub to [SyncAudiobookNavigator] or [TTSNavigator].
     * Delegates to [syncVisualToLocator] for EPUB; kept for call-site compatibility.
     */
    suspend fun epubSyncToLocator(
        locator: Locator,
        animated: Boolean,
        segmentDuration: Double? = null,
    ) {
        syncVisualToLocator(locator, animated, segmentDuration)
    }

    /**
     * Routes an audio-cue sync to the active visual navigator, respecting the manual-mode gate.
     *
     * For EPUB: delegates to [EpubNavigator.syncToLocator] which also tracks [lastSyncLocator]
     * so re-enabling sync can catch up immediately.
     * For comic (CBZ / DiViNa): calls [ComicNavigator.goToLocator] directly since comics have
     * no per-word scroll logic. Highlight decorations are applied regardless of the sync flag
     * (matching the existing [disableSynchronization] behaviour for EPUB).
     *
     * Note: this only fires when the comic publication actually carries narration cues mapped
     * through [mediaOverlays]. DiViNa guided-navigation cues are parsed on Android only when
     * [Publication.hasGuidedNavigationMediaOverlays] is true and [makeSyncAudiobook] succeeds;
     * if that prerequisite is not met the [SyncAudiobookNavigator] will not be active and this
     * path is never reached.
     */
    private suspend fun syncVisualToLocator(
        locator: Locator,
        animated: Boolean,
        segmentDuration: Double? = null,
    ) {
        val epub = epubNavigator
        if (epub != null) {
            if (!narrationSyncEnabled) {
                // Manual mode: remember the cue so a later Re-sync can catch up, but don't
                // move the view. (Highlight decorations are still applied by the caller.)
                PluginLog.d(TAG, "::syncVisualToLocator - manual mode: deferring epub sync")
                epub.recordDeferredSync(locator, segmentDuration)
                return
            }
            withMainContext {
                epub.syncToLocator(locator, animated, segmentDuration)
            }
            return
        }

        val comic = comicNavigator
        if (comic != null) {
            if (!narrationSyncEnabled) {
                // Manual mode: record the cue so a later Re-sync can catch up to the
                // current page, but don't move the view.
                PluginLog.d(TAG, "::syncVisualToLocator - manual mode: deferring comic sync")
                comic.recordDeferredSync(locator)
                return
            }
            withMainContext {
                comic.syncToLocator(locator, animated, segmentDuration)
            }
        }
    }

    /** Shared by [ttsEnable] and [ttsSetPreferences] — both can set this. */
    private fun applyPageBreakBehavior(ttsPrefs: FlutterTtsPreferences) {
        pageBreakIteratorFactory?.pageBreakBehavior = ttsPrefs.pageBreakBehavior ?: PageBreakBehavior.READ_AS_IS
    }

    /**
     * Get all cssSelectors for an EPUB file.
     * Note: These only includes text elements, so body, page breaks etc are not included.
     */
    suspend fun epubGetAllDocumentCssSelectors(href: Url): List<String> {
        val cssSelectorMap = currentPublicationCssSelectorMap ?: mutableMapOf()
        currentPublicationCssSelectorMap = cssSelectorMap

        val cleanHref = href.cleanHref()
        return cssSelectorMap.getOrPut(cleanHref) {
            currentPublication?.findAllCssSelectors(
                cleanHref,
            ) ?: listOf()
        }
    }

    /**
     * Emit reader status update to the flutter layer.
     */
    fun emitReaderStatusUpdate(statusUpdate: ReadiumReaderStatus) {
        readiumReaderStatusEventChannel?.sendEvent(statusUpdate)
    }

    /**
     * Emit an error event to the flutter layer.
     */
    fun emitError(error: ReadiumError) {
        errorChannel?.sendEvent(error)
    }

    /**
     * Emit text locator to the flutter layer
     */
    fun emitTextLocatorUpdate(locator: Locator) {
        textLocatorEventChannel?.sendEvent(locator)

        currentTextLocator.value = locator
    }

    /**
     * Emit narration-sync state change to the Flutter layer.
     */
    fun emitNarrationSyncChanged(enabled: Boolean) {
        narrationSyncEventChannel?.sendEvent(enabled)
    }

    /**
     * Returns `true` when a timed-based (narrating) navigator is active and playing.
     * Used to decide whether explicit user navigation should trigger manual mode.
     */
    private fun isNarrationActive(): Boolean = timebasedNavigator != null

    /**
     * If narration is active, enters manual mode and emits the state change.
     *
     * Called from explicit page navigation (`goForward`/`goBackward`) and from in-reader user
     * gestures: the Flutter `reader_widget.dart` `Listener` detects a swipe / edge-tap and calls
     * the reader-view channel `"notifyUserNavigation"`, which routes here. Those pointer events
     * fire only for genuine user interaction (audio-driven page turns are programmatic and never
     * reach the Listener), so they are a clean "user took control" signal.
     */
    fun enterManualModeIfNarrating(callSite: String) {
        if (!isNarrationActive()) return
        if (!narrationSyncEnabled) return
        PluginLog.d(TAG, "::$callSite - user navigation detected while narrating; entering manual mode")
        narrationSyncEnabled = false
        emitNarrationSyncChanged(false)
    }

    /**
     * Enable or disable narration sync at runtime.
     *
     * When `true`: clears manual mode and immediately re-positions the visual reader to
     * the current audio cue (catch-up). When `false`: enters manual mode — the visual
     * reader stops following audio cues, but audio keeps playing.
     *
     * This is the single unified runtime sync gate. [FlutterEpubPreferences.disableSynchronization]
     * only seeds it at [epubEnable] and flips it on a preference transition (see
     * [EpubNavigator.updatePreferences]); all gating for both EPUB and comic cue sync goes through
     * [narrationSyncEnabled] in [syncVisualToLocator].
     */
    fun setNarrationSyncEnabled(enabled: Boolean) {
        PluginLog.d(TAG, "::setNarrationSyncEnabled enabled=$enabled")
        narrationSyncEnabled = enabled
        if (enabled) {
            // Re-sync to the last known audio cue immediately.
            launch {
                val epub = epubNavigator
                if (epub != null) {
                    epub.resyncAfterManualMode()
                    return@launch
                }
                val comic = comicNavigator
                if (comic != null) {
                    // Page-level catch-up: jump to the last audio-cue page immediately.
                    // Panel-level framing is Phase 2 (no panel pan/zoom API on Android yet —
                    // see docs/parity/native-divina-sync-handoff.md).
                    comic.resyncAfterManualMode()
                    return@launch
                }
            }
        }
        emitNarrationSyncChanged(enabled)
    }
}
