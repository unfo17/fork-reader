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
    required bool chromeVisible,
  }) {
    controls = ValueNotifier(chromeVisible);
    opening = _open();
  }
  final Novel book;
  final LocalBookStore books;
  final int initialChapter;
  final bool restoreLocator;
  bool get hasSavedLocator => _hasSavedLocator;
  bool _hasSavedLocator = false;
  static ReadiumReaderBridge? _owner;
  static Future<void> _lifecycle = Future.value();
  final _firstLocation = Completer<void>();
  rd.EPUBPreferences _preferences = const rd.EPUBPreferences(
    publisherStyles: true,
  );
  final void Function(int chapter, double fraction, String? text) onLocation;
  final void Function(String action, String text) onAction;
  final ValueChanged<Offset> onTap;
  final VoidCallback onUserNavigation;
  final progress = ValueNotifier<double>(0);
  final api = rd.FlutterReadium();
  late final ValueNotifier<bool> controls;
  late final Future<void> opening;
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
  Future<void> _saves = Future.value();
  Future<void> _commands = Future.value();
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

  Future<void> _open() async {
    chapter = initialChapter;
    try {
      _prefs = await SharedPreferences.getInstance();
      _staging = await (await getTemporaryDirectory()).createTemp(
        'readium-render-',
      );
      final file = File('${_staging!.path}/publication.epub');
      await books.copyEpubForReadium(book.id, file);
      if (_disposed) return;
      final acquire = _lifecycle.catchError((Object _) {}).then((_) async {
        if (_disposed) return;
        final previous = _owner;
        if (previous != null) {
          await previous._events?.cancel();
          await previous._commands;
          await api.closePublication();
        }
        _owner = this;
        api.setDefaultPreferences(_preferences);
        publication = await api.openPublication(file.uri.toString());
      });
      _lifecycle = acquire.catchError((Object _) {});
      await acquire;
      if (_disposed || _owner != this) return;
      final raw = _prefs!.getString(_key);
      if (restoreLocator && raw != null) {
        try {
          final saved = rd.Locator.fromJson(
            jsonDecode(raw) as Map<String, dynamic>,
          );
          if (saved != null && chapterFor(saved) == initialChapter) {
            initialLocator = saved;
            _hasSavedLocator = true;
          }
        } catch (_) {}
      }
      final first = linkForChapter(initialChapter);
      if (initialLocator == null && first != null)
        initialLocator = publication!.locatorFromLink(first);
      _events = api.onTextLocatorChanged.listen((at) {
        if (_disposed || _owner != this) return;
        if (!_firstLocation.isCompleted) _firstLocation.complete();
        locator = at;
        chapter = chapterFor(at);
        fraction = at.locations?.progression ?? 0;
        ready = true;
        progress.value = fraction;
        onLocation(chapter, fraction, at.text?.highlight);
        persist();
        notifyListeners();
      });
      notifyListeners();
    } catch (e) {
      error = 'EPUB 打开失败：$e';
      if (!_disposed) notifyListeners();
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
  Future<void> go(int index, {double? progression, String? text}) =>
      command(() async {
        final link = linkForChapter(index);
        if (link == null) throw const FormatException('原书目录没有对应的阅读位置');
        final base = publication!.locatorFromLink(link);
        if (base == null) return;
        final json = base.toJson();
        if (text != null && text.trim().isNotEmpty) {
          json['text'] = {'highlight': text};
        } else if (progression != null) {
          json['locations'] = {
            ...?(json['locations'] as Map?),
            'progression': progression.clamp(0.0, 1.0),
          };
        }
        await api.goToLocator(rd.Locator.fromJson(json)!);
      });
  Future<void> preferences(
    ReaderPreferences prefs, {
    required double fontSize,
    required double lineHeight,
    required bool dark,
    required String? fontFamily,
  }) {
    _preferences = rd.EPUBPreferences(
      publisherStyles: true,
      fontSize: fontSize / 18,
      lineHeight: lineHeight,
      fontFamily: fontFamily,
      fontWeight: switch (prefs.textWeight) {
        ReaderTextWeight.regular => null,
        ReaderTextWeight.medium => 1.25,
        ReaderTextWeight.bold => 1.5,
      },
      scroll: prefs.mode == ReaderMode.scroll,
      verticalText: prefs.mode == ReaderMode.vertical,
      pageMargins: prefs.bindingMargin / 26,
      paragraphIndent: prefs.indent ? 2 : null,
      paragraphSpacing: prefs.paragraphGapOverride
          ? prefs.paragraphGap / fontSize
          : null,
      letterSpacing: prefs.letterSpacing / fontSize,
      textAlign: prefs.justify ? TextAlign.justify : null,
      backgroundColor: prefs.background(dark),
      textColor: prefs.darkPaper(dark) ? prefs.foreground(dark) : null,
    );
    // Set before the platform view is constructed, including its scroll mode.
    api.setDefaultPreferences(_preferences);
    return command(
      () => api.setEPUBPreferences(_preferences),
      waitForView: false,
    );
  }

  Future<void> scrollBy(double pixels) => command(() async {
    if (!Platform.isAndroid) return;
    await native.invokeMethod<String>('evaluate', {
      'script': 'window.scrollBy(0,${pixels.toStringAsFixed(3)});',
    });
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

  Widget view() => publication == null
      ? Center(
          child: error == null
              ? const CircularProgressIndicator()
              : Text(error!),
        )
      : rd.ReadiumReaderWidget(
          publication: publication!,
          initialLocator: initialLocator,
          shouldShowControls: controls,
          onReaderTap: onTap,
          onUserNavigation: onUserNavigation,
          selectionActions: const [
            rd.SelectionAction(id: 'copy', title: '复制'),
            rd.SelectionAction(id: 'bookmark', title: '书签'),
            rd.SelectionAction(id: 'dictionary', title: '查词'),
            rd.SelectionAction(id: 'illustrations', title: '本卷插图'),
          ],
          onSelectionAction: (e) => onAction(e.actionId, e.selectedText ?? ''),
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
