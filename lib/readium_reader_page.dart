import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_readium/flutter_readium.dart' as rd;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'catalog.dart';
import 'local_books.dart';
import 'reader_preferences.dart';

/// Engine adapter only. ReaderPage retains every Flutter control and panel.
class ReadiumReaderBridge extends ChangeNotifier {
  ReadiumReaderBridge({
    required this.book,
    required this.books,
    required this.initialChapter,
    this.restoreLocator = true,
    required this.onLocation,
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

  rd.Locator? selectedLocator;
  final progress = ValueNotifier<double>(0);
  final api = rd.FlutterReadium();
  late final ValueNotifier<bool> controls;
  late Future<void> opening;
  rd.Publication? publication;
  rd.Locator? initialLocator, locator;
  StreamSubscription<rd.Locator>? _events;
  Directory? _staging;
  SharedPreferences? _prefs;
  String? error;
  bool _disposed = false;
  bool ready = false;
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
    _layoutAnchor = null;
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
          previous.ready = false;
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
        if (!_navigating) _located(at);
      });
      notifyListeners();
    } catch (e) {
      error = 'EPUB 打开失败：$e';
      if (!_disposed) notifyListeners();
    }
  }

  bool _navigating = false;
  void _located(rd.Locator at) {
    final locationTicket = ++_locationTicket;
    if (_disposed || _owner != this) return;
    if (!_firstLocation.isCompleted) _firstLocation.complete();
    final sameResource = locator?.href == at.href;
    if (!sameResource) _scrollAtEnd = false;
    locator = at;
    chapter = chapterFor(at);
    fraction = at.locations?.progression ?? (sameResource ? fraction : 0);
    ready = true;
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

  Future<void> _navigate(rd.Locator at) async {
    _navigating = Platform.isAndroid;
    try {
      await api.goToLocator(at);
      if (Platform.isAndroid) {
        // The request locator may omit progression. Read the actual visual
        // position after Readium has resolved its text quote and laid it out.
        await Future<void>.delayed(const Duration(milliseconds: 240));
        final raw = await native.invokeMethod<String>('locator');
        if (raw != null && !_disposed && _owner == this) {
          final json = jsonDecode(raw) as Map<String, dynamic>;
          final nextLocations =
              json['locations'] as Map<String, dynamic>? ?? {};
          if (locator?.href == json['href']) {
            final previous = locator?.locations?.toJson() ?? {};
            for (final key in ['currentPage', 'totalPages']) {
              if (!nextLocations.containsKey(key) && previous.containsKey(key))
                nextLocations[key] = previous[key];
            }
          }
          json['locations'] = nextLocations;
          final visible = rd.Locator.fromJson(json);
          if (visible != null) _located(visible);
        }
      }
    } finally {
      _navigating = false;
    }
  }

  Future<void> command(
    Future<void> Function() action, {
    bool waitForView = true,
  }) {
    final next = _commands.catchError((Object _) {}).then((_) async {
      await opening;
      if (_disposed || publication == null || _owner != this) return;
      if (waitForView) await _firstLocation.future;
      if (_disposed || _owner != this) return;
      await action();
      error = null;
    });
    _commands = next.catchError((Object e) {
      error = '阅读操作失败：$e';
      if (!_disposed) notifyListeners();
    });
    return _commands;
  }

  Future<void> pageBy(int direction, {bool animated = true}) =>
      command(() async {
        if (Platform.isAndroid) {
          await native.invokeMethod<void>('page', {
            'direction': direction,
            'animated': animated,
          });
        } else if (direction > 0) {
          await api.goForward();
        } else {
          await api.goBackward();
        }
      });
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
  Future<void> go(
    int index, {
    double? progression,
    String? text,
    String? before,
    String? after,
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
      await _navigate(at);
    });
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

  Future<void> decorations(String group, List<Map<String, dynamic>> marks) =>
      command(() async {
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
    File? fontFile,
    File? backgroundFile,
    Set<String> hiddenLanguages = const {},
    String? onlyLanguage,
  }) {
    final ticket = ++_preferencesTicket;
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
    );
    // Set before the platform view is constructed, including its scroll mode.
    _preferences = nextPreferences;
    api.setDefaultPreferences(nextPreferences);
    return command(() async {
      if (ticket != _preferencesTicket) return;
      await api.setEPUBPreferences(nextPreferences);
      if (Platform.isAndroid) {
        final paper = prefs
            .background(dark)
            .toARGB32()
            .toRadixString(16)
            .padLeft(8, '0')
            .substring(2);
        // Readium's custom background preference clears every publisher background.
        // Apply the page paper only, leaving coloured boxes/tables untouched.
        final css = StringBuffer(
          'html{background-color:#$paper !important;}body{background-color:transparent !important;line-height:$lineHeight !important;}',
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
        await native.invokeMethod<String>('style', {
          'script':
              '(function(){var s=document.getElementById("wenku-reader-preferences");if(!s){s=document.createElement("style");s.id="wenku-reader-preferences";document.head.appendChild(s);}s.textContent=${jsonEncode(css.toString())};document.documentElement.style.setProperty("background-color","#$paper","important");document.body.style.setProperty("background-color","transparent","important");})();',
        });
      }
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
    }, waitForView: false);
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
      : rd.ReadiumReaderWidget(
          key: _viewKey,
          publication: publication!,
          initialLocator: initialLocator,
          shouldShowControls: controls,
          onReaderTap: onTap,
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
          onImageTapped: (_) => onAction('illustrations', ''),
          onTextSelected: (e) =>
              unawaited(_selected(e).catchError((Object _) {})),
          onSelectionAction: (e) {
            selectedLocator = e.locator;
            onAction(e.actionId, e.selectedText ?? '');
          },
          onDecorationInteraction: (e) {
            if (e.group == 'wenku-annotations') onAnnotation(e.decorationId);
          },
          goBackwardSemanticLabel: '上一页',
          goForwardSemanticLabel: '下一页',
          toggleShowControlsSemanticLabel: '切换阅读控件',
        );
  @override
  void dispose() {
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
    controls.dispose();
    progress.dispose();
    super.dispose();
  }
}
