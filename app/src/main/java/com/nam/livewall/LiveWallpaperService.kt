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
import kotlin.math.max
import kotlin.math.min

class LiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WallEngine()

    inner class WallEngine : Engine(), SensorEventListener {
        private val prefs = Prefs(this@LiveWallpaperService)
        private val handler = Handler(Looper.getMainLooper())
        private val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        private var player: MediaPlayer? = null
        private var img: Drawable? = null
        private var visible = false
        private var w = 0
        private var h = 0
        private var tx = 0f
        private var ty = 0f
        private var targetX = 0f
        private var targetY = 0f

        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible && img != null) handler.postDelayed(this, 33)
            }
        }
        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            handler.post { load(); draw() }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            load()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                player?.let { if (!it.isPlaying) runCatching { it.start() } }
                (img as? AnimatedImageDrawable)?.start()
                handler.removeCallbacks(tick); handler.post(tick)
                sm.unregisterListener(this)
                if (prefs.parallax && img != null) {
                    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                        sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    }
                }
            } else {
                player?.let { if (it.isPlaying) it.pause() }
                (img as? AnimatedImageDrawable)?.stop()
                handler.removeCallbacks(tick)
                sm.unregisterListener(this)
            }
        }

        override fun onDestroy() {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
            handler.removeCallbacksAndMessages(null)
            sm.unregisterListener(this)
            release()
            super.onDestroy()
        }

        private fun release() {
            player?.release(); player = null
            (img as? AnimatedImageDrawable)?.stop(); img = null
        }

        private fun load() {
            release()
            val f = prefs.file() ?: return
            runCatching {
                if (prefs.isVideo) {
                    player = MediaPlayer().apply {
                        setSurface(surfaceHolder.surface)
                        setDataSource(f.path)
                        isLooping = true
                        setVolume(0f, 0f)
                        setOnPreparedListener {
                            it.setVideoScalingMode(
                                if (prefs.mode == Prefs.STRETCH) MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
                                else MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                            )
                            if (visible) it.start()
                        }
                        prepareAsync()
                    }
                } else {
                    val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f))
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
            }
            if (visible) onVisibilityChanged(true)
        }

        private fun draw() {
            val d = img ?: return
            if (w == 0 || h == 0) return
            val c = surfaceHolder.lockCanvas() ?: return
            try {
                c.drawColor(Color.BLACK)
                val iw = d.intrinsicWidth.toFloat(); val ih = d.intrinsicHeight.toFloat()
                if (iw <= 0 || ih <= 0) return
                val sx = w / iw; val sy = h / ih
                var kx: Float; var ky: Float
                when (prefs.mode) {
                    Prefs.FIT -> { kx = min(sx, sy); ky = kx }
                    Prefs.STRETCH -> { kx = sx; ky = sy }
                    else -> { kx = max(sx, sy); ky = kx }
                }
                if (prefs.parallax && prefs.mode != Prefs.FIT) { kx *= 1.1f; ky *= 1.1f }
                tx += (targetX - tx) * 0.12f; ty += (targetY - ty) * 0.12f
                val dw = iw * kx; val dh = ih * ky
                val l = (w - dw) / 2 + if (prefs.parallax) tx else 0f
                val t = (h - dh) / 2 + if (prefs.parallax) ty else 0f
                d.setBounds(l.toInt(), t.toInt(), (l + dw).toInt(), (t + dh).toInt())
                d.draw(c)
            } finally {
                surfaceHolder.unlockCanvasAndPost(c)
            }
        }

        override fun onSensorChanged(e: SensorEvent) {
            targetX = (-e.values[0] / 9.8f).coerceIn(-1f, 1f) * w * 0.045f
            targetY = (e.values[1] / 9.8f).coerceIn(-1f, 1f) * h * 0.035f
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
}
