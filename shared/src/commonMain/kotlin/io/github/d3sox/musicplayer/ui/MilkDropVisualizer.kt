package io.github.d3sox.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.d3sox.musicplayer.visualizer.MilkDropPresetInfo
import io.github.d3sox.musicplayer.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

@Composable
internal fun MilkDropButton(open: () -> Unit) = AppIconButton(AppIcon.WAVEFORM, "MilkDrop visualizer", onClick = open)

@Composable
internal expect fun MilkDropVisualizer(close: () -> Unit)

@Composable
internal fun MilkDropControls(presets: List<MilkDropPresetInfo>, selected: Int, select: (Int) -> Unit,
    automatic: Boolean, changeAutomatic: (Boolean) -> Unit) {
    var browse by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (presets.isNotEmpty()) {
            OutlinedCard(onClick = { browse = true }, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Choose MilkDrop preset" }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(presets[selected].name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${presets[selected].collection} · ${presets.size} presets", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(AppIcon.SEARCH.vector, null, Modifier.padding(start = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { select((selected - 1 + presets.size) % presets.size) }, modifier = Modifier.weight(1f)) { Text("Previous preset") }
                OutlinedButton(onClick = { select((selected + 1) % presets.size) }, modifier = Modifier.weight(1f)) { Text("Next preset") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Change preset every 20 seconds", Modifier.weight(1f))
            Switch(automatic, changeAutomatic, Modifier.semantics { contentDescription = "Change presets automatically" })
        }
    }
    if (browse) MilkDropPresetPicker(presets, selected, { select(it); browse = false }, { browse = false })
}

@Composable
@OptIn(ExperimentalResourceApi::class)
private fun MilkDropPresetPicker(presets: List<MilkDropPresetInfo>, selected: Int, select: (Int) -> Unit, close: () -> Unit) {
    var credits by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf("") }
    LaunchedEffect(credits) { if (credits) notices = Res.readBytes("files/visualizer/milkdrop-notices.txt").decodeToString() }
    var query by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    val collections = remember(presets) { presets.map { it.collection }.distinct().sorted() }
    val categories = remember(presets, collection) {
        presets.filter { collection == null || it.collection == collection }.map { it.category.substringBefore('/') }
            .filter { it.isNotEmpty() }.distinct().sorted()
    }
    val matches = remember(presets, query, collection, category) {
        presets.indices.filter { index -> presets[index].let {
            (collection == null || it.collection == collection) &&
                (category == null || it.category.substringBefore('/') == category) &&
                (query.isBlank() || "${it.name} ${it.collection} ${it.category}".contains(query.trim(), ignoreCase = true))
        } }
    }
    val list = rememberLazyListState()
    LaunchedEffect(query, collection, category) { list.scrollToItem(0) }
    AlertDialog(onDismissRequest = close, title = { Text("MilkDrop presets") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search presets") }, singleLine = true)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(collection == null, { collection = null; category = null }, label = { Text("All") })
                collections.forEach { name -> FilterChip(collection == name, { collection = name; category = null }, label = { Text(name) }) }
            }
            if (categories.size > 1) SettingChoice("Category", category,
                listOf(null to "All categories") + categories.map { it to it }, card = false) { category = it }
            Text("${matches.size} presets", style = MaterialTheme.typography.bodySmall)
            if (matches.isEmpty()) Text("No presets match your search.")
            LazyColumn(Modifier.weight(1f), state = list) {
                items(matches, key = { it }) { index ->
                    val preset = presets[index]
                    ListItem(headlineContent = { Text(preset.name) }, supportingContent = {
                        Text(listOf(preset.collection, preset.category).filter { it.isNotEmpty() }.joinToString(" · "))
                    }, trailingContent = { if (index == selected) Icon(AppIcon.CHECK.vector, "Selected preset") },
                        colors = ListItemDefaults.colors(containerColor = if (index == selected)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            headlineColor = if (index == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            supportingColor = if (index == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier.selectable(index == selected, role = Role.RadioButton) { select(index) })
                }
            }
        }
    }, dismissButton = { TextButton(onClick = { credits = true }) { Text("Preset credits") } },
        confirmButton = { TextButton(onClick = close) { Text("Close") } })
    if (credits) AlertDialog(onDismissRequest = { credits = false }, title = { Text("Preset credits") },
        text = { Text(notices, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { credits = false }) { Text("Close") } })
}
