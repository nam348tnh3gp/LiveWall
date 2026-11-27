package com.tgfn.livewall

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One OpenGL ES 2 renderer per wallpaper surface. Images, GIF/WebP frames and video all go through it,
 * so the surface is only ever used through EGL. (Mixing EGL and lockCanvas on one surface is refused by
 * the system: "connect: already connected", which killed the wallpaper when the file type changed.)
 * All GL work runs on its own thread; public methods are safe to call from any thread.
 */
class GlWall(private val target: Surface) {
    companion object { const val NONE = 0; const val VIDEO = 1; const val IMAGE = 2 }

    private class Prog(val id: Int, val aPos: Int, val uRect: Int, val uView: Int, val uTex: Int)

    private val thread = HandlerThread("lw-gl").apply { start() }
    private val gh = Handler(thread.looper)

    @Volatile private var released = false

    // ---- GL-thread state ----
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var esurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oes: Prog? = null
    private var flat: Prog? = null
    private var oesTex = 0
    private var imgTex = 0
    private var st: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var ok = false
    private var newFrame = false
    private var hasFrame = false
    private var content = NONE
    private var vw = 0f
    private var vh = 0f
    private var imgW = 0
    private var imgH = 0
    private var imgOrig = 1f
    private var mode = Prefs.AUTO
    private var par = false
    private var crisp = false
    private var tx = 0f
    private var ty = 0f
    private val stMat = FloatArray(16)
    // Bitmaps have row 0 at the top; the quad's v axis points up, so flip v.
    private val flipMat = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    private val rect = FloatArray(4)
    private val dim = IntArray(1)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    init {
        gh.post {
            try { initGl(); ok = true } catch (e: Throwable) { ok = false }
        }
    }

    /** Switch to video; [cb] (on the GL thread) gets the Surface a MediaPlayer should render into. */
    fun useVideoSurface(cb: (Surface) -> Unit) {
        gh.post {
            if (!ok || released) return@post
            // Keep the last frame on screen while a player is re-created (hide/show), but start clean
            // when coming from an image.
            if (content != VIDEO) { hasFrame = false; vw = 0f; vh = 0f }
            content = VIDEO
            videoSurface?.let(cb)
        }
    }

    fun setVideoSize(w: Int, h: Int) {
        gh.post {
            if (released) return@post
            vw = w.toFloat(); vh = h.toFloat()
            drawFrame()
        }
    }

