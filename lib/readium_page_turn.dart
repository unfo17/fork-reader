import 'dart:ui' as ui;
import 'dart:async';
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
}

class ReadiumPageTurnController {
  _ReadiumPageSurfaceState? _owner;
  Completer<bool>? _release;
  double _progress = 0, _sign = 1, _width = 1, _lastDistance = 0, _velocity = 0;
  final _watch = Stopwatch();
  int _lastMicros = 0;
  void beginDrag(double dx, double width) {
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

  void updateDrag(double dx) {
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
          frame?.image.dispose();
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
      _cached?.image.dispose();
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
      _cached?.image.dispose();
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
      // Cover the native view before it moves. Native slide animation is disabled
      // so adjacent page edges cannot bleed through this full-page transition.
      await WidgetsBinding.instance.endOfFrame;
      moved = await widget.step(direction, false);
      if (!moved) return;
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
      from?.image.dispose();
      to?.image.dispose();
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
                    children: _liveDestination
                        ? [
                            CustomPaint(
                              painter: _ReadiumCurl(frame, _clock, _direction),
                            ),
                          ]
                        : _to == null
                        ? [RawImage(image: frame.image, fit: BoxFit.fill)]
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
    _cached?.image.dispose();
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

/// A cylindrical fold of retained native pixels. No WebView transforms or DOM
/// layout take place while the fold is drawn; the back of the sheet is shaded.
class _ReadiumCurl extends CustomPainter {
  _ReadiumCurl(this.from, this.clock, this.direction) : super(repaint: clock);
  final ReadiumPageFrame from;
  final Animation<double> clock;
  final double direction;
  void _image(Canvas c, ReadiumPageFrame f, Size size) => c.drawImageRect(
    f.image,
    Rect.fromLTWH(0, 0, f.image.width.toDouble(), f.image.height.toDouble()),
    Offset.zero & size,
    Paint()..filterQuality = FilterQuality.low,
  );
  @override
  void paint(Canvas c, Size size) {
    final backwards = direction < 0;
    final t = clock.value;
    final front = from;
    if (t <= 0) {
      _image(c, front, size);
      return;
    }
    if (t >= 1) return;
    final w = size.width, h = size.height;
    final radius = w * .13 * math.sin(math.pi * t);
    final edge = w * (1 - t);
    c.save();
    if (backwards) {
      c.translate(w, 0);
      c.scale(-1, 1);
    }
    // Mirror geometry, not the book's text.
    void pixels(Rect target) {
      c.save();
      c.clipRect(target);
      if (backwards) {
        c.translate(w, 0);
        c.scale(-1, 1);
        _image(c, front, size);
      } else {
        _image(c, front, size);
      }
      c.restore();
    }

    if (edge > 0) pixels(Rect.fromLTWH(0, 0, edge, h));
    final shadow = Rect.fromLTWH(edge, 0, radius * 1.6, h);
    c.drawRect(
      shadow,
      Paint()
        ..shader = ui.Gradient.linear(
          Offset(edge, 0),
          Offset(shadow.right, 0),
          [const Color(0x55000000), const Color(0x00000000)],
        ),
    );
    const strips = 40;
    for (var i = 0; i < strips; i++) {
      final a = math.pi * i / strips, b = math.pi * (i + 1) / strips;
      final x1 = edge + radius * math.sin(a), x2 = edge + radius * math.sin(b);
      final left = math.min(x1, x2), width = (x2 - x1).abs() + .6;
      final sx = (edge + radius * a).clamp(0.0, w);
      final sw = math.min(radius * (b - a), w - sx);
      if (sw <= 0) continue;
      final source = Rect.fromLTWH(
        (backwards ? w - sx - sw : sx) / w * front.image.width,
        0,
        sw / w * front.image.width,
        front.image.height.toDouble(),
      );
      final dest = Rect.fromLTWH(left, 0, width, h);
      c.drawImageRect(
        front.image,
        source,
        dest,
        Paint()..filterQuality = FilterQuality.low,
      );
      // Back face is paper with faint ink showing through.
      c.drawRect(
        dest,
        Paint()
          ..color = front.paper.withValues(alpha: a > math.pi / 2 ? .88 : .08),
      );
      c.drawRect(
        dest,
        Paint()..color = Colors.black.withValues(alpha: .24 * math.sin(a)),
      );
    }
    c.restore();
  }

  @override
  bool shouldRepaint(covariant _ReadiumCurl old) =>
      old.from != from || old.direction != direction;
}
