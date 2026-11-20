package org.mewx.wenku8_flutter

import dk.nota.flutterreadium.ReadiumReader
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Narrow app-owned bridge to the pinned Readium navigator, not another WebView. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadiumControlsPlugin : FlutterPlugin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var channel: MethodChannel? = null
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "org.mewx.wenku8/readium_controls").also { ch ->
            ch.setMethodCallHandler { call, result ->
                scope.launch {
                    try {
                        when (call.method) {
                            "locator" -> result.success(ReadiumReader.appCurrentLocatorJson())
                            "pageKey" -> result.success(ReadiumReader.appPageKeyJson())
                            "fixedLayout" -> result.success(ReadiumReader.appIsFixedLayout())
                            "clearSelection" -> {
                                ReadiumReader.appSelectionMode?.finish()
                                ReadiumReader.epubEvaluateJavascript("window.getSelection().removeAllRanges();")
                                result.success(null)
                            }
                            "ready" -> result.success(ReadiumReader.appWaitForPageReady(call.argument<String>("style"), call.argument<String>("anchor")))
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
                            "capture" -> result.success(ReadiumReader.appCapturePage(call.argument<Boolean>("bands") == true, call.argument<Boolean>("vertical") == true))
                            "decorations" -> {
                                val marks = call.argument<List<Map<String, Any>>>("marks") ?: emptyList()
                                ReadiumReader.applyAppDecorations(marks, call.argument<String>("group") ?: "wenku")
                                result.success(null)
                            }
                            "style" -> {
                                val script = call.argument<String>("script") ?: ""
                                result.success(ReadiumReader.appApplyStyle(script))
                            }
                            "evaluate" -> result.success(ReadiumReader.epubEvaluateJavascript(call.argument<String>("script") ?: ""))
                            else -> result.notImplemented()
                        }
                    } catch (error: Exception) { result.error("readium_control", error.message, null) }
                }
            }
        }
        ReadiumReader.appSelectionDidEnd = { channel?.invokeMethod("selectionEnded", null) }
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        ReadiumReader.appSelectionDidEnd = null
        channel?.setMethodCallHandler(null); channel = null; scope.cancel()
    }
}
