package com.nierduolong.morningbell.data

import android.content.Context
import com.nierduolong.morningbell.core.StickyThemeRegistry

/** 便利贴语录分支：用户选中的主题包 id（持久化） */
class StickyThemeSettings(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getUserSelectedThemePack(): String {
        val raw = prefs.getString(KEY_USER_SELECTED_THEME_PACK, null)
        return if (raw != null && StickyThemeRegistry.isValidPackId(raw)) {
            raw
        } else {
            StickyThemeRegistry.DEFAULT_PACK_ID
        }
    }

    fun setUserSelectedThemePack(id: String) {
        val safe =
            if (StickyThemeRegistry.isValidPackId(id)) {
                id
            } else {
                StickyThemeRegistry.DEFAULT_PACK_ID
            }
        prefs.edit().putString(KEY_USER_SELECTED_THEME_PACK, safe).apply()
    }

    companion object {
        private const val PREFS_NAME = "morning_bell_sticky_theme"
        private const val KEY_USER_SELECTED_THEME_PACK = "user_selected_theme_pack"
    }
}
