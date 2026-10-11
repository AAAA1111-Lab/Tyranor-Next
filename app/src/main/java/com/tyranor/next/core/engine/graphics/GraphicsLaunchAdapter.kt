package com.tyranor.next.core.engine.graphics

import android.content.Intent

/** Transport-only adapter, shared by built-in and external launchers. It never selects another family's defaults. */
internal fun Intent.putDisabledGraphicsRequest() {
    for ((key, value) in GraphicsLaunchPolicy.disabledExtras()) {
        when (value) {
            is Int -> putExtra(key, value)
            is Boolean -> putExtra(key, value)
            is String -> putExtra(key, value)
        }
    }
}
