part of 'reading_report_page.dart';

// Labels and section order come from populated reports. The editor keeps a
// single set of switches so a theme change preserves the reader's choices.
typedef _ReportSection = (ReadingReportModule, String, String);
const _rhythm = ReadingReportModule.rhythm;
const _days = ReadingReportModule.days;
const _calendar = ReadingReportModule.calendar;
const _books = ReadingReportModule.books;
const _progress = ReadingReportModule.progress;
const _collection = ReadingReportModule.collection;
const _excerpts = ReadingReportModule.excerpts;
const _reportCalendar = (_calendar, '阅读日历', 'READING DAYS / DAILY RECORD');

List<_ReportSection> _reportSections(ReadingReportTemplate template) =>
    switch (template) {
      ReadingReportTemplate.archive => const [
        (_progress, '书页上的记录', 'ON FILE'),
        (_days, '留下来的日期', ''),
        _reportCalendar,
        (_collection, '此刻的藏书', 'THE READING HALL'),
        (_rhythm, '时间的分档', 'PEAK MONTH'),
        (_books, '书籍索引', ''),
      ],
      ReadingReportTemplate.shelf => const [
        (_days, '日程印记', 'DATE / RECORDED DAYS'),
        (_books, '相伴书目清单', 'MANIFEST / BOOK INDEX'),
        _reportCalendar,
        (_rhythm, '时间刻度', 'WATCH / TIME LEDGER'),
        (_progress, '航次中的书页', 'CHECKPOINTS / PAGE LOG'),
        (_collection, '此刻的书架', 'INVENTORY / SNAPSHOT'),
      ],
      ReadingReportTemplate.editorial => const [
        (_days, '手帖里的日子', ''),
        (_rhythm, '四季时长', ''),
        _reportCalendar,
        (_books, '这一册里相伴的书', ''),
        (_progress, '此刻夹着的页签', ''),
        (_collection, '手帖最后一页 · 书架', ''),
      ],
      ReadingReportTemplate.constellation => const [
        (_rhythm, '阅读最多的月份', ''),
        (_days, '灯亮过的日子', ''),
        _reportCalendar,
        (_progress, '书页留下的时间', ''),
        (_collection, '此刻的书架', ''),
        (_books, '相伴的书', 'BOOKS / WITH YOU'),
      ],
      ReadingReportTemplate.signal => const [
        (_days, '剪下来的数字', 'CUT-OUTS / MEASURED MOMENTS'),
        _reportCalendar,
        (_books, '相伴书目', 'BOOK INDEX'),
        (_progress, '纸页上的足迹', 'PAGES'),
        (_collection, '此刻的书架', 'THE SHELF NOW'),
        (_rhythm, '月份纸条', 'STRIPS / MONTHS IN THIS PERIOD'),
      ],
      ReadingReportTemplate.score => const [
        (_rhythm, '三幕放映', 'ACT I–III'),
        (_books, '本期相伴书目', 'END CREDITS'),
        _reportCalendar,
        (_progress, '当前书页', 'CURRENT FRAME'),
        (_collection, '书架', 'ART DEPARTMENT'),
      ],
      ReadingReportTemplate.ink => const [
        (_days, '日子留下的脚印', 'DAYS / FOOTSTEPS'),
        _reportCalendar,
        (_books, '经过的书中世界', 'BOOKS / WAYPOINTS'),
        (_rhythm, '途中停留的时间', 'MONTHS / SMALL PAUSES'),
        (_progress, '走到这里了', 'ALONG THE WAY / PAGE MARKS'),
        (_collection, '此刻的书架', 'INVENTORY / SNAPSHOT'),
      ],
      ReadingReportTemplate.festival => const [
        (_books, '在书中停靠', 'STATIONS / BOOK WORLDS'),
        (_rhythm, '月份站台', 'TIMETABLE / READING ROUTE'),
        (_days, '日程印记', 'DATE / RECORDED DAYS'),
        (_collection, '此刻的书架', 'INVENTORY / SNAPSHOT'),
        _reportCalendar,
        (_progress, '书页签注', 'VALIDATION / PAGE STAMPS'),
      ],
    };

