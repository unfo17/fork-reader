import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_interactions.dart';
import 'app_ui.dart';
import 'archive_feature_ui.dart';
import 'catalog.dart';
import 'gallery_save_button.dart';
import 'library_state.dart';
import 'local_platform.dart';
import 'reading_export_theme.dart';
import 'reading_moment_export.dart';
import 'reading_report_options.dart';
import 'reading_report_range.dart';
import 'reading_statistics_analysis.dart';
import 'reading_statistics_charts.dart';
import 'reading_statistics_data.dart';
import 'reading_statistics_shared_charts.dart';
import 'reading_visual_theme_picker.dart';

class ReadingReportPage extends StatefulWidget {
  const ReadingReportPage({
    super.key,
    required this.state,
    required this.repository,
    this.now,
    this.initialRange,
  });

  final LibraryState state;
  final NovelRepository repository;
  final DateTime? now;
  final ReadingReportRange? initialRange;

  @override
  State<ReadingReportPage> createState() => _ReadingReportPageState();
}

class _ReadingReportPageState extends State<ReadingReportPage> {
  late final DateTime _now = widget.now ?? DateTime.now();
  ReadingReportOptions? _options;
  late ReadingStatisticsData _data;
  Timer? _measureTimer;
  Size? _dimensions;
  Uint8List? _image;
  bool _exporting = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final saved = await ReadingReportOptions.load();
    if (!mounted) return;
    final range = widget.initialRange ?? saved.range;
    setState(() => _setRange(saved, range));
    _measure();
  }

  void _setRange(ReadingReportOptions options, ReadingReportRange range) {
    _options = options.copyWith(
      clearDates: !range.custom,
      period: range.period,
      from: range.from,
      until: range.until,
    );
    _data = ReadingStatisticsData(
      state: widget.state,
      repository: widget.repository,
      now: range.endAt(_now),
      snapshotAt: _now,
      period: range.daysAt(_now),
    );
  }

  Widget _artwork(ReadingReportOptions options) =>
      _ReportArtwork(options: options, data: _data);

  void _measure() {
    _measureTimer?.cancel();
    _measureTimer = Timer(const Duration(milliseconds: 140), () async {
      final options = _options!;
      try {
        final size = await measureReadingArtworkPng(context, _artwork(options));
        if (mounted && identical(options, _options)) {
          setState(() => _dimensions = size);
        }
      } on FormatException {
        // The user can still shorten a report that exceeds the image canvas.
      }
    });
  }

  void _update(ReadingReportOptions options, {ReadingReportRange? range}) {
    if (_exporting) return;
    setState(() {
      if (range == null) {
        _options = options;
      } else {
        _setRange(options, range);
      }
      _dimensions = null;
      _image = null;
      _error = null;
    });
    _measure();
    unawaited(_save(_options!));
  }

  Future<void> _save(ReadingReportOptions options) async {
    try {
      await options.save();
    } catch (_) {
      if (mounted) showNovelFeedback(context, '报告设置未能保存，请重试');
    }
  }

  Future<void> _customRange() async {
    final range = _options!.range;
    final dates = await pickReadingReportRange(
      context,
      now: _now,
      range: range,
    );
    if (!mounted || dates == null) return;
    _update(
      _options!,
      range: ReadingReportRange(
        period: range.period,
        from: dates.start,
        until: dates.end,
      ),
    );
  }

  Future<Uint8List?> _render() async {
    final art = AssetImage(
      _reportArt(_options!, _data),
      bundle: DefaultAssetBundle.of(context),
    );
    if (LocalPlatform.android) {
      await precacheImage(art, context, onError: (_, _) {});
    }
    if (!mounted) return null;
    return _image ??= await renderReadingArtworkPng(
      context,
      _artwork(_options!),
      name: '报告',
    );
  }

  Future<void> _useImage({
    bool preview = false,
    bool share = false,
    String? token,
  }) async {
    if (_exporting) return;
    setState(() {
      _exporting = true;
      _error = null;
    });
    try {
      final bytes = await _render();
      if (!mounted || bytes == null) return;
      final name =
          '阅读报告-${_day(_data.analysis.start)}-${_day(_data.analysis.end)}.png';
      if (preview) {
        await pushNovelPage<void>(
          context,
          _ReportPreview(
            bytes,
            "${_options!.title}，${_day(_data.analysis.start)}至${_day(_data.analysis.end)}",
          ),
          reduceMotion: widget.state.reduceMotion,
        );
      } else if (share) {
        await LocalPlatform.shareImage(bytes, name);
      } else {
        await LocalPlatform.saveImageToGallery(
          bytes,
          name,
          authorizationToken: token,
        );
        if (mounted) showNovelFeedback(context, '已保存到相册');
      }
    } catch (error) {
      if (mounted) {
        setState(
          () => _error = error is FormatException
              ? error.message
              : preview || share
              ? '图片未能生成，请重试'
              : gallerySaveFailureMessage(error),
        );
      }
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  Widget _choices<T>(
    Map<T, String> choices,
    T? value,
    ValueChanged<T> change, {
    Widget Function(T)? leading,
  }) => Wrap(
    spacing: 8,
    runSpacing: 8,
    children: [
      for (final entry in choices.entries)
        _choice(
          entry.value,
          entry.key == value,
          () => change(entry.key),
          leading: leading?.call(entry.key),
        ),
    ],
  );

  Widget _choice(
    String label,
    bool selected,
    VoidCallback change, {
    Widget? leading,
    bool compact = false,
  }) => Semantics(
    selected: selected,
    child: GlassTextButton(
      onPressed: _exporting ? null : change,
      selected: selected,
      compact: compact,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (leading != null) ...[leading, const SizedBox(width: 6)],
          Flexible(
            child: compact
                ? FittedBox(
                    fit: BoxFit.scaleDown,
                    child: AppText(label, maxLines: 1),
                  )
                : AppText(label),
          ),
        ],
      ),
    ),
  );

  Widget _modules(ReadingReportOptions options) {
    final available = _availableModules(_data);
    if (available.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const AppText('报告内容', style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          LayoutBuilder(
            builder: (context, size) {
              final minWidth =
                  MediaQuery.textScalerOf(context).scale(14) * 5 + 12;
              final columns = ((size.maxWidth + 6) / (minWidth + 6))
                  .floor()
                  .clamp(1, 4);
              final width = (size.maxWidth - (columns - 1) * 6) / columns;
              return Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final module in ReadingReportModule.values)
                    if (available.contains(module))
                      SizedBox(
                        width: width,
                        child: _choice(
                          module.label,
                          options.modules.contains(module),
                          () {
                            final modules = {...options.modules};
                            if (!modules.remove(module)) modules.add(module);
                            _update(options.copyWith(modules: modules));
                          },
                          compact: true,
                        ),
                      ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _fileActions(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    Widget label(IconData icon, String text) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        ArchiveIcon(icon, size: 18),
        const SizedBox(width: 8),
        Flexible(child: AppText(text)),
      ],
    );
    Widget saveButton(VoidCallback? action) => GlassTextButton(
      key: const ValueKey('reading-report-save'),
      onPressed: action,
      child: label(CupertinoIcons.arrow_down_to_line, '保存图片'),
    );
    final save = GallerySaveButton(
      enabled: !_exporting,
      foreground: colors.surface,
      background: colors.onSurface,
      onSave: (token) => _useImage(token: token),
      onError: (error) =>
          setState(() => _error = gallerySaveFailureMessage(error)),
      builder: saveButton,
    );
    final share = GlassTextButton(
      onPressed: _exporting ? null : () => _useImage(share: true),
      child: label(CupertinoIcons.share, _exporting ? '正在生成' : '分享图片'),
    );
    return LayoutBuilder(
      builder: (context, size) {
        final requiredWidth =
            MediaQuery.textScalerOf(context).scale(15) * 8 + 84;
        return size.maxWidth < requiredWidth
            ? Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [save, const SizedBox(height: 8), share],
              )
            : Row(
                children: [
                  Expanded(child: save),
                  const SizedBox(width: 10),
                  Expanded(child: share),
                ],
              );
      },
    );
  }

  Widget _textField(
    String label,
    String value,
    int limit,
    ValueChanged<String> change, {
    int lines = 1,
  }) => Padding(
    padding: const EdgeInsets.only(top: 10),
    child: TextFormField(
      initialValue: value,
      maxLines: lines,
      maxLength: limit,
      enabled: !_exporting,
      decoration: InputDecoration(labelText: label),
      onChanged: change,
    ),
  );

  Widget _toggle(String label, bool value, ValueChanged<bool> change) =>
      ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 44),
        child: Row(
          children: [
            Expanded(child: AppText(label)),
            const SizedBox(width: 12),
            AppControls.toggle(
              context,
              value: value,
              onChanged: _exporting ? null : change,
            ),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    final options = _options;
    final dimensions = _dimensions;
    return ArchiveFeaturePage(
      title: '图片报告',
      code: 'READING / PERSONAL EDITION',
      child: options == null
          ? const Center(child: CupertinoActivityIndicator())
          : Align(
              alignment: Alignment.topCenter,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 760),
                child: ListView(
                  key: const PageStorageKey('reading-report-editor'),
                  keyboardDismissBehavior:
                      ScrollViewKeyboardDismissBehavior.onDrag,
                  padding: featurePadding(context).add(
                    EdgeInsets.symmetric(
                      horizontal: MediaQuery.paddingOf(context).horizontal / 2,
                    ),
                  ),
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(14),
                      child: Semantics(
                        button: true,
                        label: '打开完整报告预览',
                        child: JellyTapSurface(
                          onTap: _exporting
                              ? null
                              : () => _useImage(preview: true),
                          child: _ReportCover(options: options, data: _data),
                        ),
                      ),
                    ),
                    const SizedBox(height: 20),
                    Row(
                      children: [
                        Expanded(
                          child: AppText(
                            dimensions == null
                                ? 'PNG'
                                : 'PNG · ${dimensions.width.toInt()} × ${dimensions.height.toInt()}',
                          ),
                        ),
                        GlassTextButton(
                          onPressed: _exporting
                              ? null
                              : () => _useImage(preview: true),
                          child: const AppText('完整预览'),
                        ),
                      ],
                    ),
                    _modules(options),
                    const SizedBox(height: 16),
                    if (LocalPlatform.fileActions) _fileActions(context),
                    FeatureError(_error),
                    const SizedBox(height: 30),
                    const ArchiveSection(
                      title: '版式与配色',
                      code: '01 / ART DIRECTION',
                    ),
                    const SizedBox(height: 14),
                    ReadingVisualThemePicker(
                      value: options.template.theme,
                      presentationFor: (theme) =>
                          ReadingVisualThemePresentation.fromExportTheme(
                            theme,
                            at: _data.analysis.end,
                          ),
                      onChanged: _exporting
                          ? null
                          : (theme) => _update(
                              options.copyWith(
                                template: ReadingReportTemplate.values.byName(
                                  theme.name,
                                ),
                              ),
                            ),
                    ),
                    const SizedBox(height: 12),
                    _choices(
                      {
                        for (final palette in ReadingReportPalette.values)
                          palette: palette.label,
                      },
                      options.palette,
                      (palette) => _update(options.copyWith(palette: palette)),
                      leading: (palette) => Icon(
                        Icons.circle,
                        size: 10,
                        color: _reportTint(options.copyWith(palette: palette)),
                      ),
                    ),
                    const SizedBox(height: 28),
                    const ArchiveSection(
                      title: '这段时间',
                      code: '02 / DATE RANGE',
                    ),
                    const SizedBox(height: 12),
                    _choices<int>(
                      const {7: '7 天', 30: '30 天', 90: '90 天', 365: '365 天'},
                      options.range.custom ? null : options.period,
                      (period) => _update(
                        options,
                        range: ReadingReportRange(period: period),
                      ),
                    ),
                    const SizedBox(height: 8),
                    GlassTextButton(
                      onPressed: _exporting ? null : _customRange,
                      child: AppText(
                        options.range.custom
                            ? '${_day(_data.analysis.start)} — ${_day(_data.analysis.end)}'
                            : '自定日期区间',
                      ),
                    ),
                    const SizedBox(height: 28),
                    const ArchiveSection(
                      title: '写上你的名字',
                      code: '03 / PERSONAL TEXT',
                    ),
                    _textField(
                      '标题',
                      options.title,
                      36,
                      (text) => _update(_options!.copyWith(title: text)),
                    ),
                    _textField(
                      '昵称（可留空）',
                      options.nickname,
                      24,
                      (text) => _update(_options!.copyWith(nickname: text)),
                    ),
                    _textField(
                      '寄语（可留空）',
                      options.note,
                      80,
                      (text) => _update(_options!.copyWith(note: text)),
                      lines: 2,
                    ),
                    const SizedBox(height: 28),
                    const ArchiveSection(title: '分享与隐私', code: '04 / SHARING'),
                    const SizedBox(height: 12),
                    const AppText('书单显示数量'),
                    const SizedBox(height: 8),
                    _choices(
                      const {3: '3 本', 5: '5 本', 10: '10 本', 20: '20 本'},
                      options.bookLimit,
                      (limit) => _update(options.copyWith(bookLimit: limit)),
                    ),
                    _toggle(
                      '隐藏书名',
                      options.hideBookTitles,
                      (v) => _update(options.copyWith(hideBookTitles: v)),
                    ),
                    _toggle(
                      '隐藏作者姓名',
                      options.hideAuthorNames,
                      (v) => _update(options.copyWith(hideAuthorNames: v)),
                    ),
                    _toggle(
                      '摘录中显示批注文字',
                      options.includeNotes,
                      (v) => _update(options.copyWith(includeNotes: v)),
                    ),
                    const SizedBox(height: 12),
                    const AppText('时长来自前台阅读记录；书单优先按本期已知单书时长排列。未知日期保持空白。'),
                  ],
                ),
              ),
            ),
    );
  }

  @override
  void dispose() {
    _measureTimer?.cancel();
    super.dispose();
  }
}

