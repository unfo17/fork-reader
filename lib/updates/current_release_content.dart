import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _menus = '恢复原版阅读控件的磨砂效果、菜单位置与展开动画。';
const _insets = '阅读正文保持上下边距，菜单动画期间暂停后台预载。';
const _swipes = '减少插图与文字之间段落接续的卡顿，翻页确认时震动。';
const _catalogue = '原版折叠分组行显示 EPUB 多级目录，图书详情和阅读页共用目录结构。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_menus
- $_insets
- $_swipes
- $_catalogue

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _menus,
  _insets,
  _swipes,
  _catalogue,
];
const currentReleaseSections = [
  ReleaseSection('欢迎使用$appDisplayName', [
    currentReleaseSourcePolicy,
    projectFreeNotice,
    projectRightsNotice,
    projectDataNotice,
  ]),
  ReleaseSection('本次更新', [
    _menus,
    _insets,
    _swipes,
    _catalogue,
    _androidInstall,
  ]),
];
