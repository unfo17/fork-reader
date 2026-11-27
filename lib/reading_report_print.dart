part of 'reading_report_page.dart';

const _archiveStacks = 'assets/reading_art/archive-110.webp';
const _harborCover = 'assets/reading_art/shelf-110.webp';
const _dawnCover = 'assets/reading_art/ink-110.webp';
const _filmStills = [
  'assets/reading_art/constellation-110.webp',
  'assets/reading_art/score-110.webp',
  'assets/reading_art/shelf-110.webp',
];

class _ReportCover extends StatelessWidget {
  const _ReportCover({
    required this.options,
    required this.data,
    this.compact = false,
  });
  final ReadingReportOptions options;
  final ReadingStatisticsData data;
  final bool compact;

  ReadingReportTemplate get template => options.template;
  ReadingExportTheme get style => readingExportTheme(template.theme);
  Color get accent => _reportTint(options);
  String get title =>
      options.title.trim().isEmpty ? '阅读报告' : options.title.trim();
  String get reader =>
      options.nickname.trim().isEmpty ? '一位读者' : options.nickname.trim();
  String get dates =>
      '${_reportDate(data.analysis.start)} — ${_reportDate(data.analysis.end)}';
  String get year => '${data.analysis.end.year}';

  Widget _headline({double size = 33}) => Text(
    title,
    maxLines: 3,
    overflow: TextOverflow.ellipsis,
    style: TextStyle(
      fontSize: size,
      fontWeight: FontWeight.w800,
      height: 1.14,
      fontFamily: style.prefersSerif ? 'ReadingSerifDisplay' : null,
    ),
  );

