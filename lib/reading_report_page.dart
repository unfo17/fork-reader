import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_interactions.dart';
import 'app_ui.dart';
import 'archive_feature_ui.dart';
import 'catalog.dart';
import 'gallery_save_button.dart';
import 'glass_choices.dart';
import 'library_state.dart';
import 'local_platform.dart';
import 'reading_export_theme.dart';
import 'reading_moment_export.dart';
import 'reading_report_options.dart';
import 'reading_report_range.dart';
import 'reading_statistics_charts.dart';
import 'reading_statistics_data.dart';
import 'setting_menu.dart';

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
    setState(() => _setRange(saved, widget.initialRange ?? saved.range));
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

  Future<void> _save() async {
    try {
      await _options!.save();
    } catch (_) {
      if (mounted) showNovelFeedback(context, '报告设置未能保存，请重试');
    }
  }

  Future<void> _chooseRange() async {
    final before = _options!;
    final choice = await showJellySheet<int>(
      context: context,
      reduceMotion: widget.state.reduceMotion,
      builder: (sheet) => SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const ArchiveSection(title: '报告日期', code: 'READING / DATE RANGE'),
            for (final period in [7, 30, 90, 365])
              GlassMenuItem(
                label: '最近 $period 天',
                selected: !before.range.custom && before.period == period,
                onPressed: () => Navigator.pop(sheet, period),
              ),
            GlassMenuItem(
              label: '自定义日期',
              selected: before.range.custom,
              onPressed: () => Navigator.pop(sheet, 0),
            ),
          ],
        ),
      ),
    );
    if (!mounted || choice == null) return;
    final ReadingReportRange range;
    if (choice == 0) {
      final dates = await pickReadingReportRange(
        context,
        now: _now,
        range: before.range,
      );
      if (!mounted || dates == null) return;
      range = ReadingReportRange(
        period: before.period,
        from: dates.start,
        until: dates.end,
      );
    } else {
      range = ReadingReportRange(period: choice);
    }
    setState(() => _setRange(before, range));
    await _save();
  }

  Future<void> _edit({required bool contents}) async {
    final edited = await showJellySheet<ReadingReportOptions>(
      context: context,
      reduceMotion: widget.state.reduceMotion,
      heightFactor: .86,
      builder: (_) => _ReportEditor(
        options: _options!,
        available: _availableModules(_data),
        contents: contents,
        reduceMotion: widget.state.reduceMotion,
      ),
    );
    if (!mounted || edited == null) return;
    setState(() => _options = edited);
    await _save();
  }

  Future<void> _export({required bool share, String? token}) async {
    if (_exporting) return;
    setState(() {
      _exporting = true;
      _error = null;
    });
    try {
      final bytes = await renderReadingArtworkPng(
        context,
        _ReportArtwork(options: _options!, data: _data),
        name: '报告',
      );
      if (!mounted) return;
      final name =
          '阅读报告-${_day(_data.analysis.start)}-${_day(_data.analysis.end)}.png';
      if (share) {
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
              : share
              ? '分享未完成，请重试'
              : gallerySaveFailureMessage(error),
        );
      }
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  void _preview() => pushNovelPage<void>(
    context,
    ArchiveFeaturePage(
      title: '完整预览',
      code: 'READING / PREVIEW',
      child: _reportScroll(context, [
        _ReportArtwork(options: _options!, data: _data),
      ]),
    ),
    reduceMotion: widget.state.reduceMotion,
  );

  Widget _reportScroll(BuildContext context, List<Widget> children) => Align(
    alignment: Alignment.topCenter,
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 600),
      child: ListView(
        padding: featurePadding(context).add(
          EdgeInsets.only(
            left: MediaQuery.paddingOf(context).left,
            right: MediaQuery.paddingOf(context).right,
          ),
        ),
        children: children,
      ),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final options = _options;
    final scheme = Theme.of(context).colorScheme;
    return ArchiveFeaturePage(
      title: '阅读报告',
      code: 'READING / REPORT',
      child: options == null
          ? const Center(child: CircularProgressIndicator())
          : _reportScroll(context, [
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  GlassTextButton(
                    compact: true,
                    onPressed: _exporting ? null : _chooseRange,
                    child: const AppText('报告日期'),
                  ),
                  GlassTextButton(
                    compact: true,
                    onPressed: _exporting ? null : () => _edit(contents: false),
                    child: const AppText('样式与文字'),
                  ),
                  GlassTextButton(
                    compact: true,
                    onPressed: _exporting ? null : () => _edit(contents: true),
                    child: const AppText('报告内容'),
                  ),
                  GlassTextButton(
                    compact: true,
                    onPressed: _exporting ? null : _preview,
                    child: const AppText('完整预览'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              if (LocalPlatform.fileActions)
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    GallerySaveButton(
                      enabled: !_exporting,
                      foreground: scheme.onSurface,
                      background: scheme.surface,
                      onSave: (token) => _export(share: false, token: token),
                      onError: (error) => setState(
                        () => _error = gallerySaveFailureMessage(error),
                      ),
                      builder: (save) => GlassTextButton(
                        key: const ValueKey('reading-report-save'),
                        onPressed: save,
                        child: const AppText('保存图片'),
                      ),
                    ),
                    GlassTextButton(
                      onPressed: _exporting ? null : () => _export(share: true),
                      child: AppText(_exporting ? '正在生成' : '分享'),
                    ),
                  ],
                ),
              FeatureError(_error),
              const SizedBox(height: 14),
              _ReportArtwork(options: options, data: _data),
            ]),
    );
  }
}

