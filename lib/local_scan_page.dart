import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_ui.dart';
import 'archive_feature_ui.dart';
import 'book_documents.dart';
import 'book_import_limits.dart';
import 'local_book_scanner.dart';

class LocalScanPage extends StatefulWidget {
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
  State<LocalScanPage> createState() => _LocalScanPageState();
}

class _LocalScanPageState extends State<LocalScanPage> {
  BookScanFolder? _folder;
  Set<String>? _formats;
  bool _changing = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _change(() async {
      _folder = await widget.documents.scanFolderInfo();
      _formats = await widget.documents.scanFormats();
    });
  }

  Future<void> _change(Future<void> Function() action) async {
    if (_changing || widget.isBusy()) return;
    setState(() {
      _changing = true;
      _error = null;
    });
    try {
      await action();
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _changing = false);
    }
  }

  Future<void> _choose({required bool all}) async {
    var shouldScan = false;
    await _change(() async {
      final folder = all
          ? await widget.documents.selectAllScan()
          : await widget.documents.selectScanFolder();
      if (folder == null) return;
      _folder = folder;
      shouldScan = folder.readable;
    });
    if (mounted && shouldScan) await widget.onScan(context);
  }

  void _saveFormats(Set<String> value) => _change(() async {
    await widget.documents.setScanFormats(value);
    _formats = value;
  });

  @override
  Widget build(BuildContext context) => ArchiveFeaturePage(
    title: '扫描本地书籍',
    code: 'LOCAL / SCAN',
    child: ListenableBuilder(
      listenable: widget.changes,
      builder: (context, _) {
        final available = bookFormatLabels;
        final selected = _formats ?? const <String>{};
        final busy = _changing || widget.isBusy();
        final folder = _folder;
        final result = widget.result();
        return Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 680),
            child: ListView(
              padding: featurePadding(context),
              children: [
                ContentPanel(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const AppText(
                        '扫描格式',
                        style: TextStyle(fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 10),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          GlassTextButton(
                            compact: true,
                            selected: selected.containsAll(available.keys),
                            onPressed: busy
                                ? null
                                : () => _saveFormats(available.keys.toSet()),
                            child: const AppText('全部格式'),
                          ),
                          for (final entry in available.entries)
                            GlassTextButton(
                              compact: true,
                              selected: selected.contains(entry.key),
                              onPressed: busy
                                  ? null
                                  : () {
                                      final next = {...selected};
                                      next.remove(entry.key) ||
                                          next.add(entry.key);
                                      _saveFormats(next);
                                    },
                              child: AppText(entry.value),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 18),
                ContentPanel(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const AppText(
                        '选择扫描范围',
                        style: TextStyle(fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 8),
                      const AppText(
                        '先扫描候选文件，再由你勾选导入。全部扫描需要共享存储访问权限，不扫描其他 App 的私有目录。',
                      ),
                      const SizedBox(height: 12),
                      GlassTextButton(
                        onPressed: busy || selected.isEmpty
                            ? null
                            : () => _choose(all: true),
                        child: const AppText('全部扫描'),
                      ),
                      const SizedBox(height: 10),
                      GlassTextButton(
                        onPressed: busy || selected.isEmpty
                            ? null
                            : () => _choose(all: false),
                        child: const AppText('指定文件夹扫描'),
                      ),
                      if (folder != null) ...[
                        const SizedBox(height: 14),
                        AppText(
                          folder.readable
                              ? folder.name
                              : '${folder.name} · 需要重新授权',
                        ),
                        if (folder.readable) ...[
                          const SizedBox(height: 8),
                          GlassTextButton(
                            onPressed: busy || selected.isEmpty
                                ? null
                                : () => widget.onScan(context),
                            child: const AppText('重新扫描'),
                          ),
                          Row(
                            children: [
                              const Expanded(child: AppText('自动扫描新增书籍')),
                              AppControls.toggle(
                                context,
                                value: folder.automatic,
                                onChanged: busy
                                    ? null
                                    : (enabled) => _change(() async {
                                        await widget.documents.setScanAutomatic(
                                          enabled,
                                        );
                                        _folder = await widget.documents
                                            .scanFolderInfo();
                                      }),
                              ),
                            ],
                          ),
                        ],
                        GlassTextButton(
                          onPressed: busy
                              ? null
                              : () => _change(() async {
                                  await widget.documents.disconnectScanFolder();
                                  _folder = null;
                                }),
                          child: const AppText('断开扫描范围'),
                        ),
                      ],
                    ],
                  ),
                ),
                if (busy) ...[
                  const SizedBox(height: 14),
                  RefreshProgress(
                    label: switch (widget.progress()) {
                      (final done, final total) => '正在导入 $done / $total 本',
                      _ => '正在准备扫描',
                    },
                  ),
                ],
                if (result != null) ...[
                  const SizedBox(height: 14),
                  AppText(result.summary),
                  for (final warning in result.warnings) AppText(warning),
                ],
                FeatureError(_error ?? widget.failure()),
                const SizedBox(height: 18),
                GlassTextButton(
                  onPressed: () => showJellySheet<void>(
                    context: context,
                    reduceMotion: MediaQuery.disableAnimationsOf(context),
                    builder: (_) => Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const ArchiveSection(
                            title: '支持格式与大小',
                            code: 'LOCAL / FORMATS',
                          ),
                          const SizedBox(height: 12),
                          AppText(bookImportFormatsSummary),
                          const SizedBox(height: 8),
                          AppText(bookImportLimitsSummary),
                        ],
                      ),
                    ),
                  ),
                  child: const AppText('支持格式与大小'),
                ),
              ],
            ),
          ),
        );
      },
    ),
  );
}