bool _reportSectionEnabled(
  ReadingReportModule module,
  ReadingReportOptions options,
  Set<ReadingReportModule> available,
) =>
    available.contains(module) &&
    (options.modules.contains(module) ||
        (options.template == ReadingReportTemplate.score &&
            module == _rhythm &&
            options.modules.contains(_days)));

String _reportPreviewDescription(
  ReadingReportOptions options,
  ReadingStatisticsData data,
) {
  final available = _availableModules(data);
  return [
    options.title.trim().isEmpty ? '阅读报告' : options.title,
    '${_reportDate(data.analysis.start)} — ${_reportDate(data.analysis.end)}',
    if (data.observedDays > 0) _reportDuration(data.periodSeconds),
    for (final (module, title, _) in _reportSections(options.template))
      if (_reportSectionEnabled(module, options, available)) title,
    if (_reportSectionEnabled(_excerpts, options, available)) _excerpts.label,
  ].join('，');
}

String _reportDate(DateTime day) => _day(day).replaceAll('-', '.');

String _datesCaption(DateTime start, DateTime end) => start == end
    ? _reportDate(start)
    : '${_reportDate(start)}—${_reportDate(end)}';

class _ReportArtwork extends StatelessWidget {
  const _ReportArtwork({required this.options, required this.data});
  final ReadingReportOptions options;
  final ReadingStatisticsData data;

  ReadingReportTemplate get template => options.template;
  String get _snapshot => _reportDate(data.analysis.snapshotAt);
  bool get _showProgressBands => switch (template) {
    ReadingReportTemplate.archive ||
    ReadingReportTemplate.ink ||
    ReadingReportTemplate.festival => false,
    _ => true,
  };

