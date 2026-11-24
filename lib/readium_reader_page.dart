import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_readium/flutter_readium.dart' as rd;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:html/parser.dart' as html;
import 'catalog.dart';
import 'frame_rate_settings.dart';
import 'local_books.dart';
import 'reader_preferences.dart';
import 'readium_page_turn.dart';

class _PendingReaderDrag {
  _PendingReaderDrag(this.start);
  final Map<String, dynamic> start;
  final clock = Stopwatch()..start();
  Map<String, dynamic>? latest;
  double? velocity;
  int _lastMove = 0;

  void update(Map<String, dynamic> event) {
    final dx = (event['dx'] as num).toDouble();
    final lastDx = ((latest ?? start)['dx'] as num).toDouble();
    final now = clock.elapsedMicroseconds;
    if (dx != lastDx && now > _lastMove) {
      velocity = (dx - lastDx) * 1000000 / (now - _lastMove);
      _lastMove = now;
    } else if (now - _lastMove > 100000) {
      velocity = 0;
    }
    latest = event;
  }
}

/// Engine adapter only. ReaderPage retains every Flutter control and panel.
class ReadiumReaderBridge extends ChangeNotifier {
  ReadiumReaderBridge({
    required this.book,
    required this.books,
    required this.initialChapter,
    this.restoreLocator = true,
    required this.onLocation,
    required this.onPage,
    required this.onAction,
    required this.onTap,
    required this.onUserNavigation,
    required this.onAnnotation,
    required this.onSelection,
    required this.onSelectionEnd,
    required bool chromeVisible,
  }) {
    controls = ValueNotifier(chromeVisible);
    native.setMethodCallHandler((call) async {
      final owner = _owner;
      if (owner == null || owner._disposed) return;
      if (call.method == 'selectionEnded') {
        final owner = _owner;
        if (owner != null) {
          owner._selectionTicket++;
          owner.onSelectionEnd();
        }
      }
    });
    chapter = initialChapter;
    opening = _open();
    if (Platform.isAndroid) {
      FrameRateSettings.shared.addListener(_frameRateChanged);
      unawaited(FrameRateSettings.shared.load());
    }
  }
  double _refreshRateLimit = 0;
  void _frameRateChanged() {
    final rate = FrameRateSettings.shared.info?.target ?? 0;
    if (_disposed || rate == _refreshRateLimit) return;
    _refreshRateLimit = rate;
    notifyListeners();
  }

  Novel book;
  final LocalBookStore books;
  final int initialChapter;
  final bool restoreLocator;
  bool get hasSavedLocator => _hasSavedLocator;
  bool _hasSavedLocator = false;
  static ReadiumReaderBridge? _owner;
  static Future<void> _lifecycle = Future.value();
  var _firstLocation = Completer<void>();
  bool _suspended = false;
  bool _openedOnce = false;
  rd.EPUBPreferences _preferences = const rd.EPUBPreferences(
    publisherStyles: true,
  );
  final void Function(int chapter, double fraction, String? text) onLocation;
  final ValueChanged<int> onPage;
  final void Function(String action, String text) onAction;
  final ValueChanged<Offset> onTap;
  final VoidCallback onUserNavigation;
  final ValueChanged<String> onAnnotation;
  final void Function(String text, Rect bounds) onSelection;
  final VoidCallback onSelectionEnd;
  final _viewKey = GlobalKey();
  int _selectionTicket = 0;
  Future<void> clearSelection() async {
    if (Platform.isAndroid && _owner == this)
      await native.invokeMethod<void>('clearSelection');
  }

  Future<void> _selected(rd.TextSelectionEvent event) async {
    selectedLocator = event.locator;
    _seekTicket++;
    onUserNavigation();
    if (!Platform.isAndroid) return;
    final ticket = ++_selectionTicket;
    final raw = await native.invokeMethod<String>('evaluate', {
      'script':
          '(function(){var s=window.getSelection();if(!s||!s.rangeCount||s.isCollapsed)return null;var r=s.getRangeAt(0).getBoundingClientRect();return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height,v:window.innerWidth});})()',
    });
    if (_disposed || ticket != _selectionTicket || raw == null) return;
    Object? decoded = jsonDecode(raw);
    if (decoded is String) decoded = jsonDecode(decoded);
    final box = _viewKey.currentContext?.findRenderObject();
    if (decoded is! Map || box is! RenderBox || !box.hasSize) return;
    final scale = box.size.width / (decoded['v'] as num).toDouble();
    final bounds = Rect.fromLTWH(
      (decoded['x'] as num).toDouble() * scale,
      (decoded['y'] as num).toDouble() * scale,
      (decoded['w'] as num).toDouble() * scale,
      (decoded['h'] as num).toDouble() * scale,
    ).shift(box.localToGlobal(Offset.zero));
    if (bounds.isEmpty) return;
    onSelection(
      event.selectedText ?? event.locator.text?.highlight ?? '',
      bounds,
    );
  }

  bool _previewing = false, _landing = false;
  int _previewEpoch = 0;
  void _interruptPreview() {
    _previewEpoch++;
    if (Platform.isAndroid && _previewing) {
      unawaited(native.invokeMethod<void>('interruptPreview').catchError((Object _) {}));
    }
  }
  bool _dragInFlight = false, _receivingDrag = false;
  rd.Locator? _dragLocation;
  _PendingReaderDrag? _pendingDrag;
  ({int direction, bool animated})? _pendingTap;

  void _pageDrag(Map<String, dynamic> event) {
    if (_disposed || _owner != this) return;
    if (event['phase'] == 'Start') {
      if (!ready) return;
      if (_pagePending) {
        _pendingDrag = _PendingReaderDrag(event);
        _pendingTap = null;
      } else {
        _startPageDrag(event);
      }
      return;
    }
    if (_pendingDrag case final pending?) {
      pending.update(event);
    } else if (_receivingDrag) {
      _updatePageDrag(event);
    }
  }

  void _startPageDrag(Map<String, dynamic> event) {
    final dx = (event['dx'] as num).toDouble();
    final box = _viewKey.currentContext?.findRenderObject();
    final width = box is RenderBox && box.hasSize ? box.size.width : 400.0;
    final rtl = _preferences.verticalText == true ||
        publication?.metadata.readingProgression == rd.ReadingProgression.rtl;
    final direction = (dx < 0 ? 1 : -1) * (rtl ? -1 : 1);
    turns.requestTurn(direction);
    _pagePending = true;
    _pageIdle ??= Completer<void>();
    _dragInFlight = _receivingDrag = true;
    _dragLocation = null;
    onUserNavigation();
    turns.beginDrag(dx, width, (event['y'] as num?)?.toDouble() ?? .5);
    unawaited(command(() => turns.drag(direction), waitForPreparation: false).whenComplete(() {
      _dragInFlight = _receivingDrag = false;
      final at = _dragLocation;
      if (at != null && !_disposed && _owner == this) _located(at);
      _completePageRequest();
    }));
  }

