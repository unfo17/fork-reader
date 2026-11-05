import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:crypto/crypto.dart';
import 'package:xml/xml.dart';
import 'models.dart';

class CustomSpeechConfig {
  const CustomSpeechConfig({
    required this.endpoint,
    required this.voiceId,
    this.locale = 'zh-CN',
    this.key = '',
  });
  final String endpoint, voiceId, locale, key;
  String get namespace =>
      sha256.convert(utf8.encode('$endpoint|$voiceId|$locale')).toString();
  SpeechVoice get voice => SpeechVoice(
    id: voiceId,
    label: voiceId,
    source: ListeningSource.custom,
    engine: namespace,
    locale: locale,
  );
  Map<String, String> toJson() => {
    'endpoint': endpoint,
    'voiceId': voiceId,
    'locale': locale,
    'key': key,
  };
}

class CustomSpeechSettings extends ChangeNotifier {
  CustomSpeechConfig? config;
  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    try {
      final j = jsonDecode(p.getString('readium.customSpeech.v1') ?? 'null');
      if (j is Map)
        config = CustomSpeechConfig(
          endpoint: j['endpoint'],
          voiceId: j['voiceId'],
          locale: j['locale'] ?? 'zh-CN',
          key: j['key'] ?? '',
        );
    } catch (_) {
      config = null;
    }
  }

  Future<void> save(CustomSpeechConfig value) async {
    final uri = Uri.tryParse(value.endpoint);
    if (uri == null || uri.scheme != 'https' || uri.host.isEmpty)
      throw const FormatException('请输入 HTTPS 语音接口地址');
    if (value.voiceId.trim().isEmpty) throw const FormatException('请填写语音名称');
    final p = await SharedPreferences.getInstance();
    if (!await p.setString(
      'readium.customSpeech.v1',
      jsonEncode(value.toJson()),
    ))
      throw const FormatException('保存失败');
    config = value;
    notifyListeners();
  }
}

class CustomSpeechClient {
  Future<Uint8List> synthesize(
    String text,
    CustomSpeechConfig config,
    SpeechCancellation cancellation,
  ) async {
    cancellation.check();
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 20);
    final cancel = cancellation.onCancel(() => client.close(force: true));
    try {
      final request = await client.postUrl(Uri.parse(config.endpoint));
      request.headers.set('Content-Type', 'application/ssml+xml');
      request.headers.set(
        'X-Microsoft-OutputFormat',
        'audio-24khz-48kbitrate-mono-mp3',
      );
      if (config.key.isNotEmpty)
        request.headers.set('Ocp-Apim-Subscription-Key', config.key);
      // XmlText handles all text/attribute escaping without accepting markup.
      final body = XmlElement(
        XmlName('speak'),
        [
          XmlAttribute(XmlName('version'), '1.0'),
          XmlAttribute(XmlName('lang', 'xml'), config.locale),
        ],
        [
          XmlElement(
            XmlName('voice'),
            [XmlAttribute(XmlName('name'), config.voiceId)],
            [XmlText(text)],
          ),
        ],
      );
      request.add(utf8.encode(body.toXmlString()));
      final response = await request.close().timeout(
        const Duration(seconds: 35),
      );
      if (response.statusCode != 200)
        throw SpeechFailure('语音接口返回 ${response.statusCode}');
      final bytes = BytesBuilder();
      await for (final chunk in response) {
        cancellation.check();
        bytes.add(chunk);
        if (bytes.length > 16 * 1024 * 1024)
          throw const SpeechFailure('语音响应过大');
      }
      if (bytes.isEmpty) throw const SpeechFailure('语音接口返回空音频');
      return bytes.takeBytes();
    } finally {
      cancel();
      client.close(force: true);
    }
  }
}
