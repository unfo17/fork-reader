import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_interactions.dart';
import 'app_ui.dart';
import 'archive_feature_ui.dart';
import 'library_state.dart';
import 'local_books.dart';
import 'nearby_reading.dart';

class NearbyReadingScope extends InheritedWidget {
  const NearbyReadingScope({
    super.key,
    required this.books,
    required this.open,
    required super.child,
  });

  final LocalBookStore books;
  final Future<void> Function(NearbyReadingReceipt) open;

  static NearbyReadingScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<NearbyReadingScope>();

  @override
  bool updateShouldNotify(NearbyReadingScope oldWidget) =>
      books != oldWidget.books || open != oldWidget.open;
}

class NearbyReadingPage extends StatefulWidget {
  const NearbyReadingPage({
    super.key,
    required this.scope,
    required this.reduceMotion,
  });

  final NearbyReadingScope scope;
  final bool reduceMotion;

  @override
  State<NearbyReadingPage> createState() => _NearbyReadingPageState();
}

class _NearbyReadingPageState extends State<NearbyReadingPage> {
  final _code = TextEditingController();
  NearbyReadingReceiver? _receiver;
  NearbyReadingReceipt? _receipt;
  String? _status;
  double? _progress;
  bool _opening = false;
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onPause: _stop);
  }

  void _stop() => _receiver?.close();

  @override
  void dispose() {
    _lifecycle.dispose();
    _stop();
    _code.dispose();
    super.dispose();
  }

  Future<void> _receive() async {
    if (_receiver != null) return;
    NearbyReadingReceiver? receiver;
    try {
      receiver = NearbyReadingReceiver(_code.text);
      FocusScope.of(context).unfocus();
      setState(() {
        _receiver = receiver;
        _status = '正在连接发送设备…';
        _progress = null;
      });
      final receipt = await receiver.receive(
        widget.scope.books,
        confirm: (title, bytes) async {
          if (!mounted) return false;
          final detail = bytes == 0
              ? '本机已有此书，只接收阅读位置。'
              : '同时接收原书和目录，约 ${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB。';
          return confirmNovelAction(
            context,
            title: '接收《$title》？',
            message: detail,
            confirmLabel: '接收',
            reduceMotion: widget.reduceMotion,
          );
        },
        onProgress: (received, total) {
          if (!mounted) return;
          setState(() {
            _status = '正在接收书籍…';
            _progress = total == 0 ? null : received / total;
          });
        },
      );
      if (!mounted) return;
      setState(() {
        _code.clear();
        _receipt = receipt;
        _status = '已收到《${receipt.book.title}》的阅读位置';
      });
    } on NearbyReadingCancelled {
      if (mounted) setState(() => _status = null);
    } on FormatException catch (error) {
      if (mounted) setState(() => _status = error.message);
    } catch (_) {
      if (mounted) setState(() => _status = '续读连接中断，请检查两台设备的 Wi-Fi 后重试');
    } finally {
      receiver?.close();
      if (mounted) setState(() => _receiver = null);
    }
  }

  Future<void> _open(NearbyReadingReceipt receipt) async {
    setState(() => _opening = true);
    try {
      await widget.scope.open(receipt);
    } catch (_) {
      if (mounted) setState(() => _status = '暂时无法打开阅读位置，请重试');
    } finally {
      if (mounted) setState(() => _opening = false);
    }
  }

  @override
  Widget build(BuildContext context) => ArchiveFeaturePage(
    title: '局域网续读',
    code: 'READING / NEARBY',
    child: ListView(
      padding: featurePadding(context),
      children: [
        const AppText('两台设备连接同一 Wi-Fi。在另一台设备的阅读页点「在其他设备上续读」，复制连接码后在此接收。'),
        const SizedBox(height: 14),
        if (_receipt == null) ...[
          CupertinoTextField(
            controller: _code,
            placeholder: '粘贴续读连接码',
            autocorrect: false,
            enableSuggestions: false,
            maxLines: 2,
            enabled: _receiver == null,
          ),
          const SizedBox(height: 12),
          GlassTextButton(
            onPressed: _receiver == null ? _receive : null,
            child: const AppText('接收续读'),
          ),
        ],
        if (_receiver != null) ...[
          LinearProgressIndicator(value: _progress),
          GlassTextButton(onPressed: _stop, child: const AppText('停止接收')),
        ],
        if (_status case final message?) ...[
          const SizedBox(height: 12),
          Semantics(liveRegion: true, child: AppText(message)),
        ],
        if (_receipt case final receipt?)
          GlassTextButton(
            onPressed: _opening ? null : () => _open(receipt),
            child: const AppText('继续阅读'),
          ),
      ],
    ),
  );
}