  Widget _readerLine({bool note = true, bool passport = false}) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    mainAxisSize: MainAxisSize.min,
    children: [
      Text(
        passport ? 'PASSENGER / $reader' : reader,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontWeight: FontWeight.w700),
      ),
      const SizedBox(height: 5),
      _PrintCaption(dates),
      if (note && options.note.trim().isNotEmpty) ...[
        const SizedBox(height: 8),
        _PrintCaption(options.note.trim()),
      ],
    ],
  );

  Widget _masthead({bool showYear = true}) => Row(
    crossAxisAlignment: CrossAxisAlignment.center,
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              readingExportBrandName,
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800),
            ),
            _PrintCaption('PERSONAL READING REPORT', tracking: true, size: 8),
          ],
        ),
      ),
      if (showYear)
        Text(
          year,
          style: const TextStyle(
            fontSize: 23,
            height: 1,
            fontFamily: 'ReadingDisplay',
            fontWeight: FontWeight.w700,
          ),
        ),
    ],
  );

  Widget _duration(String label, {Color? ink, double size = 57}) =>
      _PrintDuration(
        label: label,
        seconds: _reportRecordedSeconds(data),
        ink: ink ?? accent,
        size: size,
      );

  Widget _picture(double aspect, {Alignment? alignment, bool dim = false}) =>
      AspectRatio(
        aspectRatio: aspect,
        child: _PrintPicture(
          _reportArt(options, data),
          alignment: alignment ?? style.previewAlignment,
          dim: dim,
        ),
      );

  Widget _archive() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _PrintInset(child: _masthead()),
      SizedBox(
        height: 237,
        child: Stack(
          fit: StackFit.expand,
          children: [
            Positioned.fill(
              left: 104,
              child: _PrintPicture(
                _reportArt(options, data),
                alignment: const Alignment(.7, 0),
              ),
            ),
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: [
                    style.background,
                    style.background,
                    style.background.withValues(alpha: 0),
                  ],
                  stops: const [0, .34, .72],
                ),
              ),
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: FractionallySizedBox(
                widthFactor: .54,
                child: _PrintInset(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      DecoratedBox(
                        decoration: BoxDecoration(color: style.foreground),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 3,
                          ),
                          child: Text(
                            'FILE / ${_reportDate(data.analysis.end).replaceAll('.', '')}',
                            style: TextStyle(
                              color: style.background,
                              fontFamily: 'ReadingDisplay',
                              fontSize: 11,
                              letterSpacing: 1,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 10),
                      _headline(size: 25),
                      const SizedBox(height: 9),
                      _readerLine(),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
      _PrintTone(
        background: const Color(0xff22312d),
        foreground: const Color(0xfff1f3ed),
        child: _PrintInset(
          vertical: 14,
          child: _duration('TOTAL READING TIME', ink: const Color(0xfff1f3ed)),
        ),
      ),
      if (_reportRecordedSeconds(data) != null)
        _PrintTone(
          background: Color.lerp(accent, style.foreground, .23)!,
          foreground: Colors.white,
          child: _PrintInset(
            vertical: 12,
            child: _PrintStatsStrip(
              values: [
                ('阅读日', '${data.activeDays}'),
                ('最长连续 / 天', '${data.analysis.longestStreak.days}'),
                ('相伴书目', '${data.periodBooks}'),
                ('已记录日均', statisticsDuration(data.averageSeconds)),
              ],
            ),
          ),
        ),
    ],
  );

  Widget _shelf() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _PrintInset(child: _masthead()),
      _picture(1.68, alignment: const Alignment(.13, .22), dim: true),
      _PrintInset(
        vertical: 22,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Flexible(
              flex: 4,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _headline(size: 28),
                  const SizedBox(height: 10),
                  _readerLine(note: false),
                ],
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              flex: 7,
              child: Container(
                decoration: BoxDecoration(
                  border: Border(
                    left: BorderSide(color: accent.withValues(alpha: .48)),
                  ),
                ),
                padding: const EdgeInsets.only(left: 14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _duration('READING / VOYAGE', ink: style.foreground),
                    if (options.note.trim().isNotEmpty) ...[
                      const SizedBox(height: 13),
                      _PrintCaption(options.note.trim()),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    ],
  );

  Widget _editorial() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _PrintInset(child: _masthead(showYear: false)),
      _PrintInset(
        vertical: 12,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              year,
              style: TextStyle(
                color: accent,
                fontFamily: 'ReadingDisplay',
                height: .98,
                fontSize: 68,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 6),
            _headline(size: 29),
          ],
        ),
      ),
      _picture(1.75, alignment: const Alignment(.15, .2)),
      _PrintInset(
        vertical: 16,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _readerLine(),
            const SizedBox(height: 12),
            _duration('这本手帖的阅读时长', size: 42),
            if (data.activeDays > 0 && options.modules.contains(_days)) ...[
              const SizedBox(height: 6),
              _PrintCaption(
                '${data.activeDays} 个阅读日 · 最长连续 ${data.analysis.longestStreak.days} 天',
              ),
            ],
          ],
        ),
      ),
    ],
  );

  Widget _imageCover({required bool night}) {
    final base = night ? style.background : const Color(0xff242423);
    final white = night ? style.foreground : const Color(0xfff6f2e6);
    return _PrintTone(
      background: base,
      foreground: white,
      child: SizedBox(
        height: night ? 497 : 440,
        child: Stack(
          fit: StackFit.expand,
          children: [
            _PrintPicture(
              _reportArt(options, data),
              alignment: night
                  ? const Alignment(.85, .24)
                  : const Alignment(.96, 0),
              monochrome: !night,
            ),
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    base.withValues(alpha: .93),
                    base.withValues(alpha: .5),
                    base.withValues(alpha: 0),
                    base.withValues(alpha: .27),
                  ],
                  stops: const [0, .33, .67, 1],
                ),
              ),
            ),
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    base.withValues(alpha: .25),
                    base.withValues(alpha: 0),
                    base.withValues(alpha: .4),
                    base,
                  ],
                  stops: const [0, .62, .84, 1],
                ),
              ),
            ),
            _PrintInset(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _masthead(),
                  const SizedBox(height: 18),
                  FractionallySizedBox(
                    widthFactor: .72,
                    child: _headline(size: 28),
                  ),
                  const SizedBox(height: 12),
                  _duration(night ? '夜读时长' : '本期阅读', ink: white, size: 70),
                  const Spacer(),
                  FractionallySizedBox(widthFactor: .75, child: _readerLine()),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _cinema() => _PrintInset(
    vertical: 18,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _masthead(),
        const SizedBox(height: 17),
        DecoratedBox(
          decoration: BoxDecoration(
            border: Border.all(color: accent.withValues(alpha: .45)),
          ),
          child: Padding(
            padding: const EdgeInsets.all(8),
            child: _PrintStatsStrip(
              values: [
                ('制作', readingExportBrandName),
                ('范围', '${data.period} 天'),
                ('读者', reader),
              ],
              numberSize: 13,
            ),
          ),
        ),
        const SizedBox(height: 14),
        Divider(height: 1, color: accent.withValues(alpha: .55)),
        const SizedBox(height: 14),
        _headline(size: 38),
        const SizedBox(height: 9),
        _readerLine(),
        const SizedBox(height: 13),
        _duration('RUNNING TIME / 本期放映时长'),
      ],
    ),
  );

  Widget _scenic({required bool ticket}) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _PrintInset(
        vertical: 12,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _masthead(),
            const SizedBox(height: 14),
            _headline(size: 37),
          ],
        ),
      ),
      _picture(
        1.72,
        alignment: ticket ? const Alignment(.72, 0) : Alignment.center,
      ),
      _PrintInset(
        vertical: 20,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(flex: 5, child: _duration(ticket ? '本期旅程' : '沿途的阅读时长')),
            const SizedBox(width: 14),
            Expanded(
              flex: 5,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _readerLine(passport: ticket),
                  if (ticket && data.periodBooks > 0) ...[
                    const SizedBox(height: 7),
                    _PrintCaption('${data.periodBooks} 本相伴书目'),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    ],
  );

  Widget _compactCover() => _PrintInset(
    vertical: 12,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _masthead(),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              flex: 6,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _headline(size: 24),
                  const SizedBox(height: 8),
                  _readerLine(note: false),
                ],
              ),
            ),
            const SizedBox(width: 10),
            Expanded(flex: 5, child: _picture(1.12)),
          ],
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) => _PrintTone(
    background: style.background,
    foreground: style.foreground,
    child: compact
        ? _compactCover()
        : switch (template) {
            ReadingReportTemplate.archive => _archive(),
            ReadingReportTemplate.shelf => _shelf(),
            ReadingReportTemplate.editorial => _editorial(),
            ReadingReportTemplate.constellation => _imageCover(night: true),
            ReadingReportTemplate.signal => _imageCover(night: false),
            ReadingReportTemplate.score => _cinema(),
            ReadingReportTemplate.ink => _scenic(ticket: false),
            ReadingReportTemplate.festival => _scenic(ticket: true),
          },
  );
}

