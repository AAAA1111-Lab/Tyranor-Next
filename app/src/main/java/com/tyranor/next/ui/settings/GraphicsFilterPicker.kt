package com.tyranor.next.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.settings.GraphicsFilterCategory
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.DialogTextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** Two-level algorithm -> preset navigation, shared by global and per-game chain editors. */
@Composable
internal fun GraphicsFilterPicker(
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<GraphicsFilterCategory?>(null) }
    val labels = options.toMap()
    LaunchedEffect(enabled) { if (!enabled) { open = false; category = null } }
    ArrowPreference(
        title = stringResource(R.string.graphics_chain_add),
        enabled = enabled,
        onClick = { if (enabled) { category = null; open = true } },
    )
    if (!open) return
    AppAlertDialog(
        onDismissRequest = { open = false; category = null },
        title = { Text(stringResource(R.string.graphics_chain_add_type), style = MaterialTheme.typography.titleMedium) },
        text = {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= (520 * fontScale).dp
                val selected = category
                val choosePreset: (String) -> Unit = { id ->
                    if (enabled && selected != null && id in selected.presetIds) { onSelect(id); open = false }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterCategories(enabled, Modifier.weight(0.38f)) {
                            if (it.presetIds.size == 1) { onSelect(it.presetIds.single()); open = false } else category = it
                        }
                        if (selected != null) {
                            FilterPresets(selected, labels, enabled, Modifier.weight(0.62f), choosePreset)
                        } else {
                            Spacer(Modifier.weight(0.62f))
                        }
                    }
                } else if (selected == null) {
                    FilterCategories(enabled, Modifier.fillMaxWidth()) {
                        if (it.presetIds.size == 1) { onSelect(it.presetIds.single()); open = false } else category = it
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(graphicsFilterCategoryTitle(selected), style = MaterialTheme.typography.titleMedium)
                        FilterPresets(selected, labels, enabled, Modifier.weight(1f, fill = false), choosePreset)
                    }
                }
            }
        },
        buttonArrangement = if (category != null) Arrangement.SpaceBetween else Arrangement.End,
        dismissButton = if (category != null) ({
            DialogTextButton(stringResource(R.string.graphics_filter_back_categories), onClick = { category = null })
        }) else null,
        confirmButton = {
            DialogTextButton(stringResource(R.string.common_cancel), onClick = { open = false })
        },
    )
}

@Composable
private fun FilterCategories(enabled: Boolean, modifier: Modifier, onSelect: (GraphicsFilterCategory) -> Unit) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(GraphicsFilterCategory.entries.toList(), key = { it.name }) { category ->
            AppNavItem(
                title = graphicsFilterCategoryTitle(category),
                showLeadingIcon = false,
                showArrow = category.presetIds.size > 1,
                titleMaxLines = 2,
                indication = null,
                containerColor = DialogItemSurface,
                onClick = if (enabled) ({ onSelect(category) }) else null,
            )
        }
    }
}

@Composable
private fun FilterPresets(
    category: GraphicsFilterCategory,
    labels: Map<String, String>,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(category.presetIds, key = { it }) { id ->
            AppNavItem(
                title = labels.getValue(id),
                showLeadingIcon = false,
                showArrow = false,
                titleMaxLines = 3,
                indication = null,
                containerColor = DialogItemSurface,
                onClick = if (enabled) ({ onSelect(id) }) else null,
            )
        }
    }
}
