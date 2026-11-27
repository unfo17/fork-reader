import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _opening = '恢复原项目的局域网续读流程，Android 支持同一 Wi-Fi 自动发现与双方确认配对。';
const _swipes = '已配对设备可传送选中的原书和 Readium 阅读位置，并在前台同步阅读记录。';
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
