import 'dart:ui' as ui;
import 'dart:async';
import 'dart:typed_data';
import 'dart:math' as math;
import 'package:flutter/gestures.dart' show VelocityTracker, kLongPressTimeout, kPrimaryButton;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'reader_preferences.dart';
import 'reader_sheet_motion.dart';
import 'reader_turn_motion.dart';

class ReadiumLoadingIndicator extends StatelessWidget {
  const ReadiumLoadingIndicator({super.key});

  @override
  Widget build(BuildContext context) => const Center(
    child: RepaintBoundary(
      child: CircularProgressIndicator(
        // The pinned Flutter SDK still defaults to the older Material 3 style.
        // ignore: deprecated_member_use
        year2023: false,
        semanticsLabel: '正在加载阅读页面',
      ),
    ),
  );
}

/// A retained image of Readium's rendered page, never re-typeset Flutter text.
class ReadiumPageFrame {
  ReadiumPageFrame(this.image, this.location, this.bands, this.paper, {
    this.anchor, this.returnBottoms = const [], this.sampled = false,
  });
  final ui.Image image;
  final String location;
  final List<Rect> bands;
  final List<double> returnBottoms;
  final Color paper;
  final String? anchor;
  final bool sampled;
  FilterQuality get filterQuality => sampled ? FilterQuality.low : FilterQuality.none;
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

class _ReadiumMotionShape extends ChangeNotifier {
  void notify() => notifyListeners();
}

class _ReadiumOverlap {
  _ReadiumOverlap({
    required TickerProvider vsync,
    required ReadiumPageFrame origin,
    required ReadiumPageFrame? previous,
    required ReadiumPageFrame? next,
    required this.direction,
    required this.duration,
    required this.onCommit,
  }) : clock = AnimationController(vsync: vsync),
       from = origin.retain(),
       previous = previous?.retain(),
       next = next?.retain();

  final AnimationController clock;
  final ReadiumPageFrame from;
  ReadiumPageFrame? previous, next;
  final Duration duration;
  final VoidCallback? onCommit;
  final shape = _ReadiumMotionShape();
  final released = Completer<void>();
  final landed = Completer<bool>();
  final continued = Completer<void>();
  Future<void>? settling;
  Future<void>? preparing;
  bool _commitAcknowledged = false;
  int direction;
  double touchY = .5, startY = .5;
  bool committed = false;
  ReadiumPageFrame? get target => direction > 0 ? next : previous;

  void attachTarget(int side, ReadiumPageFrame frame) {
    if (side > 0) {
      next ??= frame.retain();
    } else {
      previous ??= frame.retain();
    }
  }

  void finish(bool commit) {
    committed = commit;
    released.complete();
    if (!commit || target != null) settle();
  }

  void settle() {
    if (settling != null) return;
    committed = committed && target != null;
    acknowledgeCommit();
    final end = committed ? 1.0 : 0.0;
    settling = clock.animateTo(
      end,
      duration: clock.value == end ? Duration.zero : duration,
      curve: Curves.easeOutSine,
    ).orCancel.catchError((Object error) {
      if (error is! TickerCanceled) throw error;
    });
  }

  void acknowledgeCommit() {
    if (!committed || target == null || _commitAcknowledged) return;
    _commitAcknowledged = true;
    onCommit?.call();
  }

  void abandon() {
    if (!released.isCompleted) released.complete();
    if (!landed.isCompleted) landed.complete(false);
    if (!continued.isCompleted) continued.complete();
    clock.stop();
  }

  void dispose() {
    clock.dispose();
    shape.dispose();
    from.dispose();
    previous?.dispose();
    next?.dispose();
  }
}

class _ReadiumPaperGrab {
  _ReadiumPaperGrab(this.clock, this.overlap, this.wasCommitted, this.touchY)
    : progress = clock.value;

