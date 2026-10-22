package com.dataproxy.ui.theme

/**
 * User preference for app theme.
 *
 * [System] follows the device dark-mode setting; [Light] / [Dark] override it.
 * The selection is made on the Settings screen.
 */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: System
    }
}
