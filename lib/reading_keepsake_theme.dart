import 'package:flutter/painting.dart';
import 'package:flutter/services.dart';
import 'dart:typed_data';
import 'local_platform.dart';

import 'reading_export_theme.dart';
import 'reading_visual_theme.dart';
import 'reading_visual_theme_picker.dart';

/// Export templates and previews reuse the artwork already bundled for Android
/// widgets. No duplicate high-resolution illustration pack is needed.
class ReadingArtAssetBundle extends CachingAssetBundle {
  ReadingArtAssetBundle({AssetBundle? base}) : _base = base ?? rootBundle;
  final AssetBundle _base;

  String? _resource(String key) {
    const templates = {
      'char_cover': 'archive',
      'scene_mist_harbor': 'shelf',
      'scene_mist_harbor_portrait': 'shelf',
      'char_night_desk': 'constellation',
      'char_papercut': 'signal',
      'char_pv_open': 'score',
      'scene_mountain_dawn': 'ink',
      'scene_mountain_dawn_portrait': 'ink',
      'char_pv_train': 'festival',
    };
    if (key.startsWith('assets/reading_previews/')) {
      for (final theme in ReadingVisualTheme.values) {
        if (key == theme.artAsset)
          return 'reading_widget_art_${theme.name}_117';
      }
    }
    if (!key.startsWith('assets/report/') || !key.endsWith('.webp'))
      return null;
    final name = key.substring('assets/report/'.length, key.length - 5);
    for (final season in ['spring', 'summer', 'autumn', 'winter']) {
      if (name == 'char_season_$season') return 'widget_season_$season';
    }
    final theme = templates[name];
    return theme == null ? null : 'reading_widget_art_${theme}_117';
  }

  @override
  Future<ByteData> load(String key) async {
    final resource = _resource(key);
    if (resource == null || !LocalPlatform.android) return _base.load(key);
    final bytes = await LocalPlatform.channel.invokeMethod<Uint8List>(
      'loadReadingArtwork',
      {'resource': resource},
    );
    if (bytes == null || bytes.isEmpty) throw StateError('阅读插画未能加载');
    return ByteData.sublistView(bytes);
  }
}

/// Selects artwork only. Callers keep the actual recorded date separate.
String readingKeepsakeArt(
  ReadingVisualTheme theme, {
  DateTime? recordedAt,
  DateTime? artAt,
  bool portrait = false,
}) => readingExportArt(theme, at: recordedAt ?? artAt, portrait: portrait);

ReadingVisualThemePresentation readingKeepsakeThemePresentation(
  ReadingVisualTheme theme, {
  DateTime? recordedAt,
  DateTime? artAt,
}) {
  final style = readingExportTheme(theme);
  return ReadingVisualThemePresentation(
    label: style.label,
    description: style.description,
    artAsset: readingKeepsakeArt(theme, recordedAt: recordedAt, artAt: artAt),
    background: style.background,
    foreground: style.foreground,
    secondary: style.secondary,
    prefersSerif: style.prefersSerif,
    alignment: theme == ReadingVisualTheme.editorial
        ? Alignment.centerRight
        : style.previewAlignment,
  );
}