    /** Upload a bitmap as the current picture (does not draw; call [render] after). Must be ARGB_8888. */
    fun setImage(bmp: Bitmap, orig: Float, recycle: Boolean) {
        gh.post {
            try {
                if (ok && !released) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgTex)
                    if (bmp.width == imgW && bmp.height == imgH) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
                    else GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    imgW = bmp.width; imgH = bmp.height; imgOrig = orig
                    content = IMAGE
                }
            } catch (e: Throwable) {
                // bad bitmap: keep what is on screen
            } finally {
                if (recycle) runCatching { bmp.recycle() }
            }
        }
    }

    fun clear() {
        gh.post {
            if (released) return@post
            content = NONE; hasFrame = false
            drawFrame()
        }
    }

    /** Push display options + parallax offset and redraw. */
    fun render(mode: Int, parallax: Boolean, crisp: Boolean, tx: Float, ty: Float) {
        gh.post {
            if (released) return@post
            this.mode = mode; this.par = parallax; this.crisp = crisp; this.tx = tx; this.ty = ty
            drawFrame()
        }
    }

    /** Blocks briefly so the EGL surface is disconnected before the wallpaper surface is destroyed. */
    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        if (gh.post { try { teardown() } finally { latch.countDown() } }) {
            runCatching { latch.await(1, TimeUnit.SECONDS) }
        }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------------------------------

    private fun initGl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(dpy != EGL14.EGL_NO_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(dpy, ver, 0, ver, 1))
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attribs, 0, cfgs, 0, 1, n, 0) && n[0] > 0)
        ctx = EGL14.eglCreateContext(
            dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(ctx != EGL14.EGL_NO_CONTEXT)
        esurf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        check(esurf != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(dpy, esurf, esurf, ctx))

        val vs = """
            attribute vec2 aPos;
            uniform vec4 uRect;
            uniform vec2 uView;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() {
                vec2 p = vec2(uRect.x + aPos.x * uRect.z, uRect.y + (1.0 - aPos.y) * uRect.w);
                gl_Position = vec4(p.x / uView.x * 2.0 - 1.0, 1.0 - p.y / uView.y * 2.0, 0.0, 1.0);
                vTex = (uTex * vec4(aPos, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fsOes = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        val fsFlat = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        oes = prog(v, compile(GLES20.GL_FRAGMENT_SHADER, fsOes))
        flat = prog(v, compile(GLES20.GL_FRAGMENT_SHADER, fsFlat))

        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        oesTex = t[0]; imgTex = t[1]
        for ((tgt, id) in listOf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES to oesTex, GLES20.GL_TEXTURE_2D to imgTex)) {
            GLES20.glBindTexture(tgt, id)
            GLES20.glTexParameteri(tgt, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(tgt, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        val s = SurfaceTexture(oesTex)
        s.setOnFrameAvailableListener({
            if (!released) { newFrame = true; drawFrame() }
        }, gh)
        st = s
        videoSurface = Surface(s)
        android.opengl.Matrix.setIdentityM(stMat, 0)
    }

    private fun drawFrame() {
        if (!ok || released) return
        val s = st ?: return
        if (newFrame) {
            newFrame = false
            try { s.updateTexImage(); s.getTransformMatrix(stMat); hasFrame = true } catch (e: Throwable) { return }
        }
        EGL14.eglQuerySurface(dpy, esurf, EGL14.EGL_WIDTH, dim, 0); val w = dim[0]
        EGL14.eglQuerySurface(dpy, esurf, EGL14.EGL_HEIGHT, dim, 0); val h = dim[0]
        if (w <= 0 || h <= 0) return
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        when {
            content == VIDEO && hasFrame && vw > 0 && vh > 0 ->
                quad(oes!!, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, vw, vh, 1f, stMat, w, h)
            content == IMAGE && imgW > 0 && imgH > 0 ->
                quad(flat!!, GLES20.GL_TEXTURE_2D, imgTex, imgW.toFloat(), imgH.toFloat(), imgOrig, flipMat, w, h)
        }
        EGL14.eglSwapBuffers(dpy, esurf)
    }

    private fun quad(p: Prog, texTarget: Int, texId: Int, iw: Float, ih: Float, orig: Float, m: FloatArray, w: Int, h: Int) {
        Layout.rect(mode, par, w, h, iw, ih, orig, tx, ty, rect)
        val filter = if (crisp) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(texTarget, texId)
        GLES20.glTexParameteri(texTarget, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(texTarget, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glUseProgram(p.id)
        GLES20.glUniform4f(p.uRect, rect[0], rect[1], rect[2], rect[3])
        GLES20.glUniform2f(p.uView, w.toFloat(), h.toFloat())
        GLES20.glUniformMatrix4fv(p.uTex, 1, false, m, 0)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPos)
    }

    private fun teardown() {
        ok = false
        runCatching { videoSurface?.release() }
        runCatching { st?.setOnFrameAvailableListener(null) }
        runCatching { st?.release() }
        videoSurface = null; st = null
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            runCatching { GLES20.glFinish() }
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (esurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, esurf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        }
        dpy = EGL14.EGL_NO_DISPLAY; ctx = EGL14.EGL_NO_CONTEXT; esurf = EGL14.EGL_NO_SURFACE
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val r = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, r, 0)
        check(r[0] != 0) { GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun prog(v: Int, f: Int): Prog {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val r = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, r, 0)
        check(r[0] != 0) { GLES20.glGetProgramInfoLog(p) }
        return Prog(
            p,
            GLES20.glGetAttribLocation(p, "aPos"),
            GLES20.glGetUniformLocation(p, "uRect"),
            GLES20.glGetUniformLocation(p, "uView"),
            GLES20.glGetUniformLocation(p, "uTex")
        )
    }
}
