import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _contents = '图书详情和阅读目录恢复原版按卷分组与编号，沿用 EPUB 的跳转地址。';
const _opening = '在图书详情页提前打开 EPUB，缩短开始阅读和继续阅读的等待。';
const _swipes = '减少插图翻页的纹理开销，预载相邻页时减少往返。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_contents
- $_opening
- $_swipes

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _contents,
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
    _contents,
    _opening,
    _swipes,
    _androidInstall,
  ]),
];
