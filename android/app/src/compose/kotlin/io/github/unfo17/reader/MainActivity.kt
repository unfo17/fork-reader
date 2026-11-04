package io.github.unfo17.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReaderTheme { LibraryApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LibraryApp() {
    val context = LocalContext.current
    val library = remember { Library.get(context) }
    var books by remember { mutableStateOf(library.books()) }
    var appFonts by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    var sort by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedBooks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchAction by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Book?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { books = library.books() }
    fun open(book: Book) { context.startActivity(Intent(context, ReaderActivity::class.java).putExtra("book", book.id)) }
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingTree by remember { mutableStateOf<Uri?>(null) }
    var copyImport by remember { mutableStateOf(false) }
    var coverTarget by remember { mutableStateOf<Book?>(null) }
    var relinking by remember { mutableStateOf<Book?>(null) }
    var importMenu by remember { mutableStateOf(false) }
    fun importFiles(uris: List<Uri>, copy: Boolean, tree: Uri?) { scope.launch {
            busy = true
            var imported = 0
            val errors = mutableListOf<String>()
            try { uris.forEach { uri -> try { library.import(uri, copy, tree); imported++ } catch (e: Exception) { errors += e.message ?: "导入失败" } } }
            finally { busy = false; books = library.books() }
            snackbar.showSnackbar("已导入 $imported 本" + errors.firstOrNull()?.let { "；$it" }.orEmpty())
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { pendingTree = null; pending = it }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> if (tree != null) scope.launch {
        busy = true
        try {
            pendingTree = tree
            runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pending = scanFolder(context, tree); if (pending.isEmpty()) snackbar.showSnackbar("目录中没有 EPUB 或 TXT") }
        catch (e: Exception) { snackbar.showSnackbar(e.message ?: "扫描失败") }
        finally { busy = false }
    } }
    val relinker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = relinking
        relinking = null
        if (uri != null && target != null) scope.launch {
            busy = true
            try { library.relink(target, uri); books = library.books(); snackbar.showSnackbar("原文件已重新关联，进度和书签保留") }
            catch (e: Exception) { snackbar.showSnackbar(e.message ?: "关联失败") }
            finally { busy = false }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = coverTarget
        coverTarget = null
        if (target != null && uri != null) scope.launch {
            busy = true
            try { val updated = library.replaceCover(target, uri); books = library.books(); editing = updated }
            catch (error: Exception) { snackbar.showSnackbar(error.message ?: "封面替换失败") }
            finally { busy = false }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) scope.launch {
        busy = true
        try { exportLibrary(context, uri); snackbar.showSnackbar("备份已保存") }
        catch (e: Exception) { snackbar.showSnackbar(e.message ?: "备份失败") }
        finally { busy = false }
    } }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        busy = true
        try { val count = restoreLibrary(context, uri); books = library.books(); snackbar.showSnackbar("已恢复 $count 本书及阅读记录") }
        catch (e: Exception) { snackbar.showSnackbar(e.message ?: "恢复失败") }
        finally { busy = false }
    } }
    Scaffold(
        topBar = { TopAppBar(title = { Text(listOf("主页", "本地", "我的")[tab]) }, actions = { if (tab == 1) TextButton(onClick = { selectionMode = !selectionMode; selectedBooks = emptySet() }) { Text(if (selectionMode) "完成" else "批量管理") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { NavigationBar {
            listOf("主页" to Icons.Outlined.Home, "本地" to Icons.Outlined.LibraryBooks, "我的" to Icons.Outlined.Person).forEachIndexed { index, item ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
            }
        } },
        floatingActionButton = { if (tab != 2) ExtendedFloatingActionButton(onClick = { if (!busy) importMenu = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(if (busy) "正在导入…" else "导入书籍") }) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            when (tab) {
                0 -> {
                    item { Text("把时间留给阅读", style = MaterialTheme.typography.headlineMedium) }
                    item { Text("${books.size} 本藏书 · ${books.sumOf { it.minutes } / 60} 分钟阅读", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val recent = books.filter { it.lastRead > 0 }.sortedByDescending { it.lastRead }
                    item { Text("继续阅读", style = MaterialTheme.typography.titleLarge) }
                    if (recent.isEmpty()) item { Text("从本地导入一本书，开始你的阅读。") }
                    items(recent.take(5), key = { it.id }) { book -> BookCard(book, { open(book) }, { editing = book }) }
                }
                1 -> {
                    item { OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索书名、作者") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text(category) }
                            DropdownMenu(expanded, { expanded = false }) { (listOf("全部", "未分类") + books.map { it.category }).distinct().forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; expanded = false }) } }
                        }
                        FilterChip(selected = sort, onClick = { sort = !sort }, label = { Text(if (sort) "按书名" else "最近阅读") })
                    } }
                    if (selectionMode) item { Row {
                        TextButton(onClick = { selectedBooks = books.map { it.id }.toSet() }) { Text("全选") }
                        TextButton(enabled = selectedBooks.isNotEmpty(), onClick = { batchAction = "分类" }) { Text("移动分类 (${selectedBooks.size})") }
                        TextButton(enabled = selectedBooks.isNotEmpty(), onClick = { batchAction = "删除" }) { Text("删除") }
                    } }
                    val filtered = books.filter { (category == "全部" || it.category == category) && (it.title.contains(query, true) || it.author.contains(query, true)) }
                        .let { if (sort) it.sortedBy { b -> b.title } else it.sortedByDescending { b -> b.lastRead } }
                    if (filtered.isEmpty()) item { Text(if (books.isEmpty()) "书架还是空的\n点击“导入书籍”添加 EPUB 或 TXT。" else "没有符合条件的书籍") }
                    items(filtered, key = { it.id }) { book -> BookCard(book, { if (selectionMode) selectedBooks = if (book.id in selectedBooks) selectedBooks - book.id else selectedBooks + book.id else open(book) }, { editing = book }, if (selectionMode) book.id in selectedBooks else null) }
                }
                2 -> {
                    item { Text("我的书房", style = MaterialTheme.typography.headlineMedium) }
                    item { ListItem(modifier = Modifier.clickable { history = true }, headlineContent = { Text("阅读记录") }, supportingContent = { Text("读过 ${library.records().count { it.lastRead > 0 }} 本 · ${library.records().sumOf { it.bookmarks.size }} 个书签") }, leadingContent = { Icon(Icons.Outlined.AutoStories, null) }) }
                    item { ListItem(modifier = Modifier.clickable { appearance = true }, headlineContent = { Text("外观") }, supportingContent = { Text("Material 3 · 跟随系统深浅色和动态配色") }, leadingContent = { Icon(Icons.Outlined.Palette, null) }) }
                    item { ListItem(headlineContent = { Text("数据存储") }, supportingContent = { Text("默认关联原文件，也可选择复制。删除本机文件后保留进度和书签。") }, leadingContent = { Icon(Icons.Outlined.Storage, null) }) }
                    item { OutlinedButton(enabled = !busy, onClick = { exportPicker.launch("本地书房备份.zip") }) { Text("导出书库与阅读记录") } }
                    item { OutlinedButton(enabled = !busy, onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("从备份合并恢复") } }
                    item { TextButton(onClick = { appFonts = true }) { Text("应用字体与字体管理") } }
                    item { TextButton(onClick = { licenses = true }) { Text("开源许可") } }
                    item { ListItem(headlineContent = { Text("关于本地书房") }, supportingContent = { Text("0.2.0-dev · Compose / Readium\n基于 Wenku8_Flutter，遵循 GPL-2.0") }) }
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
    if (appFonts) FontManager(onDismiss = { appFonts = false }, selected = context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).getString("fontId", "").orEmpty(), onSelect = { id ->
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().putString("fontId", id).apply()
        appFonts = false
    })
    if (appearance) ModalBottomSheet(onDismissRequest = { appearance = false }) {
        val preferences = context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
        var theme by remember { mutableStateOf(preferences.getString("theme", "system")) }
        var dynamic by remember { mutableStateOf(preferences.getBoolean("dynamicColor", true)) }
        Column(Modifier.padding(24.dp)) {
            Text("外观", style = MaterialTheme.typography.titleLarge)
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                Row(Modifier.clickable { theme = value; preferences.edit().putString("theme", value).apply() }) { RadioButton(theme == value, { theme = value; preferences.edit().putString("theme", value).apply() }); Text(label, Modifier.padding(top = 12.dp)) }
            }
            Row { Text("动态配色", Modifier.weight(1f)); Switch(dynamic, { dynamic = it; preferences.edit().putBoolean("dynamicColor", it).apply() }) }
        }
    }
    if (licenses) Licenses { licenses = false }
    if (history) ModalBottomSheet(onDismissRequest = { history = false }) {
        val records = library.records().filter { it.lastRead > 0 || it.bookmarks.isNotEmpty() }.sortedByDescending { it.lastRead }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(20.dp)) {
            item { Text("阅读记录", style = MaterialTheme.typography.titleLarge) }
            if (records.isEmpty()) item { Text("还没有阅读记录。", Modifier.padding(vertical = 20.dp)) }
            items(records, key = { it.id }) { record ->
                ListItem(headlineContent = { Text(record.title) }, supportingContent = { Text("${(record.progress * 100).toInt()}% · ${record.bookmarks.size} 个书签 · ${record.minutes / 60} 分钟" + if (record.removed) "\n本机文件已删除，重新导入原书可恢复阅读" else "") },
                    modifier = if (record.removed) Modifier else Modifier.clickable { history = false; open(record) })
            }
        }
    }
    batchAction?.let { action ->
        var group by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { batchAction = null }, title = { Text("$action ${selectedBooks.size} 本书") }, text = {
            if (action == "删除") Text("仅删除应用内文件并移出书架。外部原文件、阅读进度和书签保留。")
            else OutlinedTextField(group, { group = it }, label = { Text("目标分类") })
        }, confirmButton = { TextButton(onClick = {
            val targets = books.filter { it.id in selectedBooks }
            batchAction = null
            scope.launch {
                busy = true
                val failed = mutableSetOf<String>()
                val errors = mutableListOf<String>()
                withContext(Dispatchers.IO) { targets.forEach { target ->
                    try { if (action == "删除") library.remove(target) else library.update(target.copy(category = group.trim().ifBlank { "未分类" })) }
                    catch (e: Exception) { failed += target.id; errors += e.message ?: "操作失败" }
                } }
                books = library.books(); selectedBooks = failed; busy = false
                snackbar.showSnackbar(if (failed.isEmpty()) "已完成 ${targets.size} 本书" else "${failed.size} 本操作失败，已保留选择：${errors.first()}")
            }
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { batchAction = null }) { Text("取消") } })
    }
    if (importMenu) AlertDialog(onDismissRequest = { importMenu = false }, title = { Text("导入书籍") }, text = { Text("选择文件，或扫描指定目录中的 EPUB 和 TXT。") }, confirmButton = { TextButton(onClick = { importMenu = false; importer.launch(arrayOf("application/epub+zip", "text/plain")) }) { Text("选择文件") } }, dismissButton = { TextButton(onClick = { importMenu = false; folderPicker.launch(null) }) { Text("扫描目录") } })
    if (pending.isNotEmpty()) {
        var selected by remember(pending) { mutableStateOf(pending.toSet()) }
        AlertDialog(onDismissRequest = { pending = emptyList() }, title = { Text("导入预览 · ${pending.size} 个文件") }, text = {
            Column {
                Text("默认关联原文件，请保留原文件的位置。重复内容自动跳过。")
                Row { Checkbox(copyImport, { copyImport = it }); Text("复制到应用", Modifier.padding(top = 12.dp)) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) { items(pending) { uri ->
                    var name by remember(uri) { mutableStateOf("读取文件名…") }
                    LaunchedEffect(uri) { name = withContext(Dispatchers.IO) { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "未知文件" } ?: "未知文件" } }
                    Row { Checkbox(uri in selected, { selected = if (it) selected + uri else selected - uri }); Text(name, Modifier.padding(top = 12.dp)) }
                } }
            }
        }, confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { val files = selected.toList(); pending = emptyList(); importFiles(files, copyImport, pendingTree) }) { Text("导入 ${selected.size} 本") } }, dismissButton = { TextButton(onClick = { pending = emptyList() }) { Text("取消") } })
    }
    editing?.let { book ->
        var title by remember(book.id) { mutableStateOf(book.title) }
        var author by remember(book.id) { mutableStateOf(book.author) }
        var group by remember(book.id) { mutableStateOf(book.category) }
        var summary by remember(book.id) { mutableStateOf(book.summary) }
        var tags by remember(book.id) { mutableStateOf(book.tags.joinToString("，")) }
        var status by remember(book.id) { mutableStateOf(book.status) }
        var deleting by remember { mutableStateOf(false) }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这本书？") }, text = { Text("仅删除应用内文件并移出书架。外部原文件、阅读进度和书签保留。") }, confirmButton = { TextButton(onClick = { scope.launch {
            try { withContext(Dispatchers.IO) { library.remove(book) }; books = library.books(); editing = null }
            catch (error: Exception) { snackbar.showSnackbar(error.message ?: "删除失败，书籍已保留") }
        } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
        else AlertDialog(onDismissRequest = { editing = null }, title = { Text("编辑书籍") }, text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("书名") })
            OutlinedTextField(author, { author = it }, label = { Text("作者") })
            OutlinedTextField(group, { group = it }, label = { Text("分类") })
            OutlinedTextField(summary, { summary = it }, label = { Text("简介") })
            OutlinedTextField(tags, { tags = it }, label = { Text("标签（逗号分隔）") })
            var statusesExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { statusesExpanded = true }) { Text(readingStateLabel(status)) }
                DropdownMenu(statusesExpanded, { statusesExpanded = false }) { listOf("auto", "unread", "reading", "finished", "paused").forEach { value -> DropdownMenuItem(text = { Text(readingStateLabel(value)) }, onClick = { status = value; statusesExpanded = false }) } }
            }
            Row {
                TextButton(onClick = { coverTarget = book; editing = null; coverPicker.launch(arrayOf("image/*")) }) { Text("替换封面") }
                TextButton(onClick = { scope.launch {
                    busy = true
                    try { editing = library.restoreCover(book); books = library.books() }
                    catch (e: Exception) { snackbar.showSnackbar(e.message ?: "恢复封面失败") }
                    finally { busy = false }
                } }) { Text("恢复原封面") }
            }
            Text(if (book.sourceReference != null) "已关联原文件" else "应用内副本", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { relinking = book; editing = null; relinker.launch(arrayOf(if (book.file.endsWith(".epub")) "application/epub+zip" else "text/plain")) }) { Text("重新关联原文件") }
            TextButton(onClick = { deleting = true }) { Text("删除书籍", color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { library.update(book.copy(title = title.trim(), author = author.trim(), category = group.trim().ifBlank { "未分类" }, summary = summary.trim(), tags = tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct(), status = status)); books = library.books(); editing = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
}

@Composable private fun BookCard(book: Book, open: () -> Unit, edit: () -> Unit, selected: Boolean? = null) {
    ElevatedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected != null) Checkbox(selected, { open() })
            val cover by produceState<android.graphics.Bitmap?>(null, book.file, book.coverRevision) { value = loadCover(book) }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                if (cover != null) Image(cover!!.asImageBitmap(), "封面", Modifier.size(56.dp, 80.dp), contentScale = ContentScale.Crop)
                else Icon(Icons.Outlined.MenuBook, null, Modifier.padding(16.dp).size(32.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium)
                Text(book.author.ifBlank { "未知作者" } + " · " + book.category, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { book.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${(book.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = edit) { Icon(Icons.Outlined.MoreVert, "编辑书籍") }
        }
    }
}