  Widget _muted(String text) => Builder(
    builder: (context) => Text(
      text,
      style: TextStyle(
        fontSize: 11,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
    ),
  );

  Widget _metrics(List<(String, String)> values) => Wrap(
    spacing: 16,
    runSpacing: 8,
    children: [
      for (final (label, value) in values)
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              value,
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
            _muted(label),
          ],
        ),
    ],
  );

  Widget _timeSummary() {
    final duration = data.observedDays > 0
        ? data.periodSeconds
        : data.analysis.measuredSeconds;
    final recorded =
        data.observedDays > 0 || data.analysis.coverage.hasTimeDetail;
    final label = switch (template) {
      ReadingReportTemplate.archive => 'TOTAL READING TIME',
      ReadingReportTemplate.shelf => 'READING / VOYAGE',
      ReadingReportTemplate.editorial => '这本手帖的阅读时长',
      ReadingReportTemplate.constellation => '夜读时长',
      ReadingReportTemplate.signal => '本期阅读',
      ReadingReportTemplate.score => 'RUNNING TIME / 本期放映时长',
      ReadingReportTemplate.ink => '沿途的阅读时长',
      ReadingReportTemplate.festival => '本期旅程',
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _muted(label),
        const SizedBox(height: 3),
        Text(
          recorded ? _reportDuration(duration) : '本期暂无阅读时长记录',
          style: const TextStyle(fontSize: 27, fontWeight: FontWeight.w800),
        ),
        if (recorded && template == ReadingReportTemplate.archive) ...[
          const SizedBox(height: 12),
          _metrics([
            ('阅读日', '${data.activeDays}'),
            ('最长连续 / 天', '${data.analysis.longestStreak.days}'),
            ('相伴书目', '${data.periodBooks}'),
            ('已记录日均', statisticsDuration(data.averageSeconds)),
          ]),
        ],
      ],
    );
  }

  Widget _daysSection() {
    final active = data.analysis.days
        .where((day) => (day.seconds ?? 0) > 0)
        .toList();
    final streak = data.analysis.longestStreak;
    final streakDates = streak.start != null && streak.end != null
        ? _datesCaption(streak.start!, streak.end!)
        : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (template == ReadingReportTemplate.signal)
          _metrics([
            ('有阅读记录的日子', '${data.activeDays}'),
            ('最长连续 / 天', '${streak.days}'),
            ('本期实际读过的书', '${data.periodBooks}'),
            if (options.modules.contains(_collection))
              ('当前藏书', '${data.onlineBooks + data.localBooks}'),
          ])
        else ...[
          if (template != ReadingReportTemplate.archive)
            Text('${data.activeDays} 个阅读日'),
          if (template == ReadingReportTemplate.archive ||
              template == ReadingReportTemplate.constellation)
            Text('记录覆盖  ${data.observedDays} / ${data.period} 天'),
          if (active.isNotEmpty &&
              (template == ReadingReportTemplate.archive ||
                  template == ReadingReportTemplate.constellation)) ...[
            if (data.peakDate case final peak?)
              Text(
                '最长的一日  ${_reportDate(peak)} · ${statisticsDuration(data.peakSeconds)}',
              ),
          ] else if (active.isNotEmpty &&
              template != ReadingReportTemplate.editorial) ...[
            Text('本期第一日  ${_reportDate(active.first.date)}'),
            Text('最近阅读日  ${_reportDate(active.last.date)}'),
          ],
          if (streakDates != null) Text('最长连续 ${streak.days} 天 · $streakDates'),
        ],
      ],
    );
  }

  Widget _rhythmSection() {
    if (template == ReadingReportTemplate.score) {
      return _ReportFilmActs(
        days: data.analysis.days,
        showTiming: options.modules.contains(_rhythm),
        showDays: options.modules.contains(_days),
      );
    }
    final months = data.analysis.months;
    final known = months.where((month) => month.totalSeconds != null);
    final peak = known.fold<StatisticsAnalysisMonth?>(
      null,
      (best, month) => best == null || month.totalSeconds! > best.totalSeconds!
          ? month
          : best,
    );
    final peakMonth = peak != null && peak.totalSeconds! > 0 ? peak : null;
    final maxSeconds = math.max(1, peak?.totalSeconds ?? 0);
    final showPeak =
        template == ReadingReportTemplate.archive ||
        template == ReadingReportTemplate.constellation ||
        template == ReadingReportTemplate.signal;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (showPeak && peakMonth != null)
          Text(
            '${_reportMonth(peakMonth.date)} · ${statisticsDuration(peakMonth.totalSeconds!)}',
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
        if (template == ReadingReportTemplate.archive)
          _muted('${known.length} 个有覆盖的月份'),
        if (template == ReadingReportTemplate.editorial)
          Text(
            '${_reportSeasons(months)} · ${_datesCaption(data.analysis.start, data.analysis.end)}',
          ),
        const SizedBox(height: 7),
        if (template == ReadingReportTemplate.archive ||
            template == ReadingReportTemplate.constellation)
          ReadingMonthlyChart(
            dates: [for (final month in months) month.date],
            seconds: [for (final month in months) month.totalSeconds],
            height: 94,
            presentation: template == ReadingReportTemplate.archive
                ? ReadingChartPresentation.archive
                : ReadingChartPresentation.night,
            reduceMotion: true,
          ),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            for (final month in months)
              _ReportMonthLabel(
                month: month,
                circle: template == ReadingReportTemplate.ink,
                area: (month.totalSeconds ?? 0) / maxSeconds,
              ),
          ],
        ),
        if (template == ReadingReportTemplate.ink) ...[
          const SizedBox(height: 6),
          _muted('着色圆面积按已记录时长'),
        ],
      ],
    );
  }

  Widget _bookSection() {
    final books = _rankedReportBooks(data);
    final selected = books.take(options.bookLimit).toList();
    final datedBooks =
        template == ReadingReportTemplate.ink ||
        template == ReadingReportTemplate.festival;
    final breadth = data.analysis.bookDayDistribution;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (index, book) in selected.indexed)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: 30,
                  child: _muted((index + 1).toString().padLeft(2, '0')),
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        options.hideBookTitles ? '书籍 ${index + 1}' : book.title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      _muted(
                        [
                          if (!options.hideAuthorNames &&
                              book.book?.author.trim().isNotEmpty == true)
                            book.book!.author,
                          if (book.seconds case final seconds?)
                            statisticsDuration(seconds)
                          else
                            '阅读 ${book.activeDates.length} 天',
                          if (datedBooks && book.activeDates.isNotEmpty)
                            _datesCaption(
                              book.activeDates.first,
                              book.activeDates.last,
                            ),
                        ].join(' · '),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        _muted(
          '选录 ${selected.length}/${books.length} ${template == ReadingReportTemplate.festival ? '站' : '本'}',
        ),
        if (breadth.hasData &&
            (template == ReadingReportTemplate.archive ||
                template == ReadingReportTemplate.constellation ||
                template == ReadingReportTemplate.signal))
          _muted(
            [
              for (final bucket in breadth.buckets)
                '${bucket.label} ${bucket.days}天',
            ].join(' · '),
          ),
      ],
    );
  }

  Widget _progressSection() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      if (_showProgressBands && data.progressBands.any((count) => count > 0))
        ReadingProgressBands(
          bands: data.progressBands,
          reduceMotion: true,
          compact: true,
        )
      else
        Text('当前阅读位置 ${data.readingBooks} 本'),
      if (data.unknownProgress > 0) _muted('未知进度 ${data.unknownProgress} 本'),
      _muted('$_snapshot · 阅读位置快照'),
    ],
  );

  Widget _collectionSection() {
    final genres = data.analysis.genres;
    final limited = template == ReadingReportTemplate.signal;
    final showRatio =
        template != ReadingReportTemplate.signal &&
        template != ReadingReportTemplate.constellation &&
        template != ReadingReportTemplate.shelf &&
        template != ReadingReportTemplate.festival;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '${data.onlineBooks + data.localBooks} 本当前藏书',
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
        ),
        _muted(
          '在线 ${data.onlineBooks} / 本地 ${data.localBooks}'
          '${options.hideAuthorNames || data.authorCount == 0 ? '' : ' · ${data.authorCount} 位作者'}',
        ),
        for (final genre in genres.take(limited ? 1 : 4))
          Text(
            '${genre.label} · ${statisticsDuration(genre.seconds)}'
            '${showRatio ? ' · ${(genre.ratio * 100).toStringAsFixed(1)}%' : ''}',
          ),
        _muted('$_snapshot · 藏书快照'),
      ],
    );
  }

  Widget _excerptSection() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      _metrics([('书签', '${data.bookmarks} 条'), ('划线', '${data.underlines} 条')]),
      for (final excerpt in data.excerpts.take(3))
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(
            [
              excerpt.quote,
              if (!options.hideBookTitles &&
                  excerpt.title.isNotEmpty &&
                  excerpt.title != '书名待读取')
                excerpt.title,
              if (options.includeNotes && excerpt.note.isNotEmpty) excerpt.note,
            ].join('\n'),
            maxLines: 8,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      _muted('$_snapshot · 摘录快照'),
    ],
  );

  Widget _module(ReadingReportModule module) => switch (module) {
    _rhythm => _rhythmSection(),
    _days => _daysSection(),
    _calendar => _ReportCalendar(data: data),
    _books => _bookSection(),
    _progress => _progressSection(),
    _collection => _collectionSection(),
    _excerpts => _excerptSection(),
  };

  @override
  Widget build(BuildContext context) {
    final style = readingExportTheme(template.theme);
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
    final sections = [
      ..._reportSections(template),
      (_excerpts, _excerpts.label, ''),
    ].where((section) => _reportSectionEnabled(section.$1, options, available));
    return Theme(
      data: theme.copyWith(colorScheme: scheme),
      child: Material(
        color: style.background,
        child: DefaultTextStyle(
          style: theme.textTheme.bodyMedium!.copyWith(
            color: style.foreground,
            height: 1.4,
          ),
          child: Padding(
            padding: const EdgeInsets.all(22),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _ReportCover(options: options, data: data),
                if (options.note.trim().isNotEmpty) _muted(options.note),
                const SizedBox(height: 16),
                _timeSummary(),
                for (final (module, title, caption) in sections) ...[
                  const SizedBox(height: 16),
                  _ReportSectionFrame(
                    template: template,
                    title: title,
                    caption: caption,
                    child: _module(module),
                  ),
                ],
                if (options.signature) ...[
                  const SizedBox(height: 16),
                  _muted(switch (template) {
                    ReadingReportTemplate.archive =>
                      'ARCHIVE ENDS / KEEP READING · $readingExportBrandName',
                    ReadingReportTemplate.shelf =>
                      '下一页，继续启航。 · $readingExportBrandName',
                    ReadingReportTemplate.editorial =>
                      '下一页，留给将来的自己。 · $readingExportBrandName',
                    ReadingReportTemplate.constellation =>
                      '夜深，仍有书页。 · $readingExportBrandName',
                    ReadingReportTemplate.signal =>
                      'TO BE CONTINUED · $readingExportBrandName',
                    ReadingReportTemplate.score =>
                      'THE END · $readingExportBrandName',
                    ReadingReportTemplate.ink =>
                      '走得慢一点，也会遇见下一页。 · $readingExportBrandName',
                    ReadingReportTemplate.festival =>
                      '下一站，仍有书页。 · $readingExportBrandName',
                  }),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ReportSectionFrame extends StatelessWidget {
  const _ReportSectionFrame({
    required this.template,
    required this.title,
    required this.caption,
    required this.child,
  });
  final ReadingReportTemplate template;
  final String title, caption;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final outlined =
        template == ReadingReportTemplate.score ||
        template == ReadingReportTemplate.editorial ||
        template == ReadingReportTemplate.festival;
    final edge = BorderSide(color: scheme.outlineVariant);
    return Container(
      padding: EdgeInsets.fromLTRB(outlined ? 12 : 0, 10, outlined ? 12 : 0, 2),
      decoration: BoxDecoration(
        border: outlined ? Border.fromBorderSide(edge) : Border(top: edge),
        color: outlined ? scheme.primary.withValues(alpha: .04) : null,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (caption.isNotEmpty)
            Text(
              caption,
              style: TextStyle(
                color: scheme.onSurfaceVariant,
                fontSize: 10,
                letterSpacing: 1.0,
              ),
            ),
          Text(
            title,
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w700,
              color: scheme.primary,
            ),
          ),
          const SizedBox(height: 7),
          child,
          if (outlined) const SizedBox(height: 7),
        ],
      ),
    );
  }
}

String _reportMonth(DateTime date) =>
    '${date.year}.${date.month.toString().padLeft(2, '0')}';

String _reportSeasons(List<StatisticsAnalysisMonth> months) => {
  for (final month in months)
    switch (month.date.month) {
      3 || 4 || 5 => '春',
      6 || 7 || 8 => '夏',
      9 || 10 || 11 => '秋',
      _ => '冬',
    },
}.join(' · ');

class _ReportMonthLabel extends StatelessWidget {
  const _ReportMonthLabel({
    required this.month,
    required this.circle,
    required this.area,
  });
  final StatisticsAnalysisMonth month;
  final bool circle;
  final double area;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final seconds = month.totalSeconds;
    final diameter = 21 * math.sqrt(area);
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 3, horizontal: 6),
      decoration: BoxDecoration(
        border: Border.all(color: colors.outlineVariant),
        borderRadius: BorderRadius.circular(circle ? 11 : 3),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (circle && diameter > 0) ...[
            Container(
              width: diameter,
              height: diameter,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: colors.primary,
              ),
            ),
            const SizedBox(width: 4),
          ],
          Text(
            '${_reportMonth(month.date)}  ${seconds == null
                ? '—'
                : seconds == 0
                ? '0秒'
                : statisticsDuration(seconds)}',
            style: const TextStyle(fontSize: 10),
          ),
        ],
      ),
    );
  }
}

