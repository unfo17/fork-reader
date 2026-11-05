import 'package:flutter/material.dart';
import 'custom_tts.dart';
import 'controller.dart';

class CustomSpeechPage extends StatefulWidget {
  const CustomSpeechPage({super.key, required this.settings, this.controller});
  final CustomSpeechSettings settings;
  final ListeningController? controller;
  @override
  State<CustomSpeechPage> createState() => _CustomState();
}

class _CustomState extends State<CustomSpeechPage> {
  late final endpoint = TextEditingController(
    text: widget.settings.config?.endpoint,
  );
  late final voice = TextEditingController(
    text: widget.settings.config?.voiceId ?? 'zh-CN-XiaoxiaoNeural',
  );
  late final key = TextEditingController(text: widget.settings.config?.key);
  bool busy = false;
  String? error;
  @override
  void dispose() {
    endpoint.dispose();
    voice.dispose();
    key.dispose();
    super.dispose();
  }

  Future<void> save() async {
    setState(() => busy = true);
    try {
      await widget.settings.save(
        CustomSpeechConfig(
          endpoint: endpoint.text.trim(),
          voiceId: voice.text.trim(),
          key: key.text.trim(),
        ),
      );
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) setState(() => error = '$e');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('自定义语音接口')),
    body: ListView(
      padding: const EdgeInsets.all(24),
      children: [
        TextField(
          controller: endpoint,
          decoration: const InputDecoration(labelText: 'HTTPS SSML 接口'),
        ),
        TextField(
          controller: voice,
          decoration: const InputDecoration(labelText: '语音名称'),
        ),
        TextField(
          controller: key,
          obscureText: true,
          decoration: const InputDecoration(labelText: '接口密钥（可选，仅保存在本机）'),
        ),
        if (error != null) Text(error!),
        const SizedBox(height: 24),
        FilledButton(onPressed: busy ? null : save, child: const Text('保存')),
      ],
    ),
  );
}