class _ReportEditor extends StatefulWidget {
  const _ReportEditor({
    required this.options,
    required this.available,
    required this.contents,
    required this.reduceMotion,
  });
  final ReadingReportOptions options;
  final Set<ReadingReportModule> available;
  final bool contents, reduceMotion;

  @override
  State<_ReportEditor> createState() => _ReportEditorState();
}

class _ReportEditorState extends State<_ReportEditor> {
  late ReadingReportOptions _value = widget.options;

  Widget _field(
    String label,
    String value,
    int limit,
    ValueChanged<String> change,
  ) => Padding(
    padding: const EdgeInsets.only(top: 12),
    child: TextFormField(
      initialValue: value,
      maxLength: limit,
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
            AppControls.toggle(context, value: value, onChanged: change),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) => ListView(
    shrinkWrap: true,
    padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
    children: [
      ArchiveSection(
        title: widget.contents ? '报告内容' : '样式与文字',
        code: 'READING / OPTIONS',
      ),
      const SizedBox(height: 14),
      if (widget.contents) ...[
        if (widget.available.isEmpty) const AppText('当前区间没有可加入报告的记录。'),
        for (final module in ReadingReportModule.values)
          if (widget.available.contains(module))
            _toggle(module.label, _value.modules.contains(module), (enabled) {
              final modules = {..._value.modules};
              enabled ? modules.add(module) : modules.remove(module);
              setState(() => _value = _value.copyWith(modules: modules));
            }),
        if (widget.available.contains(ReadingReportModule.books))
          GlassChoiceGroup<int>(
            name: 'report-book-limit',
            label: '书单数量',
            value: _value.bookLimit,
            options: const {3: '3 本', 5: '5 本', 10: '10 本', 20: '20 本'},
            onChanged: (limit) =>
                setState(() => _value = _value.copyWith(bookLimit: limit)),
          ),
        if (widget.available.contains(ReadingReportModule.excerpts))
          _toggle(
            '包含批注',
            _value.includeNotes,
            (v) => setState(() => _value = _value.copyWith(includeNotes: v)),
          ),
        _toggle(
          '隐藏书名',
          _value.hideBookTitles,
          (v) => setState(() => _value = _value.copyWith(hideBookTitles: v)),
        ),
        _toggle(
          '隐藏作者',
          _value.hideAuthorNames,
          (v) => setState(() => _value = _value.copyWith(hideAuthorNames: v)),
        ),
      ] else ...[
        GlassSettingMenu<ReadingReportTemplate>(
          icon: CupertinoIcons.rectangle_grid_1x2,
          label: '报告主题',
          value: _value.template,
          options: {
            for (final template in ReadingReportTemplate.values)
              template: template.label,
          },
          onChanged: (v) =>
              setState(() => _value = _value.copyWith(template: v)),
          reduceMotion: widget.reduceMotion,
        ),
        GlassChoiceGroup<ReadingReportPalette>(
          name: 'report-palette',
          label: '配色',
          value: _value.palette,
          options: {
            for (final palette in ReadingReportPalette.values)
              palette: palette.label,
          },
          onChanged: (v) =>
              setState(() => _value = _value.copyWith(palette: v)),
        ),
        _field(
          '报告标题',
          _value.title,
          36,
          (v) => _value = _value.copyWith(title: v),
        ),
        _field(
          '昵称',
          _value.nickname,
          24,
          (v) => _value = _value.copyWith(nickname: v),
        ),
        _field('寄语', _value.note, 80, (v) => _value = _value.copyWith(note: v)),
        _toggle(
          '显示署名',
          _value.signature,
          (v) => setState(() => _value = _value.copyWith(signature: v)),
        ),
      ],
      const SizedBox(height: 14),
      GlassTextButton(
        onPressed: () => Navigator.pop(context, _value),
        child: const AppText('应用'),
      ),
    ],
  );
}

String _day(DateTime date) =>
    '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';

Set<ReadingReportModule> _availableModules(ReadingStatisticsData data) => {
  if (data.observedDays > 0) ...[
    ReadingReportModule.rhythm,
    ReadingReportModule.days,
    ReadingReportModule.calendar,
  ],
  if (data.booksByReadingDays.any((book) => book.title?.isNotEmpty == true))
    ReadingReportModule.books,
  if (data.readingBooks > 0) ReadingReportModule.progress,
  if (data.collectionBooks.isNotEmpty) ReadingReportModule.collection,
  if (data.bookmarks + data.underlines > 0 || data.excerpts.isNotEmpty)
    ReadingReportModule.excerpts,
};

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
    ReadingReportModule.rhythm => ReadingRhythmChart(
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
        for (final (index, book)
            in data.booksByReadingDays
                .where((book) => book.title?.isNotEmpty == true)
                .take(options.bookLimit)
                .indexed)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              [
                options.hideBookTitles ? '书籍 ${index + 1}' : book.title!,
                if (!options.hideAuthorNames && book.author?.isNotEmpty == true)
                  book.author!,
                '阅读 ${book.days} 天',
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
                if (!options.hideBookTitles && excerpt.title.isNotEmpty)
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
    final tint = switch (options.palette) {
      ReadingReportPalette.pine => style.accent,
      ReadingReportPalette.vermilion =>
        style.isDark ? const Color(0xfff1ab9c) : const Color(0xffa33e31),
      ReadingReportPalette.blue =>
        style.isDark ? const Color(0xffa6c6f5) : const Color(0xff355d99),
      ReadingReportPalette.plum =>
        style.isDark ? const Color(0xffdfb5e1) : const Color(0xff815080),
    };
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
                Text(
                  style.label,
                  style: TextStyle(color: tint, fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 8),
                Text(
                  options.title.trim().isEmpty ? '阅读报告' : options.title,
                  style: TextStyle(
                    color: style.foreground,
                    fontSize: 32,
                    fontWeight: FontWeight.w800,
                    fontFamily: style.prefersSerif ? 'serif' : null,
                    height: 1.15,
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  '${_day(data.analysis.start)} — ${_day(data.analysis.end)}',
                ),
                if (options.nickname.trim().isNotEmpty) Text(options.nickname),
                const SizedBox(height: 20),
                if (data.observedDays > 0 ||
                    data.analysis.coverage.hasTimeDetail)
                  _metrics([
                    (
                      '阅读时长',
                      statisticsDuration(
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
                if (options.note.trim().isNotEmpty) ...[
                  const SizedBox(height: 20),
                  Text(options.note, style: TextStyle(color: style.muted)),
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
