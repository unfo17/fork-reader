import 'package:xml/xml.dart';

import 'local_epub_navigation.dart';

/// Adapts a Readium Web Publication Manifest to the renderer's resource index.
/// Hrefs are publication-root relative, never resolved against the OPF again.
class ReadiumEpubManifest {
  ReadiumEpubManifest(this.json);
  final Map<String, dynamic> json;

  static String path(String href) {
    final uri = Uri.parse(href);
    if (uri.hasScheme || uri.hasAuthority || uri.path.startsWith('/')) {
      throw const FormatException('EPUB 引用了外部资源');
    }
    final decoded = Uri.decodeComponent(uri.path);
    if (decoded.startsWith('/') ||
        decoded.split('/').contains('..') ||
        decoded.contains('\\') ||
        decoded.contains('\u0000')) {
      throw const FormatException('EPUB 资源路径无效');
    }
    return decoded;
  }

  String _text(Object? value) => switch (value) {
    String s => s,
    Map m => _text(m['name'] ?? m.values.firstOrNull),
    List l => l.map(_text).where((s) => s.isNotEmpty).join('、'),
    _ => '',
  };

  XmlDocument resourceIndex() {
    final builder = XmlBuilder();
    final metadata = json['metadata'] as Map? ?? {};
    final order = (json['readingOrder'] as List).cast<Map>();
    final resources = (json['resources'] as List? ?? []).cast<Map>();
    builder.element(
      'package',
      nest: () {
        builder.element(
          'metadata',
          nest: () {
            for (final entry in {
              'title': 'title',
              'creator': 'author',
              'description': 'description',
              'language': 'language',
            }.entries) {
              builder.element(entry.key, nest: _text(metadata[entry.value]));
            }
          },
        );
        builder.element(
          'manifest',
          nest: () {
            var index = 0;
            for (final link in [...order, ...resources]) {
              final href = link['href'] as String;
              try {
                path(href);
              } on FormatException {
                if (index < order.length) rethrow;
                index++;
                continue;
              }
              final rel = link['rel'];
              final cover = rel is List
                  ? rel.contains('cover')
                  : rel == 'cover';
              builder.element(
                'item',
                attributes: {
                  'id': 'r${index++}',
                  'href': href,
                  'media-type': link['type'] as String? ?? '',
                  if (cover) 'properties': 'cover-image',
                },
              );
            }
          },
        );
        builder.element(
          'spine',
          nest: () {
            for (var i = 0; i < order.length; i++) {
              builder.element('itemref', attributes: {'idref': 'r$i'});
            }
          },
        );
      },
    );
    return builder.buildDocument();
  }

  List<EpubNavigationLink> get navigation {
    final result = <EpubNavigationLink>[];
    void walk(List links, int depth) {
      if (depth > 32) return;
      for (final link in links.cast<Map>()) {
        if (result.length >= 10000) return;
        final href = link['href'] as String?;
        final title = _text(link['title']).trim();
        if (href != null && title.isNotEmpty) {
          try {
            final resource = path(href);
            final fragment = Uri.decodeComponent(Uri.parse(href).fragment);
            result.add((
              key: '$resource#$fragment',
              documentKey: '$resource#',
              title: title,
              depth: depth,
            ));
          } on FormatException {
            // External TOC links are not local reading destinations.
          }
        }
        walk(link['children'] as List? ?? [], depth + 1);
      }
    }

    walk(json['toc'] as List? ?? [], 0);
    return result;
  }
}
