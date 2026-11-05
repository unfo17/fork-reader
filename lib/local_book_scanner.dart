import 'book_documents.dart';
import 'local_import_batch.dart';

class LocalScanResult {
  const LocalScanResult({
    required this.report,
    required this.total,
    required this.unchanged,
    this.warnings = const [],
    this.pending = 0,
  });
  final LocalImportReport report;
  final int total, unchanged, pending;
  final List<String> warnings;
  String get summary => '${report.summary} · 待导入 $pending 本';
}

Future<LocalScanResult> scanLocalBooks({
  required BookDocuments documents,
  required BookScanSnapshot snapshot,
  required Set<String> selectedTokens,
  required bool linkText,
  required Future<LocalImportReport> Function(BookDocumentSelection)
  importSource,
  required bool Function() current,
  void Function(int, int)? onProgress,
}) async {
  var added = 0, existing = 0, done = 0;
  final ids = <int>{};
  final failures = <LocalImportNotice>[];
  final selected = snapshot.books
      .where((b) => selectedTokens.contains(b.token))
      .toList();
  try {
    for (final item in selected) {
      if (!current()) break;
      BookDocumentSelection? source;
      try {
        source = linkText
            ? await documents.linkScanBook(item.token)
            : await documents.stageScanBook(item.token);
        final report = await importSource(source);
        added += report.added;
        existing += report.existing;
        ids.addAll(report.bookIds);
        failures.addAll(report.failures);
        if (report.failures.isEmpty) await documents.markScanBook(item.token);
      } catch (error) {
        failures.add((name: item.name, message: '$error'));
      } finally {
        if (source != null) await documents.releaseBooks([source]);
        onProgress?.call(++done, selected.length);
      }
    }
  } finally {
    await documents.finishBookScan();
  }
  return LocalScanResult(
    report: LocalImportReport(
      bookIds: ids.toList(),
      added: added,
      existing: existing,
      failures: failures,
    ),
    total: snapshot.total,
    unchanged: snapshot.unchanged,
    warnings: snapshot.warnings,
    pending: selected.length - done,
  );
}
