# Wenku8 Readium fork

基于 `mikufoxxx/Wenku8_Flutter` 的 `master`，基线提交为
`ebcd7f1891b54a92fc045c372974d70ec829f10a`（0.5.6+2277）。

## 修改

- 首页固定为“本地 / 我的”，不再创建发现和书架主页。旧启动页配置回退到本地；网络状态和旧合并书架设置不会重新显示已移除的主页。
- 移除合并书架、离线适配和阅读器模式开关；直接阅读页面选项仅保留本地。
- 新导入 EPUB 使用 `flutter_readium 0.3.3` 的 `loadPublication`，由 Readium 解析元数据、资源清单、正文顺序、封面和多级目录；资源索引转换后接入现有正文、插图、注释和分页排版。
- 支持普通文件、内存数据及系统文件选择器授权的文件描述符。描述符输入只为原生解析生成临时 EPUB，解析完成后清理，不改变原文件。
- TXT 继续使用原 TXT 章节解析。Android 预览版目前限定 EPUB、TXT 和 ZIP，暂未实现其他文档转换。旧书库维护、目录升级及封面重建保留历史解析器，以兼容已有阅读坐标；此版本没有迁移已有书籍。

## 平台

Readium EPUB 导入支持 Android / iOS；HarmonyOS 暂无此插件实现，会明确提示不支持。
Android 最低 API 24、compileSdk 36，AGP 8.13.2、Kotlin 2.3.21；
iOS 最低 15.0，Podfile 已添加 Readium pod 源，插件指定 Swift toolkit 3.9.x。
选择 0.3.3 是为兼容现有 AGP 8 工程，避免引入要求 AGP 9 / API 37 的新版插件。
这里只使用解析 API，不创建 Readium 原生阅读视图；现有 Android 音频 Activity 可以保留。

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