  final AnimationController clock;
  final _ReadiumOverlap? overlap;
  final double progress, touchY;
  final bool wasCommitted;
  final released = Completer<void>();
}

typedef _ReadiumRelease = ({bool commit, bool hadDestination});

class ReadiumPageTurnController {
  _ReadiumPageSurfaceState? _owner;
  Completer<_ReadiumRelease>? _release;
  double _progress = 0, _displacement = 0, _sign = 1, _width = 1, _lastDistance = 0, _velocity = 0;
  final _watch = Stopwatch();
  int _lastMicros = 0;
  double _initialSign = 1, _directionMultiplier = -1;
  double _touchY = .5, _startY = .5;
  final _shape = ValueNotifier<int>(0);
  void beginDrag(double dx, double width, double touchY, {int? direction}) {
    _touchY = _startY = touchY;
    _release = Completer<_ReadiumRelease>();
    _progress = 0;
    _width = width;
    _sign = _initialSign = dx < 0 ? -1 : 1;
    if (direction != null) _directionMultiplier = direction / _initialSign;
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
    if (_owner?.widget.preview != null && _owner?._paperGrab == null && nextSign != _sign) {
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
    _displacement = distance / _width;
    _progress = _displacement.clamp(0.0, 1.0);
    _owner?._scrub(shapeChanged: previousY != _touchY);
  }

  void endDrag({
    bool cancel = false,
    double? releaseVelocity,
    int? durationMillis,
  }) {
    final release = _release;
    if (release == null || release.isCompleted) return;
    if (_watch.elapsedMicroseconds - _lastMicros > 100000) _velocity = 0;
    _watch.stop();
    if (releaseVelocity != null) _velocity = releaseVelocity * _sign;
    if (_owner?._paperGrab != null) {
      release.complete((commit: false, hadDestination: false));
      _owner?._releasePaper(cancel, _velocity);
      return;
    }
    // Very short gestures may have too few movement samples for a useful
    // release velocity. Use their native touch duration and displacement.
    final commit = !cancel && _commitsDrag(_progress, _width, _velocity, durationMillis);
    final direction = (_sign * _directionMultiplier).round();
    release.complete((
      commit: commit,
      hadDestination: _owner?._neighbors.containsKey(direction) == true,
    ));
    _owner?._finishDrag(commit);
  }

  static bool _commitsDrag(double progress, double width, double velocity, int? durationMillis) {
    // Two or three samples can yield no release velocity on a short swipe.
    // Use event time and net displacement as a second measure of the flick.
    if (durationMillis != null && durationMillis > 0 && durationMillis <= 140) {
      final travelledQuickly = progress >= .07 &&
          progress * width * 1000 >= 700 * durationMillis;
      // On a very short flick, trust the overall movement over a brief
      // backwards velocity reported by the last two batched samples.
      if (travelledQuickly && durationMillis <= 60 && velocity > -1400) return true;
      if (velocity >= 0 && (progress >= .12 || travelledQuickly)) return true;
    }
    return (progress >= .25 || (progress > .06 && velocity > 700)) && velocity > -600;
  }

  bool beginOverlappingDrag(int direction, double dx, double width, double touchY) {
    final owner = _owner;
    if (_release?.isCompleted == false || owner == null ||
        !(owner._grabPaper(direction) || owner._addOverlap(direction))) return false;
    beginDrag(dx, width, touchY, direction: direction);
    return true;
  }

  bool turnExposedPage(int direction, {required bool animated}) {
    if (_release?.isCompleted == false) return false;
    return _owner?._addOverlap(direction, drag: false, animated: animated) ?? false;
  }

  Future<void> drag(int direction) async {
    final release = _release;
    try {
      requestTurn(direction);
      _directionMultiplier = direction / _initialSign;
      await _owner?._turn(direction, true, gesture: release);
    } finally {
      if (identical(_release, release)) _release = null;
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

  ReadiumPageFrame? get pendingPage => _owner?._handoffTarget;
  void previewHandedOff() { _owner?._previewHandedOff = true; }

  void requestTurn(int direction) => _owner?._prioritizeTurn(direction);

  void animateReturn() => unawaited(_owner?._animateReturn());
  void animateEntry(ReaderPageEffect effect) =>
      unawaited(_owner?._animateReturn(effect: effect));
  bool get entering => _owner?._entering ?? false;

  Future<void> freezeForRoute() async => _owner?._freezeForRoute();
  Future<void> thawAfterRoute() async => _owner?._thawAfterRoute();
  void setOverlayOpen(bool open) => _owner?._setOverlayOpen(open);
  void setChromeAnimating(bool animating) => _owner?._setChromeAnimating(animating);

  Future<void> get whenPrepared => _owner?._preparing ?? Future<void>.value();

  Future<void> navigate(
    Future<void> Function() action, {
    Future<ReadiumPageFrame?> Function()? capture,
    int? transitionDirection,
    bool chapterEntrance = false,
  }) async {
    final owner = _owner;
    if (owner == null) return action();
    await owner._navigate(action, capture: capture, transitionDirection: transitionDirection,
      chapterEntrance: chapterEntrance);
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
    this.previewRun,
    this.land,
    this.layoutKey,
    this.lookAhead = 1,
    this.rightToLeft = false,
    this.onPrioritize,
    this.onTurnAvailable,
    this.onPageTurn,
    this.coverInitialEntry = false,
    this.openingPage,
    this.replayTap,
    this.onRetainedDrag,
    this.refreshRateLimit = 0,
  });
  final ReadiumPageTurnController controller;
  final ReaderPreferences preferences;
  final Color paper;
  final Future<ReadiumPageFrame?> Function() capture;
  final Future<bool> Function(int direction, bool animated) step;
  final String frameKey;
  final String? layoutKey;
  final int lookAhead;
  final bool rightToLeft;
  final double refreshRateLimit;
  final VoidCallback? onPrioritize;
  final VoidCallback? onTurnAvailable;
  final VoidCallback? onPageTurn;
  final bool coverInitialEntry;
  final ReadiumPageFrame? openingPage;
  final Future<void> Function(Offset fraction)? replayTap;
  final ValueChanged<Map<String, dynamic>>? onRetainedDrag;
  final Future<bool> Function(int direction)? canTurn;
  final Future<ReadiumPageFrame?> Function(int direction, ReadiumPageFrame origin)? preview;
  final Future<void> Function(
    List<int> offsets,
    ReadiumPageFrame origin,
    void Function(int offset, ReadiumPageFrame? frame) onFrame,
  )? previewRun;
  final Future<bool> Function(ReadiumPageFrame frame)? land;
  final Widget child;
  @override
  State<ReadiumPageSurface> createState() => _ReadiumPageSurfaceState();
}

class _ReadiumPageSurfaceState extends State<ReadiumPageSurface>
    with TickerProviderStateMixin {
  late final _clock = AnimationController(vsync: this);
  late final _returnClock = AnimationController(vsync: this);
  int _returnEpoch = 0;
  ReadiumPageFrame? _returnFrame;
  ReaderPageEffect _entryEffect = ReaderPageEffect.staggered;
  bool _entryFromBottom = true;
  late bool _coverInitialEntry = widget.coverInitialEntry;
  ReadiumPageFrame? _from, _to;
  ReaderPageEffect _effect = ReaderPageEffect.none;
  double _direction = 1;
  bool _busy = false;
  bool _liveDestination = false;
  bool _destinationReady = false;
  bool _dragPrepared = false;
  Future<void>? _settling;
  bool _primaryCommit = false, _primaryAdopted = false;
  bool _primaryLandingStarted = false;
  Completer<void>? _primaryContinued;
  double _primaryTouchY = .5, _primaryStartY = .5;
  final _overlaps = <_ReadiumOverlap>[];
  _ReadiumOverlap? _trackingOverlap;
  Future<void>? _nativeOverlaps;
  bool _overlapLandingFailed = false;
  _ReadiumPaperGrab? _paperGrab;
  ReadiumPageFrame? _cached;
  ReadiumPageFrame? _refilledFrom;
  bool _refilling = false;
  Timer? _warmTimer;
  bool _warming = false;
  Completer<void>? _warmDone;
  int _warmRetries = 0;
  final _neighbors = <int, ReadiumPageFrame>{};
  final _edges = <int>{};
  Future<void>? _preparing;
  int? _preparingOffset;
  Completer<void>? _neighborUpdate;
  ReadiumPageFrame? _previewCover;
  int _dragDirection = 1;
  bool _adoptingPage = false;
  bool _primaryCommitAcknowledged = false;
  ReadiumPageFrame? _handoffTarget;
  bool _previewHandedOff = false;
  bool _preparationInterrupted = false, _routePaused = false;
  bool _overlayOpen = false;
  bool _chromeAnimating = false;
  ReadiumPageFrame? _routeCover;
  ReadiumPageFrame? _overlayCover;
  ReadiumPageFrame? _presented;
  int? _scrubCallback;
  Duration? _nextDragTick;
  bool _shapeChanged = false;
  bool get _entering => _coverInitialEntry || _returnFrame != null;

  double _visualDirection(int direction) =>
      (widget.rightToLeft || widget.preferences.mode == ReaderMode.vertical
          ? -direction : direction).toDouble();

  void _setOverlayOpen(bool open) {
    if (open == _overlayOpen) return;
    _overlayOpen = open;
    if (open) {
      _warmTimer?.cancel();
      if (!_busy && _preparing != null) widget.onPrioritize?.call();
      if ((_presented ?? _cached) case final frame? when !_busy) {
        setState(() => _overlayCover = frame.retain());
      }
    } else {
      _releaseOverlayCover();
      _resumeWarmWhenReady();
    }
  }

  void _setChromeAnimating(bool animating) {
    if (animating == _chromeAnimating) return;
    _chromeAnimating = animating;
    if (animating) {
      _warmTimer?.cancel();
      if (!_busy && _preparing != null) widget.onPrioritize?.call();
    } else {
      _resumeWarmWhenReady();
    }
  }

  void _resumeWarmWhenReady() {
    final warming = _warming ? _warmDone?.future : _preparing;
    if (warming == null) {
      _warmLater();
    } else {
      unawaited(warming.whenComplete(() {
        if (mounted && !_overlayOpen && !_chromeAnimating) _warmLater();
      }));
    }
  }

  void _releaseOverlayCover() {
    final frame = _overlayCover;
    if (frame == null) return;
    setState(() => _overlayCover = null);
    WidgetsBinding.instance.addPostFrameCallback((_) => frame.dispose());
  }

  Widget _pageImage(ReadiumPageFrame frame) => RawImage(
    image: frame.image,
    fit: BoxFit.fill,
    filterQuality: frame.filterQuality,
  );

  void _holdPresentedPage(ReadiumPageFrame frame) {
    final previous = _presented;
    _presented = frame.retain();
    if (previous != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => previous.dispose());
    }
  }

  void _releasePresentedPage() {
    final previous = _presented;
    _presented = null;
    if (previous != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => previous.dispose());
    }
  }
  List<int> _warmOffsets(ReadiumPageFrame frame) {
    final ahead = _dragDirection, behind = -ahead;
    final bytes = frame.image.width * frame.image.height * 4;
    final slots = ((96 * 1024 * 1024 ~/ bytes) - 1)
        .clamp(2, math.max(2, 2 * widget.lookAhead));
    if (widget.lookAhead >= 3 && slots >= 4 && slots < 2 * widget.lookAhead) {
      // Fit the active direction first within the existing bitmap budget.
      // Keep the immediately previous page ready for a reversal at any size.
      final preceding = math.max(1, slots - widget.lookAhead);
      return [
        ahead,
        behind,
        for (var distance = 2; distance <= slots - preceding; distance++) distance * ahead,
        for (var distance = 2; distance <= preceding; distance++) distance * behind,
      ];
    }
    return [for (var slot = 0; slot < slots; slot++)
      (slot ~/ 2 + 1) * (slot.isEven ? ahead : behind)];
  }

  bool get _windowComplete => _cached != null && _warmOffsets(_cached!).every(
    (offset) => _neighbors.containsKey(offset) || _edges.contains(offset),
  );
  void _prioritizeTurn(int direction) {
    _stopReturnAnimation();
    if (_coverInitialEntry) setState(() => _coverInitialEntry = false);
    if (!_preparationInterrupted && _preparingOffset != direction) {
      widget.onPrioritize?.call();
    }
    _dragDirection = direction;
    _preparationInterrupted = true;
    _warmTimer?.cancel();
  }

  Future<void> _animateReturn({ReaderPageEffect? effect}) async {
    final initial = effect != null;
    void clearEntryCover() {
      if (initial && mounted && _coverInitialEntry) {
        setState(() => _coverInitialEntry = false);
        _warmLater();
      }
    }
    if (!mounted || _busy || _routePaused || effect == ReaderPageEffect.none ||
        MediaQuery.disableAnimationsOf(context)) {
      clearEntryCover();
      return;
    }
    _stopReturnAnimation();
    final epoch = _returnEpoch, key = widget.frameKey;
    _warmTimer?.cancel();
    if (initial) {
      widget.onPrioritize?.call();
      _preparationInterrupted = true;
    }
    final opening = initial ? widget.openingPage : null;
    ReadiumPageFrame? frame = (opening ?? _presented ?? _cached)?.retain();
    try {
      if (frame == null) {
        if (_warming) await _warmDone?.future;
        await _preparing;
        if (mounted && _returnEpoch == epoch && !_busy) {
          frame = (_presented ?? _cached)?.retain() ?? await widget.capture();
        }
      }
    } catch (_) {
      // An unavailable snapshot should still reveal the live reader.
    } finally {
      if (initial && mounted && _returnEpoch == epoch) _preparationInterrupted = false;
    }
    if (frame == null || !mounted || _returnEpoch != epoch || key != widget.frameKey || _busy || _routePaused) {
      frame?.dispose();
      clearEntryCover();
      if (mounted && _returnEpoch == epoch) _warmLater();
      return;
    }
    final entryEffect = effect ?? ReaderPageEffect.staggered;
    _returnClock.duration = widget.preferences.pageSpeed.durationFor(entryEffect);
    _returnClock.value = 0;
    setState(() {
      _returnFrame = frame;
      _entryEffect = entryEffect;
      _entryFromBottom = effect == null;
      if (initial) {
        _coverInitialEntry = false;
        if (widget.preview != null && opening == null) _cached ??= frame!.retain();
      }
    });
    if (initial) await WidgetsBinding.instance.endOfFrame;
    if (!mounted || _returnEpoch != epoch) return;
    unawaited(_returnClock.forward().orCancel.then((_) {
      if (mounted && _returnEpoch == epoch) _stopReturnAnimation();
    }).catchError((Object error) {
      if (error is! TickerCanceled) throw error;
    }));
  }

