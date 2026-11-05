/// File imports stream TXT and EPUB entries off the UI isolate.
const maxLocalTextBytes = 128 * 1024 * 1024;
const maxLocalEpubBytes = 512 * 1024 * 1024;
const maxLocalArchiveBytes = 512 * 1024 * 1024;
const bookFormatLabels = <String, String>{
  'epub': 'EPUB',
  'txt': 'TXT',
  'zip': 'ZIP',
};
String bookFormatKey(String name) =>
    switch (name.split('.').last.toLowerCase()) {
      'mobi' || 'azw' || 'azw3' || 'prc' => 'kindle',
      'html' || 'htm' || 'xhtml' => 'html',
      'md' || 'markdown' => 'markdown',
      final value => value,
    };
Set<String> bookFormatExtensions(Iterable<String> formats) => {
  for (final format in formats)
    ...switch (format) {
      'kindle' => ['mobi', 'azw', 'azw3', 'prc'],
      'html' => ['html', 'htm', 'xhtml'],
      'markdown' => ['md', 'markdown'],
      _ => [format],
    },
};
const bookImportFormatsSummary = '预览版支持 TXT、EPUB 和 ZIP 批量导入';
const iosBookImportFormatsSummary = bookImportFormatsSummary;
const bookImportLimitsSummary =
    'TXT：128 MiB；EPUB / ZIP：512 MiB；ZIP 展开后不超过 1 GiB。';
const iosBookImportLimitsSummary = bookImportLimitsSummary;
bool supportsBookImport(String name) =>
    RegExp(r'\.(txt|epub|zip)$', caseSensitive: false).hasMatch(name);

int localBookByteLimit(String name) =>
    switch (name.split('.').last.toLowerCase()) {
      'zip' => maxLocalArchiveBytes,
      'epub' || 'pdf' || 'docx' => maxLocalEpubBytes,
      'mobi' || 'azw' || 'azw3' || 'prc' => 256 * 1024 * 1024,
      'html' || 'htm' || 'xhtml' || 'md' || 'markdown' => 32 * 1024 * 1024,
      _ => maxLocalTextBytes,
    };

String localBookSizeMessage(String name) =>
    '单个 ${name.split('.').last.toUpperCase()} 不能超过 ${localBookByteLimit(name) ~/ (1024 * 1024)} MiB';
