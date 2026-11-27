import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

import 'reading_moment_content.dart';
import 'reading_keepsake_theme.dart';
import 'reading_visual_theme.dart';

/// Renders the complete artwork independently of popup size and scroll position.
/// A compact popup is only a view of this immutable event, not the export canvas.
Future<Uint8List> renderReadingMomentPresentationPng(
  BuildContext context,
  ReadingMomentPresentation presentation,
) async {
  Future<void> warm(ImageProvider provider) async {
    Object? failure;
    await precacheImage(
      provider,
      context,
      onError: (error, _) => failure = error,
    );
    if (failure != null) throw failure!;
  }

  await Future.wait([
    warm(
      AssetImage(
        readingKeepsakeArt(
          presentation.theme,
          recordedAt: presentation.moment.earnedAt,
          portrait: presentation.theme == ReadingVisualTheme.shelf,
        ),
      ),
    ),
    if (presentation.showBookDetails && presentation.coverBytes != null)
      warm(MemoryImage(presentation.coverBytes!)),
  ]);
  if (!context.mounted) throw StateError('纪念弹窗已关闭');
  return renderReadingArtworkPng(
    context,
    ReadingMomentContent(presentation: presentation),
    name: '纪念',
  );
}

/// Captures the full artwork independently of the route's viewport.
Future<Uint8List> renderReadingArtworkPng(
  BuildContext context,
  Widget artwork, {
  required String name,
}) => _withReadingArtwork(
  context,
  artwork,
  name: name,
  paint: true,
  finish: (boundary) => renderReadingMomentPng(boundary, name: name),
);

Future<Size> measureReadingArtworkPng(BuildContext context, Widget artwork) =>
    _withReadingArtwork(
      context,
      artwork,
      name: '报告',
      paint: false,
      finish: (boundary) {
        final size = boundary.size;
        final ratio = _imageScale(size);
        return Size(
          (size.width * ratio).ceilToDouble(),
          (size.height * ratio).ceilToDouble(),
        );
      },
    );

Future<T> _withReadingArtwork<T>(
  BuildContext context,
  Widget artwork, {
  required String name,
  required bool paint,
  required FutureOr<T> Function(RenderRepaintBoundary) finish,
}) async {
  const canvas = Size(386, 8192);
  final theme = Theme.of(context);
  final media = MediaQuery.of(context).copyWith(
    size: canvas,
    devicePixelRatio: 1,
    padding: EdgeInsets.zero,
    viewPadding: EdgeInsets.zero,
    viewInsets: EdgeInsets.zero,
    disableAnimations: true,
  );
  final boundary = RenderRepaintBoundary();
  // Loose height lets the boundary adopt the content's complete natural height.
  final position = RenderPositionedBox(
    alignment: Alignment.topLeft,
    child: boundary,
  );
  final renderView = RenderView(
    view: View.of(context),
    configuration: ViewConfiguration(
      logicalConstraints: BoxConstraints.tight(canvas),
      physicalConstraints: BoxConstraints.tight(canvas),
      devicePixelRatio: 1,
    ),
    child: position,
  );
  final pipeline = PipelineOwner()..rootNode = renderView;
  final focus = FocusManager();
  final owner = BuildOwner(focusManager: focus);
  renderView.prepareInitialFrame();
  final child = InheritedTheme.captureAll(
    context,
    Localizations.override(
      context: context,
      child: DefaultAssetBundle(
        bundle: DefaultAssetBundle.of(context),
        child: MediaQuery(
          data: media,
          child: Directionality(
            textDirection: Directionality.of(context),
            child: TickerMode(
              enabled: false,
              child: Material(
                color: Colors.transparent,
                child: DefaultTextStyle(
                  style: theme.textTheme.bodyMedium!,
                  child: SizedBox(width: canvas.width, child: artwork),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  final root = RenderObjectToWidgetAdapter<RenderBox>(
    container: boundary,
    child: child,
  ).attachToRenderTree(owner);
  void flush() {
    owner.buildScope(root);
    pipeline.flushLayout();
    if (paint) {
      pipeline.flushCompositingBits();
      pipeline.flushPaint();
    }
  }

  try {
    flush();
    if (paint) {
      // Cached image streams may finish attaching after the first build.
      await Future<void>.delayed(Duration.zero);
      flush();
    }
    if (boundary.size.height >= canvas.height) {
      throw FormatException('$name内容过长，无法完整生成图片');
    }
    return await finish(boundary);
  } finally {
    owner.buildScope(
      root,
      () => root.update(
        RenderObjectToWidgetAdapter<RenderBox>(container: boundary),
      ),
    );
    owner.finalizeTree();
    pipeline.rootNode = null;
    renderView.child = null;
    position.child = null;
    boundary.dispose();
    position.dispose();
    renderView.dispose();
    pipeline.dispose();
    focus.dispose();
  }
}

/// Capture the pure event content, never controls, feedback or celebration.
/// The immutable ReadingMomentPresentation is its only source of facts.
Future<Uint8List> renderReadingMomentPng(
  RenderRepaintBoundary content, {
  String name = '纪念',
}) async {
  if (content.debugNeedsPaint) await WidgetsBinding.instance.endOfFrame;
  final image = await content.toImage(pixelRatio: _imageScale(content.size));
  try {
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    if (bytes == null) throw FormatException('$name图片未能生成');
    final png = bytes.buffer.asUint8List();
    if (png.length > 12 * 1024 * 1024) {
      throw FormatException('$name内容过长，图片超过保存上限');
    }
    return png;
  } finally {
    image.dispose();
  }
}

double _imageScale(Size size) => math.min(
  1080 / size.width,
  math.min(
    8192 / size.height,
    math.sqrt(12000000 / (size.width * size.height)),
  ),
);
