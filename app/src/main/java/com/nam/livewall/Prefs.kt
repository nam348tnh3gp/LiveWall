package com.nam.livewall

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("lw", Context.MODE_PRIVATE)
    var mode: Int
        get() = sp.getInt("mode", AUTO)
        set(v) = sp.edit().putInt("mode", v).apply()
    var parallax: Boolean
        get() = sp.getBoolean("parallax", false)
        set(v) = sp.edit().putBoolean("parallax", v).apply()
    var crisp: Boolean
        get() = sp.getBoolean("crisp", false)
        set(v) = sp.edit().putBoolean("crisp", v).apply()
    val isVideo: Boolean get() = sp.getBoolean("video", false)
    val name: String get() = sp.getString("name", "") ?: ""

    fun file(): File? = sp.getString("path", null)?.let { File(it) }?.takeIf { it.exists() }

    /** One atomic edit so the wallpaper reloads once, never half-way. */
    fun setSource(path: String, video: Boolean, name: String) {
        sp.edit().putString("path", path).putBoolean("video", video)
            .putString("name", name).putLong("stamp", System.currentTimeMillis()).apply()
    }

    companion object { const val AUTO = 0; const val FILL = 1; const val FIT = 2; const val STRETCH = 3; const val ORIGINAL = 4 }
}
