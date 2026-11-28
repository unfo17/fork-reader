import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_ui.dart';
import 'glass_palette.dart';
import 'library_state.dart';
import 'reader_preferences.dart';
import 'app_palette.dart';

ThemeData readerTheme(
  ThemeData app,
  ReaderPreferences preferences, {
  String? fontFamily,
  bool followSystemPalette = false,
}) {
  final appDark = app.brightness == Brightness.dark;
  final systemPaper =
      followSystemPalette && preferences.paper == ReaderPaper.system;
  final paper = systemPaper
      ? app.colorScheme.surface
      : preferences.background(appDark);
  final brightness = app.brightness;
  final dark = brightness == Brightness.dark;
  final base = novelTheme(brightness, fontFamily: fontFamily);
  // Dim only the article's ink. Sliders, buttons and text selection retain
  // their readable control contrast while a user previews night text levels.
  final ink = systemPaper
      ? app.colorScheme.onSurface
      : preferences.controlForeground(appDark);
  final selectionAccent = systemPaper ? app.colorScheme.primary : ink;
  final selectionColor = selectionAccent.withValues(
    alpha: systemPaper ? (dark ? .38 : .27) : (dark ? .20 : .18),
  );
  Color raised(double amount) => Color.lerp(paper, ink, amount)!;
  final scheme = base.colorScheme.copyWith(
    surface: paper,
    onSurface: ink,
    onSurfaceVariant: raised(.64),
    primary: ink,
    onPrimary: paper,
    surfaceDim: paper,
    surfaceBright: raised(.12),
    surfaceContainerLowest: paper,
    surfaceContainerLow: raised(.025),
    surfaceContainer: raised(.05),
    surfaceContainerHigh: raised(.08),
    surfaceContainerHighest: raised(.12),
    surfaceTint: Colors.transparent,
    outline: raised(.38),
    outlineVariant: raised(.18),
    inverseSurface: ink,
    onInverseSurface: paper,
  );
  return base.copyWith(
    colorScheme: scheme,
    scaffoldBackgroundColor: paper,
    iconTheme: base.iconTheme.copyWith(color: ink),
    disabledColor: raised(.35),
    dividerColor: scheme.outlineVariant,
    textTheme: base.textTheme.apply(
      fontFamily: fontFamily ?? 'Roboto',
      bodyColor: ink,
      displayColor: ink,
    ),
    inputDecorationTheme: base.inputDecorationTheme.copyWith(
      fillColor: scheme.surfaceContainerLow,
    ),
    textSelectionTheme: TextSelectionThemeData(
      cursorColor: selectionAccent,
      selectionColor: selectionColor,
      selectionHandleColor: selectionAccent,
    ),
    cupertinoOverrideTheme: CupertinoThemeData(
      brightness: brightness,
      primaryColor: selectionAccent,
      primaryContrastingColor: paper,
      scaffoldBackgroundColor: paper,
    ),
    extensions: [
      ...base.extensions.values.where(
        (extension) =>
            extension is! GlassPalette && extension is! AppTonalColors,
      ),
      _ReaderInk(
        dark ? Color.lerp(paper, ink, preferences.nightTextBrightness)! : ink,
      ),
      GlassPalette(
        tint: dark ? raised(.10) : Color.lerp(paper, Colors.white, .24)!,
        thumb: Color.lerp(paper, Colors.white, dark ? .90 : .62)!,
      ),
    ],
  );
}

bool readerFollowsSystemPalette(
  LibraryState state,
  ReaderPreferences preferences,
) =>
    preferences.paper == ReaderPaper.system &&
    state.appPalette.source == AppPaletteSource.system &&
    state.systemTonalColors != null;

/// The same live palette wraps the article, chrome and entire popup surface.
/// Typography-only notifications reuse ThemeData and do not repaint chrome.
class ReaderAppearance extends StatefulWidget {
  const ReaderAppearance({
    super.key,
    required this.state,
    required this.child,
    this.bookId,
  });
  final LibraryState state;
  final Widget child;
  final int? bookId;