  void _updatePageDrag(Map<String, dynamic> event, {double? releaseVelocity}) {
    final phase = event['phase'];
    if (phase != 'Cancel') {
      turns.updateDrag((event['dx'] as num).toDouble(), touchY: (event['y'] as num?)?.toDouble());
    }
    if (phase == 'End' || phase == 'Cancel') {
      _receivingDrag = false;
      turns.endDrag(cancel: phase == 'Cancel', releaseVelocity: releaseVelocity);
    }
  }

  void _completePageRequest() {
    _pagePending = false;
    final drag = _pendingDrag, tap = _pendingTap;
    _pendingDrag = null;
    _pendingTap = null;
    if (!_disposed && _owner == this && ready) {
      if (drag != null && drag.latest?['phase'] != 'Cancel') {
        _startPageDrag(drag.start);
        if (drag.latest case final event?) _updatePageDrag(event, releaseVelocity: drag.velocity);
        return;
      }
      if (tap != null) {
        unawaited(pageBy(tap.direction, animated: tap.animated));
        return;
      }
    }
    _pageIdle?.complete();
    _pageIdle = null;
  }

  rd.Locator? selectedLocator;
  final progress = ValueNotifier<double>(0);
  final api = rd.FlutterReadium();
  final turns = ReadiumPageTurnController();
  ReaderPreferences _appearance = const ReaderPreferences();
  bool _dark = false;
  Color? _pagePaper;
  Color get pagePaper => _pagePaper ?? _appearance.background(_dark);
  void motionPreferences(ReaderPreferences preferences, bool dark) {
    _appearance = preferences;
    _dark = dark;
  }

  late final ValueNotifier<bool> controls;
  late Future<void> opening;
  rd.Publication? publication;
  rd.Locator? initialLocator, locator;
  StreamSubscription<rd.Locator>? _events;
  Directory? _staging;
  SharedPreferences? _prefs;
  String? error;
  bool _disposed = false;
  bool _navigatorReady = false, _styleReady = false;
  bool get ready => _navigatorReady && _styleReady && !_navigating;
  bool? _fixedLayout;
  String? _appliedStyleSignature;
  int chapter = 0;
  double fraction = 0;
  bool _scrollAtEnd = false;
  bool get atResourceEnd {
    if (_preferences.scroll == true) return _scrollAtEnd;
    final locations = locator?.locations?.toJson();
    final page = locations?['currentPage'], count = locations?['totalPages'];
    return page is num && count is num && count > 0 && page >= count - 1;
  }

  bool get atBookEnd =>
      atResourceEnd &&
      publication?.readingOrder.lastOrNull?.href.split('#').first ==
          locator?.href.split('#').first;
  Future<void> _saves = Future.value();
  Future<void> _commands = Future.value();
  static const positionExtent = 100000.0;
  int _locationTicket = 0;
  static const native = MethodChannel('org.mewx.wenku8/readium_controls');
  String get _key => 'readium.locator.v1.${book.id}';
  rd.Link? linkForChapter(int index) {
    final pub = publication;
    if (pub == null || index < 0 || index >= book.chapters.length) return null;
    final id = book.chapters[index].id;
    final origin =
        books.readiumChapterHref(book.id, index) ??
        book.navigation?.targets.entries
            .where((e) => e.value.chapterId == id)
            .map((e) => e.key)
            .firstOrNull;
    if (origin != null && pub.linkWithHref(origin) != null)
      return rd.Link(href: origin, title: book.chapters[index].title);
    final names = {
      book.chapters[index].title,
      ...?book.navigation?.entries
          .where((e) => e.target.chapterId == id)
          .map((e) => e.title),
    };
    return pub.tocFlattened.where((l) => names.contains(l.title)).firstOrNull;
  }

  String get chapterTitle {
    final tocHref = locator?.locations?.toJson()['tocHref'];
    final title = publication?.tocFlattened
        .where((l) => l.href == tocHref)
        .firstOrNull
        ?.title;
    if (title != null && title.trim().isNotEmpty) return title.trim();
    return book.chapters[chapter.clamp(0, book.chapters.length - 1)].title;
  }

  Future<List<String>> visibleImages() async {
    if (!Platform.isAndroid || _disposed) return const [];
    final raw = await native.invokeMethod<String>('evaluate', {'script':
      "(function(){return JSON.stringify(Array.from(document.querySelectorAll('img,image')).filter(function(i){var r=i.getBoundingClientRect();return r.right>0&&r.left<innerWidth&&r.bottom>0&&r.top<innerHeight;}).slice(0,2).map(function(i){return new URL(i.currentSrc||(typeof i.src==='string'?i.src:null)||(i.href&&i.href.baseVal)||'',document.baseURI).href;}));})()"});
    if (raw == null || raw == 'null') return const [];
    Object? decoded = jsonDecode(raw);
    if (decoded is String) decoded = jsonDecode(decoded);
    if (decoded is! List) return const [];
    final paths = decoded.whereType<String>();
    final links = [...?publication?.resources, ...?publication?.readingOrder];
    return [for (final source in paths)
      ...links.where((link) {
        try { return ('/'+Uri.parse(source).pathSegments.join('/')).endsWith('/'+Uri.parse(link.href).pathSegments.join('/')); }
        catch (_) { return false; }
      }).take(1).map((link)=>link.href)];
  }

  bool get turnBusy => _pagePending || _previewing;
  Completer<void>? _pageIdle;
  Future<void> get whenTurnIdle => _pageIdle?.future ?? Future<void>.value();
  Future<void> get whenPageIdle async {
    await _pageIdle?.future;
    await turns.whenPrepared;
  }

