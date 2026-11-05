import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'reading_widget_snapshot.dart';
import 'reading_widget_reference.dart';

Future<void> warmReadingDesktopImages(ReadingWidgetSnapshot value) async {}
Future<Uint8List> renderReadingDesktopCard(
  ReadingWidgetSnapshot data,
  ReadingDesktopSize size, {
  Brightness brightness = Brightness.light,
  bool paintQuote = true,
}) async {
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder)..scale(2);
  ReadingDesktopPainter(
    data,
    brightness,
  ).paint(canvas, Size(size.width, size.height));
  final picture = recorder.endRecording();
  final image = await picture.toImage(
    (size.width * 2).round(),
    (size.height * 2).round(),
  );
  try {
    return (await image.toByteData(
      format: ui.ImageByteFormat.png,
    ))!.buffer.asUint8List();
  } finally {
    image.dispose();
    picture.dispose();
  }
}

Future<Map<String, Uint8List>> renderReadingDesktopCards(
  ReadingWidgetSnapshot data,
) async {
  final images = <String, Uint8List>{};
  for (final brightness in Brightness.values) {
    for (final size in ReadingDesktopSize.values) {
      images['${size.name}_${brightness.name}'] =
          await renderReadingDesktopCard(data, size, brightness: brightness);
    }
  }
  return images;
}