int? _reportRecordedSeconds(ReadingStatisticsData data) => data.observedDays > 0
    ? data.periodSeconds
    : data.analysis.coverage.hasTimeDetail
    ? data.analysis.measuredSeconds
    : null;

class _PrintDuration extends StatelessWidget {
  const _PrintDuration({
    required this.label,
    required this.seconds,
    required this.ink,
    required this.size,
  });
  final String label;
  final int? seconds;
  final Color ink;
  final double size;

  @override
  Widget build(BuildContext context) {
    final elapsed = seconds;
    final unit = elapsed == null
        ? 1
        : elapsed >= 3600
        ? 3600
        : elapsed >= 60
        ? 60
        : 1;
    final detail = elapsed == null
        ? null
        : [
            if (unit == 3600) '小时' else if (unit == 60) '分钟' else '秒',
            if (unit == 3600 && elapsed % 3600 >= 60)
              '${elapsed % 3600 ~/ 60} 分钟',
            if (unit > 1 && elapsed % 60 > 0) '${elapsed % 60} 秒',
          ].join(' ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        _PrintCaption(label, tracking: true),
        const SizedBox(height: 4),
        if (elapsed == null)
          const Text(
            '本期暂无阅读时长记录',
            style: TextStyle(fontWeight: FontWeight.w700),
          )
        else ...[
          Text(
            '${elapsed ~/ unit}',
            style: TextStyle(
              fontFamily: 'ReadingDisplay',
              fontSize: size,
              height: .87,
              fontWeight: FontWeight.w700,
              color: ink,
            ),
          ),
          const SizedBox(height: 5),
          _PrintCaption(detail!),
        ],
      ],
    );
  }
}

class _PrintStatsStrip extends StatelessWidget {
  const _PrintStatsStrip({required this.values, this.numberSize = 24});
  final List<(String, String)> values;
  final double numberSize;

  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      for (final (caption, value) in values)
        Expanded(
          child: Padding(
            padding: const EdgeInsets.only(right: 5),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  value,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: numberSize,
                    height: 1,
                    fontFamily: 'ReadingDisplay',
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 4),
                _PrintCaption(caption, size: 9),
              ],
            ),
          ),
        ),
    ],
  );
}

