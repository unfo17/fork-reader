import 'dart:ui' as ui;
import 'dart:async';
import 'dart:typed_data';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'reader_preferences.dart';
import 'reader_sheet_motion.dart';
import 'reader_turn_motion.dart';

/// A retained image of Readium's rendered page, never re-typeset Flutter text.
class ReadiumPageFrame {
  ReadiumPageFrame(this.image, this.location, this.bands, this.paper);
  final ui.Image image;
  final String location;
  final List<Rect> bands;
  final Color paper;
  ui.ImageShader? _shader;
  ui.ImageShader get shader => _shader ??= ui.ImageShader(
    image,
    TileMode.clamp,
    TileMode.clamp,
    Float64List.fromList([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]),
  );
  void dispose() {
    _shader?.dispose();
    image.dispose();
  }
}

class ReadiumPageTurnController {
  _ReadiumPageSurfaceState? _owner;
  Completer<bool>? _release;
  double _progress = 0, _sign = 1, _width = 1, _lastDistance = 0, _velocity = 0;
  final _watch = Stopwatch();
  int _lastMicros = 0;
  double _touchY = .5;
  void beginDrag(double dx, double width, double touchY) {
    _touchY = touchY;
    _release = Completer<bool>();
    _progress = 0;
    _width = width;
    _sign = dx < 0 ? -1 : 1;
    _lastDistance = dx * _sign;
    _velocity = 0;
    _lastMicros = 0;
    _watch
      ..reset()
      ..start();
    updateDrag(dx);
  }

  void updateDrag(double dx, {double? touchY}) {
    if (touchY != null) _touchY = touchY.clamp(0.0, 1.0);
    if (_release == null || _release!.isCompleted) return;
    final distance = dx * _sign, now = _watch.elapsedMicroseconds;
    if (now - _lastMicros > 1000) {
      _velocity = (distance - _lastDistance) * 1000000 / (now - _lastMicros);
      _lastDistance = distance;
      _lastMicros = now;
    }
    _progress = (distance / _width).clamp(0.0, 1.0);
    _owner?._scrub();
  }

  void endDrag({bool cancel = false}) {
    final release = _release;
    if (release == null || release.isCompleted) return;
    _watch.stop();
    release.complete(
      !cancel &&
          (_progress >= .25 || (_progress > .06 && _velocity > 700)) &&
          _velocity > -600,
    );
  }

  Future<void> drag(int direction) async {
    try {
      await _owner?._turn(direction, true, gesture: _release);
    } finally {
      _release = null;
    }
  }

  Future<void> turn(int direction, {required bool animated}) async {
    await _owner?._turn(direction, animated);
  }
}

/// Reuses the original page effects over native page images. The live navigator
/// stays mounted underneath, owning layout, links, selection and saved locators.
class ReadiumPageSurface extends StatefulWidget {
  const ReadiumPageSurface({
    super.key,
    required this.controller,
    required this.preferences,
    required this.paper,
    required this.capture,
    required this.step,
    required this.child,
    required this.frameKey,
  });
  final ReadiumPageTurnController controller;
  final ReaderPreferences preferences;
  final Color paper;
  final Future<ReadiumPageFrame?> Function() capture;
  final Future<bool> Function(int direction, bool animated) step;
  final String frameKey;
  final Widget child;
  @override
  State<ReadiumPageSurface> createState() => _ReadiumPageSurfaceState();
}

