package dk.nota.flutterreadium

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.LinearLayout.generateViewId
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import dk.nota.flutterreadium.events.ReadiumError
import dk.nota.flutterreadium.events.ReadiumReaderStatus
import dk.nota.flutterreadium.fragments.EpubReaderFragment
import dk.nota.flutterreadium.fragments.PdfReaderFragment
import dk.nota.flutterreadium.models.PageInformation
import dk.nota.flutterreadium.navigators.EpubNavigator
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

private const val TAG = "ReadiumReaderView"
internal const val VIEW_TYPE_CHANNEL_NAME = "dk.nota.flutter_readium/ReadiumReaderWidget"

@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
class ReadiumReaderWidget(
    private val context: Context,
    id: Int,
    creationParams: Map<String?, Any?>,
    messenger: BinaryMessenger,
    attrs: AttributeSet? = null,
) : PlatformView,
    MethodChannel.MethodCallHandler,
    EpubReaderFragment.Listener,
    PdfReaderFragment.Listener,
    EpubNavigator.VisualListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) {
    internal val channel: ReadiumReaderChannel

    /**
     * Make sure we only sent ready status once.
     */
    var currentPageIndex: Int? = null
        private set
    var currentPageCount: Int? = null
        private set
    var hasSentReady = false

    private val layout: AppPageGestureLayout

    internal fun setAppViewportInsets(top: Double, bottom: Double, minimumStatus: Double) {
        layout.setReaderInsets(top, bottom, minimumStatus)
    }

    // Source the host activity from the plugin's ActivityAware binding (via ReadiumReader),
    // not the PlatformView's view context. The view context is the Activity only under
    // Texture-Layer Hybrid Composition; under Hybrid Composition it is a non-Activity context
    // and casting it throws ClassCastException.
    private val activity: FragmentActivity
        get() =
            ReadiumReader.fragmentActivity
                ?: throw IllegalStateException(
                    "::activity. No FragmentActivity available — is the plugin attached to a FragmentActivity host?",
                )
    private val fragmentManager
        get() = activity.supportFragmentManager

    override fun getView(): View {
        // PluginLog.d(TAG, "::getView")
        return layout
    }

    override fun dispose() {
        PluginLog.d(TAG, "::dispose")
        // A previous route may finish its exit after the next reader is ready.
        // Its disposal must not close the new route's global navigator.
        if (ReadiumReader.currentReaderWidget === this) {
            ReadiumReader.visualClose()
            ReadiumReader.emitReaderStatusUpdate(ReadiumReaderStatus.Closed)
        }
        hasSentReady = false

        channel.setMethodCallHandler(null)

        coroutineContext.cancelChildren()
        layout.removeAllViews()
    }

    override fun onFlutterViewAttached(flutterView: View) {
        // Seems to never be called, so can't use this. Flutter bug?
        PluginLog.d(TAG, "::onFlutterViewAttached")
        super.onFlutterViewAttached(flutterView)
    }

    override fun onFlutterViewDetached() {
        // Seems to never be called, so can't use this. Flutter bug?
        PluginLog.d(TAG, "::onFlutterViewDetached")
        super.onFlutterViewDetached()
    }

    init {
        PluginLog.d(TAG, "::init")

        @Suppress("UNCHECKED_CAST")
        val initPrefsMap =
            creationParams["preferences"] as Map<String, String>?
        @Suppress("UNCHECKED_CAST")
        val initialActions = creationParams["selectionActions"] as? List<Map<String, String>> ?: emptyList()
        ReadiumReader.selectionActions = initialActions.map { SelectionActionConfig(it["id"] ?: "", it["title"] ?: "") }
        ReadiumReader.appSelectionToolbar = creationParams["appSelectionToolbar"] == true
        val publication = ReadiumReader.currentPublication
        val locatorString = creationParams["initialLocator"] as String?
        val allowScreenReaderNavigation = creationParams["allowScreenReaderNavigation"] as Boolean?

        // Accepted for API parity with iOS but currently no-op: kotlin-toolkit's
        // EpubNavigatorFragment.Configuration does not expose preload-count fields
        // (preload is governed by an internal R2ViewPager.offscreenPageLimit). Revisit
        // when upstream adds a public knob.
        @Suppress("UNUSED_VARIABLE")
        val preloadPreviousPositionCount = creationParams["preloadPreviousPositionCount"] as Int?

        @Suppress("UNUSED_VARIABLE")
        val preloadNextPositionCount = creationParams["preloadNextPositionCount"] as Int?
        val initialLocator =
            if (locatorString == null) null else Locator.fromJSON(jsonDecode(locatorString) as JSONObject)

        val initialPreferences =
            initPrefsMap?.let { FlutterEpubPreferences.fromMap(it) } ?: FlutterEpubPreferences()

        PluginLog.d(TAG, "publication = $publication")

        layout = AppPageGestureLayout(context, attrs,
            ownsGesture = { ReadiumReader.appOwnsPageGestures() },
            onMarginTap = { x, y ->
                val density = context.resources.displayMetrics.density
                channel.onReaderTap(x / density, y / density)
            },
            onSwipe = { phase, dx, dy, y, startX, velocityX, durationMillis ->
                val density = context.resources.displayMetrics.density
                channel.onReaderDrag(phase, dx / density, dy / density, y, startX, velocityX / density, durationMillis)
            })
        layout.id = generateViewId()
        (creationParams["appViewportInsets"] as? List<*>)?.let { values ->
            setAppViewportInsets(
                (values.getOrNull(0) as? Number)?.toDouble() ?: 0.0,
                (values.getOrNull(1) as? Number)?.toDouble() ?: 0.0,
                (values.getOrNull(2) as? Number)?.toDouble() ?: 0.0,
            )
        }
        layout.setBackgroundColor(Color.TRANSPARENT)

        ReadiumReader.currentReaderWidget = this

        channel = ReadiumReaderChannel(messenger, "$VIEW_TYPE_CHANNEL_NAME:$id")
        channel.setMethodCallHandler(this)

        ReadiumReader.emitReaderStatusUpdate(ReadiumReaderStatus.Loading)

        hasSentReady = false

        // By default reader contents are hidden from screen-readers, as not to trap them within it.
        // This can be toggled back on via the 'allowScreenReaderNavigation' creation param.
        // See issue: https://notalib.atlassian.net/browse/NOTA-9828
        if (allowScreenReaderNavigation != true) {
            layout.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        // Remove existing fragment if any (this is to avoid crashing on restore).
        // Cast as base Fragment so we strip either reader type cleanly.
        fragmentManager.findFragmentByTag(NAVIGATOR_FRAGMENT_TAG)?.let { fragment ->
            PluginLog.d(TAG, "::init - remove existing fragment")
            fragmentManager.commitNow {
                remove(fragment)
            }
        }

        launch {
            try {
                ReadiumReader.visualEnable(
                    initialLocator,
                    initialPreferences,
                    fragmentManager,
                    layout,
                    this@ReadiumReaderWidget,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PluginLog.e(TAG, "::init - visualEnable failed: ${e.message}", e)
                ReadiumReader.emitReaderStatusUpdate(ReadiumReaderStatus.Error)
                // Heterogeneous failure surface (fragment/navigator-creation errors from
                // kotlin-toolkit) with no single vocabulary code that fits - "unknown" is
                // itself a vocabulary member, unlike a raw exception class name.
                ReadiumReader.emitError(ReadiumError(PublicationError.Unknown(e.message ?: e.toString())))
            }
        }
    }


    override fun onPageLoaded() {
        PluginLog.d(TAG, "::onPageLoaded")
    }

    // To avoid duplicate onPageChanged events.
    private var lastPageLoadedKey: String? = null

    // Some navigator transitions update currentLocator without a page-changed callback.
    // Mirror those into the text-locator stream once so Flutter can observe progress.
    private var lastVisualLocationKey: String? = null

    override fun onPageChanged(
        pageIndex: Int,
        totalPages: Int,
        locator: Locator,
    ) {
        currentPageIndex = pageIndex
        currentPageCount = totalPages
        val currentKey = "${locator.href}@${locator.progression}"
        PluginLog.d(
            TAG,
            "::onPageChanged $pageIndex/$totalPages ${locator.href} ${locator.progression} ${locator.locations}",
        )

        if (lastPageLoadedKey == currentKey) {
            // Sometimes we get duplicate calls to onPageChanged with same locator.
            // Not sure why, but ignore them.
            return
        }

        lastPageLoadedKey = currentKey

        launch {
            if (!hasSentReady) {
                hasSentReady = true

                ReadiumReader.emitReaderStatusUpdate(ReadiumReaderStatus.Ready)
            }

            emitOnPageChanged(pageIndex, totalPages, locator)

            // Re-inject MO column-break CSS for each freshly-loaded spine item.
            if (ReadiumReader.shouldInjectMOColumnBreakCssOnPageChange) {
                ReadiumReader.epubEvaluateJavascript("window.flutterReadium.injectMOBreakCSS()")
            }
        }
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        PluginLog.i(TAG, "::onExternalLinkActivated $url")
        emitOnExternalLinkActivated(url)
    }

    override fun onVisualCurrentLocationChanged(locator: Locator) {
        PluginLog.d(TAG, "::onVisualCurrentLocationChanged $locator")

        val currentKey = "${locator.href}@${locator.progression}@${locator.locations.position}"
        if (lastVisualLocationKey == currentKey) {
            return
        }

        lastVisualLocationKey = currentKey
        ReadiumReader.emitTextLocatorUpdate(locator)
        PluginLog.d(TAG, "::onVisualCurrentLocationChanged emitted text locator update")
    }

    override fun onVisualReaderIsReady() {
        PluginLog.i(TAG, "::onVisualReaderIsReady")
        if (!hasSentReady) {
            ReadiumReader.emitReaderStatusUpdate(ReadiumReaderStatus.Ready)

            hasSentReady = true
        }
    }

    @Throws(IllegalArgumentException::class)
    private suspend fun setPreferencesFromMap(prefMap: Map<String, Any>) {
        PluginLog.d(TAG, "::setPreferencesFromMap")
        val newPreferences = FlutterEpubPreferences.fromMap(prefMap)
        updatePreferences(newPreferences)
        // Push the comic re-sync policy to the injected helper.
        newPreferences.syncPolicy?.let { policy ->
            ReadiumReader.epubEvaluateJavascript(
                "window.flutterReadium && window.flutterReadium.setComicSyncPolicy(${JSONObject.quote(policy)});",
            )
        }
    }

    private suspend fun emitOnPageChanged(
        pageIndex: Int,
        totalPages: Int,
        locator: Locator,
    ) {
        var emittingLocator = locator
        try {
            when {
                ReadiumReader.isPdf -> {
                    // Enrich PDF locator with the current TOC chapter title/href by
                    // matching "#page=N" fragments from the publication's table of contents.
                    emittingLocator = ReadiumReader.pdfEnrichLocatorWithTocHref(emittingLocator)
                }

                ReadiumReader.isComic -> {
                    // Comic (CBZ/DiViNa): no JS webview — just emit the locator as-is.
                    // ImageNavigatorFragment already produces a correct position-bearing locator.
                }

                else -> {
                    // EPUB: JS page-info eval + TOC href enrichment.
                    try {
                        evaluateJavascript("window.flutterReadium.getPageInformation()")
                            ?.let {
                                PageInformation.fromJson(
                                    it,
                                    locator.href,
                                )
                            }?.let { pageInfo ->
                                emittingLocator =
                                    emittingLocator.copyWithAdditionalLocations(pageInfo.otherLocations)
                            } ?: {
                            PluginLog.d(TAG, "::emitOnPageChanged - no page information")
                        }
                    } catch (e: Error) {
                        PluginLog.d(TAG, "::emitOnPageChanged - pageInformation error: $e")
                    }

                    emittingLocator = emittingLocator.addPageNumber(pageIndex, totalPages)
                    emittingLocator = ReadiumReader.epubEnrichLocatorWithTocHref(emittingLocator)
                }
            }

            channel.onPageChanged(emittingLocator)
            ReadiumReader.emitTextLocatorUpdate(emittingLocator)
            PluginLog.d(TAG, "::emitOnPageChanged: emitted $emittingLocator")
        } catch (e: Exception) {
            PluginLog.e(TAG, "::emitOnPageChanged: failed! $e")
        }
    }

    private fun emitOnExternalLinkActivated(url: AbsoluteUrl) {
        channel.onExternalLinkActivated(url)
    }

    override fun onMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        // TODO: To be safe we're doing everything on the Main thread right now.
        // Could probably optimize by using .IO and then change to Main
        // when affecting readerView or returning a result.
        launch {
            PluginLog.d(TAG, "::onMethodCall ${call.method}")
            try {
                dispatchMethodCall(call, result)
            } catch (e: Exception) {
                PluginLog.e(TAG, "::onMethodCall - ${call.method} threw: $e")
                // Branches below throw bare exceptions (e.g. malformed args, `!!` on a
                // failed Locator.fromJSON) rather than calling result.error themselves -
                // without this, the exception would escape this SupervisorJob-scoped
                // launch uncaught and crash the app instead of surfacing to Dart.
                val code =
                    when (e) {
                        is ClassCastException, is NullPointerException -> "InvalidArgument"
                        else -> "unknown"
                    }
                result.error(code, "Failed to handle ${call.method}", e.message)
            }
        }
    }

    private suspend fun dispatchMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        when (call.method) {
            "setPreferences" -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val prefsMap =
                        call.arguments as? Map<String, Any> ?: run {
                            result.error(
                                "InvalidArgument",
                                "Failed to set preferences",
                                "Invalid argument",
                            )
                            return
                        }
                    when {
                        ReadiumReader.isPdf -> {
                            ReadiumReader.pdfUpdatePreferences(FlutterPdfPreferences.fromMap(prefsMap))
                        }

                        ReadiumReader.isComic -> {
                            // ImageNavigatorFragment has no user-configurable preferences.
                            PluginLog.d(TAG, "::setPreferences - not supported for comic")
                        }

                        else -> {
                            setPreferencesFromMap(prefsMap)
                        }
                    }
                    result.success(null)
                } catch (ex: Exception) {
                    // Preferences are deserialized from caller-supplied data
                    // (FlutterEpubPreferences.fromMap); treat failures here as
                    // caller-misuse rather than a generic/unmapped code.
                    result.error("InvalidArgument", "Failed to set preferences", ex.message)
                }
            }

            "go" -> {
                val args = call.arguments as List<*>
                val locatorJson = JSONObject(args[0] as String)
                val animated = args[1] as Boolean
                if (locatorJson.optString("type") == "") {
                    locatorJson.put("type", " ")
                    PluginLog.w(
                        TAG,
                        "Got locator with empty type! This shouldn't happen. $locatorJson",
                    )
                }
                val locator = Locator.fromJSON(locatorJson)!!
                ReadiumReader.visualGoToLocator(locator, animated)
                result.success(null)
            }

            "goBackward" -> {
                val animated = call.arguments as Boolean
                goBackward(animated)
                result.success(null)
            }

            "goForward" -> {
                val animated = call.arguments as Boolean
                goForward(animated)
                result.success(null)
            }

            "applyDecorations" -> {
                if (ReadiumReader.isPdf || ReadiumReader.isComic) {
                    // PdfNavigatorFragment and ImageNavigatorFragment do not expose a
                    // DecorableNavigator surface in kotlin-toolkit 3.2.0.
                    PluginLog.d(TAG, "::applyDecorations - not supported for PDF/comic")
                    result.success(null)
                    return
                }
                val args = call.arguments as List<*>
                val groupId = args[0] as String

                @Suppress("UNCHECKED_CAST")
                val decorationListStr =
                    args[1] as List<String>
                val decorations = decorationListStr.mapNotNull { decorationFromJson(it) }

                ReadiumReader.applyDecorations(decorations, groupId)
                result.success(null)
            }

            "configureSelectionActions" -> {
                @Suppress("UNCHECKED_CAST")
                val actions = call.arguments as? List<Map<String, String>> ?: emptyList()
                ReadiumReader.selectionActions =
                    actions.map { map ->
                        SelectionActionConfig(
                            id = map["id"] ?: "",
                            title = map["title"] ?: "",
                        )
                    }
                result.success(null)
            }

            "notifyUserNavigation" -> {
                // User swiped or edge-tapped the reader (detected by the Flutter Listener
                // above the platform view). Enter narration manual mode if narration is
                // currently driving the reader; otherwise a no-op.
                ReadiumReader.enterManualModeIfNarrating("notifyUserNavigation")
                result.success(null)
            }

            "dispose" -> {
                dispose()
                result.success(null)
            }

            else -> {
                PluginLog.w(TAG, "Unhandled call ${call.method}")
                result.notImplemented()
            }
        }
    }

    /**
     * Navigate backward in the active visual navigator.
     */
    private suspend fun goBackward(animated: Boolean) {
        PluginLog.d(TAG, "::goBackward")
        ReadiumReader.visualGoBackward(animated)
    }

    private suspend fun goForward(animated: Boolean) {
        PluginLog.d(TAG, "::goForward")
        ReadiumReader.visualGoForward(animated)
    }

    private suspend fun evaluateJavascript(script: String): String? {
        val ret = ReadiumReader.epubEvaluateJavascript(script)
        if (ret == null || ret == "null" || ret == "undefined") {
            // Hopefully can't happen.
            PluginLog.w(TAG, "::evaluateJavascript($script) returned null $ret")

            return null
        }

        return ret
    }

    private suspend fun updatePreferences(preferences: FlutterEpubPreferences) {
        ReadiumReader.epubUpdatePreferences(preferences)
    }

    companion object {
        const val NAVIGATOR_FRAGMENT_TAG = "NAVIGATOR_READER_FRAGMENT"
    }
}

