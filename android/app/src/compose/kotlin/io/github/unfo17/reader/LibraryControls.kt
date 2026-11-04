package io.github.unfo17.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CategoryManager(organization: LibraryOrganization, onDismiss: () -> Unit, onChanged: () -> Unit, onChoose: ((String) -> Unit)? = null) {
    var groups by remember { mutableStateOf(organization.groups()) }
    var editId by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun change(action: () -> Unit) {
        try { action(); groups = organization.groups(); onChanged(); error = null }
        catch (e: Exception) { error = e.message ?: "分类保存失败" }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 550.dp), contentPadding = PaddingValues(20.dp)) {
            item { Text(if (onChoose == null) "管理分类" else "移至分类", style = MaterialTheme.typography.titleLarge) }
            if (onChoose != null) item { ListItem(headlineContent = { Text("未分类") }, modifier = Modifier.clickable { onChoose(LibraryOrganization.UNFILED) }) }
            items(groups.entries.toList(), key = { it.key }) { (id, label) ->
                ListItem(headlineContent = { Text(label) }, modifier = if (onChoose == null) Modifier else Modifier.clickable { onChoose(id) }, trailingContent = { Row {
                    IconButton(onClick = { editId = id; name = label; naming = true }) { Icon(Icons.Outlined.Edit, "重命名 $label") }
                    IconButton(onClick = { deleting = id }) { Icon(Icons.Outlined.DeleteOutline, "删除 $label") }
                } })
            }
            item { TextButton(onClick = { editId = null; name = ""; naming = true }) { Text("新建分类") } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text(if (editId == null) "新建分类" else "重命名分类") }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("名称（1–30 个字）") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = { change { if (editId == null) organization.create(name) else organization.rename(editId!!, name); naming = false } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } })
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除分类？") }, text = { Text("分类中的书籍回到未分类，原书与阅读记录保留。") }, confirmButton = { TextButton(onClick = { change { organization.remove(id); deleting = null } }) { Text("删除分类") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable fun <T> ChoiceMenu(label: String, value: T, choices: List<Pair<T, String>>, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) { Text(label + "：" + (choices.firstOrNull { it.first == value }?.second ?: "全部")) }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 420.dp)) { choices.forEach { (key, name) ->
            DropdownMenuItem(enabled = enabled, text = { Text(name) }, onClick = { onSelect(key); expanded = false })
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LibraryDisplaySettings(filter: LibraryFilter, view: LibraryView, density: String, authors: List<String>, onFilter: (LibraryFilter) -> Unit, onView: (LibraryView) -> Unit, onDensity: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("书架与筛选", style = MaterialTheme.typography.titleLarge) }
            item { ChoiceMenu("展示", view, LibraryView.entries.map { it to it.label }, onSelect = onView) }
            item { ChoiceMenu("封面大小", density, listOf("auto" to "自动", "small" to "小", "medium" to "中", "large" to "大"), onSelect = onDensity) }
            item { ChoiceMenu("排序", filter.sort, LibrarySort.entries.map { it to it.label }) { onFilter(filter.copy(sort = it)) } }
            item { ChoiceMenu("搜索范围", filter.field, BookSearchField.entries.map { it to it.label }) { onFilter(filter.copy(field = it)) } }
            item { ChoiceMenu("阅读状态", filter.reading, listOf("all" to "全部", "unread" to "未读", "reading" to "在读", "finished" to "已读", "paused" to "暂停")) { onFilter(filter.copy(reading = it)) } }
            item { ChoiceMenu("来源", filter.source, listOf("all" to "全部", "linked" to "关联原文件", "copied" to "应用内副本", "archive" to "ZIP 内书籍")) { onFilter(filter.copy(source = it)) } }
            item { ChoiceMenu("作者", filter.author, listOf<String?>(null).map { it to "全部" } + authors.map { it to it }) { onFilter(filter.copy(author = it)) } }
            item { TextButton(onClick = { onFilter(LibraryFilter(query = filter.query)) }) { Text("重置筛选") } }
        }
    }
}
