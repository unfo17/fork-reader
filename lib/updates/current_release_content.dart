import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _margins = '固定阅读页面的上下留白，可在「操作」中分别调整上、下边距。';
const _swipes = '减少翻页时的重复合成，打开阅读菜单时暂停相邻页预载。';
const _contents = '目录分组用行尾箭头开合，最高层分组不占编号。入场与段落接续按原书内容衔接。';
const _widgets = 'Android 桌面小组件使用原版布局，支持五种样式。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_margins
- $_swipes
- $_contents
- $_widgets

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _margins,
  _swipes,
  _contents,
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
    _margins,
    _swipes,
    _contents,
    _widgets,
    _androidInstall,
  ]),
];