/** Single owner for paginated horizontal drags. InputListener only handles JS
 * events: it cannot cancel R2WebView.onTouchEvent's separate native page turn.
 * Android interception sends ACTION_CANCEL to that child before its ACTION_UP.
 * Taps, long-press selection, vertical scrolling and multi-touch stay native.
 */
private class AppPageGestureLayout(
    context: Context,
    attrs: AttributeSet?,
    private val ownsGesture: () -> Boolean,
    private val onMarginTap: (Float, Float) -> Unit,
    private val onSwipe: (String, Float, Float, Float, Float, Float, Long) -> Unit,
) : LinearLayout(context, attrs) {
    private val configuration = ViewConfiguration.get(context)
    private val slop = configuration.scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var multiplePointers = false
    private var marginTouch = false
    private var contentTop = 0
    private var contentBottom = 0
    private var minimumStatus = 0
    private var hasReaderInsets = false
    private var systemTop = 0
    private var systemBottom = 0

    fun setReaderInsets(top: Double, bottom: Double, status: Double) {
        val density = resources.displayMetrics.density
        contentTop = kotlin.math.round(top * density).toInt()
        contentBottom = kotlin.math.round(bottom * density).toInt()
        minimumStatus = kotlin.math.round(status * density).toInt()
        if (!hasReaderInsets) {
            hasReaderInsets = true
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, window ->
                applyReaderInsets(window)
                window
            }
        }
        applyReaderInsets(ViewCompat.getRootWindowInsets(this))
    }

    private fun applyReaderInsets(window: WindowInsetsCompat?) {
        // The IME, fullscreen switches and transient system bars must not
        // resize the same book page. Use the host window, independent of how
        // Flutter is compositing the platform view under the reader chrome.
        val decor = ReadiumReader.fragmentActivity?.window?.decorView
        val root = decor?.let(ViewCompat::getRootWindowInsets) ?: window
        root?.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )?.let {
            systemTop = it.top
            systemBottom = it.bottom
        }
        val top = maxOf(systemTop, minimumStatus) + contentTop
        val bottom = systemBottom + contentBottom
        if (paddingTop != top || paddingBottom != bottom)
            setPadding(paddingLeft, top, paddingRight, bottom)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (ReadiumReader.appSelectionToolbar) ReadiumReader.appContactChanged?.invoke(true)
            recycleVelocityTracker()
            marginTouch = false
            if (ownsGesture()) velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)
        try {
            if (event.actionMasked == MotionEvent.ACTION_UP &&
                velocityTracker != null && !dragging && !multiplePointers &&
                ownsGesture() && kotlin.math.abs(event.x - downX) > slop) {
                // A short swipe can cross the slop between its last delivered
                // move and the lift when the UI thread is busy drawing.
                val cancel = MotionEvent.obtain(event)
                try {
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                } finally {
                    cancel.recycle()
                }
                emitSwipe("Start", event)
                emitSwipe("End", event, releaseVelocity())
                return true
            }
            return super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                recycleVelocityTracker()
                if (ReadiumReader.appSelectionToolbar) ReadiumReader.appContactChanged?.invoke(false)
            }
        }
    }

    override fun onDetachedFromWindow() {
        recycleVelocityTracker()
        if (ReadiumReader.appSelectionToolbar) ReadiumReader.appContactChanged?.invoke(false)
        super.onDetachedFromWindow()
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // The child may request exclusivity before it knows the drag direction.
        // Retain interception only while the app owns paginated page gestures.
        if (disallowIntercept && ownsGesture()) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                multiplePointers = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiplePointers = true
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!multiplePointers && ownsGesture() &&
                    // R2WebView tracks horizontal slop even on a diagonal drag.
                    // Own that same displacement before its native scroller starts.
                    kotlin.math.abs(dx) > slop) {
                    dragging = true
                    emitSwipe("Start", event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging && event.actionMasked == MotionEvent.ACTION_DOWN) {
            marginTouch = hasReaderInsets && (event.y < paddingTop || event.y >= height - paddingBottom)
        }
        if (marginTouch && !dragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (ownsGesture() && kotlin.math.abs(event.x - downX) > slop) {
                    dragging = true
                    marginTouch = false
                    emitSwipe("Start", event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP -> {
                    marginTouch = false
                    if (kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop)
                        onMarginTap(event.x, event.y)
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> marginTouch = false
            }
            return true
        }
        if (!dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiplePointers = true
                emitSwipe("Cancel", event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!multiplePointers && ownsGesture()) {
                    emitSwipe("Move", event)
                }
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!multiplePointers) {
                    if (ownsGesture()) {
                        emitSwipe("End", event, releaseVelocity())
                    } else {
                        emitSwipe("Cancel", event)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!multiplePointers) emitSwipe("Cancel", event)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun emitSwipe(phase: String, event: MotionEvent, velocityX: Float = 0f) {
        val y = ((if (phase == "Start") downY else event.y) / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        val startX = (downX / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        onSwipe(phase, event.x - downX, event.y - downY, y, startX, velocityX, event.eventTime - event.downTime)
    }

    private fun releaseVelocity(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
        return tracker.xVelocity
    }
}