class _PrintInset extends StatelessWidget {
  const _PrintInset({required this.child, this.vertical = 10});
  final Widget child;
  final double vertical;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.symmetric(horizontal: 22, vertical: vertical),
    child: child,
  );
}

class _PrintTone extends StatelessWidget {
  const _PrintTone({
    required this.background,
    required this.foreground,
    required this.child,
  });
  final Color background, foreground;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Theme(
      data: theme.copyWith(
        colorScheme: theme.colorScheme.copyWith(
          surface: background,
          onSurface: foreground,
          onSurfaceVariant: Color.lerp(background, foreground, .7),
          outlineVariant: Color.lerp(background, foreground, .23),
        ),
      ),
      child: ColoredBox(
        color: background,
        child: DefaultTextStyle.merge(
          style: TextStyle(color: foreground),
          child: child,
        ),
      ),
    );
  }
}

class _PrintCaption extends StatelessWidget {
  const _PrintCaption(this.text, {this.size = 10, this.tracking = false});
  final String text;
  final double size;
  final bool tracking;

  @override
  Widget build(BuildContext context) => Text(
    text,
    style: TextStyle(
      color: Theme.of(context).colorScheme.onSurfaceVariant,
      fontSize: size,
      letterSpacing: tracking ? .8 : null,
      height: 1.3,
    ),
  );
}

class _PrintPicture extends StatelessWidget {
  const _PrintPicture(
    this.asset, {
    this.alignment = Alignment.center,
    this.dim = false,
    this.monochrome = false,
  });
  final String asset;
  final Alignment alignment;
  final bool dim, monochrome;

  @override
  Widget build(BuildContext context) {
    Widget art = Image.asset(
      asset,
      alignment: alignment,
      fit: BoxFit.cover,
      excludeFromSemantics: true,
      errorBuilder: (_, _, _) => ColoredBox(
        color: Theme.of(context).colorScheme.surface,
        child: Icon(
          CupertinoIcons.book,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    );
    if (monochrome) {
      art = ColorFiltered(
        colorFilter: const ColorFilter.matrix([
          .213,
          .715,
          .072,
          0,
          0,
          .213,
          .715,
          .072,
          0,
          0,
          .213,
          .715,
          .072,
          0,
          0,
          0,
          0,
          0,
          1,
          0,
        ]),
        child: art,
      );
    }
    if (dim) {
      art = ColorFiltered(
        colorFilter: const ColorFilter.mode(
          Color(0x2b142833),
          BlendMode.srcATop,
        ),
        child: art,
      );
    }
    return art;
  }
}

class _ReportFigure extends StatelessWidget {
  const _ReportFigure({
    required this.value,
    required this.size,
    this.highlight = false,
  });
  final String value;
  final double size;
  final bool highlight;

  @override
  Widget build(BuildContext context) => Text(
    value,
    style: TextStyle(
      color: highlight ? Theme.of(context).colorScheme.primary : null,
      fontFamily: 'ReadingDisplay',
      fontSize: size,
      fontWeight: FontWeight.w700,
      height: 1,
    ),
  );
}

class _ReportTwoColumns extends StatelessWidget {
  const _ReportTwoColumns({required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, space) {
      const gap = 12.0;
      final single = space.maxWidth < 232;
      final width = single ? space.maxWidth : (space.maxWidth - gap) / 2;
      return Wrap(
        spacing: gap,
        runSpacing: gap,
        children: [
          for (final item in children) SizedBox(width: width, child: item),
        ],
      );
    },
  );
}

class _PrintMetricCards extends StatelessWidget {
  const _PrintMetricCards({required this.values});
  final List<(String, String, String)> values;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return _ReportTwoColumns(
      children: [
        for (final (index, (label, number, detail)) in values.indexed)
          Container(
            padding: const EdgeInsets.all(10),
            constraints: const BoxConstraints(minHeight: 72),
            decoration: BoxDecoration(
              border: Border.all(color: scheme.outlineVariant),
              color: index.isOdd
                  ? Color.lerp(scheme.surface, scheme.primary, .22)
                  : null,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _PrintCaption(label),
                const SizedBox(height: 3),
                _ReportFigure(value: number, size: 26),
                const SizedBox(height: 3),
                _PrintCaption(detail, size: 9),
              ],
            ),
          ),
      ],
    );
  }
}

