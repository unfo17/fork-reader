import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _swipes = '减少翻页时的重复绘制，成功翻页按阅读震动设置反馈。';
const _resume = '继续阅读时使用已选的翻页效果，从后台返回时正文从屏幕中部升入。';
const _chapters = 'EPUB 目录支持按原书层级展开和收起，修复章节入场与重复打开的等待。';
const _widgets = '修复小组件预览尺寸，恢复不同卡片的数据和外观。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_swipes
- $_resume
- $_chapters
- $_widgets

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _swipes,
  _resume,
  _chapters,
  _widgets,
];
const currentReleaseSections = [
  ReleaseSection('欢迎使用$appDisplayName', [
    currentReleaseSourcePolicy,
    projectFreeNotice,
    projectRightsNotice,
    projectDataNotice,
  ]),
  ReleaseSection('本次更新', [
    _swipes,
    _resume,
    _chapters,
    _widgets,
    _androidInstall,
  ]),
];
