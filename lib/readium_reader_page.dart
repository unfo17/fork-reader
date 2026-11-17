import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_readium/flutter_readium.dart' as rd;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'local_books.dart';
import 'offline_repository.dart';
import 'pages.dart' show ReaderPage;

/// EPUB is displayed from the untouched archive by Readium's native navigator.
/// The extracted Flutter chapter bodies are never used on this route.
class NativeEpubReaderState extends State<ReaderPage> with WidgetsBindingObserver {
  static Object? _owner;
  final _readium = rd.FlutterReadium();
  rd.Publication? _publication;
  rd.Locator? _initialLocator, _lastLocator;
  StreamSubscription<rd.Locator>? _locations;
  Directory? _staging;
  SharedPreferences? _preferences;
  Timer? _debounce;
  late final Future<void> _opening;
  Future<void> _writes = Future.value();
  String? _error;
  bool _opened = false, _disposed = false;
  double _fontScale = 1;
  int _chapter = 0;
  String get _positionKey => 'readium.locator.v1.${widget.novel.id}';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _chapter = widget.initialChapter.clamp(0, widget.novel.chapters.length - 1);
    _opening = _open();
  }

  Future<void> _open() async {
    if (_owner != null) {
      setState(() => _error = '请先返回并关闭当前 EPUB 阅读器，再打开这本书。');
      return;
    }
    _owner = this;
    try {
      final books = (widget.repository as OfflineNovelRepository).local!;
      _preferences = await SharedPreferences.getInstance();
      _staging = await (await getTemporaryDirectory()).createTemp('readium-view-');
      final file = File('${_staging!.path}/publication.epub');
      await books.copyEpubForReadium(widget.novel.id, file);
      if (_disposed) return;
      // No font, spacing, alignment or color override: preserve publisher CSS.
      _readium.setDefaultPreferences(const rd.EPUBPreferences(publisherStyles: true));
      final publication = await _readium.openPublication(file.uri.toString());
      _opened = true;
      if (_disposed) return;
      final stored = _preferences!.getString(_positionKey);
      final previousChapter = widget.state.positionFor(widget.novel.id)?.chapter;
      if (stored != null && widget.initialBookmark == null &&
          (previousChapter == null || previousChapter == widget.initialChapter)) {
        try {
          _initialLocator = rd.Locator.fromJson(jsonDecode(stored) as Map<String, dynamic>);
        } catch (_) { /* A damaged locator does not prevent opening the book. */ }
      }
      if (_initialLocator == null) {
        final title = widget.novel.chapters[_chapter].title;
        final link = publication.tocFlattened.where((l) => l.title == title).firstOrNull;
        if (link != null) _initialLocator = publication.locatorFromLink(link);
      }
      _locations = _readium.onTextLocatorChanged.listen((locator) {
        _lastLocator = locator;
        final toc = publication.tocFlattened.where(
          (l) => l.href.split('#').first == locator.href.split('#').first,
        ).firstOrNull;
        final index = widget.novel.chapters.indexWhere((c) => c.title == toc?.title);
        if (index >= 0) _chapter = index;
        _debounce?.cancel();
        _debounce = Timer(const Duration(milliseconds: 400), _persist);
      });
      setState(() => _publication = publication);
    } catch (error) {
      if (!_disposed) setState(() => _error = 'EPUB 打开失败：$error');
    }
  }

  void _persist() {
    final locator = _lastLocator;
    if (locator == null || _preferences == null) return;
    final book = widget.novel;
    final state = widget.state;
    final chapter = _chapter;
    final preferences = _preferences!;
    final key = _positionKey;
    _writes = _writes.catchError((Object _) {}).then((_) async {
      if (!await preferences.setString(key, jsonEncode(locator.toJson()))) {
        throw StateError('阅读进度未保存');
      }
      await state.savePosition(novel: book, chapter: chapter,
        offset: locator.locations?.progression ?? 0, maximum: 1, width: 0,
        contentRevision: 'readium-locator-v1');
    }).catchError((Object error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('阅读进度保存失败：$error')),
      );
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) { _debounce?.cancel(); _persist(); }
  }

  Future<void> _close() async {
    if (!identical(_owner, this)) return;
    try {
      if (_opened) await _readium.closePublication();
    } finally {
      _owner = null;
      if (_staging != null) await _staging!.delete(recursive: true);
    }
  }

  @override
  void dispose() {
    _disposed = true;
    WidgetsBinding.instance.removeObserver(this);
    _debounce?.cancel();
    _persist();
    unawaited(_locations?.cancel());
    unawaited(_opening.then((_) => _close()).catchError((Object _) {}));
    super.dispose();
  }

  Future<void> _contents() async {
    final publication = _publication;
    if (publication == null) return;
    final link = await showModalBottomSheet<rd.Link>(context: context,
      isScrollControlled: true,
      builder: (context) => SafeArea(child: SizedBox(
        height: MediaQuery.sizeOf(context).height * .7,
        child: ListView(children: [
          const ListTile(title: Text('原书目录')),
          for (final link in publication.tocFlattened)
            ListTile(title: Text(link.title ?? link.href),
              onTap: () => Navigator.pop(context, link)),
        ]),
      )),
    );
    if (link != null && mounted) {
      try { await _readium.goByLink(link, publication); }
      catch (error) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('目录跳转失败：$error'))); }
    }
  }

  Future<void> _font(double delta) async {
    final next = (_fontScale + delta).clamp(.5, 3.0).toDouble();
    await _readium.setEPUBPreferences(rd.EPUBPreferences(
      publisherStyles: true, fontSize: next,
    ));
    _fontScale = next;
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(widget.novel.title), actions: [
      IconButton(tooltip: '缩小文字', onPressed: _publication == null ? null : () => _font(-.1), icon: const Icon(Icons.text_decrease)),
      IconButton(tooltip: '放大文字', onPressed: _publication == null ? null : () => _font(.1), icon: const Icon(Icons.text_increase)),
      IconButton(tooltip: '原书目录', onPressed: _publication == null ? null : _contents, icon: const Icon(Icons.toc)),
    ]),
    body: SafeArea(top: false, child: _error != null
      ? Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(_error!)))
      : _publication == null ? const Center(child: CircularProgressIndicator())
      : rd.ReadiumReaderWidget(publication: _publication!, initialLocator: _initialLocator,
          loadingWidget: const Center(child: CircularProgressIndicator()),
          goBackwardSemanticLabel: '上一页', goForwardSemanticLabel: '下一页',
          toggleShowControlsSemanticLabel: '阅读控制')),
  );
}