class _PrintRecordTable extends StatelessWidget {
  const _PrintRecordTable({required this.rows, this.route = false});
  final List<(String, String)> rows;
  final bool route;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final valueStyle = TextStyle(
      color: route ? scheme.primary : null,
      fontWeight: FontWeight.w700,
    );
    return LayoutBuilder(
      builder: (context, space) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final (label, value) in rows)
            Container(
              padding: const EdgeInsets.symmetric(vertical: 5),
              decoration: BoxDecoration(
                border: Border(
                  bottom: BorderSide(color: scheme.outlineVariant, width: .5),
                ),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (route) ...[
                    Padding(
                      padding: const EdgeInsets.only(top: 3),
                      child: Icon(
                        CupertinoIcons.circle_fill,
                        size: 7,
                        color: scheme.primary,
                      ),
                    ),
                    const SizedBox(width: 6),
                  ],
                  if (space.maxWidth < 155)
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _PrintCaption(label),
                          Text(value, style: valueStyle),
                        ],
                      ),
                    )
                  else ...[
                    Expanded(flex: 4, child: _PrintCaption(label)),
                    const SizedBox(width: 6),
                    Expanded(flex: 7, child: Text(value, style: valueStyle)),
                  ],
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _PrintMonthLedger extends StatelessWidget {
  const _PrintMonthLedger({required this.months, required this.peakSeconds});
  final List<StatisticsAnalysisMonth> months;
  final int peakSeconds;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (final month in months)
        Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              SizedBox(
                width: 32,
                child: _PrintCaption(
                  '${month.date.year % 100}.${month.date.month.toString().padLeft(2, '0')}',
                  size: 9,
                ),
              ),
              Expanded(
                child: _ReportMeter(
                  value: month.totalSeconds == null
                      ? null
                      : month.totalSeconds! / peakSeconds,
                ),
              ),
              const SizedBox(width: 6),
              SizedBox(
                width: 56,
                child: _PrintCaption(
                  _printCompactTime(month.totalSeconds),
                  size: 9,
                ),
              ),
            ],
          ),
        ),
    ],
  );
}

String _printCompactTime(int? seconds) {
  if (seconds == null) return '—';
  if (seconds >= 3600) return '${seconds ~/ 3600}h${seconds % 3600 ~/ 60}m';
  if (seconds >= 60) return '${seconds ~/ 60}m';
  return '${seconds}s';
}

class _ReportMeter extends StatelessWidget {
  const _ReportMeter({required this.value});
  final double? value;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(
      height: 3,
      width: double.infinity,
      child: ColoredBox(
        color: scheme.outlineVariant.withValues(
          alpha: value == null ? .35 : .8,
        ),
        child: Align(
          alignment: Alignment.centerLeft,
          child: FractionallySizedBox(
            widthFactor: (value ?? 0).clamp(0, 1),
            heightFactor: 1,
            child: ColoredBox(color: scheme.primary),
          ),
        ),
      ),
    );
  }
}

