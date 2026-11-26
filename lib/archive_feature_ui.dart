import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_ui.dart';
import 'library_workspace.dart';

Future<bool> confirmNovelAction(
  BuildContext context, {
  required String title,
  required String message,
  required String confirmLabel,
  bool reduceMotion = false,
}) async =>
    await showJellySheet<bool>(
      context: context,
      reduceMotion: reduceMotion,
      builder: (sheet) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AppText(
              title,
              style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 14),
            AppText(message),
            const SizedBox(height: 24),
            GlassTextButton(
              onPressed: () => Navigator.pop(sheet, true),
              haptic: HapticCue.warning,
              child: AppText(confirmLabel),
            ),
            const SizedBox(height: 8),
            GlassTextButton(
              onPressed: () => Navigator.pop(sheet, false),
              child: const AppText('取消'),
            ),
          ],
        ),
      ),
    ) ==
    true;

class ArchiveFeaturePage extends StatelessWidget {
  const ArchiveFeaturePage({
    super.key,
    required this.title,
    required this.code,
    required this.child,
    this.actions = const [],
    this.canPop = true,
  });
  final String title, code;
  final Widget child;
  final List<Widget> actions;
  final bool canPop;
  @override
  Widget build(BuildContext context) => LibraryWorkspace(
    child: Scaffold(
      body: Atmosphere(
        child: GlassHeaderLayout(
          header: ArchiveHeaderBar(
            leading: GlassIconButton(
              icon: CupertinoIcons.back,
              label: '返回',
              onPressed: canPop ? () => Navigator.pop(context) : null,
            ),
            title: ArchiveRouteTitle(title: title, code: code),
            actions: actions,
          ),
          body: child,
        ),
      ),
    ),
  );
}

EdgeInsets featurePadding(BuildContext context) => EdgeInsets.fromLTRB(
  24,
  MediaQuery.paddingOf(context).top + FrostedHeader.toolbarHeight + 20,
  24,
  MediaQuery.paddingOf(context).bottom + 32,
);

class FeatureEntry extends StatelessWidget {
  const FeatureEntry({
    super.key,
    required this.title,
    required this.icon,
    required this.onTap,
    this.subtitle,
  });
  final String title;
  final String? subtitle;
  final IconData icon;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => ContentPanel(
    onTap: onTap,
    child: ArchivePart(
      start: .18,
      end: .84,
      travel: const Offset(0, 6),
      child: Row(
        children: [
          ArchiveIcon(icon, size: 21),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                AppText(
                  title,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                if (subtitle != null) ...[
                  const SizedBox(height: 5),
                  AppText(
                    subtitle!,
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          const ArchiveIcon(CupertinoIcons.chevron_right, size: 14),
        ],
      ),
    ),
  );
}

class FeatureError extends StatelessWidget {
  const FeatureError(this.text, {super.key});
  final String? text;
  @override
  Widget build(BuildContext context) => text == null
      ? const SizedBox.shrink()
      : Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Semantics(
            liveRegion: true,
            child: AppText(
              text!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        );
}