  Future<Uint8List> imageBytes(String href) async {
    if (href.startsWith("data:image/") && !href.startsWith("data:image/svg"))
      return Uri.parse(href).data!.contentAsBytes();
    if (href.toLowerCase().split('?').first.endsWith('.svg') &&
        Platform.isAndroid) {
      final path = jsonEncode(href);
      final raw = await native.invokeMethod<String>('evaluate', {
        'script':
            '''(function(){
        var path=$path;
        var img=Array.from(document.images).find(i=>{try{return decodeURI(new URL(i.currentSrc||i.src).pathname).endsWith('/'+decodeURI(path));}catch(e){return false;}});
        if(!img||!img.complete||!img.naturalWidth)return null;
        var ratio=Math.min(1,2048/Math.max(img.naturalWidth,img.naturalHeight));
        var c=document.createElement('canvas');c.width=Math.max(1,Math.round(img.naturalWidth*ratio));c.height=Math.max(1,Math.round(img.naturalHeight*ratio));
        c.getContext('2d').drawImage(img,0,0,c.width,c.height);return c.toDataURL('image/png').split(',')[1];
      })()''',
      });
      if (raw != null && raw != 'null') {
        final value = jsonDecode(raw);
        if (value is String) return base64Decode(value);
      }
    }
    final url = Uri.parse(await api.getResourceUrl(href));
    if (url.scheme != 'file') throw StateError('原书插图未能缓存到本地');
    return File.fromUri(url).readAsBytes();
  }

  int chapterFor(rd.Locator at) {
    final pub = publication;
    if (pub == null) return chapter;
    final href = at.href.split('#').first;
    final tocHref = at.locations?.toJson()['tocHref'] as String?;
    if (tocHref != null) {
      final target = book.navigation?.targets[tocHref];
      if (target != null) {
        final i = book.chapters.indexWhere((c) => c.id == target.chapterId);
        if (i >= 0) return i;
      }
    }
    final candidates = <int>[];
    for (var i = 0; i < book.chapters.length; i++) {
      final link = linkForChapter(i);
      if (link != null && link.href.split('#').first == href) candidates.add(i);
    }
    if (candidates.length == 1) return candidates.single;
    if (candidates.contains(chapter)) return chapter;
    if (candidates.isNotEmpty) return candidates.first;
    final matches = pub.tocFlattened.where(
      (l) => l.href.split('#').first == href,
    );
    for (final link in matches) {
      for (final entry
          in book.navigation?.entries ?? const <BookNavigationEntry>[]) {
        if (entry.title == link.title) {
          final i = book.chapters.indexWhere(
            (c) => c.id == entry.target.chapterId,
          );
          if (i >= 0) return i;
        }
      }
      final i = book.chapters.indexWhere((c) => c.title == link.title);
      if (i >= 0) return i;
    }
    return chapter;
  }

  void activate() {
    if (_disposed || !_suspended) return;
    _suspended = false;
    _styleReady = false;
    _navigatorReady = false;
    _layoutAnchor = null;
    _appliedDecorationKeys.clear();
    _firstLocation = Completer<void>();
    initialLocator = null;
    _commands = Future.value();
    opening = _open();
    notifyListeners();
  }

  Future<void> _open() async {
    try {
      _prefs = await SharedPreferences.getInstance();
      _staging ??= await (await getTemporaryDirectory()).createTemp(
        'readium-render-',
      );
      final file = File('${_staging!.path}/publication.epub');
      if (!await file.exists()) await books.copyEpubForReadium(book.id, file);
      if (_disposed) return;
      final acquire = _lifecycle.catchError((Object _) {}).then((_) async {
        if (_disposed) return;
        final previous = _owner;
        if (previous != null) {
          await previous._events?.cancel();
          if (!previous._firstLocation.isCompleted)
            previous._firstLocation.complete();
          await previous._commands;
          await api.closePublication();
          previous.publication = null;
          previous._navigatorReady = false;
          previous._suspended = true;
          if (!previous._disposed) previous.notifyListeners();
        }
        _owner = this;
        api.setDefaultPreferences(_preferences);
        publication = await api.openPublication(file.uri.toString());
      });
      _lifecycle = acquire.catchError((Object _) {});
      await acquire;
      if (_disposed || _owner != this) return;
      final raw = _prefs!.getString(_key);
      if ((restoreLocator || _openedOnce) && raw != null) {
        try {
          final saved = rd.Locator.fromJson(
            jsonDecode(raw) as Map<String, dynamic>,
          );
          if (saved != null && chapterFor(saved) == chapter) {
            initialLocator = saved;
            _hasSavedLocator = true;
          }
        } catch (_) {}
      }
      final first = linkForChapter(chapter);
      _openedOnce = true;
      if (initialLocator == null && first != null)
        initialLocator = publication!.locatorFromLink(first);
      _events = api.onTextLocatorChanged.listen((at) {
        if (!_navigating) unawaited(_receiveLocator(at));
      });
      notifyListeners();
    } catch (e) {
      error = 'EPUB 打开失败：$e';
      if (!_disposed) notifyListeners();
    }
  }

  String? _stableVisualKey;
  int _nativeEventTicket = 0;
  Future<void> _receiveLocator(rd.Locator at) async {
    if (_disposed || _owner != this || _previewing || _landing || _navigating) return;
    if (!Platform.isAndroid || !_navigatorReady) { _located(at); return; }
    final ticket = ++_nativeEventTicket;
    try {
      final page = await native.invokeMapMethod<String, dynamic>('visualPage');
      if (_disposed || ticket != _nativeEventTicket || _previewing || _landing || _navigating || page == null) return;
      final key = page['key'] as String?;
      final url = page['url'] as String?;
      // A delayed SDK event is not a new rendered page. It may describe an old
      // preview, or the same last page using progression 1 instead of its start.
      if (url != null && !('/'+Uri.parse(url).pathSegments.join('/')).endsWith('/'+Uri.parse(at.href).pathSegments.join('/'))) return;
      if (key != null && key == _stableVisualKey && locator?.href == at.href && _appearance.mode.paginated) return;
      _stableVisualKey = key;
      _located(at);
    } catch (_) {
      if (!_disposed && ticket == _nativeEventTicket && !_previewing && !_landing && !_navigating) _located(at);
    }
  }

  bool _navigating = false;
  void _located(rd.Locator at) {
    final locationTicket = ++_locationTicket;
    if (_disposed || _owner != this || _previewing || _landing) return;
    if (!_firstLocation.isCompleted) _firstLocation.complete();
    if (_dragInFlight) {
      _dragLocation = at;
      return;
    }
    final sameResource = locator?.href == at.href;
    if (!sameResource) _scrollAtEnd = false;
    locator = at;
    chapter = chapterFor(at);
    fraction = at.locations?.progression ?? (sameResource ? fraction : 0);
    _navigatorReady = true;
    progress.value = fraction;
    onLocation(chapter, fraction, at.text?.highlight);
    if ((at.text?.highlight ?? '').trim().isEmpty &&
        Platform.isAndroid &&
        at.locations?.cssSelector != null) {
      unawaited(
        () async {
          final selector = jsonEncode(at.locations!.cssSelector);
          final raw = await native.invokeMethod<String>('evaluate', {
            'script':
                '(function(){var e=document.querySelector($selector);if(!e)return null;var c=e.cloneNode(true);c.querySelectorAll("rt,script,style").forEach(function(n){n.remove();});return (c.textContent||"").trim().slice(0,500);})()',
          });
          if (_disposed || locationTicket != _locationTicket || raw == null)
            return;
          final text = jsonDecode(raw);
          if (text is String && text.trim().isNotEmpty)
            onLocation(chapter, fraction, text);
        }().catchError((Object _) {}),
      );
    }
    persist();
    notifyListeners();
  }