class _PrintMonthlyStrips extends StatelessWidget {
  const _PrintMonthlyStrips({required this.months, required this.peakSeconds});
  final List<StatisticsAnalysisMonth> months;
  final int peakSeconds;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Row(
      children: [
        for (final month in months)
          Expanded(
            child: Column(
              children: [
                SizedBox(
                  height: 82,
                  child: Align(
                    alignment: Alignment.bottomCenter,
                    child: FractionallySizedBox(
                      widthFactor: .33,
                      heightFactor: month.totalSeconds == null
                          ? 0
                          : math.max(.022, month.totalSeconds! / peakSeconds),
                      child: ColoredBox(color: colors.onSurface),
                    ),
                  ),
                ),
                const SizedBox(height: 4),
                _PrintCaption(
                  month.date.month.toString().padLeft(2, '0'),
                  size: 8,
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _PrintBookRow extends StatelessWidget {
  const _PrintBookRow({
    required this.template,
    required this.index,
    required this.title,
    required this.author,
    required this.time,
    required this.dates,
    required this.relativeTime,
  });
  final ReadingReportTemplate template;
  final int index;
  final String title, time;
  final String? author, dates;
  final double? relativeTime;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final ticket = template == ReadingReportTemplate.festival;
    final editorial = template == ReadingReportTemplate.editorial;
    final archive = template == ReadingReportTemplate.archive;
    return Container(
      decoration: BoxDecoration(
        border: Border(
          bottom: BorderSide(color: colors.outlineVariant, width: .5),
        ),
      ),
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!editorial && template != ReadingReportTemplate.ink) ...[
            if (ticket)
              Container(
                width: 18,
                height: 18,
                decoration: BoxDecoration(
                  color: colors.primary,
                  shape: BoxShape.circle,
                ),
                alignment: Alignment.center,
                child: Text(
                  '${index + 1}',
                  style: TextStyle(
                    color: colors.surface,
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              )
            else
              SizedBox(
                width: 20,
                child: _ReportFigure(
                  value: (index + 1).toString().padLeft(2, '0'),
                  highlight: true,
                  size: 16,
                ),
              ),
            const SizedBox(width: 7),
          ],
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                if (author?.isNotEmpty == true) _PrintCaption(author!),
                _PrintCaption(time, size: 9),
                if (dates != null) _PrintCaption(dates!, size: 9),
                if (archive && relativeTime != null) ...[
                  const SizedBox(height: 5),
                  _ReportMeter(value: relativeTime),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ReportDailyMarks extends CustomPainter {
  const _ReportDailyMarks(this.seconds, this.maximum, this.color);
  final List<int?> seconds;
  final int maximum;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    if (maximum <= 0 || seconds.isEmpty) return;
    final step = size.width / seconds.length;
    final paint = Paint()
      ..color = color
      ..strokeWidth = math.min(1.35, step * .72);
    for (final (index, value) in seconds.indexed) {
      if (value == null || value <= 0) continue;
      final x = (index + .5) * step;
      canvas.drawLine(
        Offset(x, size.height * (1 - value / maximum)),
        Offset(x, size.height),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(_ReportDailyMarks oldDelegate) =>
      oldDelegate.seconds != seconds ||
      oldDelegate.maximum != maximum ||
      oldDelegate.color != color;
}

class _ReportPerforation extends StatelessWidget {
  const _ReportPerforation();

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 11,
    child: CustomPaint(
      painter: _ReportDashes(Theme.of(context).colorScheme.outlineVariant),
      child: const SizedBox.expand(),
    ),
  );
}

class _ReportDashes extends CustomPainter {
  const _ReportDashes(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = .8;
    for (var x = 0.0; x < size.width; x += 6) {
      canvas.drawLine(
        Offset(x, 1),
        Offset(math.min(size.width, x + 2), 1),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(_ReportDashes oldDelegate) => oldDelegate.color != color;
}

class _ReportFilmEdge extends StatelessWidget {
  const _ReportFilmEdge({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => CustomPaint(
    foregroundPainter: const _ReportFilmPerforations(),
    child: child,
  );
}

class _ReportFilmPerforations extends CustomPainter {
  const _ReportFilmPerforations();

  @override
  void paint(Canvas canvas, Size size) {
    final perforation = Paint()..color = const Color(0xff323033);
    for (var y = 4.0; y < size.height - 7; y += 13) {
      for (final x in [4.0, size.width - 9]) {
        final rect = Rect.fromLTWH(x, y, 5, 6);
        canvas.drawRRect(
          RRect.fromRectAndRadius(rect, const Radius.circular(1)),
          perforation,
        );
      }
    }
  }

  @override
  bool shouldRepaint(_ReportFilmPerforations oldDelegate) => false;
}

class _ReportColophon extends StatelessWidget {
  const _ReportColophon({required this.template, required this.year});
  final ReadingReportTemplate template;
  final int year;

  @override
  Widget build(BuildContext context) {
    final closing = switch (template) {
      ReadingReportTemplate.archive => 'ARCHIVE ENDS / KEEP READING',
      ReadingReportTemplate.shelf => '下一页，继续启航。',
      ReadingReportTemplate.editorial => '下一页，留给将来的自己。',
      ReadingReportTemplate.constellation => '夜深，仍有书页。',
      ReadingReportTemplate.signal => '把读过的折成纸页，继续前行。',
      ReadingReportTemplate.score => 'THE END',
      ReadingReportTemplate.ink => '走得慢一点，也会遇见下一页。',
      ReadingReportTemplate.festival => '下一站，仍有书页。',
    };
    final end = _PrintInset(
      vertical: 18,
      child: Column(
        crossAxisAlignment: template == ReadingReportTemplate.score
            ? CrossAxisAlignment.center
            : CrossAxisAlignment.start,
        children: [
          Divider(
            height: 1,
            color: Theme.of(context).colorScheme.outlineVariant,
          ),
          const SizedBox(height: 12),
          Text(
            closing,
            style: TextStyle(
              fontWeight: FontWeight.w700,
              fontSize: template == ReadingReportTemplate.score ? 26 : 15,
            ),
          ),
          const SizedBox(height: 5),
          _PrintCaption('$readingExportBrandName · $year', tracking: true),
        ],
      ),
    );
    if (template == ReadingReportTemplate.festival) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          AspectRatio(
            aspectRatio: 2.3,
            child: _PrintPicture(
              _filmStills.first,
              alignment: const Alignment(.65, .12),
            ),
          ),
          end,
        ],
      );
    }
    if (template != ReadingReportTemplate.shelf &&
        template != ReadingReportTemplate.signal) {
      return end;
    }
    return _PrintTone(
      background: template == ReadingReportTemplate.shelf
          ? const Color(0xff15212c)
          : const Color(0xff252524),
      foreground: const Color(0xffefebe1),
      child: end,
    );
  }
}

class _ReportWaypoints extends StatelessWidget {
  const _ReportWaypoints({required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: const BoxDecoration(),
      child: Stack(
        fit: StackFit.passthrough,
        children: [
          Positioned.fill(
            child: Center(
              child: Container(width: .65, color: scheme.outlineVariant),
            ),
          ),
          Column(
            children: [
              for (final (index, child) in children.indexed)
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(child: index.isEven ? child : const SizedBox()),
                    SizedBox(
                      width: 30,
                      child: Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: ColoredBox(
                          color: scheme.surface,
                          child: Center(
                            child: _ReportFigure(
                              value: (index + 1).toString().padLeft(2, '0'),
                              size: 17,
                              highlight: true,
                            ),
                          ),
                        ),
                      ),
                    ),
                    Expanded(child: index.isOdd ? child : const SizedBox()),
                  ],
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ReportStationMonths extends StatelessWidget {
  const _ReportStationMonths({required this.months, required this.peakSeconds});
  final List<StatisticsAnalysisMonth> months;
  final int peakSeconds;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, area) {
        final columns = (area.maxWidth / 46).floor().clamp(1, 7);
        return Column(
          children: [
            for (var start = 0; start < months.length; start += columns) ...[
              Stack(
                alignment: Alignment.topCenter,
                children: [
                  Positioned(
                    top: 9,
                    left: area.maxWidth / columns / 2,
                    width:
                        area.maxWidth *
                        ((math.min(columns, months.length - start) - 1) /
                            columns),
                    child: ColoredBox(
                      color: scheme.outlineVariant,
                      child: const SizedBox(height: 1),
                    ),
                  ),
                  Row(
                    children: [
                      for (
                        var i = start;
                        i < math.min(start + columns, months.length);
                        i++
                      )
                        SizedBox(
                          width: area.maxWidth / columns,
                          child: Column(
                            children: [
                              SizedBox(
                                height: 19,
                                child: Center(
                                  child: Icon(
                                    months[i].totalSeconds == null
                                        ? CupertinoIcons.circle
                                        : CupertinoIcons.circle_fill,
                                    color: Color.lerp(
                                      scheme.outlineVariant,
                                      scheme.primary,
                                      .34 +
                                          .66 *
                                              ((months[i].totalSeconds ?? 0) /
                                                  peakSeconds),
                                    ),
                                    size: 9,
                                  ),
                                ),
                              ),
                              _PrintCaption(
                                '${months[i].date.year % 100}.${months[i].date.month.toString().padLeft(2, '0')}',
                              ),
                              _PrintCaption(
                                _printCompactTime(months[i].totalSeconds),
                                size: 8,
                              ),
                            ],
                          ),
                        ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 6),
            ],
          ],
        );
      },
    );
  }
}
