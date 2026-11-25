import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const _swipes = '连续快滑时，第二次手势会让上一页提早完成收尾。短距离快滑结合触摸速度、用时和位移判断。';
const _branding = '应用显示名更新为「$appDisplayName」，并更新了首次启动说明和关于页面。';
const _licenses = '开源许可页补充了 Readium 组件的原始许可证和版权署名。';
const _androidInstall = '已安装本仓库固定签名版的 Android 用户可以直接覆盖更新，书籍和阅读记录会保留。';

const publicReleaseNotes =
    '''
### $appDisplayName · Android 预览

- $_swipes
- $_branding
- $_licenses

$_androidInstall
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  currentReleaseSourcePolicy,
  _swipes,
  _branding,
  _licenses,
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
    _branding,
    _licenses,
    _androidInstall,
  ]),
];
