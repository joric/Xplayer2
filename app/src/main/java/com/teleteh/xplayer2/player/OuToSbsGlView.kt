package com.teleteh.xplayer2.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class OuToSbsGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: OuToSbsRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = OuToSbsRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setOnSurfaceReadyListener(listener: (Surface) -> Unit) {
        renderer.onSurfaceReady = listener
        renderer.surface?.let { surf ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener(surf)
            } else {
                Handler(Looper.getMainLooper()).post { listener(surf) }
            }
        }
    }

    fun setSbsEnabled(enabled: Boolean) {
        renderer.sbsEnabled.set(enabled)
        requestRender()
    }

    fun setSourceIsSbs(enabled: Boolean) {
        renderer.sourceIsSbs.set(enabled)
        requestRender()
    }

    fun setDuplicateMonoToSbs(enabled: Boolean) {
        renderer.duplicateMonoToSbs.set(enabled)
        requestRender()
    }

    fun setSwapEyes(enabled: Boolean) {
        renderer.swapEyes.set(enabled)
        requestRender()
    }

    fun setEyeVerticalShiftNormalized(left: Float, right: Float) {
        val l = left.coerceIn(-0.25f, 0.25f)
        val r = right.coerceIn(-0.25f, 0.25f)
        queueEvent {
            renderer.setEyeShiftNormalized(l, r)
        }
        requestRender()
    }

    fun setEyeVerticalShiftPx(leftPx: Float, rightPx: Float, referenceHeightPx: Float) {
        if (referenceHeightPx <= 0f) return
        setEyeVerticalShiftNormalized(leftPx / referenceHeightPx, rightPx / referenceHeightPx)
    }

    fun setOppositeVerticalShiftPx(amountPx: Float, referenceHeightPx: Float) {
        if (referenceHeightPx <= 0f) return
        val amt = (amountPx / referenceHeightPx).coerceIn(0f, 0.25f)
        queueEvent {
            val swap = renderer.swapEyes.get()
            val leftFromTop = if (swap) true else false
            val rightFromTop = !leftFromTop
            val leftShift = if (leftFromTop) -amt else amt
            val rightShift = if (rightFromTop) -amt else amt
            renderer.setEyeShiftNormalized(leftShift, rightShift)
        }
        requestRender()
    }

    fun setPerEyeLetterboxPx(amountPx: Float, referenceHeightPx: Float) {
        if (referenceHeightPx <= 0f) return
        val frac = (amountPx / referenceHeightPx).coerceIn(0f, 0.25f)
        queueEvent {
            renderer.perEyePadFrac = frac
        }
        requestRender()
    }

    fun updateResizeMode(mode: Int) {
        renderer.updateResizeMode(mode)
    }

    fun updateVideoAspectRatio(width: Int, height: Int) {
        renderer.updateVideoAspectRatio(width, height)
    }

    private inner class OuToSbsRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var textureId: Int = 0
        private var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
            private set

        var onSurfaceReady: ((Surface) -> Unit)? = null
        val sbsEnabled = AtomicBoolean(false)
        val sourceIsSbs = AtomicBoolean(false)  // NEW: true if source is already SBS
        val swapEyes = AtomicBoolean(false)
        val duplicateMonoToSbs = AtomicBoolean(false)

        private var program = 0
        private var aPosLoc = 0
        private var aTexLoc = 0
        private var uTexLoc = 0
        private var uTexMatrixLoc = 0
        private var uScaleLoc = 0
        private var uOffsetLoc = 0

        @Volatile private var leftEyeShiftNorm: Float = 0f
        @Volatile private var rightEyeShiftNorm: Float = 0f
        @Volatile var perEyePadFrac: Float = 0f
        private val texMatrix = FloatArray(16)

        @Volatile private var resizeMode: Int = 0
        @Volatile private var videoAspectRatio: Float = 16f / 9f

        private val fullVertexData: FloatBuffer = floatBufferOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = buildProgram(VERT, FRAG)
            aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uScaleLoc = GLES20.glGetUniformLocation(program, "uScale")
            uOffsetLoc = GLES20.glGetUniformLocation(program, "uOffset")

            textureId = createOesTexture()
            surfaceTexture = SurfaceTexture(textureId).also {
                it.setOnFrameAvailableListener(this)
            }
            surface = Surface(surfaceTexture)
            val surf = surface!!
            Handler(Looper.getMainLooper()).post {
                onSurfaceReady?.invoke(surf)
            }

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            surfaceTexture?.let {
                it.updateTexImage()
                it.getTransformMatrix(texMatrix)
            }

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexLoc, 0)

            if (sbsEnabled.get()) {
                if (sourceIsSbs.get()) {
                    // Source is already SBS - just display left/right halves
                    drawSbsSource()
                } else {
                    // Source is OU - convert to SBS
                    drawOuToSbs()
                }
            } else {
                if (duplicateMonoToSbs.get()) {
                    drawMonoToSbs()
                } else {
                    drawFullScreen()
                }
            }

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        private fun drawOuToSbs() {
            val swap = swapEyes.get()
            val leftFromTop = if (swap) true else false
            val rightFromTop = if (swap) false else true
            drawEyeFromOu(left = true, fromTopHalf = leftFromTop)
            drawEyeFromOu(left = false, fromTopHalf = rightFromTop)
        }

        private fun drawSbsSource() {
            val swap = swapEyes.get()
            // For SBS source, each eye gets half the texture horizontally
            drawEyeFromSbs(left = true, useRightHalf = swap)
            drawEyeFromSbs(left = false, useRightHalf = !swap)
        }

        private fun drawFullScreen() {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val targetAspect = getTargetAspectRatio(resizeMode, videoAspectRatio)
            val (x, y, w, h) = calculateFitRect(viewport[0], viewport[1], viewport[2], viewport[3], targetAspect)

            GLES20.glViewport(x, y, w, h)
            drawTexture(1f, 1f, 0f, 0f)
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawMonoToSbs() {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val eyeWidth = viewport[2] / 2
            val targetAspect = getTargetAspectRatio(resizeMode, videoAspectRatio)

            // Left eye
            val (lx, ly, lw, lh) = calculateFitRect(viewport[0], viewport[1], eyeWidth, viewport[3], targetAspect)
            GLES20.glViewport(lx, ly, lw, lh)
            drawTexture(1f, 1f, 0f, 0f)

            // Right eye
            val (rx, ry, rw, rh) = calculateFitRect(viewport[0] + eyeWidth, viewport[1], eyeWidth, viewport[3], targetAspect)
            GLES20.glViewport(rx, ry, rw, rh)
            drawTexture(1f, 1f, 0f, 0f)

            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawEyeFromOu(left: Boolean, fromTopHalf: Boolean) {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)

            val eyeWidth = viewport[2] / 2
            val eyeX = if (left) viewport[0] else viewport[0] + eyeWidth
            val targetAspect = getTargetAspectRatio(resizeMode, videoAspectRatio)

            val (x, y, w, h) = calculateFitRect(eyeX, viewport[1], eyeWidth, viewport[3], targetAspect)

            val pad = (perEyePadFrac * h).toInt().coerceAtMost(h - 1)
            val yAdj = if (fromTopHalf) y + pad else y
            val hAdj = h - pad

            GLES20.glViewport(x, yAdj, w, hAdj)

            // For OU source, sample from top or bottom half of texture
            val shift = if (left) leftEyeShiftNorm else rightEyeShiftNorm
            val base = if (fromTopHalf) 0.5f else 0f
            val offsetY = if (fromTopHalf) base - shift else base + shift

            drawTexture(1f, 0.5f, 0f, offsetY)

            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawEyeFromSbs(left: Boolean, useRightHalf: Boolean) {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)

            val eyeWidth = viewport[2] / 2
            val eyeX = if (left) viewport[0] else viewport[0] + eyeWidth
            val targetAspect = getTargetAspectRatio(resizeMode, videoAspectRatio)

            val (x, y, w, h) = calculateFitRect(eyeX, viewport[1], eyeWidth, viewport[3], targetAspect)

            GLES20.glViewport(x, y, w, h)

            // For SBS source, sample from left or right half of texture
            val offsetX = if (useRightHalf) 0.5f else 0f
            drawTexture(0.5f, 1f, offsetX, 0f)

            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawTexture(scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uScaleLoc, scaleX, scaleY)
            GLES20.glUniform2f(uOffsetLoc, offsetX, offsetY)
            fullVertexData.position(0)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, fullVertexData)
            fullVertexData.position(2)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, fullVertexData)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun calculateFitRect(x: Int, y: Int, width: Int, height: Int, targetAspect: Float): Rect {
            val viewAspect = width.toFloat() / height.toFloat()

            return if (viewAspect > targetAspect) {
                val newWidth = (height * targetAspect).toInt()
                val offsetX = (width - newWidth) / 2
                Rect(x + offsetX, y, newWidth, height)
            } else {
                val newHeight = (width / targetAspect).toInt()
                val offsetY = (height - newHeight) / 2
                Rect(x, y + offsetY, width, newHeight)
            }
        }

        private fun getTargetAspectRatio(mode: Int, videoAspect: Float): Float {
            return when (mode) {
                0 -> videoAspect
                1 -> 16f / 9f
                2 -> 4f / 3f
                3 -> 21f / 9f
                4 -> 32f / 9f
                5 -> 1f / 1f
                6 -> 2.39f / 1f
                else -> videoAspect
            }
        }

        fun updateResizeMode(mode: Int) {
            resizeMode = mode
            requestRender()
        }

        fun updateVideoAspectRatio(width: Int, height: Int) {
            if (height > 0) {
                videoAspectRatio = width.toFloat() / height.toFloat()
                requestRender()
            }
        }

        fun setEyeShiftNormalized(left: Float, right: Float) {
            leftEyeShiftNorm = left
            rightEyeShiftNorm = right
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            this@OuToSbsGlView.requestRender()
        }

        private fun createOesTexture(): Int {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            return tex[0]
        }

        private fun buildProgram(vertSrc: String, fragSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val status = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("GL link error: $log")
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("GL compile error: $log")
            }
            return shader
        }
    }
}

private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

private fun floatBufferOf(vararg floats: Float): FloatBuffer =
    ByteBuffer.allocateDirect(floats.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floats)
            position(0)
        }

private const val VERT = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
  gl_Position = aPosition;
  vTexCoord = aTexCoord;
}
"""

private const val FRAG = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform mat4 uTexMatrix;
uniform vec2 uScale;
uniform vec2 uOffset;
void main() {
  vec2 tc = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
  tc = tc * uScale + uOffset;
  gl_FragColor = texture2D(uTexture, tc);
}
"""