class _ReadiumPageSurfaceState extends State<ReadiumPageSurface>
    with SingleTickerProviderStateMixin {
  late final _clock = AnimationController(vsync: this);
  ReadiumPageFrame? _from, _to;
  ReaderPageEffect _effect = ReaderPageEffect.none;
  double _direction = 1;
  bool _busy = false;
  bool _liveDestination = false;
  bool _dragPrepared = false;
  ReadiumPageFrame? _cached;
  Timer? _warmTimer;
  bool _warming = false;
  void _warmLater() {
    _warmTimer?.cancel();
    _warmTimer = Timer(const Duration(milliseconds: 180), () async {
      if (!mounted ||
          _busy ||
          _warming ||
          _cached != null ||
          !widget.preferences.mode.paginated ||
          widget.preferences.pageEffect == ReaderPageEffect.none)
        return;
      _warming = true;
      final key = widget.frameKey;
      try {
        final frame = await widget.capture();
        if (mounted && !_busy && key == widget.frameKey) {
          _cached = frame;
        } else {
          frame?.dispose();
        }
      } catch (_) {
        /* A warm image is optional. */
      } finally {
        _warming = false;
        if (mounted && key != widget.frameKey) _warmLater();
      }
    });
  }

  void _scrub() {
    if (_dragPrepared && !widget.controller._release!.isCompleted)
      _clock.value = widget.controller._progress;
  }

  @override
  void initState() {
    super.initState();
    widget.controller._owner = this;
    _warmLater();
  }

  @override
  void didUpdateWidget(covariant ReadiumPageSurface oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.frameKey != widget.frameKey) {
      _cached?.dispose();
      _cached = null;
      _warmLater();
    }
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller._owner = null;
      widget.controller._owner = this;
    }
  }

  Size? _viewport;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final viewport = MediaQuery.sizeOf(context);
    if (_viewport != viewport) {
      _viewport = viewport;
      widget.controller.endDrag(cancel: true);
      _cached?.dispose();
      _cached = null;
      _warmLater();
    }
  }

  Future<void> _turn(
    int direction,
    bool animated, {
    Completer<bool>? gesture,
  }) async {
    if (_busy || !mounted) return;
    final prefs = widget.preferences;
    if (!animated ||
        !prefs.mode.paginated ||
        prefs.pageEffect == ReaderPageEffect.none ||
        MediaQuery.disableAnimationsOf(context)) {
      if (gesture == null || await gesture.future)
        await widget.step(direction, false);
      return;
    }
    _busy = true;
    ReadiumPageFrame? from, to;
    var moved = false, committed = false;
    try {
      from = _cached;
      _cached = null;
      from ??= await widget.capture();
      if (!mounted) return;
      if (from == null) {
        if (gesture == null || await gesture.future)
          await widget.step(direction, false);
        return;
      }
      setState(() {
        _from = from;
        _to = null;
        _liveDestination = false;
        _effect = prefs.pageEffect;
        _direction = direction * (prefs.mode == ReaderMode.vertical ? -1 : 1);
        _clock.value = 0;
      });
      _dragPrepared = gesture != null;
      if (gesture != null) _clock.value = widget.controller._progress;
      // Cover the native view before it moves. Native slide animation is disabled
      // so adjacent page edges cannot bleed through this full-page transition.
      await WidgetsBinding.instance.endOfFrame;
      moved = await widget.step(direction, false);
      if (!moved) {
        _dragPrepared = false;
        await _clock
            .animateTo(0, duration: const Duration(milliseconds: 140))
            .orCancel;
        return;
      }
      if (prefs.pageEffect == ReaderPageEffect.paper) {
        // The live navigator already contains the destination. Only the turning
        // old sheet needs pixels: do not capture/decode the destination again.
        if (!mounted) return;
        setState(() => _liveDestination = true);
      } else {
        to = await widget.capture();
        if (!mounted || to == null || to.location == from.location) return;
        setState(() => _to = to);
      }
      var finish = true;
      if (gesture != null) {
        _dragPrepared = true;
        _clock.value = widget.controller._progress;
        finish = await gesture.future;
        _dragPrepared = false;
      }
      final target = finish ? 1.0 : 0.0;
      await _clock
          .animateTo(
            target,
            duration: Duration(
              microseconds:
                  (prefs.pageEffect.duration.inMicroseconds *
                          prefs.pageSpeed.durationScale *
                          (target - _clock.value).abs())
                      .round(),
            ),
            curve: Curves.easeOutCubic,
          )
          .orCancel;
      committed = finish;
      if (!finish) {
        await widget.step(-direction, false);
        moved = false;
      }
    } on TickerCanceled {
      // The route was closed during a turn.
    } finally {
      _dragPrepared = false;
      if (gesture != null && moved && !committed)
        await widget.step(-direction, false);
      if (mounted) {
        setState(() {
          _from = null;
          _to = null;
          _liveDestination = false;
        });
        await WidgetsBinding.instance.endOfFrame;
      }
      from?.dispose();
      to?.dispose();
      _busy = false;
      if (mounted) _warmLater();
    }
  }

  Widget _leaf(ReadiumPageFrame frame, bool incoming) {
    final image = RawImage(image: frame.image, fit: BoxFit.fill);
    if (_effect == ReaderPageEffect.staggered) {
      return CustomPaint(
        painter: _ReadiumParagraphTurn(
          frame,
          _clock,
          _direction,
          incoming,
          widget.preferences.mode == ReaderMode.vertical,
        ),
      );
    }
    if (_effect == ReaderPageEffect.slide) {
      return SlideTransition(
        position: _clock.drive(
          Tween<Offset>(
            begin: Offset(incoming ? _direction : 0, 0),
            end: Offset(incoming ? 0 : -_direction, 0),
          ),
        ),
        child: image,
      );
    }
    return ReaderSheetMotion(
      animation: _clock,
      effect: _effect,
      direction: _direction,
      incoming: incoming,
      front: incoming == (_direction > 0),
      active: true,
      paper: frame.paper,
      child: image,
    );
  }

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      widget.child,
      if (_from case final frame?)
        Positioned.fill(
          child: IgnorePointer(
            child: ExcludeSemantics(
              child: ClipRect(
                child: ColoredBox(
                  color: _liveDestination ? Colors.transparent : frame.paper,
                  child: Stack(
                    fit: StackFit.expand,
                    children:
                        (_liveDestination ||
                            (_dragPrepared &&
                                _effect == ReaderPageEffect.paper))
                        ? [
                            CustomPaint(
                              painter: _ReadiumCurl(
                                frame,
                                _clock,
                                _direction,
                                () => widget.controller._touchY,
                              ),
                            ),
                          ]
                        : _to == null
                        ? [
                            _dragPrepared
                                ? _leaf(frame, false)
                                : RawImage(
                                    image: frame.image,
                                    fit: BoxFit.fill,
                                  ),
                          ]
                        : _direction < 0 &&
                              (_effect == ReaderPageEffect.cover ||
                                  _effect == ReaderPageEffect.fade)
                        ? [_leaf(_to!, true), _leaf(frame, false)]
                        : [_leaf(frame, false), _leaf(_to!, true)],
                  ),
                ),
              ),
            ),
          ),
        ),
    ],
  );

  @override
  void dispose() {
    if (identical(widget.controller._owner, this))
      widget.controller._owner = null;
    _warmTimer?.cancel();
    _cached?.dispose();
    widget.controller.endDrag(cancel: true);
    _clock.dispose();
    super.dispose();
  }
}

