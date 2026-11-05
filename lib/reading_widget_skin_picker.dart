import 'package:flutter/material.dart';
import 'reading_widget_skin.dart';
import 'reading_widget_reference.dart';
import 'reading_widget_snapshot.dart';

class ReadingWidgetSkinPicker extends StatelessWidget {
  const ReadingWidgetSkinPicker({
    super.key,
    required this.value,
    required this.onChanged,
    required this.previewBuilder,
    required this.size,
    required this.snapshot,
  });
  final ReadingWidgetSkin value;
  final ValueChanged<ReadingWidgetSkin>? onChanged;
  final Widget Function(ReadingWidgetSkin) previewBuilder;
  final ReadingDesktopSize size;
  final ReadingWidgetSnapshot snapshot;
  @override
  Widget build(BuildContext context) => Column(
    children: [
      previewBuilder(value),
      const SizedBox(height: 12),
      SegmentedButton<ReadingWidgetSkin>(
        segments: [
          for (final skin in ReadingWidgetSkin.values)
            ButtonSegment(value: skin, label: Text(skin.label)),
        ],
        selected: {value},
        onSelectionChanged: onChanged == null ? null : (s) => onChanged!(s.single),
      ),
    ],
  );
}
