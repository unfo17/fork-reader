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
    final season = (data.localDay.month % 12) ~/ 3;
    const seasons = [Color(0xff4f8985), Color(0xffaf7d8b), Color(0xff5786a2), Color(0xffb18842)];
    final ink = dark ? const Color(0xfff6f1e6) : const Color(0xff272c29);
    final paper = switch ((data.skin, dark)) {
      (_, true) when data.skin == ReadingWidgetSkin.film => const Color(0xff10100f),
      (_, true) => const Color(0xff101419),
      (ReadingWidgetSkin.paper, false) => const Color(0xffecece5),
      _ => const Color(0xfff4f0e6),
    };
    final accent = switch (data.skin) {
      ReadingWidgetSkin.seasons => seasons[season],
      ReadingWidgetSkin.night || ReadingWidgetSkin.film => const Color(0xffdcb65e),
      ReadingWidgetSkin.paper => const Color(0xff606d5d),
      _ => const Color(0xff4f8985),
    };
    final brush = Paint();
    final surface = RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(22));
    canvas.save();
    canvas.clipRRect(surface);
    canvas.drawColor(paper, BlendMode.srcOver);
    switch (data.skin) {
      case ReadingWidgetSkin.seasons:
        for (final (i, tint) in seasons.indexed) {
          canvas.drawRect(Rect.fromLTWH(size.width * i / 4, size.height - 5, size.width / 4, 5),
              brush..color = tint);
        }
      case ReadingWidgetSkin.archive:
        canvas.drawRect(Rect.fromLTWH(0, 0, 4, size.height), brush..color = accent);
      case ReadingWidgetSkin.night:
        final point = Offset(size.width - 25, 25);
        canvas.drawCircle(point, 8, brush..color = accent);
        canvas.drawCircle(point + const Offset(4, -4), 8, brush..color = paper);
      case ReadingWidgetSkin.film:
        for (var x = 12.0; x < size.width - 8; x += 14) {
          canvas.drawRect(Rect.fromLTWH(x, 3, 6, 4), brush..color = const Color(0xffd6d2c7));
          canvas.drawRect(Rect.fromLTWH(x, size.height - 7, 6, 4), brush);
        }
      case ReadingWidgetSkin.paper:
        canvas.drawRect(Rect.fromLTWH(size.width - 37, 0, 24, 11),
            brush..color = Color.lerp(paper, accent, .3)!);
      case ReadingWidgetSkin.original:
        break;
    }
    canvas.restore();
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
