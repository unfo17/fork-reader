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
                            "page" -> {
                                val animated = call.argument<Boolean>("animated") ?: true
                                if ((call.argument<Int>("direction") ?: 1) > 0) ReadiumReader.visualGoForward(animated)
                                else ReadiumReader.visualGoBackward(animated)
                                result.success(null)
                            }
                            "decorations" -> {
                                val marks = call.argument<List<Map<String, Any>>>("marks") ?: emptyList()
                                ReadiumReader.applyAppDecorations(marks, call.argument<String>("group") ?: "wenku")
                                result.success(null)
                            }
                            "style" -> {
                                val script = call.argument<String>("script") ?: ""
                                ReadiumReader.appReaderStyleScript = script
                                result.success(ReadiumReader.epubEvaluateJavascript(script))
                            }
                            "evaluate" -> result.success(ReadiumReader.epubEvaluateJavascript(call.argument<String>("script") ?: ""))
                            else -> result.notImplemented()
                        }
                    } catch (error: Exception) { result.error("readium_control", error.message, null) }
                }
            }
        }
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null); channel = null; scope.cancel()
    }
}
