import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _catalogue = 'EPUB 分组只占一行。点标题跳转，点行尾箭头展开，分组不计入章节编号与项数。';
const _relay = '减少段落接续松手后的停顿。';
const _swipes = '仿真翻页动画可以接收紧接着的滑动。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_catalogue
- $_relay
- $_swipes

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _catalogue,
  _relay,
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
    _catalogue,
    _relay,
    _swipes,
    _androidInstall,
  ]),
];