  Future<void> _navigate(rd.Locator at, {int? transitionDirection, bool seek = false}) async {
    _navigating = Platform.isAndroid;
    _nativeEventTicket++;
    try {
      await turns.navigate(() async {
        if (!Platform.isAndroid) {
          await api.goToLocator(at);
          return;
        }
        final moved = await native.invokeMethod<bool>('jump', {
          'locator': jsonEncode(at.toJson()),
          'seek': seek,
        });
        if (moved != true) throw StateError('原书位置未能打开');
        await _waitForPageReady(stable: true);
        final raw = await native.invokeMethod<String>('locator');
        if (raw != null && !_disposed && _owner == this) {
          final visible = rd.Locator.fromJson(jsonDecode(raw) as Map<String, dynamic>);
          final page = await native.invokeMapMethod<String, dynamic>('visualPage');
          _stableVisualKey = page?['key'] as String?;
          if (visible != null) _located(visible);
        }
      }, capture: () => _capturePage(allowNavigating: true),
        transitionDirection: transitionDirection);
    } finally {
      _navigating = false;
      if (!_disposed) notifyListeners();
    }
  }

  Future<void> seekProgress(double fraction) {
    final ticket = ++_seekTicket;
    _interruptPreview();
    return command(() async {
      final current = locator;
      if (ticket != _seekTicket || current == null) return;
      final json = current.toJson()
        ..remove('text')
        ..['locations'] = {'progression': fraction.clamp(0.0, 1.0)};
      final requested = rd.Locator.fromJson(json);
      if (requested != null) await _navigate(requested, seek: true);
    }, waitForPreparation: false);
  }

  Future<void> command(
    Future<void> Function() action, {
    bool waitForView = true,
    bool waitForPreparation = true,
  }) {
    final next = _commands.catchError((Object _) {}).then((_) async {
      await opening;
      if (_disposed || publication == null || _owner != this) return;
      if (waitForView) await _firstLocation.future;
      if (_disposed || _owner != this) return;
      if (waitForPreparation) await turns.whenPrepared;
      await action();
      error = null;
    });
    _commands = next.catchError((Object e) {
      error = '阅读操作失败：$e';
      if (!_disposed) notifyListeners();
    });
    return _commands;
  }

  bool _pagePending = false;
  Future<void> pageBy(int direction, {bool animated = true}) async {
    if (_disposed || _navigating) return;
    if (_pagePending) {
      if (_pendingDrag == null) _pendingTap = (direction: direction, animated: animated);
      return;
    }
    turns.requestTurn(direction);
    _pagePending = true;
    _pageIdle ??= Completer<void>();
    try {
      await command(() => turns.turn(direction, animated: animated), waitForPreparation: false);
    } finally {
      _completePageRequest();
    }
  }

  Future<bool> _canTurnPage(int direction) async {
    if (!Platform.isAndroid) return true;
    final allowed = await native.invokeMethod<bool>('canTurn', {'direction': direction}) ?? true;
    if (!allowed && !_disposed) onAction('boundary', direction < 0 ? '已到书籍首页' : '已到书籍末页');
    return allowed;
  }

  Future<bool> _stepPage(int direction, bool animated) async {
    if (_disposed || _owner != this) return false;
    if (Platform.isAndroid) {
      final previousHref = (_dragLocation ?? locator)?.href;
      final result = await native.invokeMapMethod<String, dynamic>('page', {
        'direction': direction,
        'animated': animated,
      });
      final raw = result?['locator'] as String?;
      final at = raw == null ? null : rd.Locator.fromJson(jsonDecode(raw));
      if (_dragInFlight && at != null) _dragLocation = at;
      // A newly loaded spine may install the app font after its first layout.
      // Keep the old sheet covering it until the destination geometry is stable.
      if (result?['resourceChanged'] == true ||
          (at != null && at.href != previousHref)) {
        await _waitForLayoutStable();
        final settled = await native.invokeMethod<String>('locator');
        final visible = settled == null
            ? null
            : rd.Locator.fromJson(jsonDecode(settled));
        if (visible != null) {
          if (_dragInFlight)
            _dragLocation = visible;
          else
            _located(visible);
        }
      }
      return result?['moved'] == true;
    } else if (direction > 0) {
      await api.goForward();
    } else {
      await api.goBackward();
    }
    return true;
  }

  Future<ReadiumPageFrame?> _previewPage(int direction, ReadiumPageFrame origin) async {
    ReadiumPageFrame? frame;
    await (() async {
      if (_disposed || origin.anchor == null) return;
      if (await native.invokeMethod<bool>('canTurn', {'direction': direction.sign}) == false) return;
      final epoch = _previewEpoch;
      _previewing = true;
      try {
        final version = await native.invokeMethod<int>('previewVersion');
        for (var offset = 0; offset < direction.abs(); offset++) {
          if (offset > 0 && await native.invokeMethod<bool>('canTurn', {'direction': direction.sign}) == false) return;
          final result = await native.invokeMapMethod<String, dynamic>('page', {'direction': direction.sign, 'animated': false});
          if (result?['moved'] != true) throw StateError('相邻页未能就绪');
          if (epoch != _previewEpoch) throw StateError('预加载已让位于阅读操作');
          await _waitForPageReady(previewVersion: version, stable: true);
        }
        for (var attempt = 0; attempt < 3 && frame == null; attempt++) {
          frame = await _capturePage();
          if (frame == null) await Future<void>.delayed(const Duration(milliseconds: 16));
        }
        if (frame == null) throw StateError('相邻页画面未能就绪');
      } finally {
        try {
          final destination = turns.pendingPage;
          if (!await _landFrame(destination ?? origin, publish: false)) throw StateError('阅读位置未能恢复');
          if (destination != null) turns.previewHandedOff();
        } finally { _previewing = false; }
      }
    })();
    return frame;
  }

  Future<void> _waitForPageReady({String? anchor, int? previewVersion, bool stable = false}) async {
    final ready = await native.invokeMethod<bool>('ready', {
      'style': _fixedLayout == true ? null : _appliedStyleSignature,
      'stable': stable,
      if (anchor != null) 'anchor': anchor,
      if (previewVersion != null) 'previewVersion': previewVersion,
    });
    if (ready != true) throw StateError('页面尚未就绪');
  }

