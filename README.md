# 本地书房 · Fork Reader

基于 [mikufoxxx/Wenku8_Flutter](https://github.com/mikufoxxx/Wenku8_Flutter) 的 Android 原生迁移分支，使用 **Kotlin、Jetpack Compose、Material 3 和 Readium Kotlin Toolkit**。保留 GPL-2.0 许可证及上游 NOTICE。

应用仅提供 **主页 / 本地 / 我的** 三个导航入口，不包含在线书城、发现或账号页面。Android 构建入口为原项目的 `android/`。Compose 界面位于 `android/app/src/compose/`，直接复用原项目 `localcore` 内的原生业务实现；Flutter 页面及通道适配代码保留用于后续逐项迁移。此前独立的 `native-android/` 已撤销。

## 当前可用

- 系统文件选择器多文件导入；指定目录递归扫描；导入前勾选确认；SHA-256 内容去重。
- EPUB 经 Readium 解析元数据、封面、目录并使用官方 Navigator 阅读，保留 EPUB 原生排版、插图及脚注处理。
- TXT 复用原项目的编码检测、流式规范化和 EPUB 打包器，支持 UTF-8、UTF-16、GB18030、Big5；原书保留，章节规则按上游行为迁移，交由 Readium 阅读。
- MOBI/AZW/AZW3/PRC 使用原项目 libmobi；FB2、PDF、DOC/DOCX、RTF、HTML、Markdown 转为 Readium 可读 EPUB，保留常见样式、图片与目录。
- ZIP 先预览再选择，支持中文文件名及同包相对图片；关联或复制整包，兄弟书籍共享来源，备份可离线恢复。
- 书名／作者搜索、分类过滤、按书名／最近阅读排序、编辑元数据、批量分类和删除。
- 阅读进度恢复、目录跳转、书签增删与跳转、全文搜索（最多 100 条）、字号、夜间与滚动设置。
- 系统 TTS 朗读当前位置最多 3000 字；需已安装对应语言的系统语音引擎。
- 最近阅读、累计阅读时间；导出 ZIP 书库备份及合并恢复（原书、封面、分类、进度、书签）。
- MD3 动态配色与系统深浅色。

**这仍是迁移版本，不代表已与上游全部功能对齐。** 详细差异见 [迁移清单](docs/NATIVE_MIGRATION.md)。

## 构建

安装 JDK 17、Android SDK 35、NDK 27.0.12077973 和 CMake 3.22.1，在 `android/local.properties` 中配置 `sdk.dir`，然后运行：

```sh
cd android
./gradlew assembleDebug
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

测试需要 Android 26+ 模拟器或设备。APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 构建 APK、执行静态检查并在 API 35 模拟器执行21 项导入、渲染、关联、ZIP、字体及备份测试。

## 项目来源

- 上游代码及许可证：[Wenku8_Flutter](https://github.com/mikufoxxx/Wenku8_Flutter)，GPL-2.0。
- EPUB 引擎：[Readium Kotlin Toolkit 3.1.2](https://github.com/readium/kotlin-toolkit/tree/3.1.2)，BSD-3-Clause；通过 Maven 引入，无 Flutter EPUB 解析代码参与新应用。
- 上游介绍保存在 [UPSTREAM_README](docs/UPSTREAM_README.md)。
