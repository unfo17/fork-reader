# Wenku8 Readium fork

基于 `mikufoxxx/Wenku8_Flutter` 的 `master`，基线提交为
`ebcd7f1891b54a92fc045c372974d70ec829f10a`（0.5.6+2277）。

## 修改

- 首页固定为“本地 / 我的”，不再创建发现和书架主页。旧启动页配置回退到本地；网络状态和旧合并书架设置不会重新显示已移除的主页。
- 移除合并书架、离线适配和阅读器模式开关；直接阅读页面选项仅保留本地。
- 新导入 EPUB 使用 `flutter_readium 0.3.3` 的 `loadPublication`，由 Readium 解析元数据、资源清单、正文顺序、封面和多级目录；原 EPUB 由 Readium Navigator 渲染；提取的文字只作为原搜索、书签和听书模块的辅助索引。
- 支持普通文件、内存数据及系统文件选择器授权的文件描述符。描述符输入只为原生解析生成临时 EPUB，解析完成后清理，不改变原文件。
- TXT 继续使用原 TXT 章节解析。Android 预览版目前限定 EPUB、TXT 和 ZIP，暂未实现其他文档转换。旧书库维护、目录升级及封面重建保留历史解析器，以兼容已有阅读坐标；此版本没有迁移已有书籍。

## 平台

Readium EPUB 导入支持 Android / iOS；HarmonyOS 暂无此插件实现，会明确提示不支持。
Android 最低 API 24、compileSdk 36，AGP 8.13.2、Kotlin 2.3.21；
iOS 最低 15.0，Podfile 已添加 Readium pod 源，插件指定 Swift toolkit 3.9.x。
选择 0.3.3 是为兼容现有 AGP 8 工程，避免引入要求 AGP 9 / API 37 的新版插件。
Android 阅读 Activity 使用 AudioServiceFragmentActivity 承载 Readium Fragment，同时保留音频服务。iOS 导入路径保留，当前预览 APK 的控件适配仅在 Android 验证。

## 验证

```sh
flutter pub get
dart run tools/verify_readium.dart
flutter analyze
```

`verify_readium.dart` 检查元数据、作者、正文顺序、封面关系、目录层级、
中文及空格编码、外部目录过滤，以及外部正文和不安全资源路径拒绝。

上游公开源码缺失的本地导入、扫描、批量管理、基础小组件和自定义 SSML 模块已在预览分支补齐。
局域网续读、声音增强、自动分卷合并暂时关闭；纸页翻动采用基础透视效果，
小组件与阅读报告采用基础样式。缺失的报告插画不再列入打包资源。
详见 `RELEASE_PREVIEW.md`。

已在 Flutter 3.47.9 / Dart 3.13.7 下完成依赖解析、完整 Flutter 资源打包、
18 项 Readium 适配检查及 5 项导入集成测试。新增的 GLSL 前向差分计算同时兼容 SkSL，
避免上游着色器的 GPU 导数函数阻止资源打包。
APK 由 `.github/workflows/preview-apk.yml` 编译、校验签名，并发布到 GitHub 预览 Release。

## 原阅读控件与 Readium 正文（预览版 3）

`ReaderPage` 继续使用原来的 Flutter 顶栏、底栏、目录、阅读设置、搜索、书签编辑器与听书面板；`ReadiumReaderBridge` 只替换正文引擎，不再另建一套阅读页面。独立 EPUB 的包解析、正文布局和渲染均由 Readium 处理，直接加载原书 XHTML、CSS、内嵌字体和图片。原书样式默认保留。

原控件的章节跳转、进度拖动、点击区域、音量键、自动滚动/翻页、字号、行距、四边留白、纸色、自选字体、背景图、搜索、书签定位、批注及听书跟随通过适配层驱动 Readium。精确续读与新书签保存 Readium locator；旧段落坐标在有文字索引时转换为文本定位。TXT 与手动合订本保留原阅读路径。Readium 的翻页动画与旧 Flutter 自绘动效不完全相同。

`third_party/flutter_readium` 基于 BSD-3-Clause 的 0.3.3，只增加原应用点击回调、每个正文资源的阅读偏好恢复、批注边框样式，以及窄范围的原生控制接口。Readium Kotlin toolkit 固定为 3.2.0。自选字体与背景只读应用私有文件，通过样式送到 Readium，原 EPUB 文件不被改写。

样式样书：`python3 tools/create_readium_style_fixture.py`，包含外部 CSS、颜色、右对齐、边框、表格、不同字号和 ruby 注音。

## APK 固定签名

Release 构建必须提供 `android/key.properties` 和固定 keystore，缺少时直接失败，不回退 debug 签名。CI 从仓库 Secrets 恢复密钥，并用 `READIUM_SIGNING_SHA256` 固定证书指纹；私钥和密码不得进入 Git。推送只生成供验证的 artifact，验证后再发布 Release。

前两版使用了各自构建机器的临时 debug 密钥，私钥没有保留，无法用新密钥覆盖安装。这两版需要先通过应用内备份导出数据，再迁移到固定签名版；从固定签名版开始保持包名、证书一致，并递增 versionCode。
