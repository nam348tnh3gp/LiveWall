package com.nam.livewall

import kotlin.math.max
import kotlin.math.min

/** Shared display-mode maths so images (Canvas) and videos (OpenGL) honour the same options. */
object Layout {
    /**
     * Fills out = [left, top, width, height] in screen pixels.
     * @param orig scale used by ORIGINAL mode (1 for video; the decode sample size for downsampled images)
     */
    fun rect(
        mode: Int, parallax: Boolean, w: Int, h: Int,
        iw: Float, ih: Float, orig: Float, tx: Float, ty: Float, out: FloatArray
    ) {
        val sx = w / iw
        val sy = h / ih
        var kx: Float
        var ky: Float
        when (mode) {
            Prefs.AUTO -> {
                val fit = min(sx, sy); val fill = max(sx, sy)
                kx = if (fill / fit <= 1.15f) fill else fit; ky = kx
            }
            Prefs.FIT -> { kx = min(sx, sy); ky = kx }
            Prefs.STRETCH -> { kx = sx; ky = sy }
            Prefs.ORIGINAL -> { kx = orig; ky = orig }
            else -> { kx = max(sx, sy); ky = kx }
        }
        val par = parallax && mode != Prefs.FIT && mode != Prefs.ORIGINAL
        if (par) { kx *= 1.1f; ky *= 1.1f }
        val dw = iw * kx
        val dh = ih * ky
        out[0] = (w - dw) / 2 + if (par) tx else 0f
        out[1] = (h - dh) / 2 + if (par) ty else 0f
        out[2] = dw
        out[3] = dh
    }
}
