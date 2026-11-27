import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

import 'catalog.dart';
import 'library_state.dart';
import 'local_books.dart';

const _scheme = 'pagebetween';
const _manifestLimit = 24 * 1024 * 1024;
const _transferLimit = 2 * 1024 * 1024 * 1024;
const _timeout = Duration(seconds: 20);
const _offerTime = Duration(minutes: 10);
final _cipher = Chacha20.poly1305Aead();
final _hmac = Hmac.sha256();

String _encode(List<int> bytes) => base64UrlEncode(bytes).replaceAll('=', '');
List<int> _decode(String text) => base64Url.decode(base64Url.normalize(text));

bool _localAddress(InternetAddress address) {
  if (address.type != InternetAddressType.IPv4) return false;
  final bytes = address.rawAddress;
  return bytes[0] == 10 ||
      bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31 ||
      bytes[0] == 192 && bytes[1] == 168 ||
      bytes[0] == 169 && bytes[1] == 254;
}

Future<String> _proof(SecretKey key, String path) async => _encode(
  (await _hmac.calculateMac(
    utf8.encode('request:$path'),
    secretKey: key,
  )).bytes,
);

bool _matches(String? value, String expected) {
  if (value == null || value.length != expected.length) return false;
  var difference = 0;
  for (var i = 0; i < expected.length; i++) {
    difference |= value.codeUnitAt(i) ^ expected.codeUnitAt(i);
  }
  return difference == 0;
}

typedef NearbyReadingReceipt = ({
  Novel book,
  ReadingPosition position,
  Map<String, dynamic>? locator,
});

/// A temporary, foreground-only offer for one book. The key travels in the
/// manually copied code. Requests prove possession without sending that key,
/// and every response is authenticated and encrypted independently.
class NearbyReadingOffer {
  NearbyReadingOffer._(this._server, this._key, this.codes);

  final HttpServer _server;
  final SecretKey _key;
  final List<String> codes;
  final _completed = Completer<bool>();
  late final Timer _expiry;
  bool _closed = false, _serving = false;
  Future<bool> get completed => _completed.future;

  static Future<NearbyReadingOffer> start({
    required LocalBookStore books,
    required int bookId,
    required ReadingPosition position,
    Map<String, dynamic>? locator,
  }) async {
    final addresses = {
      for (final interface in await NetworkInterface.list(
        type: InternetAddressType.IPv4,
      ))
        for (final address in interface.addresses)
          if (_localAddress(address)) address.address,
    };
    if (addresses.isEmpty) throw const FormatException('请先连接同一 Wi-Fi');
    final manifest = await books.prepareHandoff(bookId);
    final bytes = utf8.encode(
      jsonEncode({
        'version': 1,
        'book': manifest.toJson(),
        'position': position.toJson(),
        'locator': ?locator,
      }),
    );
    if (bytes.length > _manifestLimit) throw const FormatException('书籍目录过大');
    final key = await _cipher.newSecretKey();
    final secret = _encode(await key.extractBytes());
    final server = await HttpServer.bind(InternetAddress.anyIPv4, 0);
    server.idleTimeout = _timeout;
    final offer = NearbyReadingOffer._(server, key, [
      for (final address in addresses)
        Uri(
          scheme: _scheme,
          host: address,
          port: server.port,
          path: '/$secret',
        ).toString(),
    ]);
    offer._expiry = Timer(_offerTime, () => unawaited(offer.close()));
    server.listen(
      (request) => unawaited(offer._serve(request, books, manifest, bytes)),
      onError: (Object _) => unawaited(offer.close()),
    );
    return offer;
  }

  Future<void> _serve(
    HttpRequest request,
    LocalBookStore books,
    LocalBookHandoff manifest,
    List<int> bytes,
  ) async {
    final response = request.response;
    final path = request.uri.path;
    try {
      final remote = request.connectionInfo?.remoteAddress;
      if (_closed ||
          remote == null ||
          !_localAddress(remote) ||
          request.method != 'GET' ||
          request.uri.hasQuery ||
          !path.startsWith('/resume/') ||
          path.length > 64 ||
          !_matches(
            request.headers.value(HttpHeaders.authorizationHeader),
            await _proof(_key, path),
          )) {
        response.statusCode = HttpStatus.notFound;
        await response.close();
        return;
      }
      if (_closed || _serving) {
        response.statusCode = HttpStatus.conflict;
        await response.close();
        return;
      }
      _serving = true;
      try {
        if (path == '/resume/received') {
          await _send(response, path, Stream.value(const []), 0);
          await close(received: true);
        } else if (path == '/resume/book') {
          await _send(response, path, Stream.value(bytes), bytes.length);
        } else {
          final index = int.tryParse(path.substring('/resume/'.length));
          if (index == null || index < 0 || index >= manifest.files.length) {
            response.statusCode = HttpStatus.notFound;
            await response.close();
            return;
          }
          final file = manifest.files[index];
          await _send(response, path, books.openHandoffFile(file), file.length);
        }
      } finally {
        _serving = false;
      }
    } catch (_) {
      // A cancelled peer or changed source must not leave the offer busy.
      await response.close().catchError((Object _) {});
    }
  }

  Future<void> _send(
    HttpResponse response,
    String path,
    Stream<List<int>> source,
    int length,
  ) async {
    final nonce = _cipher.newNonce();
    response.headers
      ..set('x-reading-nonce', _encode(nonce))
      ..set(HttpHeaders.cacheControlHeader, 'no-store');
    response.contentLength = length + _cipher.macAlgorithm.macLength;
    response.bufferOutput = false;
    late Mac mac;
    await response.addStream(
      _cipher.encryptStream(
        source,
        secretKey: _key,
        nonce: nonce,
        aad: utf8.encode(path),
        onMac: (value) => mac = value,
      ),
    );
    response.add(mac.bytes);
    await response.close();
  }