  void _stopReturnAnimation() {
    _returnEpoch++;
    final frame = _returnFrame;
    if (frame == null) return;
    _returnClock.stop();
    setState(() => _returnFrame = null);
    WidgetsBinding.instance.addPostFrameCallback((_) => frame.dispose());
    _warmLater();
  }

  Future<void> _freezeForRoute() async {
    _stopReturnAnimation();
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
    final offsets = _warmOffsets(from);
    final shifted = <int, ReadiumPageFrame>{};
    for (final entry in _neighbors.entries) {
      final offset = entry.key - direction;
      if (offsets.contains(offset)) { shifted[offset] = entry.value; }
      else { entry.value.dispose(); }
    }
    final edges = _edges.map((offset) => offset - direction).where(offsets.contains).toSet();
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
    if (requiredDirection != null &&
        (_neighbors.containsKey(requiredDirection) || _edges.contains(requiredDirection))) return;
    final key = widget.frameKey;
    if (requiredDirection != null) {
      // A request that arrives during speculative work must still get its
      // own page if that worker was preparing the opposite side.
      while (_preparing != null) {
        await _preparing;
        if (!mounted || key != widget.frameKey) return;
        if (_neighbors.containsKey(requiredDirection) ||
            _edges.contains(requiredDirection)) return;
      }
    }
    if (requiredDirection == null && _windowComplete) return;
    if (_preparing != null) return _preparing;
    final done = Completer<void>();
    _preparing = done.future;
    if (mounted) setState(() => _previewCover = origin.retain());
    await WidgetsBinding.instance.endOfFrame;
    try {
      final offsets = requiredDirection == null
          ? _warmOffsets(origin)
          : [requiredDirection];
      if (requiredDirection == null) {
        _neighbors.removeWhere((offset, frame) {
          if (offsets.contains(offset)) return false;
          frame.dispose();
          return true;
        });
        _edges.retainWhere(offsets.contains);
      }
      final batch = widget.previewRun != null;
      final ahead = _dragDirection;
      // Capture the next two pages in the same native visit. The first
      // texture is available immediately, even while the second is rendering.
      final firstRun = [ahead, if (offsets.contains(2 * ahead)) 2 * ahead];
      final runs = requiredDirection != null ? [[requiredDirection]] : batch ? [
        firstRun,
        [-ahead],
        for (final direction in [ahead, -ahead])
          offsets.where((offset) => offset.abs() > 1 &&
              offset.sign == direction && !firstRun.contains(offset)).toList(),
      ] : offsets.map((offset) => [offset]);
      void keepFrame(int direction, ReadiumPageFrame? frame) {
        if (!mounted || key != widget.frameKey) {
          frame?.dispose();
        } else {
          if (frame == null) {
            _edges.add(direction);
          } else {
            _neighbors[direction]?.dispose();
            _neighbors[direction] = frame;
            final track = _trackingOverlap;
            if (track != null) {
              final side = direction - _exposedOffset;
              if (side.abs() == 1) {
                setState(() => track.attachTarget(side, frame));
                _scrub();
              }
            }
          }
          _neighborUpdate?.complete();
          _neighborUpdate = null;
          _offerNextTurn();
        }
      }
      for (final run in runs) {
        if (!mounted || key != widget.frameKey || _routePaused ||
            (requiredDirection == null &&
                (_preparationInterrupted || _overlayOpen || _chromeAnimating))) break;
        final missing = <int>[];
        for (final offset in run) {
          if (offset.abs() > 1 && _edges.contains(offset.sign)) _edges.add(offset);
          if (!_neighbors.containsKey(offset) && !_edges.contains(offset)) missing.add(offset);
        }
        if (missing.isEmpty) continue;
        _preparingOffset = missing.first;
        for (var attempt = 0; attempt < (requiredDirection == null || _refilling ? 1 : 2); attempt++) {
          if (!mounted || key != widget.frameKey || _routePaused) break;
          try {
            if (batch) {
              await widget.previewRun!(missing, origin, keepFrame);
            } else {
              keepFrame(missing.first, await widget.preview!(missing.first, origin));
            }
          } catch (_) {
            continue;
          }
          break;
        }
      }
    } finally {
      final cover = _previewCover;
      if (mounted) { setState(() => _previewCover = null); await WidgetsBinding.instance.endOfFrame; }
      _previewCover = null;
      cover?.dispose();
      _preparing = null;
      _preparingOffset = null;
      done.complete();
    }
  }

  Future<void> _replayPreviewTap(Offset position) async {
    if (widget.replayTap == null || _busy) return;
    final key = widget.frameKey;
    final box = context.findRenderObject();
    if (box is! RenderBox || !box.hasSize) return;
    final fraction = Offset(position.dx / box.size.width, position.dy / box.size.height);
    _prioritizeTurn(_dragDirection);
    try {
      await _preparing;
      if (!mounted || _busy || key != widget.frameKey) return;
      await widget.replayTap!(fraction);
    } finally {
      _preparationInterrupted = false;
      if (mounted) _warmLater();
    }
  }

