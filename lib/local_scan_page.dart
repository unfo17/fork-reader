import 'package:flutter/material.dart';
import 'book_documents.dart';
import 'local_book_scanner.dart';

class LocalScanPage extends StatelessWidget {
  const LocalScanPage({
    super.key,
    required this.documents,
    required this.changes,
    required this.isBusy,
    required this.progress,
    required this.result,
    required this.failure,
    required this.onScan,
  });
  final BookDocuments documents;
  final Listenable changes;
  final bool Function() isBusy;
  final (int, int)? Function() progress;
  final LocalScanResult? Function() result;
  final String? Function() failure;
  final Future<void> Function(BuildContext) onScan;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('扫描本地书籍')),
    body: AnimatedBuilder(
      animation: changes,
      builder: (context, _) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('扫描已授权的目录，选择文件后再导入。'),
            const SizedBox(height: 20),
            OutlinedButton(
              onPressed: isBusy()
                  ? null
                  : () async {
                      try {
                        await documents.selectScanFolder();
                      } catch (error) {
                        if (context.mounted)
                          ScaffoldMessenger.of(
                            context,
                          ).showSnackBar(SnackBar(content: Text('$error')));
                      }
                    },
              child: const Text('选择扫描文件夹'),
            ),
            FilledButton(
              onPressed: isBusy() ? null : () => onScan(context),
              child: Text(isBusy() ? '扫描中…' : '扫描书籍'),
            ),
            if (progress() != null)
              Text('${progress()!.$1} / ${progress()!.$2}'),
            if (result() != null) Text(result()!.summary),
            if (failure() != null) Text(failure()!),
          ],
        ),
      ),
    ),
  );
}
