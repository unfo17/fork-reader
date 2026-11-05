import 'package:flutter/material.dart';
import 'library_state.dart';
import 'local_books.dart';

Future<void> openLocalBookBatch(
  BuildContext context, {
  required LocalBookStore books,
  required LibraryState library,
  Set<int> selected = const {},
}) => Navigator.of(context).push<void>(
  MaterialPageRoute(
    builder: (_) => _Batch(books: books, selected: selected),
  ),
);

class _Batch extends StatefulWidget {
  const _Batch({required this.books, required this.selected});
  final LocalBookStore books;
  final Set<int> selected;
  @override
  State<_Batch> createState() => _BatchState();
}

class _BatchState extends State<_Batch> {
  late final selected = {...widget.selected};
  bool busy = false;
  Future<void> remove() async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text('删除 ${selected.length} 本书？'),
        content: const Text('只删除书库中的副本，原文件不变。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (yes != true) return;
    setState(() => busy = true);
    try {
      for (final id in selected) await widget.books.remove(id);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) {
        setState(() => busy = false);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('$e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('批量整理'),
      actions: [
        TextButton(
          onPressed: busy || selected.isEmpty ? null : remove,
          child: const Text('删除所选'),
        ),
      ],
    ),
    body: ListView(
      children: [
        for (final b in widget.books.books)
          CheckboxListTile(
            title: Text(b.title),
            value: selected.contains(b.id),
            onChanged: busy
                ? null
                : (v) => setState(
                    () =>
                        v == true ? selected.add(b.id) : selected.remove(b.id),
                  ),
          ),
      ],
    ),
  );
}
