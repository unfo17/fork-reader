import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'app_interactions.dart';
import 'app_ui.dart';
import 'archive_feature_ui.dart';
import 'backup_page.dart';
import 'dictionary_sheet.dart';
import 'font_settings_page.dart';
import 'frame_rate_settings.dart';
import 'haptic_settings_page.dart';
import 'legacy_import_page.dart';
import 'library_state.dart';
import 'listening/custom_tts_page.dart';
import 'listening/widgets.dart';
import 'local_platform.dart';
import 'low_performance_page.dart';
import 'palette_settings_page.dart';
import 'reader_illustrations.dart';
import 'reading_moments_settings_page.dart';
import 'reading_widget_controller.dart' show ReadingWidgetBridge;
import 'reading_widget_settings_page.dart';
import 'setting_menu.dart';
import 'ui_notifiers.dart';

class SettingsPage extends StatelessWidget {
  const SettingsPage({
    super.key,
    required this.state,
    this.onOpenReadingHistory,
    this.cachePageBuilder,
    this.showHeader = true,
  });
  final LibraryState state;
  final void Function(BuildContext)? onOpenReadingHistory;
  final WidgetBuilder? cachePageBuilder;
  final bool showHeader;

  Widget buildHeader(BuildContext context) => Align(
    alignment: Alignment.centerLeft,
    child: const ArchiveMasthead(title: '设置', code: '04 / SYSTEM PROFILE'),
  );

  @override
  Widget build(BuildContext context) => ActiveListenableBuilder(
    listenable: state.settingsChanges,
    builder: (context) => GlassHeaderLayout(
      showHeader: showHeader,
      header: buildHeader(context),
      body: ListView(
        key: const PageStorageKey('settings'),
        padding: EdgeInsets.fromLTRB(
          24,
          MediaQuery.paddingOf(context).top + FrostedHeader.toolbarHeight + 20,
          24,
          MediaQuery.paddingOf(context).bottom + 120,
        ),
        children: [
          _sectionEntry(
            context,
            id: 'usage',
            title: '使用方式',
            subtitle: '启动与续读、阅读偏好',
            code: 'SYSTEM / USAGE',
            icon: CupertinoIcons.square_grid_2x2,
            options: _usageOptions,
          ),
          const SizedBox(height: 14),
          _sectionEntry(
            context,
            id: 'reading',
            title: '阅读与外观',
            subtitle: '外观模式、主题配色、字体与听书',
            code: 'SYSTEM / READING',
            icon: CupertinoIcons.textformat,
            options: _readingOptions,
          ),
          const SizedBox(height: 14),
          _sectionEntry(
            context,
            id: 'effects',
            title: '性能与动效',
            subtitle: '振动反馈、帧率偏好与动态效果',
            code: 'SYSTEM / EFFECTS',
            icon: CupertinoIcons.speedometer,
            options: _effectsOptions,
          ),
          if (ReadingWidgetBridge.supported) ...[
            const SizedBox(height: 14),
            FeatureEntry(
              key: const ValueKey('settings-reading-widgets'),
              title: '小组件',
              subtitle: '配置桌面上的阅读主题与隐私',
              icon: CupertinoIcons.square_grid_2x2,
              onTap: () => pushNovelPage<void>(
                context,
                ReadingWidgetSettingsPage(state: state),
                reduceMotion: state.reduceMotion,
                slot: ('settings', 'reading-widgets'),
              ),
            ),
          ],
          const SizedBox(height: 14),
          _sectionEntry(
            context,
            id: 'data',
            title: '数据与存储',
            subtitle: '阅读历史、缓存管理与备份恢复',
            code: 'SYSTEM / STORAGE',
            icon: CupertinoIcons.folder,
            options: _dataOptions,
          ),
        ],
      ),
    ),
  );

