import 'package:flutter/material.dart';
import 'reading_widget_snapshot.dart';
import 'reading_widget_design.dart';
import 'reading_widget_skin.dart';
export 'reading_widget_skin.dart';

enum ReadingDesktopSize {
  small(170, 170),
  medium(360, 170),
  large(360, 360);

  const ReadingDesktopSize(this.width, this.height);
  final double width, height;
}

class ReadingDesktopCard extends StatelessWidget {
  const ReadingDesktopCard({
    super.key,
    required this.data,
    required this.size,
    this.brightness = Brightness.light,
    this.quoteText,
    this.quoteSource = '',
    this.quoteCount = 0,
  });
  final ReadingWidgetSnapshot data;
  final ReadingDesktopSize size;
  final Brightness brightness;
  final String? quoteText;
  final String quoteSource;
  final int quoteCount;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: size.width,
    height: size.height,
    child: CustomPaint(
      painter: ReadingDesktopPainter(
        data,
        brightness,
        quoteText: quoteText,
        quoteSource: quoteSource,
      ),
    ),
  );
}

class ReadingDesktopPainter extends CustomPainter {
  ReadingDesktopPainter(
    this.data,
    this.brightness, {
    this.quoteText,
    this.quoteSource = '',
    this.paintQuote = true,
  });

  final ReadingWidgetSnapshot data;
  final Brightness brightness;
  final String? quoteText;
  final String quoteSource;
  final bool paintQuote;

  @override
  void paint(Canvas canvas, Size size) {
    final dark = brightness == Brightness.dark;
    final ink = dark ? const Color(0xfff3eee4) : const Color(0xff262923);
    final paper = dark ? const Color(0xff222620) : const Color(0xfff7f5ed);
    final season = (data.localDay.month % 12) ~/ 3;
    final accent = data.skin == ReadingWidgetSkin.seasons
        ? const [Color(0xff626c98), Color(0xff54805d), Color(0xff287c83), Color(0xffab6338)][season]
        : const Color(0xff66806e);
    final brush = Paint();
    canvas.drawRRect(
      RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(22)),
      brush..color = Color.lerp(paper, accent, dark ? .16 : .08)!,
    );
    final inset = size.width < 200 ? 14.0 : 20.0;
    final width = size.width - inset * 2;
    void text(String value, double y, double font, {int lines = 1, Color? tint}) {
      final painter = TextPainter(
        text: TextSpan(
          text: value,
          style: TextStyle(color: tint ?? ink, fontSize: font, height: 1.25),
        ),
        textDirection: TextDirection.ltr,
        maxLines: lines,
        ellipsis: '…',
      )..layout(maxWidth: width);
      painter.paint(canvas, Offset(inset, y));
      painter.dispose();
    }

    void heatmap(List<int?> values, int columns, int rows) {
      const gap = 3.0;
      final cell = ((width - (columns - 1) * gap) / columns)
          .clamp(1.0, (size.height - 90 - (rows - 1) * gap) / rows);
      for (final (i, seconds) in values.indexed) {
        final opacity = seconds == null ? .08 : seconds == 0 ? .2 : (.35 + seconds / 4500).clamp(.35, 1.0);
        final x = inset + i ~/ rows * (cell + gap);
        final y = 54 + i % rows * (cell + gap);
        canvas.drawRRect(
          RRect.fromRectAndRadius(Rect.fromLTWH(x, y, cell, cell), const Radius.circular(2)),
          brush..color = accent.withValues(alpha: opacity),
        );
      }
    }

    String minutes(int? seconds) => seconds == null ? '暂无记录' : '${seconds ~/ 60} 分钟';
    text(data.design.label, 17, 13);
    switch (data.design) {
      case ReadingWidgetDesign.week:
        heatmap(data.week.map((day) => day.verifiedSeconds).toList(), 7, 1);
        text(minutes(data.weekSeconds), size.height - 42, 15);
      case ReadingWidgetDesign.calendar:
        final columns = size.width < 200 ? 12 : ReadingWidgetHeatmap.weeks;
        final values = data.heatmap.verifiedSeconds;
        heatmap(values.sublist(values.length - columns * 7), columns, 7);
        text('阅读记录', size.height - 31, 12);
      case ReadingWidgetDesign.memory:
        final completion = data.latestCompletion;
        final title = data.showBookDetails ? completion?.title : null;
        text(title ?? (completion == null ? '暂无纪念' : '阅读纪念'), 54, 22, lines: 2);
        if (completion != null) {
          text(widgetDate(completion.completedAt), size.height - 34, 12);
        }
      case ReadingWidgetDesign.quote:
        if (paintQuote) {
          text(quoteText ?? '在阅读中留下喜欢的句子', 56, 16, lines: 3);
          if (data.showBookDetails && quoteSource.isNotEmpty) text(quoteSource, size.height - 28, 11);
        }
      case ReadingWidgetDesign.classic:
      case ReadingWidgetDesign.focus:
        text(minutes(data.verifiedTodaySeconds), 51, 28);
        final goal = data.dailyGoalMinutes;
        if (goal > 0) {
          final fraction = ((data.verifiedTodaySeconds ?? 0) / (goal * 60)).clamp(0.0, 1.0);
          canvas.drawRect(Rect.fromLTWH(inset, 101, width, 4), brush..color = accent.withValues(alpha: .2));
          canvas.drawRect(Rect.fromLTWH(inset, 101, width * fraction, 4), brush..color = accent);
        }
        final title = data.showBookDetails ? data.currentBook?.title : null;
        text(title ?? '连续 ${data.qualifiedStreakDays ?? 0} 天', size.height - 33, 12);
    }
  }

  @override
  bool shouldRepaint(covariant ReadingDesktopPainter old) =>
      old.data != data || old.brightness != brightness || old.paintQuote != paintQuote ||
      old.quoteText != quoteText || old.quoteSource != quoteSource;
}
