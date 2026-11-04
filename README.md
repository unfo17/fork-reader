# 本地书房 · Fork Reader

基于 [mikufoxxx/Wenku8_Flutter](https://github.com/mikufoxxx/Wenku8_Flutter) 的 Android 原生迁移分支，使用 **Kotlin、Jetpack Compose、Material 3 和 Readium Kotlin Toolkit**。保留 GPL-2.0 许可证及上游 NOTICE。

应用仅提供 **主页 / 本地 / 我的** 三个导航入口，不包含在线书城、发现或账号页面。新的 Android 构建入口为 `native-android/`；根目录原 Flutter 源码保留用于迁移核对，不参与新应用构建。

## 当前可用

- 系统文件选择器多文件导入；指定目录递归扫描；导入前勾选确认；SHA-256 内容去重。
- EPUB 经 Readium 解析元数据、封面、目录并使用官方 Navigator 阅读，保留 EPUB 原生排版、插图及脚注处理。
- TXT 阅读，支持 UTF-8 / GB18030 回退。
- 书名／作者搜索、分类过滤、按书名／最近阅读排序、编辑元数据、批量分类和删除。
- 阅读进度恢复、目录跳转、书签增删与跳转、全文搜索（最多 100 条）、字号、夜间与滚动设置。
- 系统 TTS 朗读当前位置最多 3000 字；需已安装对应语言的系统语音引擎。
- 最近阅读、累计阅读时间；导出 ZIP 书库备份及合并恢复（原书、封面、分类、进度、书签）。
- MD3 动态配色与系统深浅色。

**这仍是迁移版本，不代表已与上游全部功能对齐。** 详细差异见 [迁移清单](docs/NATIVE_MIGRATION.md)。

## 构建

安装 JDK 17、Android SDK 35，在 `native-android/local.properties` 中配置 `sdk.dir`，然后运行：

```sh
cd native-android
./gradlew assembleDebug
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

测试需要 Android 26+ 模拟器或设备。APK 位于 `native-android/app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 构建 APK、执行静态检查并在 API 35 模拟器执行真实 EPUB 导入和渲染测试。

## 项目来源

- 上游代码及许可证：[Wenku8_Flutter](https://github.com/mikufoxxx/Wenku8_Flutter)，GPL-2.0。
- EPUB 引擎：[Readium Kotlin Toolkit 3.1.2](https://github.com/readium/kotlin-toolkit/tree/3.1.2)，BSD-3-Clause；通过 Maven 引入，无 Flutter EPUB 解析代码参与新应用。
- 上游介绍保存在 [UPSTREAM_README](docs/UPSTREAM_README.md)。
