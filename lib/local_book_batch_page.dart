import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_interactions.dart';
import 'app_search_field.dart';
import 'app_ui.dart';
import 'archive_feature_ui.dart';
import 'book_categories.dart';
import 'book_search.dart';
import 'catalog.dart';
import 'library_state.dart';
import 'local_book_removal.dart';
import 'local_books.dart';

Future<void> openLocalBookBatch(
  BuildContext context, {
  required LocalBookStore books,
  required LibraryState library,
  Set<int> selected = const {},
}) => pushNovelPage<void>(
  context,
  _Batch(books: books, library: library, selected: selected),
  reduceMotion: library.reduceMotion,
);

class _Batch extends StatefulWidget {
  const _Batch({
    required this.books,
    required this.library,
    required this.selected,
  });
  final LocalBookStore books;
  final LibraryState library;
  final Set<int> selected;

  @override
  State<_Batch> createState() => _BatchState();
}

class _BatchState extends State<_Batch> {
  late final _selected = {...widget.selected};
  late final _changes = Listenable.merge([
    widget.books,
    widget.library.organization,
    widget.library.shelfViewChanges,
  ]);
  String _query = '';
  bool _busy = false;
  (int, int)? _progress;

  Future<void> _move(Set<int> ids) async {
    final organization = widget.library.organization;
    final group = await chooseBookCategory(
      context,
      organization,
      bookIds: ids,
      reduceMotion: widget.library.reduceMotion,
    );
    if (group == null || !mounted) return;
    await organization.assignMany(ids.where(widget.books.contains), group);
    if (mounted) {
      showNovelFeedback(context, '已移动至「${organization.nameOf(group)}」');
    }
  }

