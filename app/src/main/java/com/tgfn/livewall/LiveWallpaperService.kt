package com.tgfn.livewall

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class LiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WallEngine()

    inner class WallEngine : Engine(), SensorEventListener {
        private val prefs = Prefs(this@LiveWallpaperService)
        private val handler = Handler(Looper.getMainLooper())
        private val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        private var player: MediaPlayer? = null
        private var gl: GlVideo? = null
        private var gen = 0                 // bumped on every release(); invalidates stale async callbacks
        private var prepared = false
        private var retries = 0
        private var img: Drawable? = null
        private var origScale = 1f          // ORIGINAL mode scale (decode sample size)
        private var ow = 0f
        private var oh = 0f
        private var visible = false
        private var dirty = false           // source changed while hidden: reload when shown
        private var hasSurface = false
        private var useHw = true            // hardware canvas for static images (falls back if the device refuses)
        private var w = 0
        private var h = 0
        private var tx = 0f
        private var ty = 0f
        private var targetX = 0f
        private var targetY = 0f
        private val rect = FloatArray(4)

        // Display options cached here: never read SharedPreferences per frame.
        private var mode = Prefs.AUTO
        private var parallax = false
        private var crisp = false
        private var sound = false

        private var tickPosted = false
        private val tick = object : Runnable {
            override fun run() {
                tickPosted = false
                val moving = stepParallax()
                draw()
                val animated = img is AnimatedImageDrawable
                if (visible && (animated || moving)) {
                    tickPosted = true
                    handler.postDelayed(this, if (animated) 33L else 16L)
                }
            }
        }
        private val reload = Runnable { load() }

        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                // Sound: just change the player volume, no reload.
                Prefs.K_SOUND -> {
                    readSettings()
                    player?.let { applyVolume(it) }
                }
                // Display options: apply instantly, no re-decode / re-prepare.
                Prefs.K_MODE, Prefs.K_PARALLAX, Prefs.K_CRISP -> {
                    readSettings()
                    applyVisibility()
                    draw()
                }
                // New file (several keys change together): debounce into one reload.
                // While the wallpaper is hidden (the app is in front) just remember it: decoding a big
                // image / starting a video on the shared main thread is what made the app UI stutter.
                else -> {
                    if (visible) { handler.removeCallbacks(reload); handler.postDelayed(reload, 200) }
                    else { dirty = true }
                }
            }
        }

        private fun readSettings() {
            mode = prefs.mode; parallax = prefs.parallax; crisp = prefs.crisp; sound = prefs.sound
            if (!parallax) { tx = 0f; ty = 0f; targetX = 0f; targetY = 0f }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            readSettings()
            prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            hasSurface = true
            if (visible) load() else dirty = true
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            draw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            draw()
        }

        // The surface dies when the wallpaper is replaced/re-set: drop the player/GL or it errors next time.
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            hasSurface = false
            handler.removeCallbacks(tick); handler.removeCallbacks(reload)
            tickPosted = false
            release()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v && dirty && hasSurface) load() else applyVisibility()
        }

        override fun onDestroy() {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
            handler.removeCallbacksAndMessages(null)
            sm.unregisterListener(this)
            release()
            super.onDestroy()
        }

        private fun hasContent() = img != null || gl != null

        /** Schedule one frame (and keep the loop going while parallax is still settling). */
        private fun kick() {
            if (visible && !tickPosted && hasContent()) { tickPosted = true; handler.post(tick) }
        }

        private fun applyVisibility() {
            handler.removeCallbacks(tick); tickPosted = false
            sm.unregisterListener(this)
            if (visible) {
                player?.let { if (prepared && !it.isPlaying) runCatching { it.start() } }
                (img as? AnimatedImageDrawable)?.start()
                kick()
                if (parallax) {
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
            gen++
            prepared = false
            player?.let { runCatching { it.setOnErrorListener(null); it.reset(); it.release() } }
            player = null
            gl?.release()   // after the player, before the surface goes away
            gl = null
            (img as? AnimatedImageDrawable)?.let { it.stop(); it.callback = null }
            img = null
        }

        private fun load() {
            dirty = false
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

        // ---------------------------------------------------------------- video (OpenGL)

        private fun loadVideo(f: File) {
            val myGen = gen
            // The GL thread calls back once the video SurfaceTexture exists.
            gl = GlVideo(surfaceHolder.surface) { vs ->
                handler.post {
                    if (gen != myGen || !hasSurface) return@post
                    try { startPlayer(f, vs) } catch (e: Throwable) { release() }
                }
            }
        }

        private fun startPlayer(f: File, vs: Surface) {
            val mp = MediaPlayer()
            player = mp
            mp.setSurface(vs)
            mp.setDataSource(f.path)
            mp.isLooping = true
            // Wallpaper audio: media usage, and no audio focus is requested so it never pauses the user's music.
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            )
            applyVolume(mp)
            mp.setOnVideoSizeChangedListener { p, vw, vh ->
                if (player === p) gl?.setVideoSize(vw, vh)
            }
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                gl?.setVideoSize(p.videoWidth, p.videoHeight)
                prepared = true; retries = 0
                if (visible) runCatching { p.start() }
                draw()
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

        private fun applyVolume(mp: MediaPlayer) {
            val v = if (sound) 1f else 0f
            runCatching { mp.setVolume(v, v) }
        }

        // ---------------------------------------------------------------- image (Canvas)

        private fun loadImage(f: File) {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                // Hardware bitmaps draw fine on a hardware canvas; use software only after a fallback.
                if (!useHw) dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val big = max(info.size.width, info.size.height)
                val sample = if (big > 4096) (big + 4095) / 4096 else 1
                if (sample > 1) dec.setTargetSampleSize(sample)
                origScale = sample.toFloat()
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

        /** Smooths the parallax offset; returns true while it is still moving. */
        private fun stepParallax(): Boolean {
            if (!parallax) return false
            tx += (targetX - tx) * 0.12f; ty += (targetY - ty) * 0.12f
            return abs(targetX - tx) > 0.3f || abs(targetY - ty) > 0.3f
        }

        private fun draw() {
            gl?.let { it.render(mode, parallax, crisp, tx, ty); return }
            val d = img ?: return
            if (w == 0 || h == 0) return
            val animated = d is AnimatedImageDrawable
            val hw = useHw && !animated   // animated drawables need the software canvas to advance frames
            val c = try {
                if (hw) surfaceHolder.lockHardwareCanvas() else surfaceHolder.lockCanvas()
            } catch (e: Throwable) {
                if (hw) { useHw = false; handler.post(reload) }
                null
            } ?: return
            try {
                c.drawColor(Color.BLACK)
                val iw = if (ow > 0) ow else d.intrinsicWidth.toFloat()
                val ih = if (oh > 0) oh else d.intrinsicHeight.toFloat()
                if (iw <= 0 || ih <= 0) return
                d.setFilterBitmap(!crisp)
                Layout.rect(mode, parallax, w, h, iw, ih, origScale, tx, ty, rect)
                d.setBounds(
                    rect[0].toInt(), rect[1].toInt(),
                    (rect[0] + rect[2]).toInt(), (rect[1] + rect[3]).toInt()
                )
                d.draw(c)
            } catch (e: Throwable) {
                // never crash the wallpaper process on a bad frame
            } finally {
                runCatching { surfaceHolder.unlockCanvasAndPost(c) }
            }
        }

        override fun onSensorChanged(e: SensorEvent) {
            if (w == 0 || h == 0) return
            targetX = (-e.values[0] / 9.8f).coerceIn(-1f, 1f) * w * 0.045f
            targetY = (e.values[1] / 9.8f).coerceIn(-1f, 1f) * h * 0.035f
            kick()
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
}
