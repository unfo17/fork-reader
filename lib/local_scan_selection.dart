import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_search_field.dart';
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
    final counts = <String, int>{};
    for (final book in snapshot.books) {
      counts.update(
        bookFormatKey(book.name),
        (count) => count + 1,
        ifAbsent: () => 1,
      );
    }
    final visible = snapshot.books
        .where((book) {
          if (_format != null && bookFormatKey(book.name) != _format) {
            return false;
          }
          final searchable =
              '${book.name} ${snapshot.details[book.token]?.folder ?? ''}'
                  .toLowerCase();
          return terms.every(searchable.contains);
        })
        .toList(growable: false);
    final allSelected =
        visible.isNotEmpty &&
        visible.every((book) => _selected.contains(book.token));
    final labels = bookFormatLabels;
    return ArchiveFeaturePage(
      title: '选择要导入的书籍',
      code: 'LOCAL / CANDIDATES',
      backLabel: '取消导入',
      actions: [
        GlassTextButton(
          compact: true,
          onPressed: visible.isEmpty
              ? null
              : () => setState(() {
                  final ids = visible.map((book) => book.token);
                  if (allSelected) {
                    _selected.removeAll(ids);
                  } else {
                    _selected.addAll(ids);
                  }
                }),
          child: AppText(allSelected ? '取消选择结果' : '全选结果'),
        ),
      ],
      child: FeatureActionLayout(
        actions: [
          Expanded(
            child: GlassTextButton(
              onPressed: _selected.isEmpty
                  ? null
                  : () => Navigator.pop(context, Set<String>.of(_selected)),
              child: FeatureActionLabel(
                CupertinoIcons.arrow_down_doc,
                _selected.isEmpty ? '请选择书籍' : '导入 ${_selected.length} 本书',
              ),
            ),
          ),
        ],
        builder: (context, bottom) => CustomScrollView(
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          slivers: [
            SliverPadding(
              padding: featurePadding(context).copyWith(bottom: 12),
              sliver: SliverToBoxAdapter(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    AppSearchField(
                      hint: '搜索文件名、文件夹',
                      onChanged: (value) => setState(() => _query = value),
                    ),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 8,
                      runSpacing: 0,
                      children: [
                        GlassTextButton(
                          compact: true,
                          selected: _format == null,
                          onPressed: () => setState(() => _format = null),
                          child: const AppText('全部格式'),
                        ),
                        for (final entry in counts.entries)
                          GlassTextButton(
                            compact: true,
                            selected: _format == entry.key,
                            onPressed: () =>
                                setState(() => _format = entry.key),
                            child: AppText(
                              '${labels[entry.key] ?? entry.key.toUpperCase()} ${entry.value}',
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    AppText(
                      '显示 ${visible.length} / ${snapshot.books.length} 个文件 · 已选 ${_selected.length} 个',
                    ),
                    for (final warning in snapshot.warnings)
                      FeatureError(warning),
                  ],
                ),
              ),
            ),
            SliverPadding(
              padding: EdgeInsets.fromLTRB(24, 0, 24, bottom),
              sliver: SliverList.builder(
                itemCount: visible.length,
                itemBuilder: (context, index) {
                  final book = visible[index];
                  final detail = snapshot.details[book.token];
                  final format = bookFormatKey(book.name);
                  final selected = _selected.contains(book.token);
                  return Padding(
                    key: ValueKey(book.token),
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Semantics(
                      checked: selected,
                      child: ContentPanel(
                        onTap: () => setState(() {
                          if (!_selected.remove(book.token)) {
                            _selected.add(book.token);
                          }
                        }),
                        child: Row(
                          children: [
                            const ArchiveIcon(CupertinoIcons.doc, size: 20),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  AppText(
                                    book.name,
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  AppText(
                                    [
                                      labels[format] ?? format.toUpperCase(),
                                      if (detail?.size case final bytes?)
                                        '${(bytes / 1048576).toStringAsFixed(1)} MiB',
                                    ].join(' · '),
                                  ),
                                  if (detail != null &&
                                      detail.folder.isNotEmpty)
                                    AppText(
                                      detail.folder,
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis,
                                      style: Theme.of(
                                        context,
                                      ).textTheme.bodySmall,
                                    ),
                                ],
                              ),
                            ),
                            const SizedBox(width: 12),
                            FeatureSelectionMark(selected: selected),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
