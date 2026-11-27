package com.tgfn.livewall

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
    var sound: Boolean
        get() = sp.getBoolean(K_SOUND, false)
        set(v) = sp.edit().putBoolean(K_SOUND, v).apply()
    /** Which screen(s) the next imported file applies to: T_HOME, T_LOCK or T_BOTH. */
    var target: Int
        get() = sp.getInt("target", T_BOTH)
        set(v) = sp.edit().putInt("target", v).apply()

    // Home and lock screen each remember their own file.
    private fun k(base: String, lock: Boolean) = if (lock) "${base}_lock" else "${base}_home"
    fun path(lock: Boolean): String? = sp.getString(k("path", lock), null)
    fun isVideo(lock: Boolean): Boolean = sp.getBoolean(k("video", lock), false)
    fun name(lock: Boolean): String = sp.getString(k("name", lock), "") ?: ""
    fun file(lock: Boolean): File? = path(lock)?.let { File(it) }?.takeIf { it.exists() }

    /** One atomic edit so the wallpaper reloads once, never half-way. */
    fun setSource(path: String, video: Boolean, name: String, target: Int) {
        val e = sp.edit()
        for (lock in listOf(false, true)) {
            val hit = target == T_BOTH || (target == T_LOCK) == lock
            if (hit) e.putString(k("path", lock), path).putBoolean(k("video", lock), video)
                .putString(k("name", lock), name)
        }
        e.putLong("stamp", System.currentTimeMillis()).apply()
    }

    companion object {
        const val K_MODE = "mode"; const val K_PARALLAX = "parallax"; const val K_CRISP = "crisp"; const val K_SOUND = "sound"
        const val T_HOME = 0; const val T_LOCK = 1; const val T_BOTH = 2
        const val AUTO = 0; const val FILL = 1; const val FIT = 2; const val STRETCH = 3; const val ORIGINAL = 4
    }
}
