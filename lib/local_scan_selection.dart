import 'package:flutter/material.dart';
import 'book_documents.dart';

class LocalScanSelection extends StatefulWidget {
  const LocalScanSelection({super.key, required this.snapshot});
  final BookScanSnapshot snapshot;
  @override
  State<LocalScanSelection> createState() => _SelectionState();
}

class _SelectionState extends State<LocalScanSelection> {
  late final selected = {for (final b in widget.snapshot.books) b.token};
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('选择要导入的书籍'),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, selected),
          child: Text('导入 (${selected.length})'),
        ),
      ],
    ),
    body: ListView(
      children: [
        for (final b in widget.snapshot.books)
          CheckboxListTile(
            title: Text(b.name),
            value: selected.contains(b.token),
            onChanged: (v) => setState(
              () =>
                  v == true ? selected.add(b.token) : selected.remove(b.token),
            ),
          ),
      ],
    ),
  );
}
