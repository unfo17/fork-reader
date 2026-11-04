package org.mewx.wenku8_flutter

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Original Flutter channel API; shared native speech behavior lives in SystemSpeechCore. */
class SystemSpeechPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var core: SystemSpeechCore
    private lateinit var channel: MethodChannel
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        core = SystemSpeechCore(binding.applicationContext)
        channel = MethodChannel(binding.binaryMessenger, "org.mewx.wenku8/system_speech")
        channel.setMethodCallHandler(this)
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        core.close()
    }
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        @Suppress("UNCHECKED_CAST")
        val arguments = call.arguments as? Map<String, Any?> ?: emptyMap()
        core.handle(SystemSpeechCore.Call(call.method, arguments), object : SystemSpeechCore.Result {
            override fun success(value: Any?) = result.success(value)
            override fun error(code: String, message: String?, details: Any?) = result.error(code, message, details)
            override fun notImplemented() = result.notImplemented()
        })
    }
}