  void _reverseDrag(int direction) {
    if (_trackingOverlap case final overlap?) {
      setState(() => overlap.direction = direction);
      _prepareExposedPage(overlap);
      return;
    }
    _dragDirection = direction;
    if (!_dragPrepared || !mounted) return;
    setState(() {
      _to = _neighbors[direction];
      _handoffTarget = _to;
      _direction = _visualDirection(direction);
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

  Future<void> _waitForDragTarget(
    ReadiumPageFrame from, int requested, Completer<_ReadiumRelease> gesture,
  ) async {
    while (mounted && !gesture.isCompleted &&
        !_neighbors.containsKey(requested) && !_edges.contains(requested)) {
      final update = _neighborUpdate ??= Completer<void>();
      final continuing = _preparing;
      final work = continuing ?? _prepareNeighbors(from, requiredDirection: requested);
      await Future.any([gesture.future, update.future, work]);
      if (identical(_neighborUpdate, update)) _neighborUpdate = null;
      if (continuing == null) return;
    }
  }

  Future<void> _bufferedTurn(int direction, bool animated, Completer<_ReadiumRelease>? gesture) async {
    if (_busy || !mounted) return;
    _busy = true;
    _primaryCommit = _primaryAdopted = _primaryLandingStarted = false;
    final continued = _primaryContinued = Completer<void>();
    _primaryCommitAcknowledged = false;
    _overlapLandingFailed = false;
    _previewHandedOff = false;
    _handoffTarget = null;
    _dragDirection = direction;
    ReadiumPageFrame? from;
    final held = <ReadiumPageFrame>[];
    var committed = false;
    try {
      if (_cached case final cached?) {
        from = cached.retain();
      } else {
        await _preparing;
        from = await _takeFrame(widget.capture);
      }
      if (from == null || !mounted) return;
      final requested = gesture == null ? direction : (widget.controller._sign * widget.controller._directionMultiplier).round();
      if (gesture == null) {
        await _prepareNeighbors(from, requiredDirection: requested);
      } else if (!gesture.isCompleted) {
        await _waitForDragTarget(from, requested, gesture);
      }
      if (!mounted) return;
      final release = gesture?.isCompleted == true ? await gesture!.future : null;
      if (release != null && (!release.commit || !release.hadDestination)) {
        if (release.commit && _edges.contains(requested)) {
          await widget.canTurn?.call(requested);
        }
        return;
      }
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
        _handoffTarget = _to;
        _effect = widget.preferences.pageEffect;
        _direction = _visualDirection(initialDirection);
        _destinationReady = true;
        _liveDestination = false;
        _clock.value = gesture == null || _to == null
            ? 0 : math.min(widget.controller._progress, .85);
        _primaryTouchY = widget.controller._touchY;
        _primaryStartY = widget.controller._startY;
      });
      _settling = null;
      _dragPrepared = gesture != null;
      if (release != null) _finishDrag(release.commit);
      if (gesture?.isCompleted == false && _to == null && !_edges.contains(initialDirection)) {
        // The finger may have crossed back over the origin while the other
        // side was being captured.
        unawaited(_prepareReversedNeighbor(initialDirection, from));
      }
      await WidgetsBinding.instance.endOfFrame;
      if (gesture != null) await gesture.future;
      var finish = gesture == null || _primaryCommit;
      final target = _neighbors[_dragDirection];
      if (target != null && !held.contains(target)) held.add(target.retain());
      if (_to != target && mounted) setState(() => _to = target);
      if (target == null) {
        finish = false;
        if (_edges.contains(_dragDirection)) await widget.canTurn?.call(_dragDirection);
      }
      final origin = from;
      Future<bool> adopt(ReadiumPageFrame target) async {
        _primaryLandingStarted = true;
        _adoptingPage = true;
        try {
          await _preparing;
          if (!mounted) return false;
          if (!await widget.land!(target) || !mounted) return false;
          _cached?.dispose();
          _cached = target.retain();
          _shiftWindow(_dragDirection, origin);
          _primaryAdopted = true;
          _handoffTarget = null;
          _startNativeOverlaps();
          _refillAdoptedPage();
          return true;
        } catch (_) {
          return false;
        }
      }
      var landing = finish && target != null && !_deferNativeLanding
          ? adopt(target)
          : null;
      if (gesture != null) {
        if (_settling == null) _finishDrag(false);
      } else {
        _primaryCommit = finish && target != null;
        _acknowledgePrimaryCommit();
        _offerNextTurn();
        _settling = _animatePage(_clock, 1,
          animated ? widget.preferences.pageSpeed.durationFor(_effect) : Duration.zero,
          Curves.easeInOutCubic);
      }
      if (finish && target != null && widget.previewRun != null &&
          _preparing == null && !_previewHandedOff && !_primaryLandingStarted) {
        // If the next sheet wasn't captured before the first turn, use this
        // release animation to prepare it from the still-current native page.
        final following = 2 * _dragDirection;
        if (!_neighbors.containsKey(following) && !_edges.contains(following)) {
          unawaited(_prepareNeighbors(origin, requiredDirection: following));
        }
      }
      final settled = _waitForCurrentSettle(() => _settling);
      if (finish && target != null && landing == null) {
        await Future.any([settled, continued.future]);
        if (mounted && _primaryCommit) landing = adopt(target);
      }
      await settled;
      if (_effect == ReaderPageEffect.paper) finish = _primaryCommit && target != null;
      if (!finish) {
        _handoffTarget = null;
        await _preparing;
        if (_previewHandedOff && mounted) await widget.land!(from);
      }
      if (!mounted) return;
      if (finish && target != null) {
        // Hold the complete target bitmap while Readium adopts its exact anchor.
        // No live target layout is exposed in the middle of a gesture.
        committed = await (landing ?? adopt(target));
        if (!committed) {
          if (mounted) setState(_clearOverlaps);
          await _clock.animateTo(0, duration: const Duration(milliseconds: 180)).orCancel;
        } else {
          if (mounted) {
            setState(() {
              _holdPresentedPage(target);
              _from = _to = null;
            });
            do {
              await _retireSettledOverlaps();
              await _preparing;
            } while (mounted && _overlaps.isNotEmpty);
          }
        }
      }
    } on TickerCanceled {
      // Route/viewport changed during settling.
    } finally {
      _dragPrepared = false;
      _settling = null;
      _primaryCommit = _primaryAdopted = _primaryLandingStarted = false;
      _primaryContinued = null;
      _refilledFrom = null;
      _releaseAbandonedGrab();
      _clearOverlaps();
      _handoffTarget = null;
      _previewHandedOff = false;
      if (mounted) {
        setState(() { _from = null; _to = null; _liveDestination = false; });
      }
      if (!committed && from != null && mounted) {
        _cached?.dispose(); _cached = from.retain();
      }
      // The next gesture can start before a cleanup frame has painted. The
      // outgoing compositor layers still own those images until that frame.
      void releaseFrames() {
        from?.dispose();
        for (final frame in held) { frame.dispose(); }
      }
      if (mounted) {
        WidgetsBinding.instance.addPostFrameCallback((_) => releaseFrames());
      } else {
        releaseFrames();
      }
      _preparationInterrupted = false;
      _busy = false;
      if (mounted) {
        // Keep one continuous still image through lookahead. Removing it now
        // would expose the WebView, then replace it again with a snapshot.
        _warmLater();
      }
    }
  }

  // Offset of the last committed visual sheet from Readium's native page.
  int get _exposedOffset => (_primaryAdopted ? 0 : _dragDirection) +
      _overlaps.where((turn) => turn.committed && !turn.landed.isCompleted)
          .fold<int>(0, (position, turn) => position + turn.direction);

  bool _addOverlap(int direction, {bool drag = true, bool animated = true}) {
    if (!mounted || !_primaryCommit || _routePaused || _overlapLandingFailed ||
        _overlaps.length >= 4) return false;
    final last = _overlaps.lastOrNull;
    final cancelled = last != null && last.released.isCompleted && !last.committed ? last : null;
    final tail = cancelled == null ? last
        : _overlaps.length > 1 ? _overlaps[_overlaps.length - 2] : null;
    final origin = tail == null ? (_to ?? (_primaryAdopted ? _cached : null)) : tail.target;
    if (origin == null || (tail != null && !tail.committed)) return false;
    // The offsets are relative to the last native page that has been adopted.
    // The cache moves only when Readium confirms the corresponding locator.
    final offset = _exposedOffset;
    ReadiumPageFrame? at(int index) =>
        index == 0 ? _cached ?? _from : _neighbors[index];
    final previous = at(offset - 1), next = at(offset + 1);
    if (_edges.contains(offset + direction)) return false;
    if (!drag && (direction > 0 ? next : previous) == null) return false;
    if (_refilling && _preparingOffset != offset + direction) {
      widget.onPrioritize?.call();
    }
    _stopReturnAnimation();
    final overlap = _ReadiumOverlap(
      vsync: this,
      origin: origin,
      previous: previous,
      next: next,
      direction: direction,
      duration: animated ? widget.preferences.pageSpeed.durationFor(_effect) : Duration.zero,
      onCommit: widget.onPageTurn,
    );
    setState(() {
      if (cancelled != null) _overlaps.remove(cancelled);
      _overlaps.add(overlap);
      if (drag) _trackingOverlap = overlap;
    });
    if (cancelled != null) _retireOverlap(cancelled);
    _prepareExposedPage(overlap);
    if (!drag) overlap.finish(true);
    _startNativeOverlaps();
    return true;
  }

  void _offerNextTurn() {
    scheduleMicrotask(() {
      if (mounted) widget.onTurnAvailable?.call();
    });
  }

  bool _grabPaper(int direction) {
    if (!mounted || _routePaused || _effect != ReaderPageEffect.paper) return false;
    final overlap = _overlaps.lastOrNull;
    final clock = overlap?.clock ?? _clock;
    final currentDirection = overlap?.direction ?? _dragDirection;
    if (direction != -currentDirection || clock.value <= 0 || clock.value >= 1) return false;
    if (overlap == null) {
      if (_from == null || _to == null || _settling == null || _primaryLandingStarted) return false;
    } else if (overlap.target == null || overlap.settling == null || overlap.continued.isCompleted || overlap.landed.isCompleted) {
      return false;
    }
    final grab = _ReadiumPaperGrab(
      clock, overlap, overlap?.committed ?? _primaryCommit,
      overlap?.touchY ?? _primaryTouchY,
    );
    _paperGrab = grab;
    if (overlap == null) {
      _settling = grab.released.future;
    } else {
      overlap.settling = grab.released.future;
    }
    clock.stop();
    _stopReturnAnimation();
    return true;
  }

  void _releasePaper(bool cancel, double velocity) {
    final grab = _paperGrab;
    if (grab == null) return;
    _paintDrag();
    _paperGrab = null;
    _nextDragTick = null;
    final progress = grab.clock.value;
    final commit = cancel ? grab.wasCommitted
        : velocity > 700 && progress < .9 ? false
        : velocity < -700 && progress > .1 ? true
        : progress > .5;
    if (grab.overlap case final overlap?) {
      overlap.committed = commit;
      overlap.acknowledgeCommit();
    } else {
      _primaryCommit = commit;
      _acknowledgePrimaryCommit();
      _handoffTarget = commit ? _to : null;
    }
    final target = commit ? 1.0 : 0.0;
    final duration = widget.preferences.pageSpeed.durationFor(ReaderPageEffect.paper);
    unawaited(_animatePage(grab.clock, target,
      Duration(microseconds: (duration.inMicroseconds * (.3 + .7 * (target - progress).abs())).round()),
      Curves.easeOutSine).whenComplete(() {
        grab.released.complete();
        _offerNextTurn();
      }));
  }

  void _releaseAbandonedGrab() {
    _paperGrab?.released.complete();
    _paperGrab = null;
  }

  Future<void> _animatePage(AnimationController clock, double target, Duration duration, Curve curve) {
    return clock.animateTo(
      target, duration: clock.value == target ? Duration.zero : duration, curve: curve,
    ).orCancel.catchError((Object error) {
      if (error is! TickerCanceled) throw error;
    });
  }

  Future<void> _waitForCurrentSettle(Future<void>? Function() current) async {
    Future<void>? animation;
    do {
      animation = current();
      await animation;
    } while (mounted && animation != current());
  }

  void _prepareExposedPage(_ReadiumOverlap overlap) {
    if (overlap.target != null || overlap.preparing != null ||
        (overlap.released.isCompleted && !overlap.committed) ||
        overlap.landed.isCompleted) return;
    if (!_primaryAdopted || !identical(_cached, overlap.from)) {
      // Bring Readium to the source of the missing destination. Ready
      // destinations return above and leave the platform thread alone.
      final primary = _primaryContinued;
      if (primary != null && !primary.isCompleted) primary.complete();
      for (final preceding in _overlaps) {
        if (identical(preceding, overlap)) break;
        if (!preceding.continued.isCompleted) preceding.continued.complete();
      }
      return;
    }
    overlap.preparing = _fillExposedPage(overlap).whenComplete(() {
      overlap.preparing = null;
    });
  }

  Future<void> _fillExposedPage(_ReadiumOverlap overlap) async {
    var direction = overlap.direction;
    while (mounted && !overlap.landed.isCompleted && overlap.target == null) {
      await _prepareNeighbors(overlap.from, requiredDirection: direction);
      if (!mounted || overlap.landed.isCompleted ||
          (overlap.released.isCompleted && !overlap.committed)) return;
      if (_neighbors[direction] case final frame?) {
        setState(() => overlap.attachTarget(direction, frame));
      }
      if (overlap.direction == direction) break;
      direction = overlap.direction;
    }
    if (_trackingOverlap == overlap && overlap.target != null) {
      final progress = math.min(widget.controller._progress, .85);
      if (progress > overlap.clock.value) {
        unawaited(overlap.clock.animateTo(
          progress, duration: const Duration(milliseconds: 55), curve: Curves.easeOut,
        ).orCancel.catchError((Object error) {
          if (error is! TickerCanceled) throw error;
        }));
      }
    } else if (overlap.released.isCompleted) {
      overlap.settle();
    }
    _offerNextTurn();
  }

  void _startNativeOverlaps() {
    if (!mounted || !_primaryAdopted || _nativeOverlaps != null ||
        _overlapLandingFailed || _overlaps.every((turn) => turn.landed.isCompleted)) return;
    _nativeOverlaps = _adoptReleasedOverlaps().whenComplete(() {
      _nativeOverlaps = null;
      _startNativeOverlaps();
      _refillAdoptedPage();
    });
  }

  void _refillAdoptedPage() {
    if (!mounted || !_busy || !_primaryAdopted || _routePaused || _overlayOpen ||
        _nativeOverlaps != null || _preparing != null || _overlapLandingFailed) return;
    final origin = _cached;
    if (origin == null || identical(_refilledFrom, origin)) return;
    // During a visible turn only refill its advancing side. Speculative
    // captures from the other side compete for the platform thread.
    final nearest = _dragDirection;
    if (_neighbors.containsKey(nearest) || _edges.contains(nearest)) return;
    _refilledFrom = origin;
    _refilling = true;
    unawaited(_prepareNeighbors(origin, requiredDirection: nearest)
      .whenComplete(() {
        _refilling = false;
        _offerNextTurn();
      }));
  }

  Future<void> _adoptReleasedOverlaps() async {
    while (mounted) {
      final overlap = _overlaps.where((turn) => !turn.landed.isCompleted).firstOrNull;
      if (overlap == null) return;
      _prepareExposedPage(overlap);
      await overlap.released.future;
      if (!mounted || overlap.landed.isCompleted) return;
      if (overlap.committed) _prepareExposedPage(overlap);
      await overlap.preparing;
      if (!mounted || overlap.landed.isCompleted) return;
      overlap.settle();
      final target = overlap.committed ? overlap.target : null;
      if (target != null && _deferNativeLanding) {
        await Future.any([
          _waitForCurrentSettle(() => overlap.settling),
          overlap.continued.future,
        ]);
        if (!mounted || overlap.landed.isCompleted) return;
      }
      // A paper turn may have been taken back during the settling animation.
      final destination = overlap.committed ? overlap.target : null;
      await _preparing;
      if (!mounted || overlap.landed.isCompleted) return;
      final landed = destination == null ||
          await widget.land!(destination).catchError((Object _) => false);
      if (!mounted || overlap.landed.isCompleted) return;
      if (landed && destination != null) {
        _cached?.dispose();
        _cached = destination.retain();
        _dragDirection = overlap.direction;
        _shiftWindow(overlap.direction, overlap.from);
      }
      _overlapLandingFailed = !landed;
      overlap.landed.complete(landed);
      if (!landed) return;
    }
  }

  Future<void> _retireSettledOverlaps() async {
    while (mounted && _overlaps.isNotEmpty) {
      final overlap = _overlaps.first;
      final landed = await overlap.landed.future;
      await _waitForCurrentSettle(() => overlap.settling);
      if (!mounted || _overlaps.firstOrNull != overlap) return;
      final target = overlap.committed ? overlap.target : null;
      if (target != null && !landed) {
        // A later sheet depends on this anchor. Drop it before returning to the
        // last confirmed page so a failed native seek cannot skip a page.
        setState(() {
          for (final turn in _overlaps.skip(1)) {
            _retireOverlap(turn);
          }
          _overlaps.removeRange(1, _overlaps.length);
          _trackingOverlap = null;
        });
        overlap.committed = false;
        await overlap.clock.animateBack(0, duration: const Duration(milliseconds: 180)).orCancel;
        if (!mounted || _overlaps.firstOrNull != overlap) return;
      }
      setState(() {
        if (landed && target != null) {
          _holdPresentedPage(target);
        }
        _overlaps.removeAt(0);
      });
      _retireOverlap(overlap);
      _offerNextTurn();
    }
  }

  void _retireOverlap(_ReadiumOverlap overlap) {
    overlap.abandon();
    if (mounted) {
      WidgetsBinding.instance.addPostFrameCallback((_) => overlap.dispose());
    } else {
      overlap.dispose();
    }
  }

  void _clearOverlaps() {
    for (final overlap in _overlaps) {
      _retireOverlap(overlap);
    }
    _overlaps.clear();
    _trackingOverlap = null;
  }

  void _warmLater() {
    _warmTimer?.cancel();
    _warmTimer = Timer(Duration(milliseconds: _cached == null ? 48 : 32), () async {
      if (!mounted || _busy || _warming || _routePaused || _overlayOpen ||
          _chromeAnimating || _entering ||
          _preparationInterrupted) return;
      if ((_cached != null && (widget.preview == null || _windowComplete)) ||
          !widget.preferences.mode.paginated || widget.preferences.pageEffect == ReaderPageEffect.none) {
        if (_presented != null) setState(_releasePresentedPage);
        return;
      }
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
        if (mounted && !_busy && !_routePaused && _presented != null) {
          setState(_releasePresentedPage);
        }
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
    if ((!_dragPrepared && _trackingOverlap == null && _paperGrab == null) ||
        widget.controller._release?.isCompleted != false) return;
    _shapeChanged |= shapeChanged;
    _scrubCallback ??= SchedulerBinding.instance.scheduleFrameCallback(_sampleDrag);
  }

  void _sampleDrag(Duration timestamp) {
    _scrubCallback = null;
    if (!mounted || (!_dragPrepared && _trackingOverlap == null && _paperGrab == null) ||
        widget.controller._release?.isCompleted != false) return;
    final display = View.of(context).display.refreshRate;
    final limit = widget.refreshRateLimit >= display - .5 ? 0.0 : widget.refreshRateLimit;
    final next = _nextDragTick ?? timestamp;
    if (limit <= 0 || timestamp + const Duration(microseconds: 500) >= next) {
      _paintDrag();
      _nextDragTick = limit > 0
          ? (timestamp - next).inMilliseconds > 40
              ? timestamp + Duration(microseconds: (1000000 / limit).round())
              : next + Duration(microseconds: (1000000 / limit).round())
          : null;
    } else {
      _scrubCallback = SchedulerBinding.instance.scheduleFrameCallback(_sampleDrag);
    }
  }

  void _paintDrag() {
    if (_paperGrab case final grab?) {
      final controller = widget.controller;
      final height = (grab.touchY + controller._touchY - controller._startY).clamp(0.0, 1.0);
      if (grab.overlap case final overlap?) {
        overlap.touchY = height;
        overlap.shape.notify();
      } else {
        _primaryTouchY = height;
        controller._shape.value++;
      }
      grab.clock.value = (grab.progress - controller._displacement).clamp(0.0, 1.0);
      _shapeChanged = false;
      return;
    }
    if (_trackingOverlap case final overlap?) {
      overlap.touchY = widget.controller._touchY;
      overlap.startY = widget.controller._startY;
      overlap.clock.value = overlap.target == null
          ? 0
          : math.min(widget.controller._progress, .85);
      if (_shapeChanged) overlap.shape.notify();
      _shapeChanged = false;
      return;
    }
    _primaryTouchY = widget.controller._touchY;
    if (_shapeChanged) {
      _shapeChanged = false;
      widget.controller._shape.value++;
    }
    final maxProgress = widget.preview != null ? .85 : (_destinationReady ? 1.0 : .92);
    _clock.value = widget.preview != null && _to == null
        ? 0
        : math.min(widget.controller._progress, maxProgress);
  }

  void _finishDrag(bool commit) {
    if (_trackingOverlap case final overlap?) {
      _paintDrag();
      _trackingOverlap = null;
      _nextDragTick = null;
      overlap.finish(commit && overlap.target != null);
      if (!overlap.committed && overlap.clock.value == 0) {
        setState(() => _overlaps.remove(overlap));
        _retireOverlap(overlap);
      }
      _offerNextTurn();
      return;
    }
    if (!_dragPrepared || _settling != null || !mounted) return;
    if (widget.preview != null && _to == null) {
      final available = _neighbors[_dragDirection];
      if (available != null) setState(() => _to = available);
      commit = commit && available != null;
    }
    _handoffTarget = commit ? _to : null;
    _paintDrag();
    _dragPrepared = false;
    _nextDragTick = null;
    final target = commit ? (_destinationReady ? 1.0 : .92) : 0.0;
    _primaryCommit = widget.preview != null && target == 1;
    _acknowledgePrimaryCommit();
    _offerNextTurn();
    _settling = _animatePage(_clock, target,
      widget.preferences.pageSpeed.durationFor(_effect), Curves.easeOutSine);
  }

  void _acknowledgePrimaryCommit() {
    if (!_primaryCommit || _primaryCommitAcknowledged) return;
    _primaryCommitAcknowledged = true;
    widget.onPageTurn?.call();
  }

  // A single turn can finish visually before Readium lands. A subsequent
  // swipe with a missing texture can advance Readium while both sheets move.
  bool get _deferNativeLanding =>
      _effect == ReaderPageEffect.paper ||
      _effect == ReaderPageEffect.staggered;

  @override
  void initState() {
    super.initState();
    widget.controller._owner = this;
    _warmLater();
  }

  @override
  void didUpdateWidget(covariant ReadiumPageSurface oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.layoutKey != widget.layoutKey) {
      _stopReturnAnimation();
      _releaseOverlayCover();
    }
    if (oldWidget.frameKey != widget.frameKey) {
      _warmRetries = 0;
      if (_adoptingPage && oldWidget.layoutKey == widget.layoutKey) {
        _adoptingPage = false;
        _warmLater();
        return;
      }
      if (!_busy && !_warming) {
        _releasePresentedPage();
        _cached?.dispose(); _cached = null; _clearNeighbors();
      }
      _warmLater();
    }
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller._owner = null;
      widget.controller._owner = this;
    }
  }

  (Size, double)? _viewport;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final viewport = (MediaQuery.sizeOf(context), MediaQuery.devicePixelRatioOf(context));
    if (_viewport != viewport) {
      _viewport = viewport;
      _stopReturnAnimation();
      _releasePresentedPage();
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
    int? transitionDirection,
    bool chapterEntrance = false,
  }) async {
    _stopReturnAnimation();
    widget.onPrioritize?.call();
    _preparationInterrupted = true;
    _warmTimer?.cancel();
    await _preparing;
    _adoptingPage = false;
    _clearNeighbors();
    if (_busy || !mounted) return action();
    _busy = true;
    final capturePage = capture ?? widget.capture;
    ReadiumPageFrame? from, target;
    try {
      from = await _takeFrame(capturePage);
      if (from == null && transitionDirection != null && mounted) {
        from = await capturePage();
      }
      if (mounted && from != null) {
        setState(() {
          _releasePresentedPage();
          _from = from;
          _effect = ReaderPageEffect.none;
          _liveDestination = false;
          _clock.value = 0;
        });
        await WidgetsBinding.instance.endOfFrame;
      }
      await action();
      if (!mounted || (!chapterEntrance && (from == null || transitionDirection == null)) ||
          MediaQuery.disableAnimationsOf(context)) return;
      for (var attempt = 0; attempt < 2 && mounted; attempt++) {
        target = await capturePage();
        if (target != null && target.location != from?.location) break;
        target?.dispose();
        target = null;
        if (attempt == 0 && mounted) await WidgetsBinding.instance.endOfFrame;
      }
      if (!mounted) return;
      final prefs = widget.preferences;
      if (chapterEntrance && target != null) {
        _returnClock.duration = prefs.pageSpeed.durationFor(ReaderPageEffect.staggered);
        setState(() {
          _returnFrame = target!.retain();
          _entryEffect = ReaderPageEffect.staggered;
          _entryFromBottom = true;
        });
        await _returnClock.forward(from: 0).orCancel;
        _stopReturnAnimation();
        return;
      }
      if (from == null || transitionDirection == null) return;
      final direction = _visualDirection(transitionDirection);
      final effect = prefs.pageEffect == ReaderPageEffect.none ||
              (target == null && (prefs.pageEffect != ReaderPageEffect.paper || direction < 0))
          ? ReaderPageEffect.fade
          : prefs.pageEffect;
      setState(() {
        _to = target;
        _liveDestination = target == null;
        _effect = effect;
        _direction = direction.toDouble();
      });
      await _clock.animateTo(1, duration: prefs.pageSpeed.durationFor(effect),
        curve: Curves.easeInOutSine).orCancel;
    } on TickerCanceled {
      // Reader closed during the transition.
    } finally {
      if (mounted) {
        setState(() { _from = null; _to = null; _liveDestination = false; });
        await WidgetsBinding.instance.endOfFrame;
      }
      from?.dispose();
      target?.dispose();
      _busy = false;
      _preparationInterrupted = false;
      if (mounted) _warmLater();
    }
  }

