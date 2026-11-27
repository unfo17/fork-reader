import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _opening = '首页恢复原项目的合并书架，本地书籍和已缓存的收藏共用分类、筛选和搜索。';
const _swipes = '系统配色与系统纸色同时开启时，EPUB 和 TXT 的长按选区底色跟随莫奈配色。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_opening
- $_swipes

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _opening,
  _swipes,
];
const currentReleaseSections = [
  ReleaseSection('欢迎使用$appDisplayName', [
    currentReleaseSourcePolicy,
    projectFreeNotice,
    projectRightsNotice,
    projectDataNotice,
  ]),
  ReleaseSection('本次更新', [
    _opening,
    _swipes,
    _androidInstall,
  ]),
];
