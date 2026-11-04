# 本地书房 · Fork Reader

基于 [mikufoxxx/Wenku8_Flutter](https://github.com/mikufoxxx/Wenku8_Flutter) 的 Android 原生迁移分支，使用 **Kotlin、Jetpack Compose、Material 3 和 Readium Kotlin Toolkit**。保留 GPL-2.0 许可证及上游 NOTICE。

应用仅提供 **主页 / 本地 / 我的** 三个导航入口，不包含在线书城、发现或账号页面。Android 构建入口为原项目的 `android/`。Compose 界面位于 `android/app/src/compose/`，直接复用原项目 `localcore` 内的原生业务实现；Flutter 页面及通道适配代码保留用于后续逐项迁移。此前独立的 `native-android/` 已撤销。

## 当前可用

- 系统文件选择器多文件导入；指定目录递归扫描；导入前勾选确认；SHA-256 内容去重。
- 文件管理器“打开方式”：长期授权关联、临时来源显式复制、重新定位、TXT/EPUB 安全移动；原书完整复制核对后才允许删除来源。待处理请求可恢复，已处理请求不重复导入。
- EPUB 经 Readium 解析元数据、封面、目录并使用官方 Navigator 阅读，保留 EPUB 原生排版、插图及脚注处理。
- TXT 复用原项目的编码检测、流式规范化和 EPUB 打包器，支持 UTF-8、UTF-16、GB18030、Big5；原书保留，支持自定义章节/分卷规则、目录预览和编码选择；规则随书保存，超时匹配在独立进程中终止，交由 Readium 阅读。
- MOBI/AZW/AZW3/PRC 使用原项目 libmobi；FB2、PDF、DOC/DOCX、RTF、HTML、Markdown 转为 Readium 可读 EPUB，保留常见样式、图片与目录。
- ZIP 先预览再选择，支持中文文件名及同包相对图片；关联或复制整包，兄弟书籍共享来源，备份可离线恢复。
- 分类创建、改名、删除和批量移动；稳定 ID、删除记录和延迟编辑保护随备份保留。
- 多词搜索、#标签、全角归一化、字段选择；分类／作者／阅读状态／来源组合筛选；最近添加、最近阅读和书名排序。
- 列表、宫格、立体书架加列表／宫格四种布局及封面大小设置；布局、筛选和排序按设备保存。
- 编辑元数据、封面、标签和阅读状态；批量管理。
- 多卷合订本引用原书，支持确认卷序、卷名/元数据编辑、跨卷目录与书签跳转、拆分和备份；卷序变化保留原阅读坐标。
- 阅读进度恢复、目录跳转、书签增删与跳转、全书／当前卷／当前章节搜索（按目录锚点限定、分页继续加载）、字号、夜间与滚动设置。
- 选区划线、高亮、边框、笔记与批注分类；支持在当前原书重新选择范围，保留元数据并防止旧草稿覆盖；全库管理、备份合并、删除后保留。摘录图片支持八套上游插画、长文本分页、PNG/ZIP 导出、相册保存和分享。
- 每书保存四边独立页边距、行高、段距、字距（含负值）、字重、缩进和对齐；导入/下载字体可用于正文。
- 复用原系统 TTS 核心，支持引擎/音色选择、语言校验、0.5–3 倍速和试听；从当前段落连续朗读至原书末尾。支持后台和跨卷连续播放、锁屏/通知暂停续播、正文跟随、全局听书小窗与睡眠定时；完整听书游标和全章时间轴仍在迁移。
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

测试需要 Android 26+ 模拟器或设备。APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 构建 APK、执行静态检查并在 API 35 模拟器执行导入、渲染、关联、ZIP、字体、分类、筛选、备份及实际 Compose 页面操作测试。

## 项目来源

- 上游代码及许可证：[Wenku8_Flutter](https://github.com/mikufoxxx/Wenku8_Flutter)，GPL-2.0。
- EPUB 引擎：[Readium Kotlin Toolkit 3.1.2](https://github.com/readium/kotlin-toolkit/tree/3.1.2)，BSD-3-Clause；通过 Maven 引入，无 Flutter EPUB 解析代码参与新应用。
- 上游介绍保存在 [UPSTREAM_README](docs/UPSTREAM_README.md)。