  Future<void> close({bool received = false}) async {
    if (_closed) return;
    _closed = true;
    _expiry.cancel();
    _completed.complete(received);
    await _server.close(force: !received);
  }
}

class NearbyReadingReceiver {
  NearbyReadingReceiver._(this._endpoint, this._key) {
    _client.connectionTimeout = _timeout;
    _client.findProxy = (_) => 'DIRECT';
  }

  factory NearbyReadingReceiver(String code) {
    final uri = code.length <= 256 ? Uri.tryParse(code.trim()) : null;
    final address = uri == null ? null : InternetAddress.tryParse(uri.host);
    if (uri == null ||
        uri.scheme != _scheme ||
        address == null ||
        !_localAddress(address) ||
        !uri.hasPort ||
        uri.port < 1 ||
        uri.port > 65535 ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        !RegExp(r'^/[a-zA-Z0-9_-]{43}$').hasMatch(uri.path)) {
      throw const FormatException('续读连接码有误');
    }
    final key = SecretKey(_decode(uri.path.substring(1)));
    return NearbyReadingReceiver._(
      Uri(scheme: 'http', host: address.address, port: uri.port),
      key,
    );
  }

  final Uri _endpoint;
  final SecretKey _key;
  final _client = HttpClient();
  bool _closed = false;

  Stream<List<int>> _read(String path, int limit, {int? exact}) async* {
    final request = await _client.getUrl(_endpoint.replace(path: path));
    request.followRedirects = false;
    request.headers.set(
      HttpHeaders.authorizationHeader,
      await _proof(_key, path),
    );
    final response = await request.close().timeout(_timeout);
    final length = response.contentLength - _cipher.macAlgorithm.macLength;
    if (response.statusCode != HttpStatus.ok ||
        length < 0 ||
        length > limit ||
        (exact != null && exact != length)) {
      throw const FormatException('续读数据无效或连接已失效');
    }
    final nonce = _decode(response.headers.value('x-reading-nonce') ?? '');
    if (nonce.length != _cipher.nonceLength) {
      throw const FormatException('续读数据无效');
    }
    final mac = Completer<Mac>();
    Stream<List<int>> ciphertext() async* {
      final tail = BytesBuilder(copy: false);
      var offset = 0;
      await for (final part in response.timeout(_timeout)) {
        final available = (length - offset).clamp(0, part.length);
        if (available > 0) yield part.sublist(0, available);
        if (available < part.length) {
          if (tail.length + part.length - available >
              _cipher.macAlgorithm.macLength) {
            throw const FormatException('续读数据无效');
          }
          tail.add(part.sublist(available));
        }
        offset += available;
      }
      if (offset != length || tail.length != _cipher.macAlgorithm.macLength) {
        throw const FormatException('续读连接已中断');
      }
      mac.complete(Mac(tail.takeBytes()));
    }

    yield* _cipher.decryptStream(
      ciphertext(),
      secretKey: _key,
      nonce: nonce,
      mac: mac.future,
      aad: utf8.encode(path),
    );
  }

  Future<NearbyReadingReceipt> receive(
    LocalBookStore books, {
    required Future<bool> Function(String title, int bytes) confirm,
    void Function(int received, int total)? onProgress,
  }) async {
    final data = await _read(
      '/resume/book',
      _manifestLimit,
    ).fold(BytesBuilder(copy: false), (result, bytes) => result..add(bytes));
    final metadata = jsonDecode(utf8.decode(data.takeBytes()));
    if (metadata is! Map ||
        metadata['version'] != 1 ||
        metadata['position'] is! Map<String, dynamic>) {
      throw const FormatException('续读位置无效');
    }
    final manifest = LocalBookHandoff.fromJson(metadata['book']);
    final position = ReadingPosition.fromJson(metadata['position']);
    final locator = metadata['locator'];
    final needed = books.neededHandoffFiles(manifest);
    final missing = {for (final file in needed) file.key};
    final length = needed.fold(0, (sum, file) => sum + file.length);
    if (length > _transferLimit) throw const FormatException('待接收书籍超过传输大小上限');
    if (!await confirm(manifest.title, length) || _closed) {
      throw const NearbyReadingCancelled();
    }
    final staging = await books.directory.createTemp('nearby-');
    try {
      var received = 0;
      for (var index = 0; index < manifest.files.length; index++) {
        final file = manifest.files[index];
        if (!missing.contains(file.key)) continue;
        final target = File('${staging.path}/${file.key}');
        await target.parent.create(recursive: true);
        final sink = target.openWrite();
        try {
          await sink.addStream(
            _read('/resume/$index', file.length, exact: file.length),
          );
        } finally {
          await sink.close();
        }
        received += file.length;
        onProgress?.call(received, length);
      }
      if (_closed) throw const NearbyReadingCancelled();
      final book = await books.receiveHandoff(manifest, staging);
      // Completion feedback on the sender must not invalidate a finished import.
      try {
        await _read(
          '/resume/received',
          0,
        ).drain<void>().timeout(const Duration(seconds: 4));
      } catch (_) {}
      return (
        book: book,
        position: position,
        locator: locator is Map<String, dynamic> ? locator : null,
      );
    } finally {
      await staging.delete(recursive: true);
    }
  }

  void close() {
    _closed = true;
    _client.close(force: true);
  }
}

class NearbyReadingCancelled implements Exception {
  const NearbyReadingCancelled();
}