  Future<bool> _landFrame(ReadiumPageFrame frame, {bool publish = true}) async {
    if (_disposed || frame.anchor == null) return false;
    _landing = true;
    try {
      final landed = await native.invokeMethod<bool>('land', {'anchor': frame.anchor}) == true;
      if (!landed) return false;
      await _waitForPageReady(anchor: frame.anchor);
      await native.invokeMethod<void>('paper', {'color': frame.paper.toARGB32()});
      if (publish) {
        _nativeEventTicket++;
        _pagePaper = frame.paper;
        final page = await native.invokeMapMethod<String, dynamic>('visualPage');
        _stableVisualKey = page?['key'] as String?;
      }
    } finally { _landing = false; }
    // Adopt the captured page locator only after its exact scroll position is restored.
    // Preview events never reach saved progress, chapter controls or reading statistics.
    if (publish && !_disposed) {
      final anchor = jsonDecode(frame.anchor!) as Map<String, dynamic>;
      final at = rd.Locator.fromJson(anchor['locator'] as Map<String, dynamic>);
      if (at != null) {
        if (_dragInFlight) { _dragLocation = at; } else { _located(at); }
      }
    }
    return true;
  }

  Future<ReadiumPageFrame?> _capturePage({bool allowNavigating = false}) async {
    if (!Platform.isAndroid || _disposed || _owner != this || !_styleReady)
      return null;
    if (_navigating && !allowNavigating) return null;
    final style = _preferencesTicket;
    final pixels = await native.invokeMapMethod<String, dynamic>('capture', {
      'bands': _appearance.pageEffect == ReaderPageEffect.staggered,
      'vertical': _preferences.verticalText == true,
    });
    if (pixels == null) return null;
    final bytes = pixels['pixels'] as Uint8List;
    final width = pixels['width'] as int, height = pixels['height'] as int;
    if (width <= 0 || height <= 0 || bytes.length != width * height * 4)
      return null;
    final decoded = Completer<ui.Image>();
    ui.decodeImageFromPixels(
      bytes,
      width,
      height,
      ui.PixelFormat.rgba8888,
      decoded.complete,
    );
    final image = await decoded.future;
    if (_disposed ||
        (_navigating && !allowNavigating) ||
        style != _preferencesTicket) {
      image.dispose();
      return null;
    }
    final location = pixels['locator'] as String?;
    final at = location == null
        ? null
        : jsonDecode(location) as Map<String, dynamic>;
    final loc = at?['locations'] as Map?;
    final bands = (pixels['bands'] as List).map((e) {
      final r = (e as List).cast<num>();
      return Rect.fromLTRB(
        r[0].toDouble(),
        r[1].toDouble(),
        r[2].toDouble(),
        r[3].toDouble(),
      );
    }).toList();
    final color = (pixels['color'] as List).cast<num>();
    return ReadiumPageFrame(
      image,
      pixels['identity'] as String? ??
          '${at?['href']}|${loc?['currentPage']}|${loc?['progression']}',
      bands,
      Color.fromARGB(255, color[0].round(), color[1].round(), color[2].round()),
      anchor: pixels['anchor'] as String?,
    );
  }

  rd.Locator? locationFor(
    int index, {
    double? progression,
    String? text,
    String? before,
    String? after,
  }) {
    final link = linkForChapter(index);
    if (link == null) return null;
    final base = publication!.locatorFromLink(link);
    if (base == null) return null;
    final json = base.toJson();
    if (text != null && text.trim().isNotEmpty) {
      json['text'] = {
        'highlight': text,
        if (before != null) 'before': before,
        if (after != null) 'after': after,
      };
      json['locations'] = {
        if (progression != null) 'progression': progression.clamp(0.0, 1.0),
      };
    } else if (progression != null) {
      json['locations'] = {'progression': progression.clamp(0.0, 1.0)};
    }
    return rd.Locator.fromJson(json);
  }

  int _seekTicket = 0;
  Future<bool> goTarget(BookReadingTarget target, {bool animated = false}) async {
    final addresses = book.navigation?.targets.entries
        .where((entry) {
          final value = entry.value;
          return value.chapterId == target.chapterId &&
              value.paragraph == target.paragraph &&
              value.offset == target.offset;
        })
        .map((entry) => entry.key)
        .toSet();
    if (addresses == null || addresses.isEmpty) return false;
    String decoded(String href) {
      try {
        return Uri.decodeFull(href);
      } catch (_) {
        return href;
      }
    }

    final link = publication?.tocFlattened
        .where(
          (link) =>
              addresses.any((href) => decoded(href) == decoded(link.href)),
        )
        .firstOrNull;
    if (link == null) return false;
    final ticket = ++_seekTicket;
    _layoutAnchor = null;
    onUserNavigation();
    var applied = false;
    await command(() async {
      if (ticket != _seekTicket) return;
      final at = publication?.locatorFromLink(link);
      if (at == null) return;
      applied = true;
      final direction = chapterFor(at).compareTo(chapter);
      await _navigate(at, transitionDirection: animated ? (direction == 0 ? 1 : direction) : null);
    }, waitForPreparation: false);
    return applied;
  }

  Future<void> go(
    int index, {
    double? progression,
    String? text,
    String? before,
    String? after,
    bool animated = false,
  }) {
    final ticket = ++_seekTicket;
    return command(() async {
      if (ticket != _seekTicket) return;
      final at = locationFor(
        index,
        progression: progression,
        text: text,
        before: before,
        after: after,
      );
      if (at == null) throw const FormatException('原书目录没有对应的阅读位置');
      final direction = index.compareTo(chapter);
      await _navigate(at, transitionDirection: animated ? (direction == 0 ? 1 : direction) : null);
    }, waitForPreparation: false);
  }

  String _bookmarkKey(String id) => 'readium.bookmark.v1.${book.id}.$id';
  Future<void> rememberBookmark(String id, {bool selected = false}) async {
    final at = selected ? selectedLocator : locator;
    if (at != null)
      await _prefs?.setString(_bookmarkKey(id), jsonEncode(at.toJson()));
  }

  Future<bool> openBookmark(String id) async {
    await opening;
    final raw = _prefs?.getString(_bookmarkKey(id));
    if (raw == null) return false;
    try {
      final at = rd.Locator.fromJson(jsonDecode(raw) as Map<String, dynamic>);
      if (at == null) return false;
      await command(() => _navigate(at));
      return true;
    } catch (_) {
      return false;
    }
  }

