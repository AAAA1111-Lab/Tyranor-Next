package com.tyranor.next.core.game.launch

import com.core.engine.LaunchContract
import com.core.gl.Anime4kRuntime

/** Legacy filter requests retained for official Artemis launches. */
internal object ArtemisGraphicsLaunchPolicy {
    fun extras(anime4kEnabled: Boolean, anime4kMode: String): Map<String, Any> =
        mapOf(
            LaunchContract.ARTEMIS_ANIME4K_ENABLED to anime4kEnabled,
            Anime4kRuntime.EXTRA_MODE to anime4kMode,
        )
}
