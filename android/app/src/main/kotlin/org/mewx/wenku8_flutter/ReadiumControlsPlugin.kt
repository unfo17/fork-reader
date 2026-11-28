package org.mewx.wenku8_flutter

import android.content.res.Resources
import android.os.Build
import android.webkit.WebView
import dk.nota.flutterreadium.ReadiumReader
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/** Narrow app-owned bridge to the pinned Readium navigator, not another WebView. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadiumControlsPlugin : FlutterPlugin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var channel: MethodChannel? = null
    private val selectionThemes = WeakHashMap<Resources.Theme, Resources.Theme>()
    private var selectionStyle = 0

    private fun updateSelectionStyle(dark: Boolean?) {
        if (Build.VERSION.SDK_INT < 31) return
        val style = when (dark) {
            true -> R.style.ReaderSelectionMonetDark
            false -> R.style.ReaderSelectionMonetLight
            null -> 0
        }
        if (style == selectionStyle) return
        restoreSelectionThemes()
        selectionStyle = style
    }

    private fun applySelectionStyle(view: WebView) {
        if (selectionStyle == 0) return
        // WebView reads the native selection drawables from its own theme.
        val theme = view.context.theme
        selectionThemes.getOrPut(theme) {
            val original = view.resources.newTheme().apply { setTo(theme) }
            theme.applyStyle(selectionStyle, true)
            original
        }
    }

    private fun restoreSelectionThemes() {
        selectionThemes.forEach { (theme, original) -> theme.setTo(original) }
        selectionThemes.clear()
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "org.mewx.wenku8/readium_controls").also { ch ->
            ch.setMethodCallHandler { call, result ->
                scope.launch {
                    val started = android.os.SystemClock.elapsedRealtime()
                    try {
                        when (call.method) {
                            "visualPage" -> result.success(ReadiumReader.appVisualPageState())
                            "jump" -> result.success(ReadiumReader.appJumpToPage(call.argument<String>("locator") ?: "{}", call.argument<Boolean>("seek") == true))
                            "locator" -> result.success(ReadiumReader.appCurrentLocatorJson())
                            "pageKey" -> result.success(ReadiumReader.appPageKeyJson())
                            "parkPublication" -> {
                                updateSelectionStyle(null)
                                ReadiumReader.visualClose()
                                result.success(null)
                            }
                            "fixedLayout" -> result.success(ReadiumReader.appIsFixedLayout())
                            "viewportInsets" -> {
                                ReadiumReader.appSetViewportInsets(
                                    call.argument<Number>("top")?.toDouble() ?: 0.0,
                                    call.argument<Number>("bottom")?.toDouble() ?: 0.0,
                                    call.argument<Number>("minimumStatus")?.toDouble() ?: 0.0,
                                )
                                result.success(null)
                            }
                            "clearSelection" -> {
                                ReadiumReader.appSelectionMode?.finish()
                                ReadiumReader.epubEvaluateJavascript("window.getSelection().removeAllRanges();")
                                result.success(null)
                            }
                            "ready" -> result.success(ReadiumReader.appWaitForPageReady(
                                call.argument<String>("style"),
                                call.argument<String>("anchor"),
                                call.argument<Int>("previewVersion"),
                                call.argument<Boolean>("stable") == true,
                                call.argument<Boolean>("waitForPaint") != false,
                            ))
                            "tap" -> { ReadiumReader.appReplayTap(call.argument<Double>("x") ?: 0.5, call.argument<Double>("y") ?: 0.5); result.success(null) }
                            "press" -> {
                                ReadiumReader.appReplayPress(call.argument<String>("phase") ?: "",
                                    call.argument<Double>("x") ?: 0.5, call.argument<Double>("y") ?: 0.5)
                                result.success(null)
                            }
                            "previewVersion" -> result.success(ReadiumReader.appPreviewVersion)
                            "interruptPreview" -> { ReadiumReader.appInterruptPreview(); result.success(null) }
                            "canTurn" -> result.success(ReadiumReader.appCanTurnPage(call.argument<Int>("direction") ?: 1))
                            "land" -> result.success(ReadiumReader.appLandPage(call.argument<String>("anchor") ?: "{}"))
                            "position" -> { ReadiumReader.appPositionPage(call.argument<String>("anchor") ?: "{}"); result.success(null) }
                            "page" -> {
                                val animated = call.argument<Boolean>("animated") ?: true
                                val moved = ReadiumReader.appTurnPage(call.argument<Int>("direction") ?: 1, animated)
                                result.success(mapOf("moved" to moved, "locator" to ReadiumReader.appPageKeyJson(), "resourceChanged" to ReadiumReader.appPageTurnCrossedResource))
                            }
                            "paper" -> {
                                call.argument<Number>("color")?.let { ReadiumReader.appSetPaper(it.toInt()) }
                                result.success(null)
                            }
                            "capture" -> {
                                val includeBands = call.argument<Boolean>("bands") == true
                                val frame = ReadiumReader.appCapturePage(
                                    includeBands,
                                    call.argument<Boolean>("vertical") == true,
                                    call.argument<Boolean>("allowLateCapture") == true,
                                    call.argument<Number>("paper")?.toInt(),
                                    fullResolution = call.argument<Boolean>("fullResolution") == true,
                                )
                                if (includeBands && android.util.Log.isLoggable("ReadiumControlTiming", android.util.Log.DEBUG)) {
                                    val count = (frame?.get("bands") as? List<*>)?.size ?: 0
                                    android.util.Log.d("ReadiumControlTiming", "paragraphBands $count")
                                }
                                // Encoding a full native-resolution RGBA reply is expensive.
                                // MethodChannel.Result may reply from a worker thread.
                                withContext(Dispatchers.Default) { result.success(frame) }
                            }
                            "decorations" -> {
                                val marks = call.argument<List<Map<String, Any>>>("marks") ?: emptyList()
                                ReadiumReader.applyAppDecorations(marks, call.argument<String>("group") ?: "wenku")
                                result.success(null)
                            }
                            "style" -> {
                                val script = call.argument<String>("script") ?: ""
                                result.success(ReadiumReader.appApplyStyle(script, ::applySelectionStyle))
                            }
                            "palette" -> {
                                val script = call.argument<String>("script") ?: ""
                                val fallback = call.argument<Number>("paper")?.toInt() ?: android.graphics.Color.WHITE
                                updateSelectionStyle(call.argument<Boolean>("darkSelectionPalette"))
                                result.success(ReadiumReader.appApplyPalette(
                                    script,
                                    fallback,
                                    call.argument<String>("backgroundScript"),
                                    call.argument<Boolean>("awaitPaint") == true,
                                    ::applySelectionStyle,
                                ))
                            }
                            "evaluate" -> result.success(ReadiumReader.epubEvaluateJavascript(call.argument<String>("script") ?: ""))
                            else -> result.notImplemented()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) { result.error("readium_control", error.message, null) }
                    finally {
                        if (android.util.Log.isLoggable("ReadiumControlTiming", android.util.Log.DEBUG))
                            android.util.Log.d("ReadiumControlTiming", "${call.method} ${android.os.SystemClock.elapsedRealtime() - started}ms")
                    }
                }
            }
        }
        ReadiumReader.appSelectionDidStart = { channel?.invokeMethod("selectionStarted", null) }
        ReadiumReader.appSelectionDidEnd = { channel?.invokeMethod("selectionEnded", null) }
        ReadiumReader.appSelectionChanged = { channel?.invokeMethod("selectionChanged", it) }
        ReadiumReader.appContactChanged = { channel?.invokeMethod("pageContact", it) }
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        restoreSelectionThemes()
        ReadiumReader.appReaderWebViewSetup = null
        ReadiumReader.appSelectionDidStart = null
        ReadiumReader.appSelectionDidEnd = null
        ReadiumReader.appSelectionChanged = null
        ReadiumReader.appContactChanged = null
        channel?.setMethodCallHandler(null); channel = null; scope.cancel()
    }
}
