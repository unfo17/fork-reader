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
  ReadiumPageFrame(this.image, this.location, this.bands, this.paper, {this.anchor});
  final ui.Image image;
  final String location;
  final List<Rect> bands;
  final Color paper;
  final String? anchor;
  int _references = 1;
  ReadiumPageFrame retain() { _references++; return this; }
  ui.ImageShader? _shader;
  ui.ImageShader get shader => _shader ??= ui.ImageShader(
    image,
    TileMode.clamp,
    TileMode.clamp,
    Float64List.fromList([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]),
  );
  void dispose() {
    if (--_references > 0) return;
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
  double _initialSign = 1, _directionMultiplier = -1;
  double _touchY = .5, _startY = .5;
  final _shape = ValueNotifier<int>(0);
  void beginDrag(double dx, double width, double touchY) {
    _touchY = _startY = touchY;
    _release = Completer<bool>();
    _progress = 0;
    _width = width;
    _sign = _initialSign = dx < 0 ? -1 : 1;
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
    final nextSign = dx.abs() < 1 ? _sign : (dx < 0 ? -1.0 : 1.0);
    if (_owner?.widget.preview != null && nextSign != _sign) {
      _sign = nextSign;
      _lastDistance = dx * _sign;
      _lastMicros = _watch.elapsedMicroseconds;
      _velocity = 0;
      _owner?._reverseDrag((_sign * _directionMultiplier).round());
    }
    final distance = dx * _sign, now = _watch.elapsedMicroseconds;
    if (now - _lastMicros > 1000 && (distance - _lastDistance).abs() > .5) {
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
    if (_watch.elapsedMicroseconds - _lastMicros > 100000) _velocity = 0;
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
      requestTurn(direction);
      _directionMultiplier = direction / _initialSign;
      await _owner?._turn(direction, true, gesture: _release);
    } finally {
      _release = null;
    }
  }

  Future<void> turn(int direction, {required bool animated}) async {
    requestTurn(direction);
    await _owner?._turn(direction, animated);
  }

  void dispose() {
    endDrag(cancel: true);
    _shape.dispose();
  }

  void requestTurn(int direction) => _owner?._prioritizeTurn(direction);

  Future<void> freezeForRoute() async => _owner?._freezeForRoute();
  Future<void> thawAfterRoute() async => _owner?._thawAfterRoute();

  Future<void> get whenPrepared => _owner?._preparing ?? Future<void>.value();

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
    this.preview,
    this.land,
    this.layoutKey,
    this.lookAhead = 1,
    this.onPrioritize,
  });
  final ReadiumPageTurnController controller;
  final ReaderPreferences preferences;
  final Color paper;
  final Future<ReadiumPageFrame?> Function() capture;
  final Future<bool> Function(int direction, bool animated) step;
  final String frameKey;
  final String? layoutKey;
  final int lookAhead;
  final VoidCallback? onPrioritize;
  final Future<bool> Function(int direction)? canTurn;
  final Future<ReadiumPageFrame?> Function(int direction, ReadiumPageFrame origin)? preview;
  final Future<bool> Function(ReadiumPageFrame frame)? land;
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
  final _neighbors = <int, ReadiumPageFrame>{};
  final _edges = <int>{};
  Future<void>? _preparing;
  ReadiumPageFrame? _previewCover;
  int _dragDirection = 1;
  bool _adoptingPage = false;
  bool _preparationInterrupted = false, _routePaused = false;
  ReadiumPageFrame? _routeCover;
  int _radius(ReadiumPageFrame frame) => frame.image.width * frame.image.height * 4 * (2 * widget.lookAhead + 1) <= 64 * 1024 * 1024 ? widget.lookAhead : 1;
  bool get _windowComplete => _cached != null && _neighbors.length + _edges.length >= 2 * _radius(_cached!);
  void _prioritizeTurn(int direction) {
    widget.onPrioritize?.call();
    _dragDirection = direction;
    _preparationInterrupted = true;
    _warmTimer?.cancel();
  }

  Future<void> _freezeForRoute() async {
    widget.onPrioritize?.call();
    _routePaused = true;
    _preparationInterrupted = true;
    _warmTimer?.cancel();
    await _preparing;
    if (_warming) await _warmDone?.future;
    if (!mounted || _routeCover != null) return;
    final frame = _cached?.retain() ?? await widget.capture();
    if (!mounted) { frame?.dispose(); return; }
    setState(() => _routeCover = frame);
    await WidgetsBinding.instance.endOfFrame;
  }

  Future<void> _thawAfterRoute() async {
    final frame = _routeCover;
    if (mounted) {
      setState(() => _routeCover = null);
      await WidgetsBinding.instance.endOfFrame;
    }
    _routeCover = null;
    frame?.dispose();
    _routePaused = false;
    _preparationInterrupted = false;
    if (mounted) _warmLater();
  }

  void _shiftWindow(int direction, ReadiumPageFrame from) {
    final radius = _radius(from), shifted = <int, ReadiumPageFrame>{};
    for (final entry in _neighbors.entries) {
      final offset = entry.key - direction;
      if (offset != 0 && offset.abs() <= radius) { shifted[offset] = entry.value; }
      else { entry.value.dispose(); }
    }
    final edges = _edges.map((offset) => offset - direction).where((offset) => offset != 0 && offset.abs() <= radius).toSet();
    _neighbors..clear()..addAll(shifted);
    _edges..clear()..addAll(edges);
    _neighbors[-direction]?.dispose();
    _neighbors[-direction] = from.retain();
  }

  void _clearNeighbors() {
    for (final frame in _neighbors.values) { frame.dispose(); }
    _neighbors.clear();
    _edges.clear();
  }

  Future<void> _prepareNeighbors(ReadiumPageFrame origin, {int? requiredDirection}) async {
    if (widget.preview == null) return;
    if (requiredDirection != null && (_neighbors.containsKey(requiredDirection) || _edges.contains(requiredDirection))) return;
    if (requiredDirection == null && _neighbors.length + _edges.length >= 2 * _radius(origin)) return;
    if (_preparing != null) return _preparing;
    final key = widget.frameKey;
    final done = Completer<void>();
    _preparing = done.future;
    if (mounted) setState(() => _previewCover = origin.retain());
    await WidgetsBinding.instance.endOfFrame;
    try {
      final bias = _dragDirection;
      final offsets = requiredDirection == null
          ? [bias, -bias, if (_radius(origin) > 1) bias * 2, if (_radius(origin) > 1) -bias * 2]
          : [requiredDirection];
      for (final direction in offsets) {
        if (!mounted || key != widget.frameKey || _routePaused || (requiredDirection == null && _preparationInterrupted)) break;
        if (direction.abs() > 1 && _edges.contains(direction.sign)) { _edges.add(direction); continue; }
        if (_neighbors.containsKey(direction) || _edges.contains(direction)) continue;
        ReadiumPageFrame? frame;
        try { frame = await widget.preview!(direction, origin); } catch (_) { continue; }
        if (!mounted || key != widget.frameKey) { frame?.dispose(); break; }
        if (frame == null) { _edges.add(direction); }
        else { _neighbors[direction] = frame; }
      }
    } finally {
      final cover = _previewCover;
      if (mounted) { setState(() => _previewCover = null); await WidgetsBinding.instance.endOfFrame; }
      _previewCover = null;
      cover?.dispose();
      _preparing = null;
      done.complete();
    }
  }

  void _reverseDrag(int direction) {
    _dragDirection = direction;
    if (!_dragPrepared || !mounted) return;
    setState(() {
      _to = _neighbors[direction];
      _direction = direction * (widget.preferences.mode == ReaderMode.vertical ? -1 : 1);
      _clock.value = 0;
    });
    if (_neighbors[direction] == null && !_edges.contains(direction) && _from != null) {
      unawaited(_prepareReversedNeighbor(direction, _from!));
    }
  }

  Future<void> _prepareReversedNeighbor(int direction, ReadiumPageFrame origin) async {
    await _preparing;
    if (!mounted || !_dragPrepared || _dragDirection != direction) return;
    await _prepareNeighbors(origin, requiredDirection: direction);
    if (!mounted || !_dragPrepared || _dragDirection != direction) return;
    setState(() => _to = _neighbors[direction]);
    _scrub();
  }

  Future<void> _bufferedTurn(int direction, bool animated, Completer<bool>? gesture) async {
    if (_busy || !mounted) return;
    _busy = true;
    _dragDirection = direction;
    ReadiumPageFrame? from;
    final held = <ReadiumPageFrame>[];
    var committed = false;
    try {
      if (_cached != null && (_neighbors.containsKey(direction) || _edges.contains(direction))) {
        from = _cached!.retain();
      } else {
        await _preparing;
        from = await _takeFrame(widget.capture);
      }
      if (from == null || !mounted) return;
      final requested = gesture == null ? direction : (widget.controller._sign * widget.controller._directionMultiplier).round();
      await _prepareNeighbors(from, requiredDirection: requested);
      if (!mounted) return;
      // Keep both neighbours alive through frame-key changes during landing.
      for (final frame in _neighbors.values) { held.add(frame.retain()); }
      final initialDirection = gesture == null ? direction : (widget.controller._sign * widget.controller._directionMultiplier).round();
      _dragDirection = initialDirection;
      if (_neighbors[initialDirection] == null && gesture == null) {
        await widget.canTurn?.call(initialDirection);
        return;
      }
      setState(() {
        _from = from; _to = _neighbors[initialDirection];
        _effect = widget.preferences.pageEffect;
        _direction = initialDirection * (widget.preferences.mode == ReaderMode.vertical ? -1 : 1);
        _destinationReady = true;
        _liveDestination = false;
        _clock.value = gesture == null || _to == null ? 0 : widget.controller._progress;
      });
      _settling = null;
      _dragPrepared = gesture != null;
      await WidgetsBinding.instance.endOfFrame;
      var finish = gesture == null || await gesture.future;
      if (!_neighbors.containsKey(_dragDirection) && !_edges.contains(_dragDirection)) await _preparing;
      final target = _neighbors[_dragDirection];
      if (target != null && !held.contains(target)) held.add(target.retain());
      if (_to != target && mounted) setState(() => _to = target);
      if (target == null) { finish = false; await widget.canTurn?.call(_dragDirection); }
      Future<bool>? landing;
      if (finish && target != null) {
        _adoptingPage = true;
        landing = (() async {
          await _preparing;
          if (!mounted) return false;
          return widget.land!(target);
        })().catchError((Object _) => false);
      }
      if (gesture != null) {
        _finishDrag(finish);
        await _settling;
      } else {
        await _clock.animateTo(1, duration: animated ? widget.preferences.pageSpeed.durationFor(_effect) : Duration.zero, curve: Curves.easeInOutCubic).orCancel;
      }
      if (finish && target != null) {
        // Hold the complete target bitmap while Readium adopts its exact anchor.
        // No live target layout is exposed in the middle of a gesture.
        committed = await landing!;
        if (!committed) {
          await _clock.animateTo(0, duration: const Duration(milliseconds: 180)).orCancel;
        } else {
          _cached?.dispose(); _cached = target.retain();
          _shiftWindow(_dragDirection, from);
        }
      }
    } on TickerCanceled {
      // Route/viewport changed during settling.
    } finally {
      _dragPrepared = false; _settling = null;
      if (mounted) {
        setState(() { _from = null; _to = null; _liveDestination = false; });
        await WidgetsBinding.instance.endOfFrame;
      }
      if (!committed && from != null && mounted) {
        _cached?.dispose(); _cached = from.retain();
      }
      from?.dispose();
      for (final frame in held) { frame.dispose(); }
      _preparationInterrupted = false;
      _busy = false;
      if (mounted) _warmLater();
    }
  }

  void _warmLater() {
    _warmTimer?.cancel();
    _warmTimer = Timer(const Duration(milliseconds: 48), () async {
      if (!mounted ||
          _busy ||
          _warming ||
          _routePaused || _preparationInterrupted ||
          (_cached != null && (widget.preview == null || _windowComplete)) ||
          !widget.preferences.mode.paginated ||
          widget.preferences.pageEffect == ReaderPageEffect.none)
        return;
      _warming = true;
      final done = _warmDone = Completer<void>();
      final key = widget.frameKey;
      try {
        final frame = _cached ?? await widget.capture();
        if (mounted && key == widget.frameKey) {
          _cached = frame;
          if (frame != null) await _prepareNeighbors(frame);
        } else {
          frame?.dispose();
        }
      } catch (_) {
        /* A warm image is optional. */
      } finally {
        _warming = false;
        if (mounted && key != widget.frameKey && !_busy) {
          _cached?.dispose(); _cached = null; _clearNeighbors();
        }
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
      _clock.value = widget.preview != null && _to == null ? 0 : math.min(widget.controller._progress, _destinationReady ? 1.0 : .92);
    }
  }

  void _finishDrag(bool commit) {
    if (widget.preview != null && _to == null) {
      if (!_edges.contains(_dragDirection)) return;
      commit = false;
    }
    if (!_dragPrepared || _settling != null || !mounted) return;
    _dragPrepared = false;
    final target = commit ? (_destinationReady ? 1.0 : .92) : 0.0;
    _settling = _clock
        .animateTo(
          target,
          duration: _clock.value == target ? Duration.zero : widget.preferences.pageSpeed.durationFor(_effect),
          curve: Curves.easeOutSine,
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
      if (_adoptingPage && oldWidget.layoutKey == widget.layoutKey) {
        _adoptingPage = false;
        _warmLater();
        return;
      }
      if (!_busy && !_warming) {
        _cached?.dispose(); _cached = null; _clearNeighbors();
      }
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
      _clearNeighbors();
      _cached?.dispose();
      _cached = null;
      _warmLater();
    }
  }

  Future<void> _navigate(
    Future<void> Function() action, {
    Future<ReadiumPageFrame?> Function()? capture,
  }) async {
    await _preparing;
    _adoptingPage = false;
    _clearNeighbors();
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
    if (widget.preview != null && widget.land != null) {
      return _bufferedTurn(direction, animated, gesture);
    }
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
      Opacity(opacity: _routeCover == null ? 1 : 0, child: widget.child),
      if (_routeCover case final cover?)
        Positioned.fill(child: IgnorePointer(child: RawImage(image: cover.image, fit: BoxFit.fill, filterQuality: FilterQuality.none))),
      if (_previewCover case final cover?)
        Positioned.fill(child: IgnorePointer(child: RawImage(image: cover.image, fit: BoxFit.fill, filterQuality: FilterQuality.none))),
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
                            if (_direction < 0 && _to != null)
                              RawImage(image: frame.image, fit: BoxFit.fill, filterQuality: FilterQuality.none)
                            else if (_to case final target?)
                              RawImage(image: target.image, fit: BoxFit.fill),
                            CustomPaint(
                              painter: _ReadiumCurl(
                                _direction < 0 && _to != null ? _to! : frame,
                                _direction < 0 && _to != null ? ReverseAnimation(_clock) : _clock,
                                1,
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
    _routeCover?.dispose(); _routeCover = null;
    _clearNeighbors();
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
      relayCurve: const Cubic(.22, .1, .28, 1),
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
  static const segments = 24, capacity = 192;
  final _positions = Float32List(capacity * 2), _texture = Float32List(capacity * 2);
  final _frontColors = Int32List(capacity), _backColors = Int32List(capacity), _ghostColors = Int32List(capacity);
  final _frontIndices = Uint16List(capacity * 3), _backIndices = Uint16List(capacity * 3);

  @override
  void paint(Canvas c, Size size) {
    final t = clock.value.clamp(0.0, 1.0);
    if (t >= 1) return;
    final source = Rect.fromLTWH(0, 0, from.image.width.toDouble(), from.image.height.toDouble());
    if (t <= 0) {
      c.drawImageRect(from.image, source, Offset.zero & size, Paint()..filterQuality = FilterQuality.none);
      return;
    }
    final w = size.width, h = size.height, backwards = direction < 0;
    final initial = startY().clamp(0.0, 1.0);
    final anchorY = initial < .25 ? 0.0 : initial > .75 ? h : initial * h;
    final weight = (t / .12).clamp(0.0, 1.0) * ((1 - t) / .15).clamp(0.0, 1.0);
    final anchor = Offset(w, anchorY);
    final drag = Offset(w * (1 - 2 * t), anchorY + (touchY() * h - anchorY) * weight);
    final delta = anchor - drag;
    final normal = delta / delta.distance.clamp(.001, double.infinity);
    final centre = (anchor + drag) / 2;
    final radius = w * .045 * math.sin(math.pi * t);
    final arc = math.pi * radius;
    double distance(Offset p) => (p.dx-centre.dx)*normal.dx + (p.dy-centre.dy)*normal.dy;
    final page = [Offset.zero, Offset(w,0), Offset(w,h), Offset(0,h)];
    List<Offset> clip(List<Offset> input, double edge, bool greater) {
      if (input.isEmpty) return input;
      final output = <Offset>[];
      var previous = input.last, pd = distance(previous);
      var inside = greater ? pd >= edge : pd <= edge;
      for (final point in input) {
        final d = distance(point), nextInside = greater ? d >= edge : d <= edge;
        if (inside != nextInside) output.add(Offset.lerp(previous,point,(edge-pd)/(d-pd))!);
        if (nextInside) output.add(point);
        previous=point;pd=d;inside=nextInside;
      }
      return output;
    }
    var vertices=0, fronts=0, backs=0;
    final red=(from.paper.toARGB32()>>16)&255, green=(from.paper.toARGB32()>>8)&255, blue=from.paper.toARGB32()&255;
    void append(List<Offset> polygon, bool back) {
      if (polygon.length < 3) return;
      final first=vertices;
      for (final point in polygon) {
        final d=distance(point), angle=(d/radius).clamp(0.0,math.pi);
        final along=d>arc ? arc-d : radius*math.sin(angle);
        final bent=point+normal*(along-d);
        _positions[vertices*2]=bent.dx;_positions[vertices*2+1]=bent.dy;
        _texture[vertices*2]=(backwards?w-point.dx:point.dx)/w*from.image.width;
        _texture[vertices*2+1]=point.dy/h*from.image.height;
        final shade=(255*(1-.22*math.sin(angle))).round();
        _frontColors[vertices]=0xff000000|(shade<<16)|(shade<<8)|shade;
        _backColors[vertices]=0xff000000|((red*shade~/255)<<16)|((green*shade~/255)<<8)|(blue*shade~/255);
        _ghostColors[vertices]=0x16ffffff;
        vertices++;
      }
      for(var i=1;i<polygon.length-1;i++) {
        if(back) {
          _backIndices[backs++]=first;_backIndices[backs++]=first+i;_backIndices[backs++]=first+i+1;
        } else {
          _frontIndices[fronts++]=first;_frontIndices[fronts++]=first+i;_frontIndices[fronts++]=first+i+1;
        }
      }
    }
    // Sample across the bend, not a coarse screen grid. Every strip's angle is
    // stable along the crease, including diagonal corner drags. The flat back
    // is one reflected polygon, so its edges keep their full physical length.
    for(var i=0;i<segments;i++) {
      append(clip(clip(page,arc*i/segments,true),arc*(i+1)/segments,false),i>=segments~/2);
    }
    append(clip(page,arc,true),true);
    final flat=Path()..addPolygon(clip(page,0,false),true);
    c.save();c.clipRect(Offset.zero & size);
    if(backwards){c.translate(w,0);c.scale(-1,1);}
    c.save();c.clipPath(flat);
    if(backwards){c.translate(w,0);c.scale(-1,1);}
    c.drawImageRect(from.image,source,Offset.zero & size,Paint()..filterQuality=FilterQuality.none);
    c.restore();
    final top=centre.dx+normal.dy*centre.dy/normal.dx;
    final bottom=centre.dx-normal.dy*(h-centre.dy)/normal.dx;
    final near=radius*.55, far=radius*1.75;
    final shadow=Path()..moveTo(top+near/normal.dx,0)..lineTo(bottom+near/normal.dx,h)
      ..lineTo(bottom+far/normal.dx,h)..lineTo(top+far/normal.dx,0)..close();
    c.drawPath(shadow,Paint()..shader=ui.Gradient.linear(centre+normal*near,centre+normal*far,
      const [Color(0x00000000),Color(0x38000000),Color(0x00000000)],const [0,.375,1]));
    void mesh(Uint16List indices,int count,Int32List colors,bool textured){
      if(count==0)return;
      final mesh=ui.Vertices.raw(VertexMode.triangles,_positions,
        textureCoordinates:textured?_texture:null,colors:colors,indices:Uint16List.sublistView(indices,0,count));
      c.drawVertices(mesh,BlendMode.modulate,Paint()..color=Colors.white..shader=(textured?from.shader:null)..filterQuality=FilterQuality.low);
    }
    mesh(_frontIndices,fronts,_frontColors,true);
    mesh(_backIndices,backs,_backColors,false);
    mesh(_backIndices,backs,_ghostColors,true);
    c.restore();
  }
  @override
  bool shouldRepaint(covariant _ReadiumCurl old)=>old.from!=from||old.direction!=direction;
}
