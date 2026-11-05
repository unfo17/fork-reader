import 'package:flutter/material.dart';
import 'reading_widget_snapshot.dart';
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
  Widget build(BuildContext context) => AspectRatio(
    aspectRatio: size.width / size.height,
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
  });
  final ReadingWidgetSnapshot data;
  final Brightness brightness;
  final String? quoteText;
  final String quoteSource;
  @override
  void paint(Canvas canvas, Size size) {
    final dark = brightness == Brightness.dark;
    final color = dark ? const Color(0xffe8e0d5) : const Color(0xff332e29);
    canvas.drawRRect(
      RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(22)),
      Paint()
        ..color = (dark ? const Color(0xff242320) : const Color(0xfff6f0e5)),
    );
    void text(String text, double y, double fontSize) {
      final painter = TextPainter(
        text: TextSpan(
          text: text,
          style: TextStyle(color: color, fontSize: fontSize, height: 1.35),
        ),
        textDirection: TextDirection.ltr,
        maxLines: 3,
        ellipsis: '…',
      )..layout(maxWidth: size.width - 36);
      painter.paint(canvas, Offset(18, y));
    }

    text('阅读 · ${data.localDay.month}/${data.localDay.day}', 18, 14);
    text(
      quoteText ?? '今日 ${(data.verifiedTodaySeconds ?? 0) ~/ 60} 分钟',
      size.height * .32,
      20,
    );
    text(
      quoteText != null ? quoteSource : '连续 ${data.qualifiedStreakDays ?? 0} 天',
      size.height - 39,
      12,
    );
  }

  @override
  bool shouldRepaint(covariant ReadingDesktopPainter old) =>
      old.data != data ||
      old.brightness != brightness ||
      old.quoteText != quoteText;
}