  Future<void> _turn(
    int direction,
    bool animated, {
    Completer<_ReadiumRelease>? gesture,
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
      if ((gesture == null || (await gesture.future).commit) && await widget.step(direction, false) && mounted) {
        widget.onPageTurn?.call();
      }
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
        if ((gesture == null || (await gesture.future).commit) && await widget.step(direction, false) && mounted) {
          widget.onPageTurn?.call();
        }
        return;
      }
      setState(() {
        _from = from;
        _to = null;
        _liveDestination = false;
        _destinationReady = false;
        _effect = prefs.pageEffect;
        _direction = _visualDirection(direction);
        _clock.value = 0;
        _primaryTouchY = widget.controller._touchY;
        _primaryStartY = widget.controller._startY;
      });
      _dragPrepared = gesture != null;
      if (gesture != null) _clock.value = math.min(widget.controller._progress, .92);
      if (gesture?.isCompleted == true) _finishDrag((await gesture!.future).commit);
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
        finish = (await gesture.future).commit;
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
      if (committed) widget.onPageTurn?.call();
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
    final image = _pageImage(frame);
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

  CustomPainter _combinedTurn() {
    _ReadiumCompositeTurn? below;
    final vertical = widget.preferences.mode == ReaderMode.vertical;
    for (final turn in _overlaps.reversed) {
      below = _ReadiumCompositeTurn(
        from: turn.from, to: turn.target, clock: turn.clock, effect: _effect,
        direction: _visualDirection(turn.direction),
        vertical: vertical, shape: turn.shape,
        touchY: () => turn.touchY, startY: () => turn.startY, below: below,
      );
    }
    final from = _from;
    if (from == null) return below!;
    return _ReadiumCompositeTurn(
      from: from, to: _to, clock: _clock, effect: _effect, direction: _direction,
      vertical: vertical, shape: widget.controller._shape,
      touchY: () => _primaryTouchY, startY: () => _primaryStartY, below: below,
    );
  }

  bool get _sceneCoversPage =>
      _overlaps.isNotEmpty || (_from != null && !_liveDestination);

  Widget _entry(ReadiumPageFrame frame) {
    final direction = _visualDirection(1);
    if (_entryEffect == ReaderPageEffect.staggered) {
      return CustomPaint(willChange: true, painter: _ReadiumParagraphTurn(
        frame, _returnClock, direction, true,
        !_entryFromBottom && widget.preferences.mode == ReaderMode.vertical,
        enterFromBottom: _entryFromBottom,
        fillPaper: true,
      ));
    }
    if (_entryEffect == ReaderPageEffect.paper) {
      return ColoredBox(color: frame.paper, child: CustomPaint(painter: _ReadiumCurl(
        frame, ReverseAnimation(_returnClock), -direction, () => .78, () => .78,
        widget.controller._shape,
      )));
    }
    if (_entryEffect == ReaderPageEffect.slide) {
      return ColoredBox(color: frame.paper, child: SlideTransition(
        position: _returnClock.drive(Tween(begin: Offset(direction, 0), end: Offset.zero)),
        child: _pageImage(frame),
      ));
    }
    return ColoredBox(
      color: frame.paper,
      child: ReaderSheetMotion(
        animation: _returnClock, effect: _entryEffect, direction: direction,
        incoming: true, front: true, active: true, paper: frame.paper,
        child: _pageImage(frame),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      Opacity(
        opacity: _routeCover != null ||
                (_overlayCover != null && _previewCover == null) || _returnFrame != null ||
                (_sceneCoversPage && widget.preview != null && _previewCover == null)
            ? 0 : 1,
        child: widget.child,
      ),
      if (_presented case final frame? when !_sceneCoversPage && _previewCover == null && _routeCover == null)
        Positioned.fill(child: IgnorePointer(child: _pageImage(frame))),
      if (_routeCover case final cover?)
        Positioned.fill(child: IgnorePointer(child: _pageImage(cover))),
      if (_overlayCover case final cover?)
        Positioned.fill(child: IgnorePointer(child: _pageImage(cover))),
      if (_previewCover case final cover? when !_sceneCoversPage)
        Positioned.fill(child: IgnorePointer(child: _pageImage(cover))),
      if (_overlaps.isNotEmpty ||
          (_from != null && _effect == ReaderPageEffect.staggered && widget.preview != null))
        Positioned.fill(child: IgnorePointer(child: ExcludeSemantics(
          child: RepaintBoundary(child: CustomPaint(willChange: true, painter: _combinedTurn())),
        )))
      else if (_from case final frame?)
        Positioned.fill(
          child: IgnorePointer(
            child: ExcludeSemantics(
              child: ClipRect(
                child: ColoredBox(
                  color: _liveDestination && _to == null
                      ? Colors.transparent
                      : frame.paper,
                  child: RepaintBoundary(child: CustomPaint(
                    child: Stack(
                    fit: StackFit.expand,
                    children: _effect == ReaderPageEffect.paper
                        ? [
                            if (_direction < 0 && _to != null)
                              _pageImage(frame)
                            else if (_to case final target?)
                              _pageImage(target),
                            CustomPaint(
                              willChange: true,
                              painter: _ReadiumCurl(
                                _direction < 0 && _to != null ? _to! : frame,
                                _direction < 0 && _to != null ? ReverseAnimation(_clock) : _clock,
                                1,
                                () => _primaryTouchY,
                                () => _primaryStartY,
                                widget.controller._shape,
                              ),
                            ),
                          ]
                        : _to == null && _liveDestination && _effect == ReaderPageEffect.fade
                        ? [FadeTransition(opacity: ReverseAnimation(_clock), child: _pageImage(frame))]
                        : _to == null
                        ? [_leaf(frame, false)]
                        : _direction < 0 &&
                              (_effect == ReaderPageEffect.cover ||
                                  _effect == ReaderPageEffect.fade)
                        ? [_leaf(_to!, true), _leaf(frame, false)]
                        : [_leaf(frame, false), _leaf(_to!, true)],
                    ),
                    foregroundPainter: _ReadiumRestingPage(frame, _to, _clock),
                  )),
                ),
              ),
            ),
          ),
        ),
      if (widget.onRetainedDrag != null || widget.replayTap != null)
        Positioned.fill(
          child: IgnorePointer(
            ignoring: _previewCover == null &&
                !(_sceneCoversPage && widget.onRetainedDrag != null),
            child: _ReadiumRetainedInput(
              onDrag: widget.onRetainedDrag,
              onTap: widget.replayTap == null ? null : (position) {
                if (_previewCover != null) unawaited(_replayPreviewTap(position));
              },
            ),
          ),
        ),
      if (_returnFrame case final frame?)
        Positioned.fill(key: const ValueKey('readium-return-page'), child: IgnorePointer(child: ExcludeSemantics(
          child: RepaintBoundary(child: ClipRect(child: _entry(frame))),
        ))),
      if (_coverInitialEntry)
        Positioned.fill(
          child: IgnorePointer(
            child: ColoredBox(
              color: widget.paper,
              key: const ValueKey('readium-entry-cover'),
              child: const ReadiumLoadingIndicator(),
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
    if (_scrubCallback case final callback?) SchedulerBinding.instance.cancelFrameCallbackWithId(callback);
    _presented?.dispose();
    _returnFrame?.dispose();
    _overlayCover?.dispose();
    _cached?.dispose();
    _routeCover?.dispose(); _routeCover = null;
    _clearNeighbors();
    widget.controller.endDrag(cancel: true);
    _releaseAbandonedGrab();
    for (final overlap in _overlaps) {
      overlap.abandon();
      overlap.dispose();
    }
    _overlaps.clear();
    _trackingOverlap = null;
    _clock.dispose();
    _returnClock.dispose();
    super.dispose();
  }
}

/// A retained frame may cover a native view that Flutter is no longer painting.
/// Keep this recognizer mounted through the transition back to the live page
/// so a second pointer can finish the gesture it started on the frame.
class _ReadiumRetainedInput extends StatefulWidget {
  const _ReadiumRetainedInput({required this.onDrag, required this.onTap});

  final ValueChanged<Map<String, dynamic>>? onDrag;
  final ValueChanged<Offset>? onTap;

  @override
  State<_ReadiumRetainedInput> createState() => _ReadiumRetainedInputState();
}

class _ReadiumRetainedInputState extends State<_ReadiumRetainedInput> {
  // Match Android's paginated touch slop on the Flutter-owned layer.
  static const _touchSlop = 8.0;
  int? _pointer;
  Offset _origin = Offset.zero;
  Duration _downTime = Duration.zero;
  VelocityTracker? _velocity;
  bool _dragging = false, _interrupted = false;

  void _down(PointerDownEvent event) {
    if (_pointer != null) {
      if (_dragging) _send('Cancel', event);
      _dragging = false;
      _interrupted = true;
      return;
    }
    if (event.buttons != kPrimaryButton) return;
    _pointer = event.pointer;
    _origin = event.localPosition;
    _downTime = event.timeStamp;
    _velocity = VelocityTracker.withKind(event.kind)
      ..addPosition(event.timeStamp, event.localPosition);
    _interrupted = false;
  }

  void _send(String phase, PointerEvent event, {double? velocity}) {
    final height = context.size?.height ?? 0;
    final position = event.localPosition;
    widget.onDrag?.call({
      'phase': phase,
      'dx': position.dx - _origin.dx,
      'y': height > 0
          ? ((phase == 'Start' ? _origin.dy : position.dy) / height).clamp(0.0, 1.0)
          : .5,
      'durationMillis': (event.timeStamp - _downTime).inMilliseconds,
      if (velocity != null) 'velocityX': velocity,
    });
  }

  void _move(PointerEvent event) {
    if (event.pointer != _pointer || _interrupted) return;
    _velocity?.addPosition(event.timeStamp, event.localPosition);
    if (!_dragging && widget.onDrag != null &&
        (event.localPosition.dx - _origin.dx).abs() > _touchSlop) {
      _dragging = true;
      _send('Start', event);
    } else if (_dragging) {
      _send('Move', event);
    }
  }

  void _end(PointerEvent event) {
    if (event.pointer != _pointer) return;
    if (event is PointerCancelEvent) {
      if (_dragging) _send('Cancel', event);
    } else if (!_interrupted) {
      _move(event);
      if (_dragging) {
        _send('End', event, velocity: _velocity?.getVelocity().pixelsPerSecond.dx);
      } else if ((event.localPosition - _origin).distance <= _touchSlop &&
          event.timeStamp - _downTime < kLongPressTimeout) {
        widget.onTap?.call(event.localPosition);
      }
    }
    _pointer = null;
    _velocity = null;
    _dragging = false;
  }

  @override
  Widget build(BuildContext context) => Listener(
    behavior: HitTestBehavior.opaque,
    onPointerDown: _down,
    onPointerMove: _move,
    onPointerUp: _end,
    onPointerCancel: _end,
  );
}

void _drawReadiumPage(Canvas canvas, Size size, ReadiumPageFrame frame) {
  final image = frame.image;
  canvas.drawImageRect(image,
    Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
    Offset.zero & size, Paint()..filterQuality = frame.filterQuality);
}

/// A turn paints the next turn in its destination's place. Earlier sheets
/// continue to cover the newer ones as their own independent clocks advance.
class _ReadiumCompositeTurn extends CustomPainter {
  _ReadiumCompositeTurn({
    required this.from, required this.to, required this.clock,
    required this.effect, required this.direction, required bool vertical,
    required Listenable shape, required double Function() touchY,
    required double Function() startY, required this.below,
  }) : paperClock = direction < 0 ? ReverseAnimation(clock) : clock,
       super(repaint: Listenable.merge([clock, shape, below])) {
    if (effect == ReaderPageEffect.staggered) {
      _outgoing = _ReadiumParagraphTurn(from, clock, direction, false, vertical);
      if (to != null) _incoming = _ReadiumParagraphTurn(to!, clock, direction, true, vertical);
    }
    if (effect == ReaderPageEffect.paper) {
      // The reverse turn unfolds an incoming sheet. Page readiness changes its
      // texture, so the same fold geometry applies throughout the handoff.
      final reverse = direction < 0;
      _curl = _ReadiumCurl(reverse ? to ?? from : from,
        paperClock, 1, touchY, startY, shape, textured: !reverse || to != null);
    }
  }

  final ReadiumPageFrame from;
  final ReadiumPageFrame? to;
  final Animation<double> clock;
  final Animation<double> paperClock;
  final ReaderPageEffect effect;
  final double direction;
  final _ReadiumCompositeTurn? below;
  _ReadiumParagraphTurn? _outgoing, _incoming;
  _ReadiumCurl? _curl;

  void _destination(Canvas canvas, Size size) {
    if (below case final turn?) {
      turn.paint(canvas, size);
    } else if (to case final target?) {
      _drawReadiumPage(canvas, size, target);
    } else {
      _drawReadiumPage(canvas, size, from);
    }
  }

  void _origin(Canvas canvas, Size size) => _drawReadiumPage(canvas, size, from);

  @override
  void paint(Canvas canvas, Size size) {
    final bounds = Offset.zero & size;
    final progress = clock.value;
    canvas.save();
    canvas.clipRect(bounds, doAntiAlias: false);
    if (progress <= 0) {
      _drawReadiumPage(canvas, size, from);
    } else if (progress >= 1) {
      _destination(canvas, size);
    } else {
      switch (effect) {
        case ReaderPageEffect.paper:
          if (direction < 0) {
            _curl!.paintSheet(canvas, size,
              paintFlat: below == null ? null : _destination,
              paintUnderlay: _origin,
            );
          } else {
            _curl!.paintSheet(canvas, size, paintUnderlay: _destination);
          }
        case ReaderPageEffect.staggered:
          canvas.drawRect(bounds, Paint()..color = from.paper);
          _outgoing!.paint(canvas, size);
          if (below case final turn? when _incoming != null) {
            // Expose the next turn through the moving bands in one pass.
            // Its content follows its own clock in screen coordinates.
            canvas.save();
            canvas.clipPath(_incoming!.visibleBands(size), doAntiAlias: false);
            turn.paint(canvas, size);
            canvas.restore();
          } else {
            _incoming?.paint(canvas, size);
          }
        case ReaderPageEffect.slide:
        case ReaderPageEffect.cover:
          final slide = effect == ReaderPageEffect.slide;
          final travel = size.width * direction;
          if (!slide && direction < 0) _destination(canvas, size);
          canvas.save();
          if (slide || direction < 0) canvas.translate(-progress * travel, 0);
          _drawReadiumPage(canvas, size, from);
          canvas.restore();
          if (slide || direction > 0) {
            canvas.translate((1 - progress) * travel, 0);
            _destination(canvas, size);
          }
        case ReaderPageEffect.fade:
          _drawReadiumPage(canvas, size, from);
          canvas.saveLayer(bounds, Paint()..color = Color.fromRGBO(255, 255, 255, progress));
          _destination(canvas, size);
          canvas.restore();
        case ReaderPageEffect.none:
          _destination(canvas, size);
      }
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _ReadiumCompositeTurn old) => true;
}

class _ReadiumRestingPage extends CustomPainter {
  _ReadiumRestingPage(this.from, this.to, this.clock) : super(repaint: clock);
  final ReadiumPageFrame from;
  final ReadiumPageFrame? to;
  final Animation<double> clock;

  @override
  void paint(Canvas canvas, Size size) {
    final frame = clock.value == 0 ? from : clock.value == 1 ? to : null;
    if (frame == null) return;
    _drawReadiumPage(canvas, size, frame);
  }

  @override
  bool shouldRepaint(covariant _ReadiumRestingPage old) =>
      old.from != from || old.to != to || old.clock != clock;
}

class _ReadiumParagraphTurn extends CustomPainter {
  _ReadiumParagraphTurn(
    this.frame,
    this.clock,
    this.direction,
    this.incoming,
    this.vertical, {
    this.enterFromBottom = false,
    this.fillPaper = false,
  }) : super(repaint: clock);

  final ReadiumPageFrame frame;
  final Animation<double> clock;
  final double direction;
  final bool incoming, vertical, enterFromBottom;
  final bool fillPaper;
  Size? _size;
  late List<({Rect source, Interval timing})> _parts;
  late double _scaleX, _scaleY;
  late Float32List _transforms, _sources;
  final _mask = Path();
  late final _ink = Paint()..filterQuality = frame.filterQuality;
  final _paper = Paint();

  void _layout(Size size) {
    if (_size == size) return;
    _size = size;
    _scaleX = size.width / frame.image.width;
    _scaleY = size.height / frame.image.height;
    const relayCurve = Cubic(.22, .1, .28, 1);
    final motion = ReaderTurnScope(
      animation: clock,
      direction: direction,
      outgoing: !incoming,
      relay: true,
      relayCurve: relayCurve,
      vertical: vertical,
      leafSize: size,
      child: const SizedBox(),
    );
    final bands = enterFromBottom && frame.returnBottoms.isNotEmpty
        ? [
            for (var i = 0; i < frame.returnBottoms.length; i++)
              Rect.fromLTRB(0, i == 0 ? 0 : frame.returnBottoms[i - 1], 1, frame.returnBottoms[i]),
          ]
        : frame.bands.isEmpty || enterFromBottom
        ? [const Rect.fromLTRB(0, 0, 1, 1)]
        : frame.bands;
    Interval timingFor(int index) {
      final band = bands[index];
      if (!enterFromBottom && !vertical && bands.length > 1) {
        // Nearby short paragraphs still need distinct phases. Keep a spatial
        // component so long paragraphs follow their position on the page.
        final order = index / (bands.length - 1);
        final position = incoming ? band.bottom : band.top;
        final start = .02 + .22 * (.55 * position + .45 * order);
        return Interval(start, start + .75, curve: relayCurve);
      }
      return motion.relayIntervalFor(index, bands.length, bounds: Rect.fromLTRB(
        band.left * size.width, band.top * size.height,
        band.right * size.width, band.bottom * size.height,
      ));
    }
    _parts = [
      for (var index = 0; index < bands.length; index++)
        (
          source: Rect.fromLTRB(
            (bands[index].left * frame.image.width).roundToDouble(),
            (bands[index].top * frame.image.height).roundToDouble(),
            (bands[index].right * frame.image.width).roundToDouble(),
            (bands[index].bottom * frame.image.height).roundToDouble(),
          ),
          timing: timingFor(index),
        ),
    ];
    _transforms = Float32List(_parts.length * 4);
    _sources = Float32List(_parts.length * 4);
  }

  double _travel(Interval timing, double progress) {
    final fraction = timing.transform(progress);
    final distance = (incoming ? 1 - fraction : -fraction) * (enterFromBottom ? 1 : direction);
    return (distance * (enterFromBottom ? frame.image.height / 2 : frame.image.width)).roundToDouble();
  }

  Path visibleBands(Size size) {
    _layout(size);
    _mask.reset();
    final progress = clock.value;
    final viewport = Offset.zero & size;
    for (final part in _parts) {
      final source = part.source;
      final dx = _travel(part.timing, progress);
      final destination = Rect.fromLTRB(
        (source.left + dx) * _scaleX, source.top * _scaleY,
        (source.right + dx) * _scaleX, source.bottom * _scaleY,
      ).intersect(viewport);
      if (!destination.isEmpty) _mask.addRect(destination);
    }
    return _mask;
  }

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final progress = clock.value;
    if (fillPaper) {
      if (progress >= 1) {
        _drawReadiumPage(canvas, size, frame);
        return;
      }
      canvas.drawRect(Offset.zero & size, _paper..color = frame.paper);
    }
    _layout(size);
    var used = 0;
    for (final part in _parts) {
      final source = part.source;
      final travel = _travel(part.timing, progress);
      final x = source.left + (enterFromBottom ? 0 : travel);
      final y = source.top + (enterFromBottom ? travel : 0);
      if (x >= frame.image.width || x + source.width <= 0 ||
          y >= frame.image.height || y + source.height <= 0) continue;
      _sources[used] = source.left;
      _sources[used + 1] = source.top;
      _sources[used + 2] = source.right;
      _sources[used + 3] = source.bottom;
      _transforms[used] = 1;
      _transforms[used + 2] = x;
      _transforms[used + 3] = y;
      used += 4;
    }
    if (used == 0) return;
    canvas.save();
    if (fillPaper) canvas.clipRect(Offset.zero & size, doAntiAlias: false);
    canvas.scale(_scaleX, _scaleY);
    canvas.drawRawAtlas(frame.image, Float32List.sublistView(_transforms, 0, used),
        Float32List.sublistView(_sources, 0, used), null, null, null, _ink);
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _ReadiumParagraphTurn old) =>
      old.clock != clock ||
      old.frame != frame ||
      old.incoming != incoming ||
      old.direction != direction ||
      old.vertical != vertical ||
      old.enterFromBottom != enterFromBottom || old.fillPaper != fillPaper;
}

/// Textured fold around a tilted cylindrical axis. One image shader and three
/// mesh draws replace per-strip image draws; the flat page stays pixel-stable.
class _ReadiumCurl extends CustomPainter {
  _ReadiumCurl(this.from, this.clock, this.direction, this.touchY, this.startY, Listenable shape, {
    this.textured = true,
  })
    : super(repaint: Listenable.merge([clock, shape]));
  final ReadiumPageFrame from;
  final Animation<double> clock;
  final double direction;
  final double Function() touchY, startY;
  final bool textured;
  static const segments = 24, capacity = 192;
  final _positions = Float32List(capacity * 2), _texture = Float32List(capacity * 2);
  final _frontColors = Int32List(capacity), _backColors = Int32List(capacity), _ghostColors = Int32List(capacity);
  final _frontIndices = Uint16List(capacity * 3), _backIndices = Uint16List(capacity * 3);

  @override
  void paint(Canvas c, Size size) => paintSheet(c, size);

  void paintSheet(Canvas c, Size size, {
    void Function(Canvas, Size)? paintFlat,
    void Function(Canvas, Size)? paintUnderlay,
  }) {
    final t = clock.value.clamp(0.0, 1.0);
    if (t >= 1) {
      paintUnderlay?.call(c, size);
      return;
    }
    if (t <= 0) {
      if (paintFlat == null) {
        _paintPageFace(c, size);
      } else {
        paintFlat(c, size);
      }
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
    if (paintUnderlay != null) {
      // The opaque flat sheet covers this part of the underlying texture.
      // Only rasterize the next page where the curling sheet exposes it.
      c.save();
      c.clipPath(Path()..addPolygon(clip(page, 0, true), true), doAntiAlias: false);
      if (backwards) { c.translate(w, 0); c.scale(-1, 1); }
      paintUnderlay(c, size);
      c.restore();
    }
    c.save();c.clipPath(flat, doAntiAlias: paintUnderlay == null);
    if(backwards){c.translate(w,0);c.scale(-1,1);}
    if (paintFlat == null) {
      _paintPageFace(c, size);
    } else {
      paintFlat(c, size);
    }
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
    mesh(_frontIndices,fronts,textured ? _frontColors : _backColors,textured);
    mesh(_backIndices,backs,_backColors,false);
    if (textured) mesh(_backIndices,backs,_ghostColors,true);
    c.restore();
  }

  void _paintPageFace(Canvas canvas, Size size) {
    if (textured) {
      _drawReadiumPage(canvas, size, from);
    } else {
      canvas.drawRect(Offset.zero & size, Paint()..color = from.paper);
    }
  }

  @override
  bool shouldRepaint(covariant _ReadiumCurl old) =>
      old.from != from || old.direction != direction || old.textured != textured || old.clock != clock;
}