  int _marksRevision = 0;
  final _appliedDecorationKeys = <String, String>{};
  Future<void> decorations(String group, List<Map<String, dynamic>> marks) {
    final key = jsonEncode(marks);
    if (_appliedDecorationKeys[group] == key) return Future<void>.value();
    _appliedDecorationKeys[group] = key;
    _marksRevision++;
    return command(() async {
      if (Platform.isAndroid) {
        await native.invokeMethod<void>('decorations', {
          'group': group,
          'marks': marks,
        });
      } else {
        await api.applyDecorations(
          group,
          marks.map(rd.ReaderDecoration.fromJson).toList(),
        );
      }
    });
  }

  Map<String, dynamic>? mark({
    required String id,
    required int chapter,
    required String text,
    String? before,
    String? after,
    String style = 'highlight',
    int color = 0xffe9c75b,
  }) {
    final at = locationFor(chapter, text: text, before: before, after: after);
    if (at == null) return null;
    return {
      'id': id,
      'locator': at.toJson(),
      'style': {
        'style': style,
        'tint': '#${color.toRadixString(16).padLeft(8, '0').substring(2)}',
      },
    };
  }

  int _preferencesTicket = 0;
  rd.Locator? _layoutAnchor;
  Future<void> preferences(
    ReaderPreferences prefs, {
    required double fontSize,
    required double lineHeight,
    required bool dark,
    required String? fontFamily,
    required EdgeInsets pageInsets,
    bool defaultsOnly = false,
    File? fontFile,
    File? backgroundFile,
    Set<String> hiddenLanguages = const {},
    String? onlyLanguage,
  }) {
    final ticket = ++_preferencesTicket;
    _appearance = prefs;
    _dark = dark;
    final anchor = _layoutAnchor ??= locator;
    final nextPreferences = rd.EPUBPreferences(
      publisherStyles: true,
      fontSize: fontSize / 18,
      lineHeight: lineHeight,
      fontFamily: fontFamily,
      fontWeight: switch (prefs.textWeight) {
        ReaderTextWeight.regular => 1.0,
        ReaderTextWeight.medium => 1.25,
        ReaderTextWeight.bold => 1.5,
      },
      scroll: prefs.mode == ReaderMode.scroll,
      verticalText: prefs.mode == ReaderMode.vertical,
      pageMargins: 0,
      paragraphIndent: prefs.indent ? 2 : null,
      paragraphSpacing: prefs.paragraphGapOverride
          ? prefs.paragraphGap / fontSize
          : null,
      letterSpacing: prefs.letterSpacing / fontSize,
      textAlign: prefs.justify ? TextAlign.justify : null,
      textColor: prefs.darkPaper(dark) ? prefs.foreground(dark) : null,
      // Readium pairs text/background overrides for a readable night palette.
      // Daytime paper is applied only to the page, preserving publisher boxes.
      backgroundColor: prefs.darkPaper(dark) ? prefs.background(dark) : null,
    );
    // Set before the platform view is constructed, including its scroll mode.
    _preferences = nextPreferences;
    api.setDefaultPreferences(nextPreferences);
    if (defaultsOnly) return Future<void>.value();
    return command(() async {
      if (ticket != _preferencesTicket) return;
      _styleReady = false;
      notifyListeners();
      await api.setEPUBPreferences(nextPreferences);
      if (Platform.isAndroid) {
        _fixedLayout ??=
            await native.invokeMethod<bool>('fixedLayout') ?? false;
        _appliedStyleSignature = '${identityHashCode(this)}:$ticket';
        final paper = prefs
            .background(dark)
            .toARGB32()
            .toRadixString(16)
            .padLeft(8, '0')
            .substring(2);
        // In daytime, change only the paper so publisher boxes/tables survive.
        // At night Readium also clears those backgrounds to match its text colour.
        final css = StringBuffer(
          'body{box-sizing:border-box;'
          'padding:0 ${pageInsets.right}px 0 ${pageInsets.left}px !important;'
          '-webkit-box-decoration-break:clone;box-decoration-break:clone;line-height:$lineHeight !important;}',
        );
        if (prefs.indent) css.write('p{text-indent:2em !important;}');
        if (prefs.justify) css.write('p{text-align:justify !important;}');
        if (prefs.paragraphGapOverride)
          css.write(
            'p{margin-block-start:0 !important;margin-block-end:${prefs.paragraphGap}px !important;}',
          );
        if (prefs.letterSpacing != .1)
          css.write(
            'body{letter-spacing:${prefs.letterSpacing}px !important;}',
          );
        if (prefs.textWeight != ReaderTextWeight.regular)
          css.write(
            'body{font-weight:${prefs.textWeight.weight.value} !important;}',
          );
        if (prefs.verticalLatinOrientation ==
            ReaderVerticalLatinOrientation.upright)
          css.write('body{text-orientation:upright !important;}');
        if (fontFile != null && fontFamily != null) {
          final data = await _dataUrl(fontFile, 'font/ttf');
          final family = jsonEncode(fontFamily);
          css.write(
            '@font-face{font-family:$family;src:url("$data");}body,body *:not(code):not(pre){font-family:$family !important;}',
          );
        }
        if (backgroundFile != null) {
          final data = await _dataUrl(backgroundFile, 'image/png');
          final c = prefs.background(dark);
          final shade =
              'rgba(${(c.r * 255).round()},${(c.g * 255).round()},${(c.b * 255).round()},${1 - prefs.backgroundOpacity})';
          final fit = prefs.backgroundFit == BoxFit.contain
              ? 'contain'
              : prefs.backgroundFit == BoxFit.fill
              ? '100% 100%'
              : 'cover';
          css.write(
            'html{background-image:linear-gradient($shade,$shade),url("$data") !important;background-size:$fit !important;background-position:center !important;background-attachment:fixed !important;}body{background-color:transparent !important;}',
          );
        }
        // Native EPUB language tags stay intact; only the user's explicit filter hides them.
        for (final language in hiddenLanguages) {
          final code = language.replaceAll(RegExp('[^a-zA-Z0-9-]'), '');
          if (code.isNotEmpty)
            css.write(
              '[lang="$code"],[xml\\:lang="$code"]{display:none !important;}',
            );
        }
        if (onlyLanguage != null) {
          final code = onlyLanguage.replaceAll(RegExp('[^a-zA-Z0-9-]'), '');
          if (code.isNotEmpty)
            css.write(
              'body [lang]:not([lang|="$code"]){display:none !important;}',
            );
        }
        final overrideBackground =
            backgroundFile != null ||
            prefs.paper != ReaderPaper.system ||
            prefs.darkPaper(dark);
        await native.invokeMethod<String>('style', {
          'script':
              '''(function(){
                var root=document.documentElement,body=document.body;
                if(!body)return;
                if(!window.__wenkuPageReady)window.__wenkuPageReady=function(){
                  if(document.readyState!=='complete')return false;
                  var ready=true,w=innerWidth,h=innerHeight;
                  for(var img of Array.from(document.images)){
                    var r=img.getBoundingClientRect();
                    var visible=r.right>0&&r.left<w&&r.bottom>0&&r.top<h;
                    var nearby=r.right>-w&&r.left<2*w&&r.bottom>-h&&r.top<2*h;
                    if(!nearby)continue;
                    if(img.loading==='lazy')img.loading='eager';
                    if(!img.complete){if(visible)ready=false;continue;}
                    if(!img.naturalWidth||!img.decode)continue;
                    var src=img.currentSrc||img.src;
                    if(img.__wenkuDecoded===src)continue;
                    if(visible)ready=false;
                    if(img.__wenkuDecoding!==src){
                      img.__wenkuDecoding=src;
                      (function(image,url){image.decode().catch(function(){}).then(function(){image.__wenkuDecoded=url;});})(img,src);
                    }
                  }return ready;
                };
                if(!window.__wenkuImageLoadHook){
                  window.__wenkuImageLoadHook=true;
                  window.addEventListener('load',function(){window.__wenkuPageReady();},{once:true});
                }
                window.__wenkuPageReady();
                var signature=${jsonEncode(_appliedStyleSignature)};
                if(window.__wenkuAppliedStyle===signature)return;
                window.__wenkuAppliedStyle=signature;
                var s=document.getElementById("wenku-reader-preferences");
                if(s)s.remove();
                var saved=window.__wenkuRootBackground;
                if(!saved){saved={color:root.style.getPropertyValue('background-color'),image:root.style.getPropertyValue('background-image'),bodyColor:body.style.getPropertyValue('background-color'),bodyPriority:body.style.getPropertyPriority('background-color'),bodyImage:body.style.getPropertyValue('background-image'),bodyImagePriority:body.style.getPropertyPriority('background-image')};window.__wenkuRootBackground=saved;}
                for(var pair of [['background-color',saved.color],['background-image',saved.image]]){if(pair[1])root.style.setProperty(pair[0],pair[1]);else root.style.removeProperty(pair[0]);}
                if(saved.bodyColor)body.style.setProperty('background-color',saved.bodyColor,saved.bodyPriority);else body.style.removeProperty('background-color');
                if(saved.bodyImage)body.style.setProperty('background-image',saved.bodyImage,saved.bodyImagePriority);else body.style.removeProperty('background-image');
                var bg=getComputedStyle(body),html=getComputedStyle(root);
                var opaque=bg.backgroundColor!=='rgba(0, 0, 0, 0)'&&bg.backgroundColor!=='transparent';
                // Readium's default white root is not an authored book colour.
                // Keep the original app paper unless the book paints html/body.
                var authoredRoot=!!saved.color;
                function paintsRoot(rules){
                  for(var r of Array.from(rules||[])){
                    if(r.selectorText&&r.style){try{if(root.matches(r.selectorText)&&(r.style.background||r.style.backgroundColor))return true;}catch(e){}}
                    if(r.cssRules&&(!r.media||matchMedia(r.conditionText||'all').matches)&&paintsRoot(r.cssRules))return true;
                  }return false;
                }
                for(var sheet of Array.from(document.styleSheets)){
                  if(/readium_assets|readium-css/i.test(sheet.href||'')||/readium|wenku/i.test(sheet.ownerNode&&sheet.ownerNode.id||''))continue;
                  try{if(paintsRoot(sheet.cssRules))authoredRoot=true;}catch(e){}
                }
                var color=${overrideBackground ? jsonEncode('#$paper') : "opaque?bg.backgroundColor:(authoredRoot?html.backgroundColor:'#$paper')"};
                var image=${overrideBackground ? "'none'" : "bg.backgroundImage!=='none'?bg.backgroundImage:html.backgroundImage"};
                s=document.createElement('style');s.id='wenku-reader-preferences';
                s.textContent=${jsonEncode(css.toString())} + (image!=='none'&&!${overrideBackground}?'html{background-size:'+bg.backgroundSize+';background-repeat:'+bg.backgroundRepeat+';background-position:'+bg.backgroundPosition+';background-attachment:fixed;}':'');document.head.appendChild(s);
                // Readium's :root[style] body rule is !important; inline padding
                // keeps the user's margins inside each page rather than outside it.
                // The column container owns block insets so every page has them.
                // Body block padding applies only to the first/last fragment in WebView.
                root.style.setProperty('box-sizing','border-box','important');
                root.style.setProperty('padding-top','${pageInsets.top}px','important');
                root.style.setProperty('padding-bottom','${pageInsets.bottom}px','important');
                body.style.setProperty('padding','0 ${pageInsets.right}px 0 ${pageInsets.left}px','important');
                root.style.setProperty('background-color',color,'important');
                if(!${backgroundFile != null})root.style.setProperty('background-image',image,'important');
                body.style.setProperty('background-color','transparent','important');
                body.style.setProperty('background-image','none','important');
              })();''',
        });
      }
      if (Platform.isAndroid) await _waitForLayoutStable(ticket: ticket);
      if (anchor != null && ticket == _preferencesTicket) {
        // Navigator preferences and app fonts/CSS both reflow the same Readium DOM.
        // Restore its original locator after both have settled.
        if (Platform.isAndroid && fontFile != null) {
          for (var i = 0; i < 40 && ticket == _preferencesTicket; i++) {
            final status = await native.invokeMethod<String>('evaluate', {
              'script':
                  '(function(){document.body.offsetHeight;return document.fonts.status;})()',
            });
            if (status == '"loaded"') break;
            await Future<void>.delayed(const Duration(milliseconds: 50));
          }
        }
        await Future<void>.delayed(const Duration(milliseconds: 120));
        if (ticket == _preferencesTicket &&
            !_disposed &&
            _owner == this &&
            identical(_layoutAnchor, anchor)) {
          await _navigate(anchor);
          _layoutAnchor = null;
        }
      }
    }, waitForView: false).whenComplete(() {
      if (!_disposed && ticket == _preferencesTicket) {
        _styleReady = true;
        notifyListeners();
      }
    });
  }

