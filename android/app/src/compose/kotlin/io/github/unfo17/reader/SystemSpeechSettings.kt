package io.github.unfo17.reader

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.mewx.wenku8_flutter.SpeechLocalePolicy
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

data class SystemSpeechSettings(val engine: String = "@default", val voice: String = "@default", val locale: String = "zh-CN", val speed: Float = 1f, val follow: Boolean = true, val paragraphGapMillis: Int = 0) {
    fun save(context: Context) {
        require(SpeechLocalePolicy.supportsEngine(engine) && voice.isNotBlank() && voice.length <= 2048 && SpeechLocalePolicy.requestedLocale(locale) != null && speed.isFinite() && speed in .5f..3f && paragraphGapMillis in 0..3000 && paragraphGapMillis % 100 == 0) { "请检查语音引擎、语言和语速设置" }
        check(context.getSharedPreferences("system-speech", Context.MODE_PRIVATE).edit().putString("engine", engine).putString("voice", voice).putString("locale", locale).putFloat("speed", speed).putBoolean("follow", follow).putInt("paragraphGapMillis", paragraphGapMillis).commit()) { "语音设置保存失败" }
    }
    companion object {
        fun load(context: Context): SystemSpeechSettings = context.getSharedPreferences("system-speech", Context.MODE_PRIVATE).let { prefs ->
            SystemSpeechSettings(prefs.getString("engine", "@default")!!, prefs.getString("voice", "@default")!!, prefs.getString("locale", "zh-CN")!!,
                prefs.getFloat("speed", 1f).let { if (it.isFinite()) it.coerceIn(.5f, 3f) else 1f }, prefs.getBoolean("follow", true), ((prefs.getInt("paragraphGapMillis", 0).coerceIn(0, 3000) + 50) / 100) * 100)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SystemSpeechSettingsPanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val client = remember { SystemSpeechClient(context) }
    val audio = remember { SpeechAudioOutput(context) }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(SystemSpeechSettings.load(context)) }
    var engines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var voices by remember { mutableStateOf<List<SystemVoice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Job?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewEpoch by remember { mutableLongStateOf(0L) }
    var voicePicker by remember { mutableStateOf(false) }
    var detection by remember { mutableIntStateOf(0) }
    fun stopPreview() { previewEpoch++; preview?.cancel(); preview = null; audio.stop(); previewing = false }
    DisposableEffect(Unit) { onDispose { previewEpoch++; preview?.cancel(); audio.close(); client.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stopPreview() }
    LaunchedEffect(settings.engine, detection) {
        loading = true; error = null; stopPreview()
        try { engines = client.engines(); voices = if (engines.any { it.first == settings.engine }) client.voices(settings.engine) else emptyList() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("系统语音与音色", style = MaterialTheme.typography.titleLarge)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            ChoiceMenu("引擎", settings.engine, engines, enabled = !loading && !previewing) { settings = settings.copy(engine = it, voice = "@default") }
            Text("段落间隔：" + if (settings.paragraphGapMillis == 0) "不额外停顿" else "${settings.paragraphGapMillis / 1000f} 秒")
            Slider(modifier = Modifier.semantics { contentDescription = "段落间隔" }, value = settings.paragraphGapMillis.toFloat(), onValueChange = { settings = settings.copy(paragraphGapMillis = ((it / 100).roundToInt() * 100).coerceIn(0, 3000)) }, valueRange = 0f..3000f, steps = 29)
            val selectedVoice = voices.firstOrNull { it.id == settings.voice }
            OutlinedButton(enabled = !loading && !previewing, onClick = { voicePicker = true }) {
                Text("音色：" + if (settings.voice == "@default") "系统默认音色" else selectedVoice?.let { voiceLabel(it) } ?: "上次选择的音色（当前不可用）")
            }
            OutlinedTextField(settings.locale, { settings = settings.copy(locale = it) }, label = { Text("朗读语言，例如 zh-CN") }, singleLine = true, enabled = !previewing)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("朗读时跟随正文", Modifier.weight(1f)); Switch(settings.follow, { settings = settings.copy(follow = it) })
            }
            Text("语速 ${"%.2f".format(settings.speed)} 倍")
            Slider(settings.speed, { settings = settings.copy(speed = it) }, valueRange = .5f..3f, enabled = !previewing)
            if (engines.isEmpty() && !loading) Text("没有检测到系统语音引擎，请先在系统设置中安装。")
            if (voices.any { !it.installed }) Text("部分音色尚未安装，请在系统语音设置中下载语言数据。")
            Row {
                TextButton(enabled = !loading && (previewing || engines.any { it.first == settings.engine }), onClick = {
                    if (previewing) stopPreview() else {
                        if (ListeningSession.state.value.active) ListeningSession.command(context, ListeningSession.PAUSE)
                        val epoch = ++previewEpoch
                        previewing = true; error = null
                        preview = scope.launch {
                            var file: java.io.File? = null
                            try { file = client.synthesize(when (SpeechLocalePolicy.requestedLocale(settings.locale)?.language) {
                                "zh" -> "你好，这是本地书房的语音试听。"
                                "ja" -> "こんにちは。読書の時間を楽しみましょう。"
                                "ko" -> "안녕하세요. 즐거운 독서 시간입니다."
                                else -> "Hello. Welcome to your reading room."
                            }, settings.engine, settings.voice, settings.locale); audio.play(file, settings.speed) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { coroutineContext.ensureActive(); error = e.message }
                            finally { file?.delete(); if (epoch == previewEpoch) previewing = false }
                        }
                    }
                }) { Text(if (previewing) "停止试听" else "试听") }
                TextButton(enabled = !previewing, onClick = { detection++ }) { Text("重新检测") }
                TextButton(onClick = { stopPreview(); scope.launch { try { client.request("settings") } catch (e: Exception) { error = e.message } } }) { Text("系统语音设置") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { try {
                voices.firstOrNull { it.id == settings.voice }?.let { voice -> require(SpeechLocalePolicy.requestedLocale(settings.locale)?.let { SpeechLocalePolicy.matches(it, java.util.Locale.forLanguageTag(voice.locale)) } == true) { "已选音色与朗读语言不一致，请重新选择" } }
                settings.save(context); if (ListeningSession.state.value.active) ListeningSession.speed(context, settings.speed); onDismiss() } catch (e: Exception) { error = e.message } }) { Text("保存语音设置") }
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    }
    if (voicePicker) {
        var query by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { voicePicker = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("选择系统音色", style = MaterialTheme.typography.titleLarge) }
                item { OutlinedTextField(query, { query = it }, label = { Text("搜索音色或语言") }, modifier = Modifier.fillMaxWidth()) }
                item { TextButton(onClick = { settings = settings.copy(voice = "@default"); voicePicker = false }) { Text("系统默认音色") } }
                items(voices.filter { it.installed && (query.isBlank() || it.id.contains(query, true) || it.locale.contains(query, true) || java.util.Locale.forLanguageTag(it.locale).getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE).contains(query, true)) }, key = { it.id }) { voice ->
                    TextButton(onClick = { settings = settings.copy(voice = voice.id, locale = voice.locale); voicePicker = false }) { Text(voiceLabel(voice)) }
                }
                item { TextButton(onClick = { voicePicker = false }) { Text("取消选择") } }
            }
        }
    }

}

private fun voiceLabel(voice: SystemVoice) = "${voice.id} · ${voice.locale}${if (voice.networkRequired) " · 需要联网" else ""}"
