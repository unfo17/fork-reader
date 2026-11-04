package io.github.unfo17.reader;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Test APK only: real Android TTS binding and WAV output, with deterministic silent PCM. */
public final class TestSpeechService extends TextToSpeechService {
    private volatile boolean stopped;
    @Override protected String[] onGetLanguage() { return new String[]{"zho", "CHN", ""}; }
    @Override protected int onIsLanguageAvailable(String language, String country, String variant) {
        return Arrays.asList("zh", "zho", "chi", "en", "eng").contains(language) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected int onLoadLanguage(String language, String country, String variant) { return onIsLanguageAvailable(language, country, variant); }
    @Override public List<Voice> onGetVoices() { return Arrays.asList(
        new Voice("test.zh", Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()),
        new Voice("test.en", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet())); }
    @Override public String onGetDefaultVoiceNameFor(String language, String country, String variant) { return language.equals("eng") || language.equals("en") ? "test.en" : "test.zh"; }
    @Override public int onIsValidVoiceName(String name) { return name.equals("test.zh") || name.equals("test.en") ? TextToSpeech.SUCCESS : TextToSpeech.ERROR; }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopped = false;
        sendBroadcast(new android.content.Intent("io.github.unfo17.reader.TEST_SPEECH_REQUEST")
            .setPackage("io.github.unfo17.reader").putExtra("text", request.getCharSequenceText().toString()).putExtra("voice", request.getVoiceName()));
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        if (request.getCharSequenceText().toString().startsWith("[test-wait]")) {
            sendBroadcast(new android.content.Intent("io.github.unfo17.reader.TEST_SPEECH_STARTED")
                .setPackage("io.github.unfo17.reader").putExtra("text", request.getCharSequenceText().toString()));
            long end = android.os.SystemClock.uptimeMillis() + 10000;
            while (!stopped && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(10);
        }
        if (stopped) { callback.error(TextToSpeech.STOPPED); return; }
        int blocks = request.getCharSequenceText().toString().startsWith("[test-audio-session]") ? 750 : request.getCharSequenceText().toString().startsWith("[test-audio-long]") ? 150 : 1;
        for (int i = 0; i < blocks && !stopped; i++) callback.audioAvailable(new byte[640], 0, 640);
        callback.done();
    }
    @Override protected void onStop() { stopped = true; }
}
