package com.nam.livewall

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class Prefs(private val ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("lw", Context.MODE_PRIVATE)
    var mode: Int
        get() = sp.getInt("mode", FILL)
        set(v) = sp.edit().putInt("mode", v).apply()
    var parallax: Boolean
        get() = sp.getBoolean("parallax", false)
        set(v) = sp.edit().putBoolean("parallax", v).apply()
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

    companion object { const val FILL = 0; const val FIT = 1; const val STRETCH = 2 }
}