class _ReportPreview extends StatelessWidget {
  const _ReportPreview(this.image, this.description);
  final Uint8List image;
  final String description;

  @override
  Widget build(BuildContext context) => ArchiveFeaturePage(
    title: '完整预览',
    code: 'READING / PREVIEW',
    child: Padding(
      padding: featurePadding(context),
      child: Column(
        children: [
          const AppText('双指缩放，拖动查看整张报告'),
          const SizedBox(height: 8),
          Expanded(
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 760),
                child: LayoutBuilder(
                  builder: (context, size) => InteractiveViewer(
                    constrained: false,
                    alignment: Alignment.topCenter,
                    minScale: .8,
                    maxScale: 4,
                    child: Image.memory(
                      image,
                      width: size.maxWidth,
                      alignment: Alignment.topCenter,
                      semanticLabel: description,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

String _day(DateTime date) =>
    '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';

String _reportDuration(int seconds) => [
  if (seconds >= 3600) '${seconds ~/ 3600} 小时',
  if (seconds >= 60) '${seconds % 3600 ~/ 60} 分钟',
  if (seconds % 60 != 0 || seconds == 0) '${seconds % 60} 秒',
].join(' ');

Set<ReadingReportModule> _availableModules(ReadingStatisticsData data) => {
  if (data.observedDays > 0) ...[
    ReadingReportModule.rhythm,
    ReadingReportModule.days,
    ReadingReportModule.calendar,
  ],
  if (_rankedReportBooks(data).isNotEmpty) ReadingReportModule.books,
  if (data.readingBooks > 0) ReadingReportModule.progress,
  if (data.onlineBooks + data.localBooks > 0) ReadingReportModule.collection,
  if (data.bookmarks + data.underlines > 0 || data.excerpts.isNotEmpty)
    ReadingReportModule.excerpts,
};

List<StatisticsAnalysisBook> _rankedReportBooks(ReadingStatisticsData data) {
  final books = data.analysis.booksByDuration
      .where(
        (book) =>
            book.title.trim().isNotEmpty &&
            book.title != '书名待读取' &&
            (book.seconds != null || book.activeDates.isNotEmpty),
      )
      .toList();
  books.sort((a, b) {
    final time = (b.seconds ?? -1).compareTo(a.seconds ?? -1);
    if (time != 0) return time;
    final days = b.activeDates.length.compareTo(a.activeDates.length);
    return days == 0 ? a.id.compareTo(b.id) : days;
  });
  return books;
}

String _reportArt(ReadingReportOptions options, ReadingStatisticsData data) =>
    readingExportArt(options.template.theme, at: data.analysis.end);

Color _reportTint(ReadingReportOptions options) {
  final style = readingExportTheme(options.template.theme);
  return switch (options.palette) {
    ReadingReportPalette.pine => style.accent,
    ReadingReportPalette.vermilion =>
      style.isDark ? const Color(0xfff1ab9c) : const Color(0xffa33e31),
    ReadingReportPalette.blue =>
      style.isDark ? const Color(0xffa6c6f5) : const Color(0xff355d99),
    ReadingReportPalette.plum =>
      style.isDark ? const Color(0xffdfb5e1) : const Color(0xff815080),
  };
}

class _ReportCover extends StatelessWidget {
  const _ReportCover({required this.options, required this.data});
  final ReadingReportOptions options;
  final ReadingStatisticsData data;

  @override
  Widget build(BuildContext context) {
    final style = readingExportTheme(options.template.theme);
    final tint = _reportTint(options);
    final heading = TextStyle(
      color: style.foreground,
      fontSize: 31,
      height: 1.12,
      fontWeight: FontWeight.w800,
      fontFamily: style.prefersSerif ? 'ReadingSerifDisplay' : null,
    );
    return ColoredBox(
      color: style.background,
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: DefaultTextStyle(
          style: Theme.of(
            context,
          ).textTheme.bodyMedium!.copyWith(color: style.foreground),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(CupertinoIcons.book, color: tint, size: 19),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      readingExportBrandName,
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ),
                  Text(
                    '${data.analysis.end.year}',
                    style: TextStyle(
                      color: tint,
                      fontSize: 18,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  Expanded(
                    flex: 6,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          options.title.trim().isEmpty ? '阅读报告' : options.title,
                          style: heading,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: 14),
                        Text(
                          options.nickname.trim().isEmpty
                              ? '一位读者'
                              : options.nickname,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: 5),
                        Text(
                          '${_day(data.analysis.start)} — ${_day(data.analysis.end)}',
                          style: TextStyle(color: style.muted, fontSize: 11),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    flex: 5,
                    child: AspectRatio(
                      aspectRatio: 1,
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(8),
                        child: Image.asset(
                          _reportArt(options, data),
                          fit: BoxFit.cover,
                          alignment: style.previewAlignment,
                          excludeFromSemantics: true,
                          errorBuilder: (_, _, _) => ColoredBox(
                            color: style.secondary,
                            child: Icon(
                              CupertinoIcons.book,
                              color: tint,
                              size: 42,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ReportArtwork extends StatelessWidget {
  const _ReportArtwork({required this.options, required this.data});
  final ReadingReportOptions options;
  final ReadingStatisticsData data;

  Widget _metrics(List<(String, String)> values) => Wrap(
    spacing: 18,
    runSpacing: 9,
    children: [
      for (final (label, value) in values)
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              value,
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
            Text(label, style: const TextStyle(fontSize: 12)),
          ],
        ),
    ],
  );

  Widget _module(ReadingReportModule module) => switch (module) {
    ReadingReportModule.rhythm =>
      options.template == ReadingReportTemplate.archive
          ? ReadingMonthlyChart(
              dates: [for (final month in data.months) month.date],
              seconds: [for (final month in data.months) month.seconds],
              reduceMotion: true,
            )
          : ReadingRhythmChart(
              dates: data.dates,
              minutes: data.minutes,
              averageMinutes: data.averageMinutes,
              selectedIndex: data.dates.length - 1,
              onSelected: (_) {},
              reduceMotion: true,
            ),
    ReadingReportModule.days => _metrics([
      ('活跃天数', '${data.activeDays} 天'),
      ('最长连续阅读', '${data.analysis.longestStreak.days} 天'),
      ('日均阅读', statisticsDuration(data.averageSeconds)),
      ('记录覆盖', '${data.observedDays} / ${data.period} 天'),
      if (data.peakDate case final day?)
        ('最长的一日', '${_day(day)} · ${statisticsDuration(data.peakSeconds)}'),
    ]),
    ReadingReportModule.calendar =>
      data.period > 93
          ? ReadingActivityCalendar(
              dates: data.dates,
              seconds: data.seconds,
              selectedDate: null,
              onSelected: (_) {},
              showDateAnchors: true,
              reduceMotion: true,
            )
          : _ReportCalendar(data: data),
    ReadingReportModule.books => Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (index, book) in _rankedReportBooks(
          data,
        ).take(options.bookLimit).indexed)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              [
                options.hideBookTitles ? '书籍 ${index + 1}' : book.title,
                if (!options.hideAuthorNames &&
                    book.book?.author.isNotEmpty == true)
                  book.book!.author,
                if (book.seconds case final duration?)
                  statisticsDuration(duration)
                else
                  '阅读 ${book.activeDates.length} 天',
              ].join(' · '),
            ),
          ),
      ],
    ),
    ReadingReportModule.progress => _metrics([
      ('有阅读记录', '${data.readingBooks} 本'),
      ('读完', '${data.finishedBooks} 本'),
      if (data.unknownProgress > 0) ('未知进度', '${data.unknownProgress} 本'),
    ]),
    ReadingReportModule.collection => _metrics([
      ('本地书籍', '${data.localBooks} 本'),
      if (data.onlineBooks > 0) ('收藏', '${data.onlineBooks} 本'),
      if (!options.hideAuthorNames && data.authorCount > 0)
        ('作者', '${data.authorCount} 位'),
    ]),
    ReadingReportModule.excerpts => Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _metrics([
          ('书签', '${data.bookmarks} 条'),
          ('划线', '${data.underlines} 条'),
        ]),
        for (final excerpt in data.excerpts.take(3))
          Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Text(
              [
                excerpt.quote,
                if (!options.hideBookTitles &&
                    excerpt.title.isNotEmpty &&
                    excerpt.title != '书名待读取')
                  excerpt.title,
                if (options.includeNotes && excerpt.note.isNotEmpty)
                  excerpt.note,
              ].join('\n'),
              maxLines: 8,
              overflow: TextOverflow.ellipsis,
            ),
          ),
      ],
    ),
  };

  @override
  Widget build(BuildContext context) {
    final style = readingExportTheme(options.template.theme);
    final tint = _reportTint(options);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme.copyWith(
      brightness: style.isDark ? Brightness.dark : Brightness.light,
      surface: style.background,
      onSurface: style.foreground,
      onSurfaceVariant: style.muted,
      primary: tint,
      outline: Color.lerp(style.background, style.foreground, .35),
      outlineVariant: Color.lerp(style.background, style.foreground, .18),
    );
    final available = _availableModules(data);
    return Theme(
      data: theme.copyWith(colorScheme: scheme),
      child: Material(
        color: style.background,
        child: DefaultTextStyle(
          style: theme.textTheme.bodyMedium!.copyWith(
            color: style.foreground,
            height: 1.45,
          ),
          child: Padding(
            padding: const EdgeInsets.all(22),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _ReportCover(options: options, data: data),
                if (options.note.trim().isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(options.note, style: TextStyle(color: style.muted)),
                ],
                const SizedBox(height: 18),
                if (data.observedDays > 0 ||
                    data.analysis.coverage.hasTimeDetail)
                  _metrics([
                    (
                      '阅读时长',
                      _reportDuration(
                        data.observedDays > 0
                            ? data.periodSeconds
                            : data.analysis.measuredSeconds,
                      ),
                    ),
                    if (data.periodBooks > 0) ('读过的书', '${data.periodBooks} 本'),
                  ])
                else
                  const Text('本期暂无阅读时长记录'),
                for (final module in options.orderedModules)
                  if (available.contains(module)) ...[
                    const SizedBox(height: 22),
                    Divider(height: 1, color: scheme.outlineVariant),
                    const SizedBox(height: 12),
                    Text(
                      module.label,
                      style: TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w700,
                        color: tint,
                      ),
                    ),
                    if (module == ReadingReportModule.progress ||
                        module == ReadingReportModule.collection ||
                        module == ReadingReportModule.excerpts)
                      Text(
                        '当前快照 · ${_day(data.analysis.snapshotAt)}',
                        style: TextStyle(color: style.muted, fontSize: 11),
                      ),
                    const SizedBox(height: 8),
                    _module(module),
                  ],
                if (options.signature) ...[
                  const SizedBox(height: 12),
                  const Text(
                    readingExportBrandName,
                    style: TextStyle(fontSize: 12),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ReportCalendar extends StatelessWidget {
  const _ReportCalendar({required this.data});
  final ReadingStatisticsData data;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final dates = {
      for (final (i, date) in data.dates.indexed)
        (date.year, date.month, date.day): data.seconds[i],
    };
    final months = {for (final date in data.dates) (date.year, date.month)};
    final maximum = data.peakSeconds;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (year, month) in months) ...[
          Padding(
            padding: const EdgeInsets.only(top: 6, bottom: 4),
            child: Text('$year 年 $month 月'),
          ),
          Table(
            children: [
              TableRow(
                children: [
                  for (final day in ['一', '二', '三', '四', '五', '六', '日'])
                    Text(day, textAlign: TextAlign.center),
                ],
              ),
              for (
                var start = 2 - DateTime(year, month).weekday;
                start <= DateUtils.getDaysInMonth(year, month);
                start += 7
              )
                TableRow(
                  children: [
                    for (var day = start; day < start + 7; day++)
                      if (day < 1 ||
                          day > DateUtils.getDaysInMonth(year, month))
                        const SizedBox.shrink()
                      else
                        Padding(
                          padding: const EdgeInsets.all(2),
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: switch (dates[(year, month, day)]) {
                                null => null,
                                0 => colors.outlineVariant,
                                final seconds => colors.primary.withValues(
                                  alpha: maximum <= 0
                                      ? .20
                                      : .20 + .32 * seconds / maximum,
                                ),
                              },
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              '$day',
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                fontSize: 11,
                                color: dates[(year, month, day)] == null
                                    ? colors.onSurfaceVariant
                                    : colors.onSurface,
                              ),
                            ),
                          ),
                        ),
                  ],
                ),
            ],
          ),
        ],
      ],
    );
  }
}
