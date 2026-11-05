import 'dart:io';

import 'package:xml/xml.dart';
import 'package:wenku8_flutter/readium_epub_manifest.dart';

void check(bool condition, String message) {
  if (!condition) throw StateError(message);
}

void main() {
  final manifest = ReadiumEpubManifest({
    'metadata': {
      'title': {'zh': '测试 & 图书'},
      'author': [
        {'name': '作者甲'},
        {
          'name': {'zh': '作者乙'},
        },
      ],
      'language': ['zh'],
    },
    'readingOrder': [
      {'href': 'OPS/part%202.xhtml', 'type': 'application/xhtml+xml'},
      {'href': 'OPS/part1.xhtml', 'type': 'application/xhtml+xml'},
    ],
    'resources': [
      {'href': 'https://example.com/optional.css', 'type': 'text/css'},
      {
        'href': 'OPS/cover.jpg',
        'type': 'image/jpeg',
        'rel': ['cover'],
      },
    ],
    'toc': [
      {
        'href': 'OPS/part%202.xhtml#%E7%AB%A0',
        'title': '第二章',
        'children': [
          {'href': 'OPS/part1.xhtml', 'title': '第一章'},
        ],
      },
      {'href': 'https://example.com/', 'title': '网站'},
    ],
  });
  final index = manifest.resourceIndex();
  check(
    index.findAllElements('title').single.innerText == '测试 & 图书',
    'Localized title',
  );
  check(
    index.findAllElements('creator').single.innerText == '作者甲、作者乙',
    'Multiple authors',
  );
  final resources = index.findAllElements('item').toList();
  check(resources.length == 3, 'Remote optional resource excluded');
  check(
    resources[0].getAttribute('href') == 'OPS/part%202.xhtml',
    'Readium spine order',
  );
  check(
    resources.last.getAttribute('id') == 'r3',
    'Skipped resources keep stable IDs',
  );
  check(
    resources.last.getAttribute('properties') == 'cover-image',
    'Readium cover relation',
  );
  check(
    index
            .findAllElements('itemref')
            .map((e) => e.getAttribute('idref'))
            .join(',') ==
        'r0,r1',
    'Spine references',
  );
  final toc = manifest.navigation;
  check(toc.length == 2, 'External TOC omitted');
  check(toc.first.key == 'OPS/part 2.xhtml#章', 'Encoded href and fragment');
  check(toc.last.depth == 1, 'Nested TOC depth');
  for (final href in [
    'https://example.com/a',
    '//example.com/a',
    '../a',
    '%2E%2E/a',
    '%2Fa',
    'a%5Cb',
    'a%00b',
  ]) {
    var rejected = false;
    try {
      ReadiumEpubManifest.path(href);
    } on FormatException {
      rejected = true;
    }
    check(rejected, 'Unsafe href rejected: $href');
  }
  final invalid = ReadiumEpubManifest({
    'readingOrder': [
      {'href': 'https://example.com/body.xhtml'},
    ],
  });
  var rejected = false;
  try {
    invalid.resourceIndex();
  } on FormatException {
    rejected = true;
  }
  check(rejected, 'Remote spine rejected instead of silently dropping prose');
  stdout.writeln('Readium manifest verification passed (18 checks).');
}