class NearbyReadingSendSheet extends StatefulWidget {
  const NearbyReadingSendSheet({
    super.key,
    required this.books,
    required this.bookId,
    required this.position,
    this.locator,
  });

  final LocalBookStore books;
  final int bookId;
  final ReadingPosition position;
  final Map<String, dynamic>? locator;

  @override
  State<NearbyReadingSendSheet> createState() => _NearbyReadingSendSheetState();
}

class _NearbyReadingSendSheetState extends State<NearbyReadingSendSheet> {
  NearbyReadingOffer? _offer;
  bool _loading = true;
  String? _message;
  int _request = 0;
  int _address = 0;
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onPause: _stop);
    unawaited(_start());
  }

  Future<void> _start() async {
    final request = ++_request;
    try {
      final offer = await NearbyReadingOffer.start(
        books: widget.books,
        bookId: widget.bookId,
        position: widget.position,
        locator: widget.locator,
      );
      if (!mounted || request != _request) {
        await offer.close();
        return;
      }
      setState(() {
        _offer = offer;
        _loading = false;
        _message = null;
        _address = 0;
      });
      final completed = await offer.completed;
      if (mounted && request == _request) {
        setState(() {
          _offer = null;
          _message = completed ? '另一台设备已收到续读' : '连接码已失效，可以生成新的连接码';
        });
      }
    } on FormatException catch (error) {
      if (mounted && request == _request) {
        setState(() {
          _loading = false;
          _message = error.message;
        });
      }
    } catch (_) {
      if (mounted && request == _request) {
        setState(() {
          _loading = false;
          _message = '无法创建续读连接，请检查 Wi-Fi 后重试';
        });
      }
    }
  }

  void _stop() {
    if (_offer case final offer?) {
      unawaited(offer.close());
    } else if (_loading && mounted) {
      _request++;
      setState(() {
        _loading = false;
        _message = '连接已停止，可以生成新的连接码';
      });
    }
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    _request++;
    if (_offer case final offer?) unawaited(offer.close());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final offer = _offer;
    final code = offer?.codes[_address];
    return ListView(
      shrinkWrap: true,
      padding: const EdgeInsets.all(24),
      children: [
        const AppText(
          '在其他设备上续读',
          style: TextStyle(fontSize: 21, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 12),
        const AppText('在另一台设备打开「我的 → 设置 → 局域网续读」并粘贴连接码。请保持此窗口打开。'),
        if (_loading) ...[
          const SizedBox(height: 14),
          const LinearProgressIndicator(),
        ],
        if (code != null) ...[
          const SizedBox(height: 14),
          SelectableText(code),
          GlassTextButton(
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: code));
              if (context.mounted) showNovelFeedback(context, '已复制续读连接码');
            },
            child: const AppText('复制连接码'),
          ),
          if (offer!.codes.length > 1)
            GlassTextButton(
              onPressed: () => setState(
                () => _address = (_address + 1) % offer.codes.length,
              ),
              child: const AppText('切换网络地址'),
            ),
          const AppText('连接码在接收完成或十分钟后失效。'),
        ],
        if (_message case final message?) ...[
          const SizedBox(height: 14),
          Semantics(liveRegion: true, child: AppText(message)),
        ],
        if (!_loading && code == null)
          GlassTextButton(
            onPressed: () {
              setState(() {
                _loading = true;
                _message = null;
              });
              unawaited(_start());
            },
            child: const AppText('生成新连接码'),
          ),
      ],
    );
  }
}
