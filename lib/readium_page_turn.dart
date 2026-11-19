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
  double _touchY = .5, _startY = .5;
  final _shape = ValueNotifier<int>(0);
  void beginDrag(double dx, double width, double touchY) {
    _touchY = _startY = touchY;
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
    final previousY = _touchY;
    if (touchY != null) _touchY = touchY.clamp(0.0, 1.0);
    if (_release == null || _release!.isCompleted) return;
    final distance = dx * _sign, now = _watch.elapsedMicroseconds;
    if (now - _lastMicros > 1000) {
      _velocity = (distance - _lastDistance) * 1000000 / (now - _lastMicros);
      _lastDistance = distance;
      _lastMicros = now;
    }
    _progress = (distance / _width).clamp(0.0, 1.0);
    if (previousY != _touchY) _shape.value++;
    _owner?._scrub();
  }

  void endDrag({bool cancel = false}) {
    final release = _release;
    if (release == null || release.isCompleted) return;
    _watch.stop();
    final commit =
        !cancel &&
        (_progress >= .25 || (_progress > .06 && _velocity > 700)) &&
        _velocity > -600;
    release.complete(commit);
    _owner?._finishDrag(commit);
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

  void dispose() {
    endDrag(cancel: true);
    _shape.dispose();
  }

  Future<void> navigate(
    Future<void> Function() action, {
    Future<ReadiumPageFrame?> Function()? capture,
  }) async {
    final owner = _owner;
    if (owner == null) return action();
    await owner._navigate(action, capture: capture);
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
    this.canTurn,
  });
  final ReadiumPageTurnController controller;
  final ReaderPreferences preferences;
  final Color paper;
  final Future<ReadiumPageFrame?> Function() capture;
  final Future<bool> Function(int direction, bool animated) step;
  final String frameKey;
  final Future<bool> Function(int direction)? canTurn;
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
  bool _destinationReady = false;
  bool _dragPrepared = false;
  Future<void>? _settling;
  ReadiumPageFrame? _cached;
  Timer? _warmTimer;
  bool _warming = false;
  Completer<void>? _warmDone;
  int _warmRetries = 0;
  void _warmLater() {
    _warmTimer?.cancel();
    _warmTimer = Timer(const Duration(milliseconds: 48), () async {
      if (!mounted ||
          _busy ||
          _warming ||
          _cached != null ||
          !widget.preferences.mode.paginated ||
          widget.preferences.pageEffect == ReaderPageEffect.none)
        return;
      _warming = true;
      final done = _warmDone = Completer<void>();
      final key = widget.frameKey;
      try {
        final frame = await widget.capture();
        if (mounted && key == widget.frameKey) {
          _cached = frame;
        } else {
          frame?.dispose();
        }
      } catch (_) {
        /* A warm image is optional. */
      } finally {
        _warming = false;
        done.complete();
        if (mounted &&
            (key != widget.frameKey ||
                (!_busy && _cached == null && _warmRetries++ < 3)))
          _warmLater();
      }
    });
  }

  Future<ReadiumPageFrame?> _takeFrame(
    Future<ReadiumPageFrame?> Function() capture,
  ) async {
    // A warm capture already owns the native view. Reuse it instead of drawing
    // and uploading the same image page twice when a gesture starts early.
    if (_warming) await _warmDone?.future;
    if (!mounted) return null;
    final frame = _cached;
    _cached = null;
    return frame ?? await capture();
  }

  void _scrub({bool shapeChanged = false}) {
    if (_dragPrepared && !widget.controller._release!.isCompleted) {
      if (shapeChanged && _clock.value == widget.controller._progress) {
        setState(() {});
      }
      _clock.value = math.min(widget.controller._progress, _destinationReady ? 1.0 : .92);
    }
  }

  void _finishDrag(bool commit) {
    if (!_dragPrepared || _settling != null || !mounted) return;
    _dragPrepared = false;
    final target = commit ? (_destinationReady ? 1.0 : .92) : 0.0;
    final remaining = (target - _clock.value).abs();
    var micros =
        widget.preferences.pageSpeed.durationFor(_effect).inMicroseconds *
        remaining;
    final speed = widget.controller._velocity.abs();
    if (speed > 700) {
      micros = math.min(
        micros,
        remaining * widget.controller._width / speed * 1000000,
      );
    }
    _settling = _clock
        .animateTo(
          target,
          duration: Duration(microseconds: micros.round().clamp(24000, 600000)),
          curve: Curves.easeOutCubic,
        )
        .orCancel
        .catchError((Object error) {
          if (error is! TickerCanceled) throw error;
        });
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
      _warmRetries = 0;
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

  Future<void> _navigate(
    Future<void> Function() action, {
    Future<ReadiumPageFrame?> Function()? capture,
  }) async {
    if (_busy || !mounted) return action();
    _busy = true;
    ReadiumPageFrame? frame;
    try {
      frame = await _takeFrame(capture ?? widget.capture);
      if (mounted && frame != null) {
        setState(() {
          _from = frame;
          _effect = ReaderPageEffect.none;
          _liveDestination = false;
          _clock.value = 0;
        });
        await WidgetsBinding.instance.endOfFrame;
      }
      await action();
    } finally {
      if (mounted) {
        setState(() => _from = null);
        await WidgetsBinding.instance.endOfFrame;
      }
      frame?.dispose();
      _busy = false;
      if (mounted) _warmLater();
    }
  }

  Future<void> _turn(
    int direction,
    bool animated, {
    Completer<bool>? gesture,
  }) async {
    if (_busy || !mounted) return;
    if (widget.canTurn != null && !await widget.canTurn!(direction)) return;
    if (!mounted || _busy) return;
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
    _settling = null;
    ReadiumPageFrame? from, to;
    var moved = false, committed = false;
    try {
      from = await _takeFrame(widget.capture);
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
        _destinationReady = false;
        _effect = prefs.pageEffect;
        _direction = direction * (prefs.mode == ReaderMode.vertical ? -1 : 1);
        _clock.value = 0;
      });
      _dragPrepared = gesture != null;
      if (gesture != null) _clock.value = math.min(widget.controller._progress, .92);
      if (gesture?.isCompleted == true) _finishDrag(await gesture!.future);
      // Cover the native view before it moves. Native slide animation is disabled
      // so adjacent page edges cannot bleed through this full-page transition.
      await WidgetsBinding.instance.endOfFrame;
      // Start preparing the destination independently of the release clock.
      // A fast lift must not wait for a locator, a bitmap or a font/layout probe.
      final preparing = () async {
        moved = await widget.step(direction, false);
        if (!moved || !mounted) return;
        if (prefs.pageEffect == ReaderPageEffect.paper) {
          setState(() { _liveDestination = true; _destinationReady = true; });
          _scrub();
          return;
        }
        for (var attempt = 0; attempt < 4 && mounted; attempt++) {
          to = await widget.capture();
          if (to != null && to!.location != from!.location) break;
          to?.dispose();
          to = null;
          await Future<void>.delayed(const Duration(milliseconds: 50));
        }
        if (!mounted) return;
        if (to != null && to!.location != from!.location) {
          setState(() { _to = to; _liveDestination = true; _destinationReady = true; });
          _scrub();
        } else {
          setState(() { _liveDestination = true; _destinationReady = true; });
          _scrub();
        }
      }();
      var finish = true;
      if (gesture != null) {
        finish = await gesture.future;
        _finishDrag(finish);
      }
      await preparing;
      if (!moved) {
        _dragPrepared = false;
        if (_settling != null) await _settling;
        await _clock
            .animateTo(0, duration: const Duration(milliseconds: 140))
            .orCancel;
        return;
      }
      if (!mounted) return;
      final target = finish ? 1.0 : 0.0;
      if (gesture != null) {
        await _settling;
        if (finish && _clock.value < 1) {
          _settling = null;
          _dragPrepared = true;
          _finishDrag(true);
          await _settling;
        }
      } else {
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
      }
      committed = finish;
      if (!finish) {
        await widget.step(-direction, false);
        moved = false;
      }
    } on TickerCanceled {
      // The route was closed during a turn.
    } finally {
      _dragPrepared = false;
      _settling = null;
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
    final image = RawImage(image: frame.image, fit: BoxFit.fill, filterQuality: FilterQuality.none);
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
                  color: _liveDestination && _to == null
                      ? Colors.transparent
                      : frame.paper,
                  child: Stack(
                    fit: StackFit.expand,
                    children: _effect == ReaderPageEffect.paper
                        ? [
                            if (_to case final target?)
                              RawImage(image: target.image, fit: BoxFit.fill),
                            CustomPaint(
                              painter: _ReadiumCurl(
                                frame,
                                _clock,
                                _direction,
                                () => widget.controller._touchY,
                                () => widget.controller._startY,
                                widget.controller._shape,
                              ),
                            ),
                          ]
                        : _to == null
                        ? [_leaf(frame, false)]
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
    for (var i = 0; i < bands.length; i++) {
      final b = bands[i];
      final bounds = Rect.fromLTRB(
        b.left * size.width,
        b.top * size.height,
        b.right * size.width,
        b.bottom * size.height,
      );
      final offset = scope.displacementFor(i, bands.length, bounds: bounds);
      canvas.drawImageRect(
        frame.image,
        Rect.fromLTRB(
          b.left * frame.image.width,
          b.top * frame.image.height,
          b.right * frame.image.width,
          b.bottom * frame.image.height,
        ),
        bounds.shift(offset),
        Paint()..filterQuality = FilterQuality.low,
      );
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
  _ReadiumCurl(this.from, this.clock, this.direction, this.touchY, this.startY, Listenable shape)
    : super(repaint: Listenable.merge([clock, shape]));
  final ReadiumPageFrame from;
  final Animation<double> clock;
  final double direction;
  final double Function() touchY, startY;
  static const columns = 32, rows = 8, count = (columns + 1) * (rows + 1);
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
    final initial = startY().clamp(0.0, 1.0);
    final anchorY = initial < .25 ? 0.0 : initial > .75 ? h : initial * h;
    final gripWeight = (t / .12).clamp(0.0, 1.0) * ((1 - t) / .15).clamp(0.0, 1.0);
    final fingerY = anchorY + (touchY() * h - anchorY) * gripWeight;
    // The touch travels across two sheet widths. The reflected back leaves
    // the screen at mid-turn instead of collapsing towards the page centre.
    final drag = Offset(w * (1 - 2 * t), fingerY);
    final anchor = Offset(w, anchorY);
    final delta = anchor - drag;
    final length = delta.distance.clamp(.001, double.infinity);
    final nx = delta.dx / length, ny = delta.dy / length;
    final centre = (anchor + drag) / 2;
    final radius = w * .045 * math.sin(math.pi * t);
    final edge = centre.dx;
    final top = centre.dx + ny * centre.dy / nx;
    final bottom = centre.dx - ny * (h - centre.dy) / nx;
    final positions = _positions, texture = _texture, angles = _angles;
    final frontColors = _frontColors,
        backColors = _backColors,
        ghostColors = _ghostColors;
    final paper = from.paper.toARGB32();
    final red = (paper >> 16) & 255,
        green = (paper >> 8) & 255,
        blue = paper & 255;
    for (var j = 0; j <= rows; j++)
      for (var i = 0; i <= columns; i++) {
        final k = j * (columns + 1) + i, x = w * i / columns, y = h * j / rows;
        final d = (x - centre.dx) * nx + (y - centre.dy) * ny;
        double xx = x, yy = y, angle = 0;
        if (d > 0) {
          angle = math.min(math.pi, d / radius);
          final along = d > math.pi * radius
              ? -(d - math.pi * radius)
              : radius * math.sin(angle);
          xx += nx * (along - d);
          yy += ny * (along - d);
          // No projection towards the touch point: a developable sheet keeps
          // its top/bottom lengths and may protrude beyond the viewport.

        }
        positions[k * 2] = xx;
        positions[k * 2 + 1] = yy;
        texture[k * 2] = (backwards ? w - x : x) / w * from.image.width;
        texture[k * 2 + 1] = y / h * from.image.height;
        angles[k] = angle;
        final shade = (255 * (1 - .22 * math.sin(angle))).round();
        frontColors[k] = 0xff000000 | (shade << 16) | (shade << 8) | shade;
        backColors[k] =
            0xff000000 |
            ((red * shade ~/ 255) << 16) |
            ((green * shade ~/ 255) << 8) |
            (blue * shade ~/ 255);
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
    final flat = Path()
      ..moveTo(0, 0)
      ..lineTo(top, 0)
      ..lineTo(bottom, h)
      ..lineTo(0, h)
      ..close();
    c.save();
    c.clipPath(flat);
    if (backwards) {
      c.translate(w, 0);
      c.scale(-1, 1);
    }
    c.drawImageRect(from.image,
      Rect.fromLTWH(0, 0, from.image.width.toDouble(), from.image.height.toDouble()),
      Offset.zero & size, Paint()..filterQuality = FilterQuality.none);
    c.restore();
    final spread = radius * .9;
    if (spread > .01) {
      final shadow = Path()
        ..moveTo(top, 0)..lineTo(bottom, h)
        ..lineTo(bottom + spread / nx, h)..lineTo(top + spread / nx, 0)..close();
      c.save();
      c.clipPath(Path.combine(ui.PathOperation.difference, Path()..addRect(Offset.zero & size), flat));
      c.drawPath(shadow, Paint()..shader = ui.Gradient.linear(
        centre, centre + Offset(nx, ny) * spread,
        const [Color(0x40000000), Color(0x00000000)],
      ));
      c.restore();
    }
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

    c.save();
    c.clipPath(Path.combine(ui.PathOperation.difference, Path()..addRect(Offset.zero & size), flat));
    mesh(_frontIndices, frontCount, frontColors, true);
    c.restore();
    mesh(_backIndices, backCount, backColors, false);
    mesh(_backIndices, backCount, ghostColors, true);
    c.restore();
  }

  @override
  bool shouldRepaint(covariant _ReadiumCurl old) =>
      old.from != from || old.direction != direction;
}
