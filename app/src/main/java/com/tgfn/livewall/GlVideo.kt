package com.tgfn.livewall

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders a video (MediaPlayer -> SurfaceTexture) onto the wallpaper surface with OpenGL ES 2,
 * so fit / fill / stretch / original / parallax / crisp all work for video like they do for images.
 * All GL work happens on its own thread; public methods are safe to call from the main thread.
 */
class GlVideo(private val target: Surface, onReady: (Surface) -> Unit) {
    private val thread = HandlerThread("lw-gl").apply { start() }
    private val gh = Handler(thread.looper)

    @Volatile private var released = false

    // ---- GL-thread state ----
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var esurf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tex = 0
    private var st: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var prog = 0
    private var aPos = 0
    private var uRect = 0
    private var uView = 0
    private var uTex = 0
    private var ok = false
    private var newFrame = false
    private var hasFrame = false
    private var vw = 0f
    private var vh = 0f
    private var mode = Prefs.AUTO
    private var par = false
    private var crisp = false
    private var tx = 0f
    private var ty = 0f
    private val stMat = FloatArray(16)
    private val rect = FloatArray(4)
    private val dim = IntArray(1)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    init {
        gh.post {
            try {
                initGl()
                val s = Surface(st!!)
                videoSurface = s
                ok = true
                if (!released) onReady(s)
            } catch (e: Throwable) {
                ok = false
            }
        }
    }

    fun setVideoSize(w: Int, h: Int) {
        gh.post {
            if (released) return@post
            vw = w.toFloat(); vh = h.toFloat()
            drawFrame()
        }
    }

    /** Push display options + parallax offset and redraw the current frame. */
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
            varying vec2 vTex;
            uniform mat4 uTex;
            void main() {
                vec2 p = vec2(uRect.x + aPos.x * uRect.z, uRect.y + (1.0 - aPos.y) * uRect.w);
                gl_Position = vec4(p.x / uView.x * 2.0 - 1.0, 1.0 - p.y / uView.y * 2.0, 0.0, 1.0);
                vTex = (uTex * vec4(aPos, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        prog = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs))
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uRect = GLES20.glGetUniformLocation(prog, "uRect")
        uView = GLES20.glGetUniformLocation(prog, "uView")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val s = SurfaceTexture(tex)
        s.setOnFrameAvailableListener({
            if (!released) { newFrame = true; drawFrame() }
        }, gh)
        st = s
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
        if (hasFrame && vw > 0 && vh > 0) {
            Layout.rect(mode, par, w, h, vw, vh, 1f, tx, ty, rect)
            val filter = if (crisp) GLES20.GL_NEAREST else GLES20.GL_LINEAR
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glUseProgram(prog)
            GLES20.glUniform4f(uRect, rect[0], rect[1], rect[2], rect[3])
            GLES20.glUniform2f(uView, w.toFloat(), h.toFloat())
            GLES20.glUniformMatrix4fv(uTex, 1, false, stMat, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
        }
        EGL14.eglSwapBuffers(dpy, esurf)
    }

    private fun teardown() {
        ok = false
        runCatching { videoSurface?.release() }
        runCatching { st?.setOnFrameAvailableListener(null) }
        runCatching { st?.release() }
        videoSurface = null; st = null
        if (dpy != EGL14.EGL_NO_DISPLAY) {
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

    private fun link(v: Int, f: Int): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val r = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, r, 0)
        check(r[0] != 0) { GLES20.glGetProgramInfoLog(p) }
        return p
    }
}