  Future<void> _waitForLayoutStable({int? ticket}) async {
    String? previous;
    var stable = 0;
    for (
      var i = 0;
      i < 60 && !_disposed && (ticket == null || ticket == _preferencesTicket);
      i++
    ) {
      final sample = await native.invokeMethod<String>('evaluate', {
        'script':
            '(function(){document.body.offsetHeight;return (window.__wenkuPageReady&&!window.__wenkuPageReady()?"pending":document.fonts.status)+"|"+innerWidth+"|"+innerHeight+"|"+document.documentElement.scrollWidth+"|"+document.documentElement.scrollHeight+"|"+scrollX+"|"+scrollY+"|"+getComputedStyle(document.body).fontSize+"|"+(window.__wenkuAppliedStyle||"")+"|"+getComputedStyle(document.documentElement).backgroundColor+"|"+(document.body.textContent.trim()?"text":"image");})()',
      });
      if (sample != null &&
          sample.contains('loaded') &&
          sample == previous &&
          (_fixedLayout == true ||
              _appliedStyleSignature == null ||
              sample.contains(_appliedStyleSignature!)))
        stable++;
      else
        stable = 0;
      if (stable >= (sample?.contains('|image') == true ? 1 : 3)) {
        final rgb = RegExp(r'rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)').firstMatch(sample!);
        if (rgb != null) {
          final paper = rgb[4] != null && double.parse(rgb[4]!) == 0
              ? _appearance.background(_dark)
              : Color.fromARGB(255, int.parse(rgb[1]!), int.parse(rgb[2]!), int.parse(rgb[3]!));
          await native.invokeMethod<void>('paper', {'color': paper.toARGB32()});
          if (!_disposed && _pagePaper != paper) {
            _pagePaper = paper;
            notifyListeners();
          }
        }
        return;
      }
      previous = sample;
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }
  }

