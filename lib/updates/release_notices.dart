const appDisplayName = '页间阅读';

const currentReleaseSourcePolicy =
    '通过「本地」导入你自己的书籍。EPUB 由 Readium 解析和渲染，安装包不附带书籍。';

const projectFreeNotice =
    '页间阅读是基于 Wenku8 Flutter 的免费本地阅读器。安装和更新请使用 [项目的 GitHub Releases](https://github.com/unfo17/fork-reader/releases) 或应用内更新。';

const projectRightsNotice = '书籍、封面和插图的权利归各自权利人所有，请导入你有权使用的文件。';

const projectDataNotice =
    '本地书籍、阅读进度和书签保存在设备上。检查更新时会连接项目发布服务；启用 S3 同步后会连接你配置的存储。选用 Edge 在线朗读时，待合成文本会发送到语音服务。';

const projectLicenseNotice =
    '上游仓库根目录 LICENSE 为 GPL v2 文本，上游文档也引用了 GPL v3 声明。两份原文均保留，项目许可版本有待上游明确。Readium 和其他第三方组件保留各自的许可和署名。';

const projectImportantNotices =
    '''**本地阅读**

$currentReleaseSourcePolicy

$projectFreeNotice

$projectRightsNotice

**数据与联网**

$projectDataNotice

按需下载字体时会连接字体下载服务。可在「我的」导出备份。''';
