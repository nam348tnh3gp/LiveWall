package com.nam.livewall

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import kotlin.math.max
import kotlin.math.min

class LiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WallEngine()

    inner class WallEngine : Engine(), SensorEventListener {
        private val prefs = Prefs(this@LiveWallpaperService)
        private val handler = Handler(Looper.getMainLooper())
        private val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        private var player: MediaPlayer? = null
        private var prepared = false
        private var retries = 0
        private var img: Drawable? = null
        private var ow = 0f
        private var oh = 0f
        private var visible = false
        private var hasSurface = false
        private var w = 0
        private var h = 0
        private var tx = 0f
        private var ty = 0f
        private var targetX = 0f
        private var targetY = 0f

        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible && (img is AnimatedImageDrawable || prefs.parallax)) handler.postDelayed(this, 33)
            }
        }
        private val reload = Runnable { load() }
        // Debounced: several pref changes -> one reload
        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            handler.removeCallbacks(reload); handler.postDelayed(reload, 200)
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            hasSurface = true
            load()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            draw()
        }

        // The surface dies when the wallpaper is replaced/re-set: drop the player or it errors next time.
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            hasSurface = false
            handler.removeCallbacks(tick); handler.removeCallbacks(reload)
            release()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            applyVisibility()
        }

        override fun onDestroy() {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
            handler.removeCallbacksAndMessages(null)
            sm.unregisterListener(this)
            release()
            super.onDestroy()
        }

        private fun applyVisibility() {
            handler.removeCallbacks(tick)
            sm.unregisterListener(this)
            if (visible) {
                player?.let { if (prepared && !it.isPlaying) runCatching { it.start() } }
                (img as? AnimatedImageDrawable)?.start()
                if (img != null) handler.post(tick)
                if (prefs.parallax && img != null) {
                    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                        sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    }
                }
            } else {
                player?.let { if (prepared && it.isPlaying) runCatching { it.pause() } }
                (img as? AnimatedImageDrawable)?.stop()
            }
        }

        private fun release() {
            prepared = false
            player?.let { runCatching { it.setOnErrorListener(null); it.reset(); it.release() } }
            player = null
            (img as? AnimatedImageDrawable)?.stop()
            img = null
        }

        private fun load() {
            release()
            if (!hasSurface) return
            val f = prefs.file() ?: return
            try {
                if (prefs.isVideo) loadVideo(f) else loadImage(f)
            } catch (e: Throwable) {
                release()
            }
            applyVisibility()
        }

        private fun loadVideo(f: File) {
            val mp = MediaPlayer()
            player = mp
            mp.setSurface(surfaceHolder.surface)
            mp.setDataSource(f.path)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                p.setVideoScalingMode(
                    if (prefs.mode == Prefs.STRETCH) MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    else MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                )
                prepared = true; retries = 0
                if (visible) runCatching { p.start() }
            }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) {
                    handler.removeCallbacks(reload)
                    if (retries++ < 3) handler.postDelayed(reload, 500) else release()
                }
                true
            }
            mp.prepareAsync()
        }

        private fun loadImage(f: File) {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                // Software bitmaps only: hardware bitmaps crash on a software wallpaper canvas.
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val big = max(info.size.width, info.size.height)
                val sample = if (big > 4096) (big + 4095) / 4096 else 1
                if (sample > 1) dec.setTargetSampleSize(sample)
                ow = (info.size.width / sample).toFloat(); oh = (info.size.height / sample).toFloat()
            }
            if (d is AnimatedImageDrawable) {
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d.callback = object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) {}
                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
                }
            }
            img = d
        }

        private fun draw() {
            val d = img ?: return
            if (w == 0 || h == 0) return
            val c = try { surfaceHolder.lockCanvas() } catch (e: Throwable) { null } ?: return
            try {
                c.drawColor(Color.BLACK)
                val iw = if (ow > 0) ow else d.intrinsicWidth.toFloat()
                val ih = if (oh > 0) oh else d.intrinsicHeight.toFloat()
                if (iw <= 0 || ih <= 0) return
                d.setFilterBitmap(!prefs.crisp)
                val sx = w / iw; val sy = h / ih
                var kx: Float; var ky: Float
                when (prefs.mode) {
                    Prefs.AUTO -> {
                        val fit = min(sx, sy); val fill = max(sx, sy)
                        kx = if (fill / fit <= 1.15f) fill else fit; ky = kx
                    }
                    Prefs.FIT -> { kx = min(sx, sy); ky = kx }
                    Prefs.STRETCH -> { kx = sx; ky = sy }
                    Prefs.ORIGINAL -> { kx = 1f; ky = 1f }
                    else -> { kx = max(sx, sy); ky = kx }
                }
                val par = prefs.parallax && prefs.mode != Prefs.FIT && prefs.mode != Prefs.ORIGINAL
                if (par) { kx *= 1.1f; ky *= 1.1f }
                tx += (targetX - tx) * 0.12f; ty += (targetY - ty) * 0.12f
                val dw = iw * kx; val dh = ih * ky
                val l = (w - dw) / 2 + if (par) tx else 0f
                val t = (h - dh) / 2 + if (par) ty else 0f
                d.setBounds(l.toInt(), t.toInt(), (l + dw).toInt(), (t + dh).toInt())
                d.draw(c)
            } catch (e: Throwable) {
                // never crash the wallpaper process on a bad frame
            } finally {
                runCatching { surfaceHolder.unlockCanvasAndPost(c) }
            }
        }

        override fun onSensorChanged(e: SensorEvent) {
            targetX = (-e.values[0] / 9.8f).coerceIn(-1f, 1f) * w * 0.045f
            targetY = (e.values[1] / 9.8f).coerceIn(-1f, 1f) * h * 0.035f
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
}
