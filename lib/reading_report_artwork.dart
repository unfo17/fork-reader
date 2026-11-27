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

  Widget _daysSection() {
    final active = data.analysis.days
        .where((day) => (day.seconds ?? 0) > 0)
        .toList();
    final streak = data.analysis.longestStreak;
    final continuous = streak.start != null && streak.end != null
        ? _datesCaption(streak.start!, streak.end!)
        : null;
    if (template == ReadingReportTemplate.signal) {
      return _PrintMetricCards(
        values: [
          ('阅读日', '${data.activeDays}', '有阅读记录的日子'),
          ('连续阅读', '${streak.days}', continuous ?? '暂无连续记录'),
          ('相伴书目', '${data.periodBooks}', '本期实际读过的书'),
          if (options.modules.contains(_collection))
            (
              '当前藏书',
              '${data.onlineBooks + data.localBooks}',
              '$_snapshot 藏书快照',
            ),
        ],
      );
    }
    if (template == ReadingReportTemplate.archive) {
      return _PrintRecordTable(
        rows: [
          ('记录覆盖', '${data.observedDays} / ${data.period} 天'),
          if (data.peakDate case final peak?)
            (
              '最长的一日',
              '${_reportDate(peak)} · ${statisticsDuration(data.peakSeconds)}',
            ),
          if (continuous != null) ('连续阅读段', continuous),
        ],
      );
    }
    if (template == ReadingReportTemplate.constellation) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _PrintCaption(_datesCaption(data.analysis.start, data.analysis.end)),
          if (active.isNotEmpty) ...[
            const SizedBox(height: 9),
            SizedBox(
              height: 45,
              child: CustomPaint(
                painter: _ReportDailyMarks(
                  data.seconds,
                  data.peakSeconds,
                  _reportTint(options),
                ),
                child: const SizedBox.expand(),
              ),
            ),
          ],
          const SizedBox(height: 7),
          _PrintCaption(
            '${data.activeDays} 个阅读日 · ${data.observedDays}/${data.period} 天有记录'
            '${continuous == null ? '' : ' · 连续 ${streak.days} 天 · $continuous'}',
          ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (active.isNotEmpty)
          _PrintRecordTable(
            route:
                template == ReadingReportTemplate.ink ||
                template == ReadingReportTemplate.festival,
            rows: [
              ('本期第一日', _reportDate(active.first.date)),
              ('最近阅读日', _reportDate(active.last.date)),
            ],
          ),
        const SizedBox(height: 8),
        _PrintCaption(
          '${data.activeDays} 个阅读日'
          '${continuous == null ? '' : ' · 最长连续 ${streak.days} 天'}',
        ),
        if (continuous != null) _PrintCaption(continuous),
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
    final maximum = known.fold<StatisticsAnalysisMonth?>(
      null,
      (best, month) => best == null || month.totalSeconds! > best.totalSeconds!
          ? month
          : best,
    );
    final peak = maximum != null && maximum.totalSeconds! > 0 ? maximum : null;
    final scale = math.max(1, maximum?.totalSeconds ?? 0);
    final ledger = _PrintMonthLedger(months: months, peakSeconds: scale);
    if (template == ReadingReportTemplate.constellation) {
      return _PrintRecordTable(
        rows: [
          if (peak != null)
            (_reportMonth(peak.date), statisticsDuration(peak.totalSeconds!))
          else
            ('本期', '暂无阅读时长'),
          ('记录覆盖', '${data.observedDays} / ${data.period} 天'),
        ],
      );
    }
    if (template == ReadingReportTemplate.ink) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final month in months)
                _ReportMonthLabel(
                  month: month,
                  area: (month.totalSeconds ?? 0) / scale,
                ),
            ],
          ),
          const SizedBox(height: 6),
          _muted('着色圆面积按已记录时长'),
        ],
      );
    }
    if (template == ReadingReportTemplate.signal) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _PrintMonthlyStrips(months: months, peakSeconds: scale),
          if (peak != null) ...[
            const SizedBox(height: 10),
            _PrintCaption(
              '本期阅读最多：${_reportMonth(peak.date)} · ${statisticsDuration(peak.totalSeconds!)}',
            ),
          ],
        ],
      );
    }
    if (template == ReadingReportTemplate.archive ||
        template == ReadingReportTemplate.editorial) {
      final archive = template == ReadingReportTemplate.archive;
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 4,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _ReportFigure(
                  value: archive
                      ? (peak == null ? '—' : '${peak.date.month}')
                      : _reportSeasons(months),
                  highlight: true,
                  size: archive ? 44 : 33,
                ),
                _PrintCaption(
                  archive
                      ? '本期峰值月'
                      : _datesCaption(data.analysis.start, data.analysis.end),
                ),
                const SizedBox(height: 8),
                if (archive)
                  _PrintCaption('${known.length} 个有覆盖的月份')
                else
                  _PrintCaption(_reportDuration(data.periodSeconds)),
              ],
            ),
          ),
          const SizedBox(width: 14),
          Expanded(flex: 7, child: ledger),
        ],
      );
    }
    if (template == ReadingReportTemplate.festival) {
      return _ReportStationMonths(months: months, peakSeconds: scale);
    }
    return ledger;
  }

  Widget _bookSection() {
    final books = _rankedReportBooks(data);
    final selected = books.take(options.bookLimit).toList();
    final maximum = selected.fold<int>(
      1,
      (best, book) => math.max(best, book.seconds ?? 0),
    );
    final breadth = data.analysis.bookDayDistribution;
    final rows = [
      for (final (index, book) in selected.indexed)
        _PrintBookRow(
          template: template,
          index: index,
          title: options.hideBookTitles ? '书籍 ${index + 1}' : book.title,
          author: options.hideAuthorNames ? null : book.book?.author.trim(),
          time: book.seconds == null
              ? '阅读 ${book.activeDates.length} 天'
              : statisticsDuration(book.seconds!),
          dates:
              (template == ReadingReportTemplate.ink ||
                      template == ReadingReportTemplate.festival) &&
                  book.activeDates.isNotEmpty
              ? _datesCaption(book.activeDates.first, book.activeDates.last)
              : null,
          relativeTime: book.seconds == null ? null : book.seconds! / maximum,
        ),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (template == ReadingReportTemplate.editorial)
          _ReportTwoColumns(children: rows)
        else if (template == ReadingReportTemplate.ink)
          _ReportWaypoints(children: rows)
        else
          ...rows,
        const SizedBox(height: 7),
        _PrintCaption(
          '选录 ${selected.length}/${books.length} ${template == ReadingReportTemplate.festival ? '站' : '本'}',
        ),
        if (breadth.hasData &&
            (template == ReadingReportTemplate.archive ||
                template == ReadingReportTemplate.constellation ||
                template == ReadingReportTemplate.signal))
          _PrintCaption(
            [
              for (final bucket in breadth.buckets)
                '${bucket.label} ${bucket.days}天',
            ].join(' · '),
          ),
      ],
    );
  }

  Widget _progressSection() {
    final archive = template == ReadingReportTemplate.archive;
    final bars =
        archive ||
        template == ReadingReportTemplate.ink ||
        template == ReadingReportTemplate.festival;
    final bands = data.progressBands.any((count) => count > 0);
    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (bars || !bands) Text('当前阅读位置 ${data.readingBooks} 本'),
        if (bands) ...[
          const SizedBox(height: 5),
          if (bars)
            ReadingProgressBands(
              bands: data.progressBands,
              reduceMotion: true,
              compact: true,
              labelSize: 10,
            )
          else
            _PrintStatsStrip(
              numberSize: 22,
              values: [
                for (final (index, label) in const [
                  '0–25%',
                  '25–50%',
                  '50–75%',
                  '75–100%',
                ].indexed)
                  (label, '${data.progressBands[index]}'),
              ],
            ),
        ],
        const SizedBox(height: 6),
        if (data.unknownProgress > 0)
          _PrintCaption('未知进度 ${data.unknownProgress} 本'),
        _PrintCaption('$_snapshot · 阅读位置快照'),
      ],
    );
    return archive
        ? Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                flex: 3,
                child: AspectRatio(
                  aspectRatio: .76,
                  child: _PrintPicture(
                    _reportArt(options, data),
                    alignment: Alignment.centerRight,
                  ),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(flex: 7, child: content),
            ],
          )
        : content;
  }

  Widget _collectionSection() {
    final total = data.onlineBooks + data.localBooks;
    final genres = data.analysis.genres;
    final limited = template == ReadingReportTemplate.signal;
    final showRatio =
        template != ReadingReportTemplate.signal &&
        template != ReadingReportTemplate.constellation &&
        template != ReadingReportTemplate.shelf &&
        template != ReadingReportTemplate.festival;
    final archive = template == ReadingReportTemplate.archive;
    final heading = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _ReportFigure(value: '$total', highlight: !archive, size: 42),
        const _PrintCaption('本当前藏书'),
      ],
    );
    final rows = [
      _PrintCaption(
        '在线 ${data.onlineBooks} / 本地 ${data.localBooks}'
        '${options.hideAuthorNames || data.authorCount == 0 ? '' : ' · ${data.authorCount} 位作者'}',
      ),
      for (final genre in genres.take(limited ? 1 : 4))
        Text(
          '${genre.label} · ${statisticsDuration(genre.seconds)}'
          '${showRatio ? ' · ${(genre.ratio * 100).toStringAsFixed(1)}%' : ''}',
        ),
      _PrintCaption('$_snapshot · 藏书快照'),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (archive)
          SizedBox(
            height: 133,
            child: Stack(
              fit: StackFit.expand,
              children: [
                const _PrintPicture(_archiveStacks, dim: true),
                DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [
                        Colors.black.withValues(alpha: .82),
                        Colors.black.withValues(alpha: .05),
                      ],
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.all(15),
                  child: DefaultTextStyle.merge(
                    style: const TextStyle(color: Colors.white),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Theme(data: ThemeData.dark(), child: heading),
                    ),
                  ),
                ),
              ],
            ),
          )
        else
          heading,
        const SizedBox(height: 9),
        ...rows,
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
    _calendar => _ReportCalendar(
      data: data,
      round: template == ReadingReportTemplate.constellation,
    ),
    _books => _bookSection(),
    _progress => _progressSection(),
    _collection => _collectionSection(),
    _excerpts => _excerptSection(),
  };

  Widget _section(_ReportSection entry, {bool inset = true}) {
    final (module, title, caption) = entry;
    return _ReportSectionFrame(
      template: template,
      module: module,
      title: title,
      caption: caption,
      inset: inset,
      child: _module(module),
    );
  }

  @override
  Widget build(BuildContext context) {
    final style = readingExportTheme(template.theme);
    final tint = _reportTint(options);
    final theme = Theme.of(context);
    final lightBody =
        template == ReadingReportTemplate.shelf ||
        template == ReadingReportTemplate.signal;
    final paper = switch (template) {
      ReadingReportTemplate.shelf => const Color(0xffe0e9e6),
      ReadingReportTemplate.signal => const Color(0xffeeeee4),
      _ => style.background,
    };
    final ink = lightBody ? const Color(0xff24302b) : style.foreground;
    final primary = lightBody && options.palette == ReadingReportPalette.pine
        ? (template == ReadingReportTemplate.shelf
              ? const Color(0xff386d65)
              : const Color(0xff49594e))
        : lightBody
        ? Color.lerp(tint, ink, .53)!
        : tint;
    final scheme = theme.colorScheme.copyWith(
      brightness: !lightBody && style.isDark
          ? Brightness.dark
          : Brightness.light,
      surface: paper,
      onSurface: ink,
      onSurfaceVariant: Color.lerp(paper, ink, .76),
      primary: primary,
      outline: Color.lerp(paper, ink, .37),
      outlineVariant: Color.lerp(paper, ink, .19),
    );
    final available = _availableModules(data);
    final sections =
        [..._reportSections(template), (_excerpts, _excerpts.label, '')]
            .where(
              (entry) => _reportSectionEnabled(entry.$1, options, available),
            )
            .toList();
    final partner = switch (template) {
      ReadingReportTemplate.shelf => _books,
      ReadingReportTemplate.festival => _collection,
      _ => null,
    };
    final dayIndex = sections.indexWhere((entry) => entry.$1 == _days);
    final pairIndex =
        partner != null &&
            dayIndex >= 0 &&
            dayIndex + 1 < sections.length &&
            sections[dayIndex + 1].$1 == partner
        ? dayIndex
        : -1;
    final widget = Theme(
      data: theme.copyWith(colorScheme: scheme),
      child: Material(
        color: paper,
        child: DefaultTextStyle(
          style: theme.textTheme.bodyMedium!.copyWith(
            color: ink,
            height: 1.4,
            fontSize: 12,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _ReportCover(options: options, data: data),
              for (final (index, entry) in sections.indexed)
                if (index == pairIndex)
                  _PrintInset(
                    vertical: 0,
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(flex: 4, child: _section(entry, inset: false)),
                        const SizedBox(width: 13),
                        Expanded(
                          flex: 7,
                          child: _section(sections[index + 1], inset: false),
                        ),
                      ],
                    ),
                  )
                else if (pairIndex < 0 || index != pairIndex + 1)
                  _section(entry),
              if (options.signature)
                _ReportColophon(
                  template: template,
                  year: data.analysis.end.year,
                ),
            ],
          ),
        ),
      ),
    );
    return template == ReadingReportTemplate.score
        ? _ReportFilmEdge(child: widget)
        : widget;
  }
}

