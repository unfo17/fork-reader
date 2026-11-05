import 'dart:io';
import 'dart:ui';

typedef PreparedBookDocument = ({String name, String? path, String? error});

/// Android uses the existing native converters; no second conversion engine.
class PortableBookPreparer {
  PortableBookPreparer({
    required this.directory,
    required RootIsolateToken textCodecToken,
    required Future<List<String>> Function(List<String>) decodeNames,
    required this.convert,
    required Future<int> Function(String, String) extractKindle,
    Future<void> Function(String, String, String)? extractPdf,
  });
  final Directory directory;
  final Future<List<PreparedBookDocument>> Function(String, String) convert;
  static Future<void> clearPreviousRuns(Directory directory) async {
    if (await directory.exists()) await directory.delete(recursive: true);
    await directory.create(recursive: true);
  }

  Future<void> releasePaths(Iterable<String> paths) async {
    final prefix = "${directory.absolute.path}/";
    for (final path in paths) {
      if (File(path).absolute.path.startsWith(prefix) &&
          await File(path).exists())
        await File(path).delete();
    }
  }

  Future<List<PreparedBookDocument>> prepare(String name, String path) =>
      convert(name, path);
}