class _ReadiumParagraphTurn extends CustomPainter {
  _ReadiumParagraphTurn(
    this.frame,
    this.clock,
    this.direction,
    this.incoming,
    this.vertical,
  ) : super(repaint: clock);
  final ReadiumPageFrame frame;
  final Animation<double> clock;
  final double direction;
  final bool incoming, vertical;
  @override
  void paint(Canvas canvas, Size size) {
    final scope = ReaderTurnScope(
      animation: clock,
      direction: direction,
      outgoing: !incoming,
      relay: true,
      vertical: vertical,
      leafSize: size,
      child: const SizedBox(),
    );
    final bands = frame.bands.isEmpty
        ? [const Rect.fromLTRB(0, 0, 1, 1)]
        : frame.bands;
    final source = Rect.fromLTWH(
      0,
      0,
      frame.image.width.toDouble(),
      frame.image.height.toDouble(),
    );
    for (var i = 0; i < bands.length; i++) {
      final b = bands[i];
      final bounds = Rect.fromLTRB(
        b.left * size.width,
        b.top * size.height,
        b.right * size.width,
        b.bottom * size.height,
      );
      final offset = scope.displacementFor(i, bands.length, bounds: bounds);
      canvas.save();
      canvas.translate(offset.dx, offset.dy);
      canvas.clipRect(bounds);
      canvas.drawImageRect(frame.image, source, Offset.zero & size, Paint());
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _ReadiumParagraphTurn old) =>
      old.frame != frame ||
      old.incoming != incoming ||
      old.direction != direction ||
      old.vertical != vertical;
}

/// Textured fold around a tilted cylindrical axis. One image shader and three
/// mesh draws replace per-strip image draws; the flat page stays pixel-stable.
class _ReadiumCurl extends CustomPainter {
  _ReadiumCurl(this.from, this.clock, this.direction, this.touchY)
    : super(repaint: clock);
  final ReadiumPageFrame from;
  final Animation<double> clock;
  final double direction;
  final double Function() touchY;
  static const columns = 48, rows = 24, count = (columns + 1) * (rows + 1);
  final _positions = Float32List(count * 2),
      _texture = Float32List(count * 2),
      _angles = Float32List(count);
  final _frontColors = Int32List(count),
      _backColors = Int32List(count),
      _ghostColors = Int32List(count);
  final _frontIndices = Uint16List(columns * rows * 6),
      _backIndices = Uint16List(columns * rows * 6);