class _ReportSectionFrame extends StatelessWidget {
  const _ReportSectionFrame({
    required this.template,
    required this.module,
    required this.title,
    required this.caption,
    required this.inset,
    required this.child,
  });
  final ReadingReportTemplate template;
  final ReadingReportModule module;
  final String title, caption;
  final bool inset;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final index = template == ReadingReportTemplate.archive
        ? switch (module) {
            _progress => '01',
            _days => '02',
            _rhythm => '03',
            _books => '04',
            _ => null,
          }
        : null;
    final ticket = template == ReadingReportTemplate.festival;
    final serif =
        template == ReadingReportTemplate.editorial ||
        template == ReadingReportTemplate.ink;
    final panel =
        (template == ReadingReportTemplate.shelf &&
            (module == _rhythm || module == _collection)) ||
        (template == ReadingReportTemplate.signal && module == _rhythm);
    final heading = Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        if (index != null) ...[
          _ReportFigure(value: index, highlight: true, size: 25),
          const SizedBox(width: 12),
        ],
        Expanded(
          child: Text(
            title,
            style: TextStyle(
              fontSize: inset ? 18 : 16,
              height: 1.16,
              fontWeight: FontWeight.w800,
              fontFamily: serif ? 'ReadingSerifDisplay' : null,
            ),
          ),
        ),
      ],
    );
    final children = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (ticket) const _ReportPerforation(),
        if (caption.isNotEmpty) ...[
          _PrintCaption(caption, tracking: true, size: 8),
          const SizedBox(height: 4),
        ],
        heading,
        const SizedBox(height: 11),
        child,
      ],
    );
    final content = inset
        ? _PrintInset(
            vertical:
                template == ReadingReportTemplate.score && module == _rhythm
                ? 10
                : 16,
            child: children,
          )
        : Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: children,
          );
    if (panel) {
      return _PrintTone(
        background: template == ReadingReportTemplate.shelf
            ? const Color(0xff15212c)
            : const Color(0xff252524),
        foreground: const Color(0xffefebe1),
        child: content,
      );
    }
    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border(
          top: BorderSide(
            color: scheme.outlineVariant.withValues(alpha: .62),
            width: .5,
          ),
        ),
      ),
      child: content,
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
  const _ReportMonthLabel({required this.month, required this.area});
  final StatisticsAnalysisMonth month;
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
        borderRadius: BorderRadius.circular(11),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (diameter > 0) ...[
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
    return Padding(
      padding: const EdgeInsets.only(bottom: 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              _ReportFigure(
                value: (index + 1).toString().padLeft(2, '0'),
                size: 38,
                highlight: true,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '第${index + 1}幕 · ${const ['相遇', '远行', '归来'][index]}',
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    _PrintCaption('ACT ${index + 1}', tracking: true),
                  ],
                ),
              ),
              if (showTiming)
                _PrintCaption('RUNTIME\n${runtime(seconds(days))}'),
            ],
          ),
          const SizedBox(height: 5),
          _PrintCaption(
            [
              _datesCaption(days.first.date, days.last.date),
              if (showDays)
                '${days.where((day) => (day.seconds ?? 0) > 0).length} 个阅读日',
            ].join(' · '),
          ),
          const SizedBox(height: 8),
          AspectRatio(
            aspectRatio: 2.02,
            child: DecoratedBox(
              decoration: BoxDecoration(
                border: Border.all(color: colors.outlineVariant),
              ),
              child: Padding(
                padding: const EdgeInsets.all(2),
                child: _PrintPicture(_filmStills[index], dim: true),
              ),
            ),
          ),
          if (showTiming) ...[
            const SizedBox(height: 7),
            _ReportTwoColumns(
              children: [
                for (final (i, cut) in visible.indexed)
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: colors.outlineVariant.withValues(alpha: .18),
                      border: Border.all(
                        color: colors.outlineVariant,
                        width: .65,
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _PrintCaption(
                          cuts.length > 4
                              ? 'PEAK CUT'
                              : 'CUT ${(i + 1).toString().padLeft(2, '0')}',
                          tracking: true,
                        ),
                        _PrintCaption(
                          '${_reportMonth(cut.month)} · ${cut.from}—${cut.to}',
                        ),
                        Text(
                          runtime(cut.seconds),
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        const SizedBox(height: 5),
                        _ReportMeter(
                          value: cut.seconds == null || (peak.seconds ?? 0) <= 0
                              ? null
                              : cut.seconds! / peak.seconds!,
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

class _ReportCalendar extends StatelessWidget {
  const _ReportCalendar({required this.data, required this.round});
  final ReadingStatisticsData data;
  final bool round;

  @override
  Widget build(BuildContext context) {
    const legend = _PrintCaption('浅框：未记录 · 深框：已记录 0 · 色深：阅读时长', size: 9);
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
    final months = <DateTime, Map<int, int?>>{};
    for (final (i, date) in data.dates.indexed) {
      final month = DateTime(date.year, date.month);
      (months[month] ??= {})[date.day] = data.seconds[i];
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _ReportTwoColumns(
          children: [
            for (final entry in months.entries)
              _ReportCalendarMonth(
                month: entry.key,
                days: entry.value,
                maximum: data.peakSeconds,
                round: round,
              ),
          ],
        ),
        const SizedBox(height: 9),
        legend,
      ],
    );
  }
}

class _ReportCalendarMonth extends StatelessWidget {
  const _ReportCalendarMonth({
    required this.month,
    required this.days,
    required this.maximum,
    required this.round,
  });
  final DateTime month;
  final Map<int, int?> days;
  final int maximum;
  final bool round;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        '${month.year} / ${month.month.toString().padLeft(2, '0')}',
        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
      ),
      const SizedBox(height: 3),
      Table(
        children: [
          TableRow(
            children: [
              for (final weekday in const ['一', '二', '三', '四', '五', '六', '日'])
                Center(child: _PrintCaption(weekday, size: 8)),
            ],
          ),
          for (
            var start = 2 - month.weekday;
            start <= DateUtils.getDaysInMonth(month.year, month.month);
            start += 7
          )
            TableRow(
              children: [
                for (var day = start; day < start + 7; day++)
                  Padding(
                    padding: const EdgeInsets.all(1),
                    child: AspectRatio(
                      aspectRatio: 1,
                      child: !days.containsKey(day)
                          ? null
                          : _ReportCalendarCell(
                              day: day,
                              seconds: days[day],
                              maximum: maximum,
                              round: round,
                            ),
                    ),
                  ),
              ],
            ),
        ],
      ),
    ],
  );
}

class _ReportCalendarCell extends StatelessWidget {
  const _ReportCalendarCell({
    required this.day,
    required this.seconds,
    required this.maximum,
    required this.round,
  });
  final int day, maximum;
  final int? seconds;
  final bool round;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final fraction = (seconds ?? 0) / math.max(1, maximum);
    final fill = switch (seconds) {
      null => colors.surface,
      0 => Color.lerp(colors.surface, colors.outlineVariant, .4)!,
      _ => Color.lerp(colors.surface, colors.primary, .32 + fraction * .55)!,
    };
    final text = fraction > .42
        ? (fill.computeLuminance() > .35
              ? const Color(0xff151b19)
              : Colors.white)
        : colors.onSurface;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: fill,
        border: Border.all(
          color: colors.outlineVariant.withValues(
            alpha: seconds == null ? .48 : 1,
          ),
          width: .6,
        ),
        borderRadius: BorderRadius.circular(round ? 99 : 2),
      ),
      child: Center(
        child: Text('$day', style: TextStyle(fontSize: 8.5, color: text)),
      ),
    );
  }
}
