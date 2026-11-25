import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _swipes =
    '降低段落接续翻页时的主线程开销，减少预载的重复检查。';
const _resume = '从后台返回阅读页时，正文各段从屏幕底部依次升入。';
const _systemPalette = '阅读背景选择「跟随外观」时，同步应用的系统配色。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_swipes
- $_resume
- $_systemPalette

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _swipes,
  _resume,
  _systemPalette,
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
    _systemPalette,
    _androidInstall,
  ]),
];
