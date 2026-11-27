package com.tgfn.livewall

import android.app.KeyguardManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Everything (image, GIF/WebP, video) is drawn through one OpenGL renderer per surface ([GlWall]).
 * A surface must never be used through both EGL and lockCanvas, which is what used to break the
 * wallpaper (home and lock screen) whenever the file type changed.
 */
class LiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WallEngine()

    inner class WallEngine : Engine(), SensorEventListener {
        private val prefs = Prefs(this@LiveWallpaperService)
        private val handler = Handler(Looper.getMainLooper())
        private val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        private var player: MediaPlayer? = null
        private var videoFile: File? = null   // current video; the player only exists while visible
        private var gl: GlWall? = null
        private var gen = 0                 // bumped on every releaseContent(); invalidates stale async callbacks
        private var prepared = false
        private var retries = 0
        private var img: AnimatedImageDrawable? = null   // only animated images are kept; stills live in the GL texture
        private var animBmps = arrayOfNulls<Bitmap>(2)
        private var animIdx = 0
        private var origScale = 1f          // ORIGINAL mode scale (decode sample size)
        private var visible = false
        private var loadedPath: String? = null   // what this engine currently shows
        private var loadedVideo = false
        private var dirty = false           // source changed while hidden: reload when shown
        private var hasSurface = false
        private var w = 0
        private var h = 0
        private var tx = 0f
        private var ty = 0f
        private var targetX = 0f
        private var targetY = 0f

        // Options cached here: never read SharedPreferences per frame.
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
                val animated = img != null
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
                // While hidden just remember it: loading on the shared main thread made the app UI stutter.
                else -> {
                    // Home and lock engines share these prefs: only reload when OUR screen's file changed.
                    if (needsReload()) {
                        if (visible) { handler.removeCallbacks(reload); handler.postDelayed(reload, 200) }
                        else { dirty = true }
                    }
                }
            }
        }

        /** Flags of the screen this engine renders (Android 13/14+ only; 0 = unknown). */
        private fun wallFlags(): Int {
            if (Build.VERSION.SDK_INT < 33) return 0
            return runCatching {
                WallpaperService.Engine::class.java.getMethod("getWallpaperFlags").invoke(this) as Int
            }.getOrDefault(0)
        }

        /**
         * Which screen's file to show. Android 13/14+ tells the engine which screen it renders.
         * On older versions (down to Android 9) there is no such API, so we go by the keyguard:
         * visible while the lock screen is up = lock screen file, otherwise the home screen file.
         * This is engine-agnostic, so it also works when one engine serves both screens.
         */
        private fun isLock(): Boolean {
            val f = wallFlags()
            if (f != 0) return f and 2 != 0 && f and 1 == 0   // FLAG_LOCK set, FLAG_SYSTEM not
            if (isPreview) return false
            val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            return km?.isKeyguardLocked == true
        }

        private fun needsReload(): Boolean {
            val lock = isLock()
            return prefs.path(lock) != loadedPath || prefs.isVideo(lock) != loadedVideo
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

        // The surface dies when the wallpaper is replaced/re-set: drop player and GL or it errors next time.
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            hasSurface = false
            handler.removeCallbacks(tick); handler.removeCallbacks(reload)
            tickPosted = false
            releaseContent()
            releaseGl()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            // Re-check on every show: the lock/home file may differ (keyguard state can change between shows).
            if (v && hasSurface && (dirty || needsReload())) load() else applyVisibility()
        }

        override fun onDestroy() {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
            handler.removeCallbacksAndMessages(null)
            sm.unregisterListener(this)
            releaseContent()
            releaseGl()
            super.onDestroy()
        }

        /** Schedule one frame (and keep the loop going while animating / parallax is settling). */
        private fun kick() {
            if (visible && !tickPosted && gl != null) { tickPosted = true; handler.post(tick) }
        }

        private fun applyVisibility() {
            handler.removeCallbacks(tick); tickPosted = false
            sm.unregisterListener(this)
            if (visible) {
                startVideo()
                img?.start()
                kick()
                if (parallax) {
                    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                        sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    }
                }
            } else {
                // Free the hardware decoder right away: phones allow very few at once, and the home
                // and lock screen engines would otherwise starve each other (one stays black).
                stopPlayer()
                img?.stop()
            }
        }

        /** Stops the current file (player / animation). The GL renderer stays: it belongs to the surface. */
        private fun releaseContent() {
            stopPlayer()
            videoFile = null
            img?.let { it.stop(); it.callback = null }
            img = null
            animBmps = arrayOfNulls(2)
        }

        private fun stopPlayer() {
            gen++
            prepared = false
            player?.let { runCatching { it.setOnErrorListener(null); it.reset(); it.release() } }
            player = null
        }

        private fun releaseGl() {
            gl?.release()   // after the player, before the surface goes away
            gl = null
        }

        private fun load() {
            dirty = false
            releaseContent()
            if (!hasSurface) return
            val g = gl ?: GlWall(surfaceHolder.surface).also { gl = it }
            val lock = isLock()
            val f = prefs.file(lock)
            loadedPath = f?.path
            loadedVideo = prefs.isVideo(lock)
            Log.i("LiveWall", "load lock=$lock preview=$isPreview file=${f?.name} video=$loadedVideo")
            if (f == null) {
                g.clear()
                return
            }
            try {
                if (loadedVideo) loadVideo(f, g) else loadImage(f, g)
            } catch (e: Throwable) {
                Log.w("LiveWall", "load failed", e)
                releaseContent()
                g.clear()
            }
            applyVisibility()
            draw()
        }

        // ---------------------------------------------------------------- video

        private fun loadVideo(f: File, g: GlWall) {
            videoFile = f
            if (visible) startVideo()
        }

        /** Creates the player if a video is loaded, we are visible and there is none yet. */
        private fun startVideo() {
            val f = videoFile ?: return
            val g = gl ?: return
            if (player != null || !visible) return
            val myGen = gen
            // The GL thread calls back once the video SurfaceTexture is ready.
            g.useVideoSurface { vs ->
                handler.post {
                    if (gen != myGen || !hasSurface || !visible || player != null) return@post
                    try { startPlayer(f, vs) } catch (e: Throwable) {
                        Log.w("LiveWall", "startPlayer failed", e); stopPlayer()
                    }
                }
            }
        }

        private fun startPlayer(f: File, vs: Surface) {
            val mp = MediaPlayer()
            player = mp
            mp.setSurface(vs)
            mp.setDataSource(f.path)
            mp.isLooping = true
            // Media usage; no audio focus is requested so the user's music is never paused.
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
            mp.setOnErrorListener { p, what, extra ->
                Log.w("LiveWall", "MediaPlayer error what=$what extra=$extra (retry $retries)")
                if (player === p) {
                    handler.removeCallbacks(reload)
                    if (retries++ < 3) handler.postDelayed(reload, 500) else stopPlayer()
                }
                true
            }
            mp.prepareAsync()
        }

        private fun applyVolume(mp: MediaPlayer) {
            val v = if (sound) 1f else 0f
            runCatching { mp.setVolume(v, v) }
        }

        // ---------------------------------------------------------------- image / GIF / WebP

        private fun loadImage(f: File, g: GlWall) {
            var sample = 1
            // Software bitmaps only: they are uploaded to a GL texture.
            fun decode(limit: Int): Drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val big = max(info.size.width, info.size.height)
                sample = if (big > limit) (big + limit - 1) / limit else 1
                if (sample > 1) dec.setTargetSampleSize(sample)
            }
            var d = decode(4096)
            // Animated images keep one bitmap per frame, so cap them lower (rare: re-decode once).
            if (d is AnimatedImageDrawable && max(d.intrinsicWidth, d.intrinsicHeight) > 2048) d = decode(2048)
            origScale = sample.toFloat()
            when (d) {
                is AnimatedImageDrawable -> {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.callback = object : Drawable.Callback {
                        override fun invalidateDrawable(who: Drawable) {}
                        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
                        override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
                    }
                    val bw = max(1, d.intrinsicWidth); val bh = max(1, d.intrinsicHeight)
                    animBmps = arrayOf(
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888),
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    )
                    img = d
                }
                is BitmapDrawable -> {
                    var b = d.bitmap
                    if (b.config != Bitmap.Config.ARGB_8888) b = b.copy(Bitmap.Config.ARGB_8888, false)
                    g.setImage(b, origScale, recycle = true)   // we own it; the texture keeps the pixels
                }
                else -> error("unsupported image")
            }
        }

        /** Renders the current animation frame into a spare bitmap and hands it to GL. */
        private fun pushAnimatedFrame(d: AnimatedImageDrawable) {
            val b = animBmps[animIdx] ?: return
            animIdx = animIdx xor 1
            b.eraseColor(0)
            val c = Canvas(b)
            d.setBounds(0, 0, b.width, b.height)
            d.draw(c)
            gl?.setImage(b, origScale, recycle = false)
        }

        /** Smooths the parallax offset; returns true while it is still moving. */
        private fun stepParallax(): Boolean {
            if (!parallax) return false
            tx += (targetX - tx) * 0.12f; ty += (targetY - ty) * 0.12f
            return abs(targetX - tx) > 0.3f || abs(targetY - ty) > 0.3f
        }

        private fun draw() {
            val g = gl ?: return
            img?.let { pushAnimatedFrame(it) }
            g.render(mode, parallax, crisp, tx, ty)
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
