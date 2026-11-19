import 'release_notices.dart';

class ReleaseSection {
  const ReleaseSection(this.title, this.items);
  final String title;
  final List<String> items;
}

const releaseFreeNotice = '**本项目完全免费，请勿购买所谓付费版、激活码或解锁服务。**';
const releaseSponsorLink = '☕ [请我喝杯咖啡 · 爱发电](https://afdian.com/a/mikufoxxx)';
const releaseProjectNotice =
    '本项目为第三方客户端，并非轻小说文库（Wenku8）官方应用，也不运营该站点。在线书目与内容来自轻小说文库，本地书籍由用户自行导入，安装包不附带小说文件。';
const releaseRightsNotice =
    '小说、封面和插图归各自权利人所有。请仅使用有权访问的内容，遵守来源网站规则，不得绕过访问限制、破解 DRM，或未经许可传播、出售内容。';
const releaseReadingNotice = '**在线内容以轻小说文库开放状态为准，不提供因版权限制而停止开放的在线书籍阅读。**';
const releaseMaintenanceNotice =
    '这是基于 Wenku8_Flutter 的 Readium 本地阅读预览分支，问题与修改反馈请提交到本分支。';
const _interruption = '段落接续按实际段落、原版时序和连续位移移动，减少文字跳动。';
const _cache = '纸页从起始角跟随触点折叠，修正投影收缩、纸背范围及阴影。';
const _retry = '首尾页提前提示；预热邻近图片及插图查看缓存，改善快速操作时的等待。';
const _resources = '保留原阅读控件及当前仓库自动更新，沿用固定签名覆盖安装。';
const _changes = [_interruption, _cache, _retry, _resources];
const _androidInstall =
    '**Android**：本版起使用固定签名。前两版临时私钥未保留，不能直接覆盖；请先在旧版导出备份，再迁移安装。固定签名版之间可覆盖更新。';
const _iosInstall = '**iOS**：本次未提供安装包，阅读控件适配尚未完成设备验证。';
const _harmonyInstall = '**HarmonyOS**：本次未提供安装包。';
const _legacyInstall = '**iOS／HarmonyOS 0.5.0 用户**：请先手动更新一次。';
const _backup = '升级、整理存储或同步前，请先备份重要书籍和阅读记录。';
const publicReleaseNotes =
    '''
### 免费使用，请勿付费

$releaseFreeNotice

$releaseSponsorLink

$releaseProjectNotice

$releaseRightsNotice

$releaseReadingNotice

**$currentReleaseSourcePolicy**

### 本次更新

- $_interruption
- $_cache
- $_retry
- $_resources

### 后续维护

$releaseMaintenanceNotice

### 安装说明

- $_androidInstall
- $_iosInstall
- $_harmonyInstall
- $_legacyInstall

$_backup
''';
const currentReleaseNotes = publicReleaseNotes;
const currentReleaseSummary = [
  releaseFreeNotice,
  releaseSponsorLink,
  releaseReadingNotice,
  currentReleaseSourcePolicy,
  ..._changes,
  releaseMaintenanceNotice,
];
const currentReleaseSections = [
  ReleaseSection('重要说明 · 请务必阅读', [
    releaseFreeNotice,
    releaseSponsorLink,
    releaseProjectNotice,
    releaseRightsNotice,
    releaseReadingNotice,
    currentReleaseSourcePolicy,
  ]),
  ReleaseSection('本次更新与后续维护', [
    ..._changes,
    releaseMaintenanceNotice,
    _androidInstall,
    _iosInstall,
    _harmonyInstall,
    _legacyInstall,
    _backup,
  ]),
];