  Future<void> _remove(Set<int> ids) async {
    final result = await chooseLocalBooksRemoval(
      context,
      ids: ids,
      books: widget.books,
      library: widget.library,
      onProgress: (done, total) {
        if (mounted) setState(() => _progress = (done, total));
      },
    );
    if (result == null || !mounted) return;
    _selected.removeWhere((id) => !widget.books.contains(id));
    final failed = result.failed.length;
    showNovelFeedback(
      context,
      '已移除 ${result.removed.length} 本书${failed == 0 ? '' : '，$failed 本失败，请重试'}',
    );
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) return;
    FocusScope.of(context).unfocus();
    setState(() => _busy = true);
    try {
      await action();
    } catch (error) {
      if (mounted) {
        showNovelFeedback(
          context,
          error is FormatException ? error.message : '整理失败，请重试',
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _progress = null;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _changes,
    builder: (context, _) {
      final library = widget.library;
      final organization = library.organization;
      final books = widget.books.books
          .where((book) => library.localBookVisible(book.id))
          .toList(growable: false);
      final search = BookSearch(_query);
      final visible = books
          .where(
            (book) => search.matches(
              title: book.title,
              author: book.author,
              tags: '',
            ),
          )
          .toList(growable: false);
      final selected = books
          .where((book) => _selected.contains(book.id))
          .map((book) => book.id)
          .toSet();
      final allSelected =
          visible.isNotEmpty &&
          visible.every((book) => selected.contains(book.id));
      final enabled = !_busy && selected.isNotEmpty;
      final progress = _progress;
      return PopScope(
        canPop: !_busy,
        child: ArchiveFeaturePage(
          title: '批量整理',
          code: 'LOCAL / ORGANIZE',
          canPop: !_busy,
          actions: [
            GlassIconButton(
              icon: CupertinoIcons.wand_stars,
              label: '修复与整理',
              onPressed: !enabled
                  ? null
                  : () => _run(() async {
                      await pushNovelPage<void>(
                        context,
                        _Maintenance(books: widget.books, selection: selected),
                        reduceMotion: library.reduceMotion,
                      );
                    }),
            ),
            GlassTextButton(
              compact: true,
              onPressed: _busy || visible.isEmpty || allSelected
                  ? null
                  : () {
                      setState(
                        () => _selected.addAll(visible.map((book) => book.id)),
                      );
                    },
              child: AppText(_query.trim().isEmpty ? '全选' : '全选结果'),
            ),
          ],
          child: FeatureActionLayout(
            actions: [
              Expanded(
                flex: 5,
                child: GlassTextButton(
                  onPressed: enabled ? () => _run(() => _move(selected)) : null,
                  child: const FeatureActionLabel(
                    CupertinoIcons.folder,
                    '移动分类',
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 7,
                child: GlassTextButton(
                  onPressed: enabled
                      ? () => _run(() => _remove(selected))
                      : null,
                  haptic: HapticCue.warning,
                  child: FeatureActionLabel(
                    CupertinoIcons.trash,
                    progress != null
                        ? '正在移除 ${progress.$1} / ${progress.$2}'
                        : selected.isEmpty
                        ? '请选择书籍'
                        : '移除 ${selected.length} 本书',
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
                      children: [
                        AppSearchField(
                          hint: '搜索本地书名、作者',
                          onChanged: (value) => setState(() => _query = value),
                        ),
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            Expanded(
                              child: AppText(
                                '${visible.length} 本 · 已选 ${selected.length} 本',
                              ),
                            ),
                            if (selected.isEmpty)
                              const SizedBox(height: 56)
                            else
                              GlassTextButton(
                                onPressed: _busy
                                    ? null
                                    : () => setState(_selected.clear),
                                child: const AppText('清空选择'),
                              ),
                          ],
                        ),
                        if (visible.isEmpty)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            child: AppText(
                              books.isEmpty ? '还没有本地书籍' : '没有匹配的本地书籍',
                            ),
                          ),
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
                      return _BatchBookRow(
                        key: ValueKey(book.id),
                        book: book,
                        group: organization.nameOf(
                          organization.groupOf(book.id),
                        ),
                        selected: selected.contains(book.id),
                        onTap: _busy
                            ? null
                            : () => setState(() {
                                if (!_selected.remove(book.id)) {
                                  _selected.add(book.id);
                                }
                              }),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

class _BatchBookRow extends StatelessWidget {
  const _BatchBookRow({
    super.key,
    required this.book,
    required this.group,
    required this.selected,
    required this.onTap,
  });
  final Novel book;
  final String group;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Semantics(
      checked: selected,
      enabled: onTap != null,
      child: ContentPanel(
        onTap: onTap,
        child: Row(
          children: [
            ExcludeSemantics(child: NovelCover(book, width: 40, height: 56)),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  AppText(
                    book.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 4),
                  AppText(
                    book.author.isEmpty ? '本地书籍' : book.author,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 4),
                  AppText(
                    group,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
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
}

extension _MaintenanceText on LocalMaintenanceTask {
  String get label => switch (this) {
    LocalMaintenanceTask.layout => '升级排版',
    LocalMaintenanceTask.covers => '重建封面',
    LocalMaintenanceTask.storage => '整理存储',
  };

  String get detail => switch (this) {
    LocalMaintenanceTask.layout => '补回原书注音、注释与样式，保留目录、书签和阅读位置。',
    LocalMaintenanceTask.covers => '从原书恢复缺失封面，不覆盖你选过的封面。',
    LocalMaintenanceTask.storage => '压缩 TXT 阅读副本，清理 EPUB 重复资源；原书与阅读记录保留。',
  };
}

class _Maintenance extends StatefulWidget {
  const _Maintenance({required this.books, required this.selection});
  final LocalBookStore books;
  final Set<int> selection;

  @override
  State<_Maintenance> createState() => _MaintenanceState();
}

class _MaintenanceState extends State<_Maintenance> {
  final _tasks = LocalMaintenanceTask.values.toSet();
  final _results =
      <(int, LocalMaintenanceTask), ({String message, bool failed})>{};
  late final _originals = widget.books.maintenanceOriginals(widget.selection);
  bool _running = false;
  bool _stop = false;
  int _completed = 0;
  int _total = 0;
  int _freed = 0;

  Future<void> _start({bool retry = false}) async {
    if (_running || (!retry && _tasks.isEmpty)) return;
    final jobs = retry
        ? [
            for (final entry in _results.entries)
              if (entry.value.failed) entry.key,
          ]
        : [
            for (final id in _originals)
              for (final task in LocalMaintenanceTask.values)
                if (_tasks.contains(task)) (id, task),
          ];
    setState(() {
      if (!retry) {
        _results.clear();
        _freed = 0;
      }
      _running = true;
      _stop = false;
      _completed = 0;
      _total = jobs.length;
    });
    for (final job in jobs) {
      if (_stop || !mounted) break;
      final (id, task) = job;
      try {
        final result = await widget.books.maintain(id, task);
        _freed += result.freedBytes;
        _results[job] = (message: result.message, failed: false);
      } catch (error) {
        _results[job] = (
          message: error is FormatException ? error.message : '无法处理原书，请重试',
          failed: true,
        );
      }
      if (mounted) setState(() => _completed++);
    }
    if (mounted) setState(() => _running = false);
  }

  @override
  Widget build(BuildContext context) {
    final results = _results.entries.toList(growable: false);
    final failures = results.where((entry) => entry.value.failed).length;
    return PopScope(
      canPop: !_running,
      child: ArchiveFeaturePage(
        title: '修复与整理',
        code: 'LOCAL / MAINTENANCE',
        canPop: !_running,
        child: CustomScrollView(
          slivers: [
            SliverPadding(
              padding: featurePadding(context).copyWith(bottom: 14),
              sliver: SliverList.list(
                children: [
                  ArchiveSection(
                    title: '${widget.selection.length} 本书',
                    code: '保留原书与阅读记录',
                  ),
                  const SizedBox(height: 14),
                  for (final task in LocalMaintenanceTask.values) ...[
                    ContentPanel(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: AppText(
                                  task.label,
                                  style: const TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ),
                              Semantics(
                                label: task.label,
                                child: AppControls.toggle(
                                  context,
                                  value: _tasks.contains(task),
                                  onChanged: _running
                                      ? null
                                      : (value) => setState(() {
                                          if (value) {
                                            _tasks.add(task);
                                          } else {
                                            _tasks.remove(task);
                                          }
                                        }),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 10),
                          AppText(task.detail),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),
                  ],
                  if (_running)
                    RefreshProgress(label: '正在整理 $_completed / $_total 项'),
                  FeatureEntry(
                    title: _running ? (_stop ? '正在停止…' : '完成当前操作后停止') : '开始整理',
                    icon: CupertinoIcons.wand_stars,
                    subtitle: '支持 TXT 与 EPUB，合订本按原卷处理。',
                    onTap: _running
                        ? (_stop ? null : () => setState(() => _stop = true))
                        : (_tasks.isEmpty || _originals.isEmpty
                              ? null
                              : () => _start()),
                  ),
                  if (!_running && _results.isNotEmpty) ...[
                    const SizedBox(height: 14),
                    AppText(
                      '${_stop ? '已停止' : '整理完成'} · 已处理 $_completed / $_total 项'
                      '${failures == 0 ? '' : ' · $failures 项失败'}'
                      '${_freed == 0 ? '' : ' · 已释放 ${(_freed / 1048576).toStringAsFixed(2)} MiB'}',
                    ),
                    if (failures > 0)
                      GlassTextButton(
                        onPressed: () => _start(retry: true),
                        child: const AppText('重试失败项'),
                      ),
                  ],
                ],
              ),
            ),
            SliverPadding(
              padding: EdgeInsets.fromLTRB(
                24,
                0,
                24,
                featurePadding(context).bottom,
              ),
              sliver: SliverList.builder(
                itemCount: results.length,
                itemBuilder: (context, index) {
                  final entry = results[index];
                  final (id, task) = entry.key;
                  final title = widget.books.book(id)?.title ?? '本地书籍';
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: AppText(
                      '$title · ${task.label}：${entry.value.message}',
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
