package com.tyranor.next.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.settings.GraphicsRefreshInterval
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import java.util.Locale

@Composable
internal fun GraphicsRefreshIntervalPreference(
    intervalMs: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val presets = GraphicsRefreshInterval.PRESETS_MS
    OverlayDropdownPreference(
        title = stringResource(R.string.graphics_refresh_interval),
        items = refreshLabels(),
        selectedIndex = presets.indexOf(GraphicsRefreshInterval.normalize(intervalMs)),
        enabled = enabled,
        onSelectedIndexChange = { onChange(presets[it]) },
    )
}

@Composable
internal fun GraphicsRefreshIntervalOverridePreference(
    globalIntervalMs: Int,
    overrideIntervalMs: Int?,
    enabled: Boolean,
    onChange: (Int?) -> Unit,
) {
    val presets = GraphicsRefreshInterval.PRESETS_MS
    val inherited = stringResource(R.string.engine_settings_follow_global) + " (" +
        stringResource(R.string.graphics_refresh_seconds, refreshSeconds(GraphicsRefreshInterval.normalize(globalIntervalMs))) + ")"
    OverlayDropdownPreference(
        title = stringResource(R.string.graphics_refresh_interval),
        items = listOf(inherited) + refreshLabels(),
        selectedIndex = overrideIntervalMs?.let { presets.indexOf(GraphicsRefreshInterval.normalize(it)) + 1 } ?: 0,
        enabled = enabled,
        onSelectedIndexChange = { onChange(if (it == 0) null else presets[it - 1]) },
    )
}

@Composable
private fun refreshLabels(): List<String> = GraphicsRefreshInterval.PRESETS_MS.map {
    stringResource(R.string.graphics_refresh_seconds, refreshSeconds(it))
}

internal fun refreshSeconds(ms: Int): String =
    String.format(Locale.ROOT, "%.3f", ms / 1000.0).trimEnd('0').trimEnd('.')