  Widget _sectionEntry(
    BuildContext context, {
    required String id,
    required String title,
    required String subtitle,
    required String code,
    required IconData icon,
    required List<Widget> Function(BuildContext) options,
  }) => FeatureEntry(
    key: ValueKey('settings-$id-entry'),
    title: title,
    subtitle: subtitle,
    icon: icon,
    onTap: () => pushNovelPage<void>(
      context,
      ArchiveFeaturePage(
        key: ValueKey('settings-$id-page'),
        title: title,
        code: code,
        child: ActiveListenableBuilder(
          listenable: state.settingsChanges,
          builder: (context) => Align(
            alignment: Alignment.topCenter,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: ListView(
                key: PageStorageKey('settings-$id'),
                padding: featurePadding(context).add(
                  EdgeInsets.only(
                    left: MediaQuery.paddingOf(context).left,
                    right: MediaQuery.paddingOf(context).right,
                  ),
                ),
                children: options(context),
              ),
            ),
          ),
        ),
      ),
      reduceMotion: state.reduceMotion,
      slot: ('settings', id),
    ),
  );

  List<Widget> _usageOptions(BuildContext context) => [
    ContentPanel(
      child: Column(
        children: [
          if (LocalPlatform.readerAppearance) ...[
            _toggleSetting(
              context,
              keyName: 'settings-force-portrait',
              icon: CupertinoIcons.lock_rotation,
              title: '强制竖屏',
              description: '仅本 App 固定竖屏，关闭后跟随系统',
              value: state.forcePortrait,
              onChanged: state.setForcePortrait,
            ),
            const Divider(height: 22),
          ],
          _toggleSetting(
            context,
            keyName: 'settings-direct-reading',
            icon: CupertinoIcons.book_fill,
            title: '点书直接阅读',
            description: '点按本地图书，从上次的位置继续阅读',
            value: state.directReadingOn(ReadingEntry.local),
            onChanged: (enabled) => state.setDirectReadingPages(
              enabled ? const {ReadingEntry.local} : const {},
            ),
          ),
          const Divider(height: 22),
          const Row(
            key: ValueKey('settings-start-page'),
            children: [
              ArchiveIcon(CupertinoIcons.house, size: 21),
              SizedBox(width: 12),
              Expanded(
                child: AppText(
                  '启动页面',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
              AppText('本地'),
            ],
          ),
          const Divider(height: 22),
          _toggleSetting(
            context,
            keyName: 'settings-open-recent-on-launch',
            icon: CupertinoIcons.clock,
            title: '启动时继续阅读',
            description: '打开应用后进入最近读过的书',
            value: state.openRecentOnLaunch,
            onChanged: state.setOpenRecentOnLaunch,
          ),
        ],
      ),
    ),
  ];

  List<Widget> _readingOptions(BuildContext context) => [
    FeatureEntry(
      key: const ValueKey('settings-reading-moments'),
      title: '阅读纪念',
      subtitle: '纪念风格、阅读里程碑与书页终点',
      icon: CupertinoIcons.sparkles,
      onTap: () => pushNovelPage<void>(
        context,
        ReadingMomentsSettingsPage(state: state),
        reduceMotion: state.reduceMotion,
        slot: ('settings', 'reading-moments'),
      ),
    ),
    const SizedBox(height: 14),
    ContentPanel(
      child: Column(
        children: [
          _appearanceSetting(context),
          const Divider(height: 22),
          JellyTapSurface(
            key: const ValueKey('settings-palette'),
            onTap: () => pushNovelPage<void>(
              context,
              PaletteSettingsPage(state: state),
              reduceMotion: state.effectiveReduceMotion,
            ),
            child: const Padding(
              padding: EdgeInsets.symmetric(vertical: 10),
              child: Row(
                children: [
                  ArchiveIcon(CupertinoIcons.paintbrush, size: 21),
                  SizedBox(width: 12),
                  Expanded(
                    child: AppText(
                      '主题配色',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
                  ArchiveIcon(CupertinoIcons.chevron_forward, size: 14),
                ],
              ),
            ),
          ),
          const Divider(height: 22),
          LanguageSettingMenu(
            key: const ValueKey('settings-language'),
            state: state,
          ),
          const Divider(height: 22),
          JellyTapSurface(
            key: const ValueKey('settings-fonts'),
            onTap: () => pushNovelPage<void>(
              context,
              FontSettingsPage(state: state),
              reduceMotion: state.reduceMotion,
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Row(
                children: [
                  const ArchiveIcon(CupertinoIcons.textformat, size: 21),
                  const SizedBox(width: 12),
                  Expanded(
                    child: const AppText(
                      '字体',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
                  const SizedBox(width: 8),
                  const ArchiveIcon(CupertinoIcons.chevron_forward, size: 14),
                ],
              ),
            ),
          ),
          const Divider(height: 22),
          JellyTapSurface(
            key: const ValueKey('settings-dictionaries'),
            onTap: () => showDictionaryManagementSheet(
              context: context,
              reduceMotion: state.reduceMotion,
            ),
            child: const Padding(
              padding: EdgeInsets.symmetric(vertical: 10),
              child: Row(
                children: [
                  ArchiveIcon(CupertinoIcons.book, size: 21),
                  SizedBox(width: 12),
                  Expanded(
                    child: AppText(
                      '离线词典',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
                  ArchiveIcon(CupertinoIcons.chevron_forward, size: 14),
                ],
              ),
            ),
          ),
        ],
      ),
    ),
    if (LocalPlatform.listening) ...[
      if (ListeningScope.maybeOf(context) case final listening?)
        if (listening.customSpeech case final settings?) ...[
          const SizedBox(height: 14),
          FeatureEntry(
            title: '自定义语音接口',
            subtitle: 'Microsoft SSML 兼容服务',
            icon: CupertinoIcons.waveform,
            onTap: () => pushNovelPage<void>(
              context,
              CustomSpeechPage(settings: settings, controller: listening),
              reduceMotion: state.reduceMotion,
            ),
          ),
        ],
    ],
  ];

  List<Widget> _effectsOptions(BuildContext context) => [
    sectionHeader(context, '显示流畅度', ''),
    const SizedBox(height: 14),
    const FrameRateOptions(),
    const SizedBox(height: 14),
    ContentPanel(
      key: const ValueKey('settings-performance'),
      onTap: () => pushNovelPage<void>(
        context,
        LowPerformancePage(state: state),
        reduceMotion: state.reduceMotion,
      ),
      child: const Row(
        children: [
          ArchiveIcon(CupertinoIcons.speedometer, size: 21),
          SizedBox(width: 12),
          Expanded(
            child: AppText(
              '低性能设备模式',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          ArchiveIcon(CupertinoIcons.chevron_right, size: 14),
        ],
      ),
    ),
    const SizedBox(height: 24),
    sectionHeader(context, '视觉效果', '玻璃外观 · 实时预览'),
    const SizedBox(height: 14),
    ContentPanel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _glassSettingLabel(context, '模糊度', '${state.glassBlur.round()}'),
          AppControls.slider(
            context,
            key: const ValueKey('glass-blur-slider'),
            value: state.glassBlur,
            min: 0,
            max: 24,
            divisions: 24,
            onChanged: (value) => state.setGlassBlur(value, persist: false),
            onChangeEnd: state.setGlassBlur,
          ),
          const Divider(height: 22),
          _glassSettingLabel(
            context,
            '不透明度',
            '${(state.glassOpacity * 100).round()}%',
          ),
          AppControls.slider(
            context,
            key: const ValueKey('glass-opacity-slider'),
            value: state.glassOpacity,
            min: 0,
            max: 1,
            divisions: 100,
            onChanged: (value) => state.setGlassOpacity(value, persist: false),
            onChangeEnd: state.setGlassOpacity,
          ),
          GlassTextButton(
            onPressed: () async {
              await state.setGlassBlur(8);
              await state.setGlassOpacity(.87);
            },
            child: const AppText('恢复默认'),
          ),
        ],
      ),
    ),
    const SizedBox(height: 24),
    sectionHeader(context, '交互动效', ''),
    const SizedBox(height: 14),
    ContentPanel(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Row(
              children: [
                ArchiveIcon(
                  CupertinoIcons.circle_grid_3x3,
                  color: Theme.of(context).colorScheme.primary,
                  size: 21,
                ),
                const SizedBox(width: 12),
                const Expanded(
                  child: AppText(
                    '减少动态效果',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                AppControls.toggle(
                  context,
                  value: state.reduceMotion,
                  onChanged: (value) => _savePreference(
                    context,
                    () => state.setReduceMotion(value),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
    const SizedBox(height: 24),
    sectionHeader(context, '触感反馈', ''),
    const SizedBox(height: 14),
    ContentPanel(
      key: const ValueKey('settings-haptics'),
      onTap: () => pushNovelPage<void>(
        context,
        HapticSettingsPage(state: state),
        reduceMotion: state.reduceMotion,
      ),
      child: Row(
        children: [
          const ArchiveIcon(CupertinoIcons.waveform, size: 21),
          const SizedBox(width: 12),
          const Expanded(
            child: AppText(
              '振动反馈',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          AppText(state.haptics.enabled ? '已开启' : '已关闭'),
          const SizedBox(width: 10),
          const ArchiveIcon(CupertinoIcons.chevron_right, size: 14),
        ],
      ),
    ),
  ];

  List<Widget> _dataOptions(BuildContext context) => [
    if (onOpenReadingHistory case final open?) ...[
      sectionHeader(context, '阅读记录', ''),
      const SizedBox(height: 14),
      ContentPanel(
        onTap: () => open(context),
        child: const Row(
          children: [
            ArchiveIcon(CupertinoIcons.clock, size: 21),
            SizedBox(width: 12),
            Expanded(
              child: AppText(
                '阅读历史',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
            ArchiveIcon(CupertinoIcons.chevron_right, size: 14),
          ],
        ),
      ),
      const SizedBox(height: 24),
    ],
    if (cachePageBuilder case final buildCachePage?) ...[
      sectionHeader(context, '离线阅读', ''),
      const SizedBox(height: 14),
      ContentPanel(
        onTap: () => pushNovelPage<void>(
          context,
          buildCachePage(context),
          reduceMotion: state.reduceMotion,
        ),
        child: const Row(
          children: [
            ArchiveIcon(CupertinoIcons.cloud_download, size: 21),
            SizedBox(width: 12),
            Expanded(
              child: AppText(
                '缓存管理',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
            ArchiveIcon(CupertinoIcons.chevron_right, size: 14),
          ],
        ),
      ),
      const SizedBox(height: 24),
    ],
    sectionHeader(context, '备份与清理', ''),
    const SizedBox(height: 14),
    ContentPanel(
      key: const ValueKey('settings-backup'),
      onTap: () => pushNovelPage<void>(
        context,
        LibraryBackupPage(state: state),
        reduceMotion: state.reduceMotion,
      ),
      child: const Row(
        children: [
          ArchiveIcon(CupertinoIcons.folder, size: 21),
          SizedBox(width: 12),
          Expanded(
            child: AppText(
              '备份与恢复',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          ArchiveIcon(CupertinoIcons.chevron_right, size: 14),
        ],
      ),
    ),
    if (LocalPlatform.android) ...[
      const SizedBox(height: 14),
      FeatureEntry(
        key: const ValueKey('settings-legacy-import'),
        title: '导入旧版文库记录',
        icon: CupertinoIcons.folder_badge_plus,
        subtitle: '预览后合并，保留较新的阅读进度',
        onTap: () => pushNovelPage<void>(
          context,
          LegacyImportPage(state: state),
          reduceMotion: state.reduceMotion,
        ),
      ),
      const SizedBox(height: 14),
      FeatureEntry(
        title: '清理插图内存',
        icon: CupertinoIcons.photo,
        subtitle: '释放临时插图内存，保留图书和阅读记录',
        onTap: () async {
          if (!await confirmNovelAction(
            context,
            title: '清理临时插图内存？',
            message: '下次打开插图时会重新读取文件，原书和阅读记录会保留。',
            confirmLabel: '清理内存',
            reduceMotion: state.reduceMotion,
          )) {
            return;
          }
          try {
            ReaderIllustrationCache.shared.clearMemory();
            if (context.mounted) showNovelFeedback(context, '插图内存已清理');
          } catch (_) {
            if (context.mounted) showNovelFeedback(context, '缓存清理失败，请重试');
          }
        },
      ),
    ],
  ];

  Widget _appearanceSetting(BuildContext context) =>
      GlassSettingMenu<ThemeMode>(
        key: const ValueKey('settings-appearance'),
        icon: CupertinoIcons.moon_stars,
        label: '外观模式',
        value: state.theme,
        options: const {
          ThemeMode.system: '跟随系统',
          ThemeMode.light: '浅色',
          ThemeMode.dark: '深色',
        },
        reduceMotion: state.reduceMotion,
        onChanged: (value) =>
            _savePreference(context, () => state.setTheme(value)),
      );

  Widget _glassSettingLabel(BuildContext context, String title, String value) =>
      Row(
        children: [
          Expanded(
            child: AppText(
              title,
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          AppText(
            value,
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      );

  Widget _toggleSetting(
    BuildContext context, {
    required String keyName,
    required IconData icon,
    required String title,
    required String description,
    required bool value,
    required Future<void> Function(bool) onChanged,
  }) => MergeSemantics(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            ArchiveIcon(icon, size: 21),
            const SizedBox(width: 12),
            Expanded(
              child: AppText(
                title,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
            const SizedBox(width: 12),
            AppControls.toggle(
              context,
              key: ValueKey(keyName),
              value: value,
              onChanged: (value) =>
                  _savePreference(context, () => onChanged(value)),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.only(left: 33, top: 4),
          child: AppText(
            description,
            style: TextStyle(
              fontSize: 12,
              height: 1.6,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ],
    ),
  );

  Future<void> _savePreference(
    BuildContext context,
    Future<void> Function() save,
  ) async {
    try {
      await save();
    } catch (_) {
      if (context.mounted) showNovelFeedback(context, '设置保存失败，请重试');
    }
  }
}
