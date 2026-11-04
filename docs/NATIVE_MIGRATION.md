# 原生迁移状态

## 范围

目标是 Android Compose / Material 3 本地阅读器。主页放继续阅读和阅读概览，本地管理书库，我的放数据管理。在线 Wenku8 发现、排行、书城、账号不进入新应用。上游历史和源码保留用于功能核对。

## 迁移方式（已调整）

不再把独立简化版作为最终实现。Android 构建入口收回原项目的 `android/`，Compose 页面位于 `app/src/compose/`，原 Flutter 页面与适配器保留，按业务边界逐项迁移。原构建配置保存于 `docs/upstream-android-build/`，可结合 Git 历史核对。

直接复用原 Kotlin 实现，整理至 `android/app/src/main/kotlin/org/mewx/wenku8_flutter/localcore/`，包名不变：

| 原项目代码 | 当前用途 |
| --- | --- |
| BookTextDecoder | TXT 编码检测、128MiB 限制、流式 UTF-8 规范化 |
| BookDocumentInput / BookDocumentZip | 原文件及描述符输入、受限 ZIP 读取 |
| LinkedTextFile | 原有分块读取实现保留并参与编译，文件引用流程尚待接入 |
| BookImportQueue / BookImportWorkspace | 逐文件导入暂存、失败清理、会话隔离 |
| BookEpubWriter | TXT 内容打包成可重建 EPUB，保留原文件；唯一调整为内联原 512MiB 输出限制，解除缺失 BookArchiveImport 的依赖 |
| SpeechLocalePolicy | 沿用原系统朗读的语言规则 |

新增 LocalTextPublication 只负责把原有解码器和打包器连接到 Readium；默认章节／卷规则和等号装饰标题处理来自 `lib/local_import_options.dart` 与 `lib/local_text_rules.dart`。未开放自定义正则设置，原项目对规则超时的隔离策略还需迁移。

公开源码中 BookDocuments 引用 BookArchiveImport、BookMarkupImport、BookPdfImport，但当前仓库没有这些类的实现。因此多格式导入不能仅通过接上 Flutter 通道完成，后续需要补齐原业务契约并验证，不标为已复用。

应用仍沿用此前迁移版的独立 applicationId；不会覆盖上游发行版。此前迁移版的数据保持可读，但上游 Flutter 数据库尚未自动迁移。旧 TXT 百分比书签按全书 positions 映射到 Readium，是近似位置；不承诺原字符级坐标精度。

## 功能核对

| 上游本地能力 | 当前状态 |
| --- | --- |
| EPUB 导入、元数据、目录、阅读 | 已接 Readium 3.1.2，设备测试通过 |
| TXT 阅读 | 复用上游编码检测与打包器，支持 UTF-8 / UTF-16 / GB18030 / Big5，通过 Readium 阅读，生成默认章节目录 |
| 多选文件、目录扫描、导入确认 | 已实现；只处理 EPUB / TXT |
| 封面展示 | 从 EPUB 提取；暂不支持手动替换 |
| 分类、书名作者编辑、搜索、排序 | 已实现 |
| 批量分类、删除 | 已实现 |
| 进度恢复、书签、全文搜索 | 已实现；搜索最多 100 条 |
| 字号、日夜、滚动模式 | 已实现；每书保存 EPUB 设置 |
| 系统 TTS | 基础前台朗读，当前位置最多 3000 字 |
| 本地备份恢复 | 已实现 ZIP 原书、封面、分类、进度、书签；恢复按最后阅读时间合并 |
| 阅读统计 | 累计阅读时间与最近阅读；未迁移日历、报告和导出卡片 |
| 文件引用／重新关联 | 未迁移，当前导入复制到应用内 |
| MOBI/AZW/FB2/PDF/DOC/DOCX/RTF/HTML/MD/ZIP 导入 | 未迁移 |
| 系列／卷合并、卷排序、维护升级 | 未迁移 |
| 3D 书架、密度／网格设置 | 未迁移，当前为 MD3 列表 |
| 手动封面、字体导入、详细排版、竖排控制 | 未迁移 |
| 选区批注、高亮、摘录图片 | 未迁移；当前为位置书签 |
| 在线语音、后台朗读、锁屏、定时、自动翻页 | 未迁移 |
| MDict/StarDict/Kindle 离线词典 | 未迁移 |
| WebDAV/OpenList/夸克导入、S3、局域网续读 | 未迁移 |
| 桌面小组件、完整阅读报告 | 未迁移 |
| 旧 Flutter 数据库自动迁移 | 未实现；可重新导入原文件 |

## 实现约束

Readium 官方 EPUB Navigator 基于 Fragment/WebView，嵌入 Compose 的 AndroidView；应用导航、书库、设置、弹窗全部采用 Compose。Readium 的 Publication、Locator、目录和内容服务负责 EPUB 解析、位置与正文提取。

系统文档选择器仅授予用户选择的文件或目录权限，不申请全盘存储权限。导入文件复制到私有目录。卸载前需要导出备份；备份不包含设备语音模型或每书外观设置。当前备份恢复限制解压总大小 1GB。

## 验证

设备测试共四项，新增五种 TXT 编码、无换行长段落、上游默认标题规则与转换后目录校验；另使用原创生成的 EPUB，覆盖标题作者读取、内容去重、目录解析、Compose 内 Readium Fragment 渲染、阅读时间保存、备份恢复及无效 EPUB 导入回滚。当前验证设备为 Android 15 / API 35。尚未宣称对大文件、全部 EPUB 排版及全部设备完成验收。