class _ReportFilmActs extends StatelessWidget {
  const _ReportFilmActs({
    required this.days,
    required this.showTiming,
    required this.showDays,
  });
  final List<StatisticsAnalysisDay> days;
  final bool showTiming, showDays;

  @override
  Widget build(BuildContext context) {
    final count = math.min(3, days.length);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var index = 0; index < count; index++)
          _ReportFilmAct(
            index: index,
            days: days.sublist(
              index * days.length ~/ count,
              (index + 1) * days.length ~/ count,
            ),
            showTiming: showTiming,
            showDays: showDays,
          ),
      ],
    );
  }
}

class _ReportFilmAct extends StatelessWidget {
  const _ReportFilmAct({
    required this.index,
    required this.days,
    required this.showTiming,
    required this.showDays,
  });
  final int index;
  final List<StatisticsAnalysisDay> days;
  final bool showTiming, showDays;

  @override
  Widget build(BuildContext context) {
    final groups = <DateTime, List<StatisticsAnalysisDay>>{};
    for (final day in days) {
      final month = DateTime.utc(day.date.year, day.date.month);
      (groups[month] ??= []).add(day);
    }
    int? seconds(Iterable<StatisticsAnalysisDay> selection) {
      final values = selection.map((day) => day.seconds).whereType<int>();
      return values.isEmpty
          ? null
          : values.fold<int>(0, (total, value) => total + value);
    }

    String runtime(int? seconds) => switch (seconds) {
      null => '未记录',
      0 => '0秒',
      final value => statisticsDuration(value),
    };
    final cuts = groups.entries
        .map(
          (entry) => (
            month: entry.key,
            from: entry.value.first.date.day,
            to: entry.value.last.date.day,
            seconds: seconds(entry.value),
          ),
        )
        .toList(growable: false);
    final peak = cuts.reduce(
      (a, b) => (a.seconds ?? -1) >= (b.seconds ?? -1) ? a : b,
    );
    final visible = cuts.length <= 4 ? cuts : cuts.where((cut) => cut == peak);
    final colors = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 7),
      padding: const EdgeInsets.fromLTRB(10, 7, 6, 7),
      decoration: BoxDecoration(
        border: Border(left: BorderSide(color: colors.primary, width: 2)),
        color: colors.primary.withValues(alpha: .06),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '第${index + 1}幕 · ${const ['相遇', '远行', '归来'][index]}',
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          if (showTiming) Text('RUNTIME  ${runtime(seconds(days))}'),
          Text(
            [
              _datesCaption(days.first.date, days.last.date),
              if (showDays)
                '${days.where((day) => (day.seconds ?? 0) > 0).length} 个阅读日',
            ].join(' · '),
            style: const TextStyle(fontSize: 11),
          ),
          if (showTiming)
            for (final (i, cut) in visible.indexed)
              Text(
                '${cuts.length > 4 ? 'PEAK CUT' : 'CUT ${(i + 1).toString().padLeft(2, '0')}'}'
                '  ${_reportMonth(cut.month)} · ${cut.from}—${cut.to} · ${runtime(cut.seconds)}',
                style: const TextStyle(fontSize: 10),
              ),
        ],
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
    const legend = Text(
      '浅框：未记录 · 深框：已记录 0 · 色深：阅读时长',
      style: TextStyle(fontSize: 10),
    );
    if (data.period > 93) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ReadingActivityCalendar(
            dates: data.dates,
            seconds: data.seconds,
            selectedDate: null,
            onSelected: (_) {},
            showDateAnchors: true,
            reduceMotion: true,
          ),
          legend,
        ],
      );
    }
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
            child: Text('$year / $month'),
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
                      if (day < 1 || !dates.containsKey((year, month, day)))
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
                              border: Border.all(
                                color: dates[(year, month, day)] == null
                                    ? colors.outlineVariant.withValues(
                                        alpha: .5,
                                      )
                                    : colors.outlineVariant,
                              ),
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
        const SizedBox(height: 6),
        legend,
      ],
    );
  }
}
