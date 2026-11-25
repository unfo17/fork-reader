import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _swipes =
    '连续快翻时，后续页会在上一页动画收尾期间继续准备。';
const _resume = 'Android 回到前台时保留阅读页面和原有排版。';
const _curl = '反向拖动可以接住尚未翻完的纸页，沿当前折痕拉回。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_swipes
- $_resume
- $_curl

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _swipes,
  _resume,
  _curl,
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
    _curl,
    _androidInstall,
  ]),
];
