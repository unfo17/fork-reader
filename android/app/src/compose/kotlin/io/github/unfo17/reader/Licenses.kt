package io.github.unfo17.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Licenses(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val files = remember {
        context.assets.list("").orEmpty().filter { it.endsWith(".txt") } + context.assets.list("font-licenses").orEmpty().map { "font-licenses/$it" }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(selected) { text = selected?.let { path -> withContext(Dispatchers.IO) { context.assets.open(path).bufferedReader().use { it.readText() } } }.orEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).heightIn(max = 650.dp)) {
            Text("开源许可", style = MaterialTheme.typography.titleLarge)
            if (selected == null) LazyColumn { items(files.sorted()) { path -> TextButton(onClick = { selected = path }) { Text(path.substringAfterLast('/')) } } }
            else {
                TextButton(onClick = { selected = null }) { Text("返回许可列表") }
                Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
