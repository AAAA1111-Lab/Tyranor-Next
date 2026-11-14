package com.tyranor.next.ui.cover

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppSettingsStore

/**
 * 非组合上下文的来源显示名：与 [coverSourceTitle] 同源单一事实（组合上下文用后者以支持
 * 本地化条目；抽屉副文本等非组合路径用本函数，避免再散落硬编码来源名）。
 */
internal fun coverSourceTitleRaw(source: String): String = when (source) {
    AppSettingsStore.COVER_SOURCE_HIKARINAGI -> "Hikarinagi"
    AppSettingsStore.COVER_SOURCE_BANGUMI -> "Bangumi"
    AppSettingsStore.COVER_SOURCE_STEAM -> "Steam"
    AppSettingsStore.COVER_SOURCE_VNDB -> "VNDB"
    else -> source
}

@Composable
internal fun coverSourceTitle(source: String): String = when (source) {
    AppSettingsStore.COVER_SOURCE_NEXTMOE -> stringResource(R.string.cover_source_nextmoe)
    AppSettingsStore.COVER_SOURCE_LOCAL -> stringResource(R.string.cover_source_local)
    AppSettingsStore.COVER_SOURCE_CUSTOM -> stringResource(R.string.cover_source_custom)
    else -> coverSourceTitleRaw(source)
}
