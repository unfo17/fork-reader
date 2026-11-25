import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _swipes =
    '扩大可用内存下的双向预载，并在翻页收尾期间补齐相邻页。';
const _resume = '从后台返回时增加短暂的原页过渡，保留阅读位置和排版。';
const _curl = '双层仿真翻页使用连续的折痕和方向，目标页就绪时接入正文纹理。';
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