  /// Continue a reader-owned route without changing ordinary app entry points.
  /// Reuse the live book palette rather than capturing one ThemeData snapshot.
  static Widget inherit(BuildContext context, {required Widget child}) {
    final owner = context.findAncestorWidgetOfExactType<ReaderAppearance>();
    return owner == null
        ? child
        : ReaderAppearance(
            state: owner.state,
            bookId: owner.bookId,
            child: child,
          );
  }

  @override
  State<ReaderAppearance> createState() => _ReaderAppearanceState();
}

class _ReaderAppearanceState extends State<ReaderAppearance> {
  Object? _key;
  ThemeData? _theme;
  String? _family;

  ThemeData _resolve(BuildContext context) {
    final state = widget.state;
    final brightness = readerAppBrightness(context, state);
    final preferences = state.readerPreferencesFor(widget.bookId);
    final family = state.readerFontFamilyFor(widget.bookId);
    final systemColors = readerFollowsSystemPalette(state, preferences)
        ? state.systemTonalColors
        : null;
    final key = (
      brightness,
      preferences.paper,
      preferences.nightTextBrightness,
      family,
      systemColors?.signature,
    );
    if (_key != key) {
      _key = key;
      _theme = readerTheme(
        novelTheme(
          brightness,
          palette: state.appPalette,
          systemColors: systemColors,
        ),
        preferences,
        fontFamily: family,
        followSystemPalette: systemColors != null,
      );
    }
    return _theme!;
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([
      widget.state.readerSettingsChanges,
      widget.state.appearanceChanges,
    ]),
    child: widget.child,
    builder: (context, child) {
      final previousFamily = _family;
      final theme = _resolve(context);
      _family = widget.state.readerFontFamilyFor(widget.bookId);
      final animate =
          previousFamily == _family &&
          !widget.state.reduceMotion &&
          !MediaQuery.disableAnimationsOf(context);
      return AnnotatedRegion<SystemUiOverlayStyle>(
        value: novelSystemUiStyle(theme.brightness),
        child: _ReaderThemeTarget(
          theme: theme,
          child: AnimatedTheme(
            data: theme,
            duration: animate
                ? const Duration(milliseconds: 220)
                : Duration.zero,
            curve: Curves.easeInOutCubic,
            child: Builder(
              builder: (context) {
                final current = Theme.of(context);
                return DefaultSelectionStyle.merge(
                  selectionColor: current.textSelectionTheme.selectionColor,
                  cursorColor: current.textSelectionTheme.cursorColor,
                  child: DefaultTextStyle(
                    style: current.textTheme.bodyMedium!,
                    child: IconTheme(data: current.iconTheme, child: child!),
                  ),
                );
              },
            ),
          ),
        ),
      );
    },
  );
}

Brightness readerAppBrightness(BuildContext context, LibraryState state) =>
    switch (state.theme) {
      ThemeMode.light => Brightness.light,
      ThemeMode.dark => Brightness.dark,
      ThemeMode.system => MediaQuery.platformBrightnessOf(context),
    };

/// Platform views consume the final colors once while Flutter chrome blends.
class _ReaderThemeTarget extends InheritedWidget {
  const _ReaderThemeTarget({required this.theme, required super.child});
  final ThemeData theme;
  @override
  bool updateShouldNotify(_ReaderThemeTarget oldWidget) =>
      theme != oldWidget.theme;
}

ThemeData readerTargetTheme(BuildContext context) =>
    context.dependOnInheritedWidgetOfExactType<_ReaderThemeTarget>()?.theme ??
    Theme.of(context);

class _ReaderInk extends ThemeExtension<_ReaderInk> {
  const _ReaderInk(this.color);
  final Color color;
  @override
  _ReaderInk copyWith({Color? color}) => _ReaderInk(color ?? this.color);
  @override
  _ReaderInk lerp(_ReaderInk? other, double t) =>
      other == null ? this : _ReaderInk(Color.lerp(color, other.color, t)!);
}

Color readerArticleInk(BuildContext context) {
  final theme = Theme.of(context);
  return theme.extension<_ReaderInk>()?.color ?? theme.colorScheme.onSurface;
}
