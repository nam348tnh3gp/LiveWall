package com.nam.livewall

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class Prefs(private val ctx: Context) {
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
    var isVideo: Boolean
        get() = sp.getBoolean("video", false)
        set(v) = sp.edit().putBoolean("video", v).apply()
    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v).apply()
    var stamp: Long
        get() = sp.getLong("stamp", 0L)
        set(v) = sp.edit().putLong("stamp", v).apply()
    fun file(): File? = File(ctx.filesDir, "wall.bin").takeIf { it.exists() }

    companion object { const val AUTO = 0; const val FILL = 1; const val FIT = 2; const val STRETCH = 3; const val ORIGINAL = 4 }
}