  final _dataUrls = <String, Future<String>>{};
  Future<String> _dataUrl(File file, String mime) =>
      _dataUrls.putIfAbsent(file.path, () async {
        if (_dataUrls.length > 2) _dataUrls.remove(_dataUrls.keys.first);
        return 'data:$mime;base64,${base64Encode(await file.readAsBytes())}';
      });

  Future<void> scrollBy(double pixels) => command(() async {
    if (!Platform.isAndroid) return;
    final result = await native.invokeMethod<String>('evaluate', {
      'script':
          '(function(){window.scrollBy(0,${pixels.toStringAsFixed(3)});var e=document.scrollingElement||document.documentElement;return e.scrollTop+e.clientHeight>=e.scrollHeight-1;})()',
    });
    _scrollAtEnd = result == 'true';
  });
  void persist() {
    final at = locator;
    final prefs = _prefs;
    if (at == null || prefs == null) return;
    _saves = _saves
        .catchError((Object _) {})
        .then((_) async {
          if (!await prefs.setString(_key, jsonEncode(at.toJson())))
            throw StateError('EPUB 位置未保存');
        })
        .catchError((Object e) {
          error = '阅读位置保存失败：$e';
          if (!_disposed) notifyListeners();
        });
  }

  Widget view() => _suspended
      ? const SizedBox.expand()
      : publication == null
      ? Center(
          child: error == null
              ? const CircularProgressIndicator()
              : Text(error!),
        )
      : ReadiumPageSurface(
          controller: turns,
          frameKey:
              "${locator?.href}|$fraction|$_preferencesTicket|$_selectionTicket|$_marksRevision|$_styleReady|${_appearance.pageEffect}",
          layoutKey: '$_preferencesTicket|$_selectionTicket|$_marksRevision|$_styleReady|${_appearance.pageEffect}',
          preferences: _appearance,
          paper: pagePaper,
          capture: _capturePage,
          step: _stepPage,
          canTurn: _canTurnPage,
          preview: Platform.isAndroid && _appearance.mode.paginated && _appearance.pageEffect != ReaderPageEffect.none ? _previewPage : null,
          land: (frame) => _landFrame(frame),
          lookAhead: 2,
          refreshRateLimit: _refreshRateLimit,
          onPrioritize: _interruptPreview,
          replayTap: (point) => native.invokeMethod<void>('tap', {'x': point.dx, 'y': point.dy}),
          child: Stack(
            fit: StackFit.expand,
            children: [
              IgnorePointer(
                ignoring: !_styleReady,
                child: Opacity(
                  opacity: _styleReady ? 1 : 0,
                  child: rd.ReadiumReaderWidget(
                    key: _viewKey,
                    publication: publication!,
                    initialLocator: initialLocator,
                    shouldShowControls: controls,
                    // Android forwards only unhandled Readium taps through InputListener.
                    // Links, footnotes, annotations and selection handles retain their taps.
                    onReaderTap: onTap,
                    onReaderDrag: _pageDrag,
                    onFootnote: (markup) {
                      final note = html.parseFragment(markup).text;
                      if (note != null && note.trim().isNotEmpty)
                        onAction('note', note.trim());
                    },
                    onUserNavigation: () {
                      _layoutAnchor = null;
                      _seekTicket++;
                      onUserNavigation();
                    },
                    selectionActions: const [
                      rd.SelectionAction(id: 'copy', title: '复制'),
                      rd.SelectionAction(id: 'bookmark', title: '书签'),
                      rd.SelectionAction(id: 'dictionary', title: '词典'),
                      rd.SelectionAction(id: 'illustrations', title: '插图'),
                    ],
                    allowedDefaultActions: const {},
                    onImageTapped: (image) => onAction('image', image.href),
                    onTextSelected: (e) =>
                        unawaited(_selected(e).catchError((Object _) {})),
                    onSelectionAction: (e) {
                      selectedLocator = e.locator;
                      onAction(e.actionId, e.selectedText ?? '');
                    },
                    onDecorationInteraction: (e) {
                      if (e.group == 'wenku-annotations')
                        onAnnotation(e.decorationId);
                    },
                    goBackwardSemanticLabel: '上一页',
                    goForwardSemanticLabel: '下一页',
                    toggleShowControlsSemanticLabel: '切换阅读控件',
                  ),
                ),
              ),
              if (!_styleReady)
                const Center(child: CircularProgressIndicator()),
            ],
          ),
        );
  @override
  void dispose() {
    if (Platform.isAndroid) FrameRateSettings.shared.removeListener(_frameRateChanged);
    _disposed = true;
    persist();
    unawaited(_events?.cancel());
    if (!_firstLocation.isCompleted) _firstLocation.complete();
    unawaited(
      opening.then((_) {
        final release = _lifecycle.catchError((Object _) {}).then((_) async {
          await _commands;
          if (_owner == this) {
            await api.closePublication();
            _owner = null;
          }
          await _saves;
          if (_staging != null) await _staging!.delete(recursive: true);
        });
        _lifecycle = release.catchError((Object _) {});
      }),
    );
    turns.dispose();
    controls.dispose();
    progress.dispose();
    super.dispose();
  }
}
