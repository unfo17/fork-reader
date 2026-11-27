import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_ui.dart';
import 'archive_feature_ui.dart';
import 'book_documents.dart';
import 'book_import_limits.dart';

class LocalScanSelection extends StatefulWidget {
  const LocalScanSelection({super.key, required this.snapshot});
  final BookScanSnapshot snapshot;

  @override
  State<LocalScanSelection> createState() => _SelectionState();
}

class _SelectionState extends State<LocalScanSelection> {
  final _selected = <String>{};
  String _query = '';
  String? _format;

  @override
  Widget build(BuildContext context) {
    final terms = _query.toLowerCase().trim().split(RegExp(r'\s+'));
    final snapshot = widget.snapshot;
    final formats = snapshot.books
        .map((book) => bookFormatKey(book.name))
        .toSet();
    final visible = snapshot.books
        .where((book) {
          if (_format != null && bookFormatKey(book.name) != _format)
            return false;
          final searchable =
              '${book.name} ${snapshot.details[book.token]?.folder ?? ''}'
                  .toLowerCase();
          return terms.every(searchable.contains);
        })
        .toList(growable: false);
    final allSelected =
        visible.isNotEmpty &&
        visible.every((book) => _selected.contains(book.token));
    return ArchiveFeaturePage(
      title: '选择要导入的书籍',
      code: 'LOCAL / CANDIDATES',
      actions: [
        GlassIconButton(
          icon: CupertinoIcons.arrow_down_doc,
          label: '导入（${_selected.length} 本）',
          onPressed: _selected.isEmpty
              ? null
              : () => Navigator.pop(context, _selected),
        ),
      ],
      child: CustomScrollView(
        slivers: [
          SliverPadding(
            padding: featurePadding(context).copyWith(bottom: 12),
            sliver: SliverToBoxAdapter(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextField(
                    decoration: const InputDecoration(labelText: '搜索文件名、文件夹'),
                    onChanged: (value) => setState(() => _query = value),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      GlassTextButton(
                        selected: _format == null,
                        onPressed: () => setState(() => _format = null),
                        child: const AppText('全部格式'),
                      ),
                      for (final format in formats)
                        GlassTextButton(
                          selected: _format == format,
                          onPressed: () => setState(() => _format = format),
                          child: AppText(
                            bookFormatLabels[format] ?? format.toUpperCase(),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  GlassTextButton(
                    onPressed: visible.isEmpty
                        ? null
                        : () => setState(() {
                            final ids = visible.map((book) => book.token);
                            allSelected
                                ? _selected.removeAll(ids)
                                : _selected.addAll(ids);
                          }),
                    child: AppText(allSelected ? '取消选择结果' : '全选结果'),
                  ),
                  const SizedBox(height: 12),
                  AppText('显示 ${visible.length} 本 · 已选 ${_selected.length} 本'),
                  for (final warning in snapshot.warnings) AppText(warning),
                ],
              ),
            ),
          ),
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            sliver: SliverList.builder(
              itemCount: visible.length,
              itemBuilder: (context, index) {
                final book = visible[index];
                final detail = snapshot.details[book.token];
                return CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  title: AppText(book.name),
                  subtitle: detail == null
                      ? null
                      : AppText(
                          [
                            if (detail.folder.isNotEmpty) detail.folder,
                            if (detail.size case final bytes?)
                              '${(bytes / 1024).ceil()} KiB',
                          ].join(' · '),
                        ),
                  value: _selected.contains(book.token),
                  onChanged: (value) => setState(() {
                    value == true
                        ? _selected.add(book.token)
                        : _selected.remove(book.token);
                  }),
                );
              },
            ),
          ),
          SliverPadding(
            padding: EdgeInsets.only(
              bottom: MediaQuery.paddingOf(context).bottom + 24,
            ),
          ),
        ],
      ),
    );
  }
}
