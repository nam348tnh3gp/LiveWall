package com.tgfn.livewall

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object Importer {
    private val videoExt = setOf("mp4", "mkv", "webm", "3gp", "mov", "m4v")

    /** Copies the picked/shared file into app storage under a NEW name each time. Call off the main thread. */
    fun import(ctx: Context, uri: Uri): Boolean = runCatching {
        val cr = ctx.contentResolver
        val type = cr.getType(uri) ?: ""
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "wallpaper"
        val video = type.startsWith("video") || name.substringAfterLast('.', "").lowercase() in videoExt

        val tmp = File(ctx.filesDir, "import.tmp")
        cr.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val dest = File(ctx.filesDir, "wall_${System.currentTimeMillis()}.bin")
        check(tmp.renameTo(dest))

        val prefs = Prefs(ctx)
        prefs.setSource(dest.path, video, name, prefs.target)
        // Keep every file still used by either screen.
        val keep = setOf(prefs.path(false), prefs.path(true))
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("wall_") && it.path !in keep }?.forEach { it.delete() }
        true
    }.getOrDefault(false)
}
