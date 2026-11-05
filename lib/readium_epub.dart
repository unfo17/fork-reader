import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_readium/flutter_readium.dart';
import 'package:path_provider/path_provider.dart';

import 'local_document_input.dart';

/// Readium owns EPUB package, metadata, spine and navigation parsing. The
/// existing renderer consumes the resulting manifest in a worker isolate.
Future<Map<String, dynamic>> loadReadiumEpub({
  String? path,
  Uint8List? bytes,
  LocalDocumentInput? document,
}) async {
  if (!Platform.isAndroid && !Platform.isIOS) {
    throw const FormatException('Readium EPUB 解析目前支持 Android 与 iOS');
  }
  Directory? staging;
  try {
    // Scoped-storage descriptors cannot be reopened by the native toolkit.
    // Keep the lease alive while streaming a temporary, byte-identical EPUB.
    var source = document?.path ?? path;
    if (source == null) {
      staging = await (await getTemporaryDirectory()).createTemp('readium-');
      final file = File('${staging.path}/publication.epub');
      if (bytes != null) {
        await file.writeAsBytes(bytes, flush: true);
      } else {
        final sink = file.openWrite();
        try {
          await sink.addStream(document!.bytes());
        } finally {
          await sink.close();
        }
      }
      source = file.path;
    }
    // loadPublication closes its native publication after serialization and
    // does not replace the active reader (unlike openPublication).
    final publication = await FlutterReadium().loadPublication(
      File(source).absolute.uri.toString(),
    );
    if (publication.readingOrder.isEmpty) {
      throw const FormatException('Readium 未找到 EPUB 正文');
    }
    return publication.toJson();
  } finally {
    if (staging != null) await staging.delete(recursive: true);
  }
}