  @override
  void paint(Canvas c, Size size) {
    final t = clock.value.clamp(0.0, 1.0);
    if (t >= 1) return;
    if (t <= 0) {
      c.drawImageRect(
        from.image,
        Rect.fromLTWH(
          0,
          0,
          from.image.width.toDouble(),
          from.image.height.toDouble(),
        ),
        Offset.zero & size,
        Paint(),
      );
      return;
    }
    final w = size.width, h = size.height, backwards = direction < 0;
    final grip = touchY().clamp(.05, .95), wave = math.sin(math.pi * t);
    final radius = w * (.085 + .045 * (grip - .5).abs()) * wave;
    final edge = w * (1 - t), tilt = (.5 - grip) * .65 * w / h * wave;
    final nx = 1 / math.sqrt(1 + tilt * tilt), ny = -tilt * nx;
    final positions = _positions, texture = _texture, angles = _angles;
    final frontColors = _frontColors,
        backColors = _backColors,
        ghostColors = _ghostColors;
    for (var j = 0; j <= rows; j++)
      for (var i = 0; i <= columns; i++) {
        final k = j * (columns + 1) + i, x = w * i / columns, y = h * j / rows;
        final crease = edge + tilt * (y - grip * h), d = (x - crease) * nx;
        double xx = x, yy = y, z = 0, angle = 0;
        if (d > 0) {
          angle = math.min(math.pi, d / radius);
          final along = d > math.pi * radius
              ? -(d - math.pi * radius)
              : radius * math.sin(angle);
          z = radius * (1 - math.cos(angle));
          xx += nx * (along - d);
          yy += ny * (along - d);
          final perspective = 4 * w / (4 * w + z);
          xx = edge + (xx - edge) * perspective;
          yy = grip * h + (yy - grip * h) * perspective;
        }
        positions[k * 2] = xx;
        positions[k * 2 + 1] = yy;
        texture[k * 2] = (backwards ? w - x : x) / w * from.image.width;
        texture[k * 2 + 1] = y / h * from.image.height;
        angles[k] = angle;
        final shade = (255 * (1 - .22 * math.sin(angle))).round();
        frontColors[k] = 0xff000000 | (shade << 16) | (shade << 8) | shade;
        final paper = from.paper;
        backColors[k] = Color.fromARGB(
          255,
          (paper.r * shade).round(),
          (paper.g * shade).round(),
          (paper.b * shade).round(),
        ).toARGB32();
        ghostColors[k] = 0x16ffffff;
      }
    var frontCount = 0, backCount = 0;
    for (var j = 0; j < rows; j++)
      for (var i = 0; i < columns; i++) {
        final a = j * (columns + 1) + i,
            b = a + 1,
            d = a + columns + 1,
            e = d + 1;
        final rear =
            (angles[a] + angles[b] + angles[d] + angles[e]) / 4 > math.pi / 2;
        final out = rear ? _backIndices : _frontIndices;
        var at = rear ? backCount : frontCount;
        out[at++] = a;
        out[at++] = b;
        out[at++] = d;
        out[at++] = b;
        out[at++] = e;
        out[at++] = d;
        if (rear)
          backCount = at;
        else
          frontCount = at;
      }
    c.save();
    c.clipRect(Offset.zero & size);
    if (backwards) {
      c.translate(w, 0);
      c.scale(-1, 1);
    }
    final shadow = Path()
      ..moveTo(edge + tilt * (-grip * h), 0)
      ..lineTo(edge + tilt * (h - grip * h), h);
    c.drawPath(
      shadow,
      Paint()
        ..color = const Color(0x48000000)
        ..style = PaintingStyle.stroke
        ..strokeWidth = radius * .7
        ..maskFilter = MaskFilter.blur(BlurStyle.normal, radius * .24),
    );
    void mesh(Uint16List indices, int count, Int32List colors, bool textured) {
      if (count == 0) return;
      final vertices = ui.Vertices.raw(
        VertexMode.triangles,
        positions,
        textureCoordinates: textured ? texture : null,
        colors: colors,
        indices: Uint16List.sublistView(indices, 0, count),
      );
      c.drawVertices(
        vertices,
        BlendMode.modulate,
        Paint()
          ..color = Colors.white
          ..shader = textured ? from.shader : null
          ..filterQuality = FilterQuality.low,
      );
    }

    mesh(_frontIndices, frontCount, frontColors, true);
    mesh(_backIndices, backCount, backColors, false);
    mesh(_backIndices, backCount, ghostColors, true);
    c.restore();
  }

  @override
  bool shouldRepaint(covariant _ReadiumCurl old) =>
      old.from != from || old.direction != direction;
}
