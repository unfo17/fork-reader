import 'book_documents.dart';
import 'catalog.dart';
import 'local_books.dart';

typedef LocalImportNotice = ({String name, String message});

class LocalImportReport {
  const LocalImportReport({
    required this.bookIds,
    required this.added,
    required this.existing,
    this.failures = const [],
    this.notices = const [],
  });
  final List<int> bookIds;
  final int added, existing;
  final List<LocalImportNotice> failures, notices;
  String get summary =>
      '新增 $added 本 · 已有 $existing 本${failures.isEmpty ? '' : ' · 失败 ${failures.length} 本'}';
}

class LocalImportGroup {
  const LocalImportGroup({
    required this.seed,
    required this.title,
    required this.volumes,
    this.targetId,
    this.needsTarget = false,
  });
  final int seed;
  final String title;
  final List<({int bookId, String title})> volumes;
  final int? targetId;
  final bool needsTarget;
}

List<LocalImportGroup> localImportGroups(
  LocalBookStore books,
  Iterable<int> ids,
) {
  // Automatic merges are intentionally conservative. Manual series editing
  // remains available from each book; importing never guesses a target series.
  return const [];
}

Future<LocalImportReport> importLocalDocuments({
  required List<BookDocumentSelection> selections,
  required BookDocuments documents,
  required LocalBookStore books,
  required Future<Novel> Function(String, String) importFile,
  required Future<Novel> Function(BookDocumentSelection) importSelection,
  void Function(int, int)? onProgress,
  void Function(Iterable<BookDocumentSelection>)? onCleanupFailed,
  bool releaseSources = false,
}) async {
  final ids = <int>{};
  var added = 0, existing = 0, done = 0;
  final failures = <LocalImportNotice>[];
  for (final selection in selections) {
    final prepared = <BookDocumentSelection>[];
    try {
      if (selection.error case final error?) throw FormatException(error);
      prepared.addAll(await documents.prepareBook(selection));
      for (final source in prepared) {
        try {
          if (source.error case final error?) throw FormatException(error);
          final before = books.books.map((b) => b.id).toSet();
          final book =
              source.reference != null || source.originReference != null
              ? await importSelection(source)
              : await importFile(source.name, source.path!);
          ids.add(book.id);
          if (before.contains(book.id)) {
            existing++;
          } else {
            added++;
          }
        } catch (error) {
          failures.add((
            name: source.name,
            message: error is FormatException ? error.message : '导入失败：$error',
          ));
        }
      }
    } catch (error) {
      failures.add((name: selection.name, message: '$error'));
    } finally {
      // Only release generated children here; callers may still need the
      // selected original for linked imports or verified move operations.
      final cleanup = [
        ...prepared.where((p) => p.path != selection.path),
        if (releaseSources) selection,
      ];
      try {
        await documents.releaseBooks(cleanup);
      } catch (_) {
        onCleanupFailed?.call(cleanup);
      }
      onProgress?.call(++done, selections.length);
    }
  }
  return LocalImportReport(
    bookIds: ids.toList(),
    added: added,
    existing: existing,
    failures: failures,
  );
}
