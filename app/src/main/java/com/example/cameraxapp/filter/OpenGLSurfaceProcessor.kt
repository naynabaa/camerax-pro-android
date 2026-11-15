package com.example.cameraxapp.filter

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * OpenGLSurfaceProcessor: Full EGL14 + OpenGL ES 2.0 CameraX SurfaceProcessor
 *
 * Renders camera OES textures onto both Preview and VideoCapture SurfaceOutputs at 60fps
 * with GPU shaders for Filters, Brightness/Contrast/Saturation, Optical Effects, 20 Art Shaders,
 * and all 7 Toon Styles so recorded MP4 videos match the live viewfinder.
 */
class OpenGLSurfaceProcessor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val glThread = HandlerThread("GLSurfaceProcessorThread").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { command -> glHandler.post(command) }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var glProgram = 0
    private var oesTextureId = 0
    private var activeSurfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private var inputWidth = 1280
    private var inputHeight = 720
    private var startTimeMs = System.currentTimeMillis()
    private var released = false

    private data class OutputHolder(
        val surfaceOutput: SurfaceOutput,
        val eglSurface: EGLSurface
    )
    private val outputSurfaces = mutableListOf<OutputHolder>()

    private val quadCoords = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )
    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(quadCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(quadCoords); position(0) }

    @Volatile
    private var activePreset: FilterManager.FilterPreset = FilterManager.FilterPreset.NORMAL

    @Volatile
    private var activeEffectId: String = "none"

    @Volatile
    private var activeEffectIntensity: Float = 0.85f

    private val effectIntensities: MutableMap<String, Float> = mutableMapOf(
        "portrait_bokeh" to 75f / 100f,
        "long_exposure" to 70f / 100f,
        "light_painting" to 80f / 100f,
        "star_trails" to 75f / 100f,
        "night_sight" to 65f / 100f,
        "macro_focus" to 70f / 100f,
        "tilt_shift" to 75f / 100f,
        "double_exposure" to 55f / 100f,
        "panorama" to 80f / 100f,
        "film_grain" to 38f / 100f,
        "soft_halation" to 65f / 100f,
        "vignette" to 50f / 100f,
        "video_bokeh" to 75f / 100f,
        "slow_motion_120_240" to 80f / 100f,
        "time_lapse" to 80f / 100f,
        "hyperlapse" to 75f / 100f,
        "anamorphic_flare" to 75f / 100f,
        "dolly_zoom" to 70f / 100f,
        "motion_blur" to 70f / 100f,
        "action_stabilization" to 85f / 100f,
        "vintage_super8" to 80f / 100f,
        "oil_canvas" to 78f / 100f,
        "charcoal_sketch" to 80f / 100f,
        "watercolor_wash" to 75f / 100f,
        "ink_and_line" to 82f / 100f,
        "pastel_chalk" to 74f / 100f,
        "halftone_print" to 80f / 100f,
        "cyanotype" to 85f / 100f,
        "woodcut_engraving" to 84f / 100f,
        "lithograph" to 76f / 100f,
        "prism_refraction" to 78f / 100f,
        "kaleidoscope" to 85f / 100f,
        "solarize" to 82f / 100f,
        "film_16mm" to 75f / 100f,
        "vhs_tape" to 82f / 100f,
        "crt_monitor" to 78f / 100f,
        "stop_motion" to 80f / 100f,
        "neon_edge" to 85f / 100f,
        "noir_graphic_novel" to 86f / 100f,
        "thermal_vision" to 88f / 100f,
        "cel_shaded_studio" to 85f / 100f,
        "graphic_novel_noir" to 88f / 100f,
        "manga_ink" to 86f / 100f,
        "pop_art_print" to 86f / 100f,
        "line_art_minimalist" to 90f / 100f,
        "ligne_claire" to 84f / 100f,
        "clay_vinyl_3d" to 82f / 100f,
        "letterbox" to 100f / 100f,
        "watermark" to 100f / 100f
    )

    @Volatile
    private var brightnessPct: Int = 0

    @Volatile
    private var contrastPct: Int = 0

    @Volatile
    private var saturationPct: Int = 0

    @Volatile
    private var nightBoost: Boolean = false

    fun getExecutor(): Executor = glExecutor

    fun setFilterPreset(preset: FilterManager.FilterPreset) {
        activePreset = preset
    }

    fun setShootingEffect(effectId: String) {
        activeEffectId = effectId
        activeEffectIntensity = effectIntensities[effectId] ?: 0.85f
    }

    fun setEffectIntensity(effectId: String, intensityPercent: Int) {
        val norm = (intensityPercent.coerceIn(0, 100)) / 100f
        effectIntensities[effectId] = norm
        if (activeEffectId == effectId) {
            activeEffectIntensity = norm
        }
    }

    fun setAdjustments(brightness: Int, contrast: Int, saturation: Int) {
        brightnessPct = brightness
        contrastPct = contrast
        saturationPct = saturation
    }

    fun setNightBoost(enabled: Boolean) {
        nightBoost = enabled
    }

    private fun ensureGlInitialized() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0] ?: throw RuntimeException("Unable to choose EGLConfig")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        val pbufAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)

        glProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onInputSurface(surfaceRequest: SurfaceRequest) {
        glExecutor.execute {
            if (released) {
                surfaceRequest.willNotProvideSurface()
                return@execute
            }
            try {
                ensureGlInitialized()
                inputWidth = surfaceRequest.resolution.width.coerceAtLeast(1)
                inputHeight = surfaceRequest.resolution.height.coerceAtLeast(1)

                val st = SurfaceTexture(oesTextureId)
                st.setDefaultBufferSize(inputWidth, inputHeight)
                st.setOnFrameAvailableListener(this, glHandler)
                activeSurfaceTexture = st

                val inputSurface = Surface(st)
                surfaceRequest.provideSurface(inputSurface, glExecutor) {
                    st.setOnFrameAvailableListener(null)
                    if (activeSurfaceTexture == st) activeSurfaceTexture = null
                    inputSurface.release()
                    st.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring input surface: ${e.message}", e)
                surfaceRequest.willNotProvideSurface()
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glExecutor.execute {
            if (released) {
                surfaceOutput.close()
                return@execute
            }
            try {
                ensureGlInitialized()
                val outSurface = surfaceOutput.getSurface(glExecutor) {
                    removeOutputSurface(surfaceOutput)
                }
                val eglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    eglConfig,
                    outSurface,
                    intArrayOf(EGL14.EGL_NONE),
                    0
                )
                outputSurfaces.add(OutputHolder(surfaceOutput, eglSurface))
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring output surface: ${e.message}", e)
                surfaceOutput.close()
            }
        }
    }

    private fun removeOutputSurface(target: SurfaceOutput) {
        val it = outputSurfaces.iterator()
        while (it.hasNext()) {
            val holder = it.next()
            if (holder.surfaceOutput == target) {
                if (holder.eglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, holder.eglSurface)
                }
                holder.surfaceOutput.close()
                it.remove()
            }
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        if (released || surfaceTexture == null) return
        try {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            val timestampNs = surfaceTexture.timestamp

            val uTimeSec = (System.currentTimeMillis() - startTimeMs) / 1000f
            val filterCode = mapFilterCode(activePreset)
            val effectCode = mapEffectCode(activeEffectId)
            val briVal = brightnessPct / 100f * 0.45f
            val conVal = if (contrastPct >= 0) 1f + contrastPct / 100f else 1f + contrastPct / 200f
            val satVal = (1f + saturationPct / 100f).coerceIn(0f, 2.5f)

            for (holder in outputSurfaces) {
                val out = holder.surfaceOutput
                EGL14.eglMakeCurrent(eglDisplay, holder.eglSurface, holder.eglSurface, eglContext)
                GLES20.glViewport(0, 0, out.size.width, out.size.height)

                Matrix.setIdentityM(mvpMatrix, 0)
                out.updateTransformMatrix(texMatrix, texMatrix)

                GLES20.glUseProgram(glProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

                val aPos = GLES20.glGetAttribLocation(glProgram, "aPosition")
                val aTex = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
                quadBuffer.position(0)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
                GLES20.glEnableVertexAttribArray(aPos)
                quadBuffer.position(2)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
                GLES20.glEnableVertexAttribArray(aTex)

                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(glProgram, "uMvpMatrix"), 1, false, mvpMatrix, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(glProgram, "uTexMatrix"), 1, false, texMatrix, 0)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(glProgram, "uFilterMode"), filterCode)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(glProgram, "uEffectMode"), effectCode)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "uEffectIntensity"), activeEffectIntensity)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(glProgram, "uAdjustments"), briVal, conVal, satVal)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "uNightBoost"), if (nightBoost) 1f else 0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "uTime"), uTimeSec)
                GLES20.glUniform2f(
                    GLES20.glGetUniformLocation(glProgram, "uTexelSize"),
                    1f / inputWidth.toFloat(),
                    1f / inputHeight.toFloat()
                )

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, holder.eglSurface, timestampNs)
                EGL14.eglSwapBuffers(eglDisplay, holder.eglSurface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame render error: ${e.message}", e)
        }
    }

    fun release() {
        glExecutor.execute {
            released = true
            for (holder in outputSurfaces.toList()) {
                if (holder.eglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglDestroySurface(eglDisplay, holder.eglSurface)
                }
                holder.surfaceOutput.close()
            }
            outputSurfaces.clear()
            activeSurfaceTexture?.release()
            activeSurfaceTexture = null
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
            if (glProgram != 0) {
                GLES20.glDeleteProgram(glProgram)
                glProgram = 0
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                pbufferSurface = EGL14.EGL_NO_SURFACE
            }
            glThread.quitSafely()
        }
    }

    private fun mapFilterCode(preset: FilterManager.FilterPreset): Int = when (preset) {
        FilterManager.FilterPreset.NORMAL -> 0
        FilterManager.FilterPreset.BLACK_AND_WHITE,
        FilterManager.FilterPreset.CLASSIC_MONO,
        FilterManager.FilterPreset.HIGH_CONTRAST_NOIR,
        FilterManager.FilterPreset.ILFORD_GRAIN,
        FilterManager.FilterPreset.LUT_NOIR_CINEMA -> 1
        FilterManager.FilterPreset.VINTAGE,
        FilterManager.FilterPreset.ANTIQUE_SEPIA,
        FilterManager.FilterPreset.SILVER_GELATIN,
        FilterManager.FilterPreset.GOLDEN_NOSTALGIA -> 2
        FilterManager.FilterPreset.CINEMATIC,
        FilterManager.FilterPreset.LUT_TEAL_ORANGE -> 3
        FilterManager.FilterPreset.CYBERPUNK,
        FilterManager.FilterPreset.LUT_MIDNIGHT_NEON -> 4
        FilterManager.FilterPreset.HDR,
        FilterManager.FilterPreset.VELVIA_LANDSCAPE,
        FilterManager.FilterPreset.LUT_REC709 -> 5
        FilterManager.FilterPreset.LUT_FLAT_LOG,
        FilterManager.FilterPreset.LUT_MATTE_CINEMA -> 6
        else -> 0
    }

    private fun mapEffectCode(effectId: String): Int = when (effectId) {
        "cel_shaded_studio" -> 1
        "graphic_novel_noir", "noir_graphic_novel" -> 2
        "manga_ink" -> 3
        "pop_art_print" -> 4
        "line_art_minimalist" -> 5
        "ligne_claire" -> 6
        "clay_vinyl_3d" -> 7
        "oil_canvas" -> 8
        "charcoal_sketch" -> 9
        "watercolor_wash", "pastel_chalk" -> 10
        "ink_and_line" -> 11
        "halftone_print" -> 12
        "cyanotype" -> 13
        "woodcut_engraving" -> 14
        "lithograph" -> 15
        "prism_refraction" -> 16
        "kaleidoscope" -> 17
        "solarize" -> 18
        "neon_edge" -> 19
        "thermal_vision" -> 20
        "vhs_tape" -> 21
        "crt_monitor" -> 22
        "super8_film", "vintage_super8", "film_16mm" -> 23
        "portrait_bokeh", "video_bokeh" -> 24
        "tilt_shift" -> 25
        "vignette" -> 26
        "soft_halation", "anamorphic_flare" -> 27
        else -> 0
    }

    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val TAG = "OpenGLSurfaceProcessor"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform int uFilterMode;
            uniform int uEffectMode;
            uniform float uEffectIntensity;
            uniform vec3 uAdjustments;
            uniform float uNightBoost;
            uniform float uTime;
            uniform vec2 uTexelSize;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            vec3 biTap(vec2 uv, vec2 off, vec3 center, float spatialW, inout float wSum) {
                vec3 s = texture2D(sTexture, clamp(uv + off, 0.0, 1.0)).rgb;
                float dc = length(s - center);
                float w = spatialW * exp(-dc * dc * 24.0);
                wSum += w;
                return s * w;
            }

            vec3 smoothSample(vec2 uv, float spread) {
                vec2 o = uTexelSize * spread;
                vec2 o2 = o * 2.0;
                vec3 center = texture2D(sTexture, uv).rgb;
                vec3 acc = center * 3.0;
                float wSum = 3.0;
                acc += biTap(uv, vec2(-o.x, -o.y), center, 0.75, wSum);
                acc += biTap(uv, vec2( 0.0, -o.y), center, 1.00, wSum);
                acc += biTap(uv, vec2( o.x, -o.y), center, 0.75, wSum);
                acc += biTap(uv, vec2(-o.x,  0.0), center, 1.00, wSum);
                acc += biTap(uv, vec2( o.x,  0.0), center, 1.00, wSum);
                acc += biTap(uv, vec2(-o.x,  o.y), center, 0.75, wSum);
                acc += biTap(uv, vec2( 0.0,  o.y), center, 1.00, wSum);
                acc += biTap(uv, vec2( o.x,  o.y), center, 0.75, wSum);
                acc += biTap(uv, vec2(-o2.x, 0.0), center, 0.45, wSum);
                acc += biTap(uv, vec2( o2.x, 0.0), center, 0.45, wSum);
                acc += biTap(uv, vec2( 0.0, -o2.y), center, 0.45, wSum);
                acc += biTap(uv, vec2( 0.0,  o2.y), center, 0.45, wSum);
                return acc / max(wSum, 0.001);
            }

            float xdogEdge(vec2 uv, float spread) {
                vec2 o = uTexelSize * spread;
                vec2 oWide = uTexelSize * (spread * 2.4);
                vec3 tl = texture2D(sTexture, clamp(uv + vec2(-o.x, -o.y), 0.0, 1.0)).rgb;
                vec3 tc = texture2D(sTexture, clamp(uv + vec2( 0.0, -o.y), 0.0, 1.0)).rgb;
                vec3 tr = texture2D(sTexture, clamp(uv + vec2( o.x, -o.y), 0.0, 1.0)).rgb;
                vec3 ml = texture2D(sTexture, clamp(uv + vec2(-o.x,  0.0), 0.0, 1.0)).rgb;
                vec3 mc = texture2D(sTexture, uv).rgb;
                vec3 mr = texture2D(sTexture, clamp(uv + vec2( o.x,  0.0), 0.0, 1.0)).rgb;
                vec3 bl = texture2D(sTexture, clamp(uv + vec2(-o.x,  o.y), 0.0, 1.0)).rgb;
                vec3 bc = texture2D(sTexture, clamp(uv + vec2( 0.0,  o.y), 0.0, 1.0)).rgb;
                vec3 br = texture2D(sTexture, clamp(uv + vec2( o.x,  o.y), 0.0, 1.0)).rgb;
                vec3 gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
                vec3 gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
                float vecEdge = sqrt(dot(gx, gx) * 0.42 + dot(gy, gy) * 0.42);

                float fineLum = (luma(mc) * 2.0 + luma(tc) + luma(bc) + luma(ml) + luma(mr)) / 6.0;
                float wideLum = (
                    luma(texture2D(sTexture, clamp(uv + vec2(-oWide.x, -oWide.y), 0.0, 1.0)).rgb) +
                    luma(texture2D(sTexture, clamp(uv + vec2( oWide.x, -oWide.y), 0.0, 1.0)).rgb) +
                    luma(texture2D(sTexture, clamp(uv + vec2(-oWide.x,  oWide.y), 0.0, 1.0)).rgb) +
                    luma(texture2D(sTexture, clamp(uv + vec2( oWide.x,  oWide.y), 0.0, 1.0)).rgb)
                ) * 0.25;
                float dog = max(0.0, wideLum - fineLum - 0.015) * 4.2;
                return clamp(max(vecEdge, dog * 0.9), 0.0, 1.5);
            }

            float softCelBand(float lum, float bands) {
                float scaled = clamp(lum, 0.0, 1.0) * bands;
                float b = floor(scaled);
                float f = scaled - b;
                return clamp((b + smoothstep(0.32, 0.68, f)) / bands * 0.94 + 0.04, 0.05, 0.99);
            }

            float halftone45(vec2 uv, float lum, float pitch) {
                vec2 p = uv * pitch;
                vec2 rot = vec2(p.x * 0.7071 - p.y * 0.7071, p.x * 0.7071 + p.y * 0.7071);
                vec2 cell = fract(rot) - 0.5;
                float radius = sqrt(clamp(1.0 - lum, 0.0, 1.0)) * 0.48;
                return smoothstep(radius - 0.06, radius + 0.06, length(cell));
            }

            float paperHash(vec2 uv) {
                return fract(sin(dot(uv, vec2(127.1, 311.7))) * 43758.5453);
            }

            vec3 kuwaharaOil(vec2 uv, float spread) {
                vec2 s = uTexelSize * spread;
                vec3 m0 = vec3(0.0); vec3 m1 = vec3(0.0); vec3 m2 = vec3(0.0); vec3 m3 = vec3(0.0);
                float q0 = 0.0; float q1 = 0.0; float q2 = 0.0; float q3 = 0.0;
                for (int dy = -2; dy <= 0; dy++) {
                    for (int dx = -2; dx <= 0; dx++) {
                        vec3 c0 = texture2D(sTexture, clamp(uv + vec2(float(dx), float(dy)) * s, 0.0, 1.0)).rgb;
                        m0 += c0; float l0 = luma(c0); q0 += l0 * l0;
                        vec3 c1 = texture2D(sTexture, clamp(uv + vec2(float(-dx), float(dy)) * s, 0.0, 1.0)).rgb;
                        m1 += c1; float l1 = luma(c1); q1 += l1 * l1;
                        vec3 c2 = texture2D(sTexture, clamp(uv + vec2(float(dx), float(-dy)) * s, 0.0, 1.0)).rgb;
                        m2 += c2; float l2 = luma(c2); q2 += l2 * l2;
                        vec3 c3 = texture2D(sTexture, clamp(uv + vec2(float(-dx), float(-dy)) * s, 0.0, 1.0)).rgb;
                        m3 += c3; float l3 = luma(c3); q3 += l3 * l3;
                    }
                }
                m0 /= 9.0; m1 /= 9.0; m2 /= 9.0; m3 /= 9.0;
                float v0 = q0 / 9.0 - pow(luma(m0), 2.0);
                float v1 = q1 / 9.0 - pow(luma(m1), 2.0);
                float v2 = q2 / 9.0 - pow(luma(m2), 2.0);
                float v3 = q3 / 9.0 - pow(luma(m3), 2.0);
                vec3 best = m0;
                float bestV = v0;
                if (v1 < bestV) { bestV = v1; best = m1; }
                if (v2 < bestV) { bestV = v2; best = m2; }
                if (v3 < bestV) { best = m3; }
                return best;
            }

            void main() {
                vec2 uv = clamp(vTexCoord, 0.0, 1.0);
                if (uEffectMode == 17) {
                    vec2 d = uv - 0.5;
                    float ang = abs(mod(atan(d.y, d.x) + 3.1415926, 0.785398) - 0.392699);
                    float rad = length(d);
                    uv = clamp(0.5 + vec2(cos(ang), sin(ang)) * rad, 0.0, 1.0);
                }

                vec3 color = texture2D(sTexture, uv).rgb;

                if (uNightBoost > 0.5) {
                    color = pow(max(color, vec3(0.001)), vec3(0.62)) + vec3(0.03);
                }

                if (uFilterMode == 1) {
                    float g = luma(color);
                    color = vec3((g - 0.5) * 1.28 + 0.5);
                } else if (uFilterMode == 2) {
                    float g = luma(color);
                    color = mix(color, vec3(g * 1.08 + 0.05, g * 0.94 + 0.02, g * 0.72), 0.72);
                } else if (uFilterMode == 3) {
                    color = vec3(color.r * 1.16, color.g * 1.02, color.b * 0.90 + 0.05);
                } else if (uFilterMode == 4) {
                    color = vec3(color.r * 1.24, color.g * 0.92, color.b * 1.32);
                } else if (uFilterMode == 5) {
                    color = (color - 0.5) * 1.22 + 0.5;
                } else if (uFilterMode == 6) {
                    color = color * 0.72 + 0.12;
                }

                vec3 baseEffectColor = texture2D(sTexture, clamp(vTexCoord, 0.0, 1.0)).rgb;
                if (uNightBoost > 0.5) {
                    baseEffectColor = pow(max(baseEffectColor, vec3(0.001)), vec3(0.62)) + vec3(0.03);
                }
                if (uFilterMode == 1) {
                    float g0 = luma(baseEffectColor);
                    baseEffectColor = vec3((g0 - 0.5) * 1.28 + 0.5);
                } else if (uFilterMode == 2) {
                    float g0 = luma(baseEffectColor);
                    baseEffectColor = mix(baseEffectColor, vec3(g0 * 1.08 + 0.05, g0 * 0.94 + 0.02, g0 * 0.72), 0.72);
                } else if (uFilterMode == 3) {
                    baseEffectColor = vec3(baseEffectColor.r * 1.16, baseEffectColor.g * 1.02, baseEffectColor.b * 0.90 + 0.05);
                } else if (uFilterMode == 4) {
                    baseEffectColor = vec3(baseEffectColor.r * 1.24, baseEffectColor.g * 0.92, baseEffectColor.b * 1.32);
                } else if (uFilterMode == 5) {
                    baseEffectColor = (baseEffectColor - 0.5) * 1.22 + 0.5;
                } else if (uFilterMode == 6) {
                    baseEffectColor = baseEffectColor * 0.72 + 0.12;
                }

                float edge = xdogEdge(uv, 1.65);
                vec3 smoothed = smoothSample(uv, 2.35);
                float lum = luma(smoothed);

                if (uEffectMode == 1) {
                    float bandL = softCelBand(lum, 5.0);
                    vec3 vib = mix(vec3(lum), smoothed, 1.36) * (bandL / max(lum, 0.02));
                    float shadowT = smoothstep(0.42, 0.14, bandL);
                    float spec = smoothstep(0.84, 0.95, lum) * 0.12;
                    vib += vec3(-0.025 * shadowT, -0.008 * shadowT, 0.045 * shadowT) + vec3(spec);
                    float ink = smoothstep(0.21, 0.40, edge);
                    color = mix(clamp(vib, 0.0, 1.0), vec3(0.035, 0.045, 0.085), ink);
                } else if (uEffectMode == 2) {
                    float ink = smoothstep(0.19, 0.36, edge);
                    float shadow = smoothstep(0.32, 0.22, lum);
                    float mid = smoothstep(0.62, 0.48, lum);
                    float baseG = mix(0.98, 0.50, mid);
                    baseG = mix(baseG, 0.035, shadow);
                    color = vec3(mix(baseG, 0.025, ink));
                } else if (uEffectMode == 3) {
                    float st = halftone45(uv, lum, 180.0);
                    float ink = smoothstep(0.18, 0.34, edge);
                    float paper = lum > 0.62 ? 0.97 : (lum < 0.22 ? 0.05 : st);
                    float finalVal = mix(paper, 0.04, ink);
                    color = vec3(finalVal * 0.99, finalVal * 0.98, finalVal * 0.96);
                } else if (uEffectMode == 4) {
                    vec3 basePop = smoothed.r > smoothed.g && smoothed.r > smoothed.b ? vec3(0.95, 0.16, 0.34)
                                 : (smoothed.b > smoothed.r ? vec3(0.12, 0.46, 0.96) : vec3(0.99, 0.85, 0.12));
                    float dotMask = halftone45(uv, clamp(lum * 1.15, 0.0, 1.0), 125.0);
                    vec3 shadedPop = mix(basePop * 0.72, basePop, dotMask);
                    float ink = smoothstep(0.19, 0.36, edge);
                    color = mix(shadedPop, vec3(0.04, 0.04, 0.06), ink);
                } else if (uEffectMode == 5) {
                    float ink = smoothstep(0.16, 0.33, edge);
                    color = mix(vec3(0.985, 0.975, 0.955), vec3(0.05, 0.06, 0.085), ink);
                } else if (uEffectMode == 6) {
                    float bandL = softCelBand(lum, 5.0) * 0.76 + 0.22;
                    vec3 clean = mix(vec3(lum), smoothed, 0.96) * (bandL / max(lum, 0.02));
                    float ink = smoothstep(0.20, 0.37, edge) * 0.88;
                    color = mix(clamp(clean, 0.0, 1.0), vec3(0.10, 0.09, 0.08), ink);
                } else if (uEffectMode == 7) {
                    vec2 eps = uTexelSize * 2.4;
                    float lL = luma(texture2D(sTexture, clamp(uv - vec2(eps.x, 0.0), 0.0, 1.0)).rgb);
                    float lR = luma(texture2D(sTexture, clamp(uv + vec2(eps.x, 0.0), 0.0, 1.0)).rgb);
                    float lU = luma(texture2D(sTexture, clamp(uv - vec2(0.0, eps.y), 0.0, 1.0)).rgb);
                    float lD = luma(texture2D(sTexture, clamp(uv + vec2(0.0, eps.y), 0.0, 1.0)).rgb);
                    vec3 n = normalize(vec3((lL - lR) * 3.8, (lU - lD) * 3.8, 0.65));
                    vec3 lightDir = normalize(vec3(-0.45, -0.55, 0.70));
                    vec3 halfDir = normalize(lightDir + vec3(0.0, 0.0, 1.0));
                    float diff = max(0.0, (dot(n, lightDir) + 0.35) / 1.35);
                    float spec = pow(max(0.0, dot(n, halfDir)), 24.0) * 0.42;
                    float rim = pow(1.0 - max(0.0, n.z), 2.2) * 0.22;
                    vec3 clay = smoothSample(uv, 3.0) * (0.68 + 0.42 * diff) + vec3(spec + rim);
                    color = clamp(clay, 0.0, 1.0);
                } else if (uEffectMode == 8) {
                    vec3 kuw = kuwaharaOil(uv, 2.2);
                    float sLum = luma(kuw);
                    vec2 gOff = uTexelSize * 2.0;
                    float dxL = luma(texture2D(sTexture, clamp(uv + vec2(gOff.x, 0.0), 0.0, 1.0)).rgb) - luma(texture2D(sTexture, clamp(uv - vec2(gOff.x, 0.0), 0.0, 1.0)).rgb);
                    float dyL = luma(texture2D(sTexture, clamp(uv + vec2(0.0, gOff.y), 0.0, 1.0)).rgb) - luma(texture2D(sTexture, clamp(uv - vec2(0.0, gOff.y), 0.0, 1.0)).rgb);
                    float relief = clamp((-dxL * 0.45 - dyL * 0.55) * 0.28, -0.06, 0.09);
                    vec3 oil = mix(vec3(sLum), kuw, 1.26) + vec3(relief + 0.012, relief + 0.004, relief - 0.010);
                    color = clamp(oil, 0.0, 1.0);
                } else if (uEffectMode == 9) {
                    float h1 = 0.5 + 0.5 * sin((uv.x + uv.y) * 520.0);
                    float h2 = 0.5 + 0.5 * sin((uv.x - uv.y) * 520.0);
                    float midShade = smoothstep(0.62, 0.28, lum) * (0.28 + 0.42 * h1);
                    float deepShade = smoothstep(0.32, 0.09, lum) * (0.42 + 0.46 * h2);
                    float inkEdge = smoothstep(0.16, 0.36, edge) * 0.94;
                    float shade = max(inkEdge, max(midShade, deepShade));
                    float paper = 0.96 - (1.0 - lum) * 0.07;
                    float val = mix(paper, 0.085, shade);
                    color = clamp(vec3(val * 1.01, val * 0.99, val * 0.95), 0.05, 0.98);
                } else if (uEffectMode == 10) {
                    vec3 wash = mix(kuwaharaOil(uv, 2.0), smoothed, 0.35);
                    float wLum = luma(wash);
                    float bleach = smoothstep(0.75, 0.96, wLum) * 0.62;
                    vec3 vibrant = mix(mix(vec3(wLum), wash, 1.38) + vec3(0.05), vec3(0.98, 0.97, 0.94), bleach);
                    float pool = 1.0 - smoothstep(0.18, 0.44, edge) * 0.24;
                    color = clamp(vibrant * pool, 0.0, 1.0);
                } else if (uEffectMode == 11) {
                    float nib = smoothstep(0.16, 0.33, edge);
                    float solid = smoothstep(0.18, 0.08, lum);
                    float w1 = smoothstep(0.62, 0.92, 0.5 + 0.5 * sin((uv.x + uv.y) * 580.0));
                    float h = max(smoothstep(0.42, 0.26, lum) * w1 * 0.78, solid);
                    float ink = max(nib, h);
                    color = mix(vec3(0.985, 0.975, 0.955), vec3(0.055, 0.06, 0.08), ink);
                } else if (uEffectMode == 12) {
                    float cInk = halftone45(uv + vec2(0.001, 0.0), smoothed.r, 150.0);
                    float mInk = halftone45(uv + vec2(0.0, 0.001), smoothed.g, 150.0);
                    float yInk = halftone45(uv - vec2(0.001, 0.0), smoothed.b, 150.0);
                    color = clamp(vec3(cInk, mInk, yInk) * 0.85 + smoothed * 0.18, 0.0, 1.0);
                } else if (uEffectMode == 13) {
                    float s = lum * lum * (3.0 - 2.0 * lum);
                    float cLum = pow(clamp(s * 1.04, 0.0, 1.0), 0.88);
                    color = mix(vec3(0.03, 0.125, 0.30), vec3(0.92, 0.965, 1.0), cLum);
                } else if (uEffectMode == 14) {
                    float wave = 0.5 + 0.5 * sin(uv.y * 560.0 + sin(uv.x * 36.0) * 2.2);
                    float burin = smoothstep(lum - 0.14, lum + 0.14, wave);
                    float borderInk = smoothstep(0.18, 0.36, edge);
                    float ink = lum < 0.14 ? 1.0 : (lum > 0.88 ? borderInk : max(borderInk, burin));
                    color = mix(vec3(0.965, 0.937, 0.878), vec3(0.06, 0.05, 0.045), ink);
                } else if (uEffectMode == 15) {
                    vec3 lithBase = mix(kuwaharaOil(uv, 1.8), smoothed, 0.35);
                    float stL = softCelBand(luma(lithBase), 5.0);
                    vec3 muted = mix(vec3(stL), lithBase * (stL / max(luma(lithBase), 0.02)), 0.42);
                    float line = smoothstep(0.19, 0.40, edge) * 0.44;
                    color = clamp((muted * 0.92 + vec3(0.06, 0.05, 0.04)) * (1.0 - line), 0.06, 0.96);
                } else if (uEffectMode == 16) {
                    float rC = texture2D(sTexture, clamp(uv + vec2(-0.014, -0.005), 0.0, 1.0)).r;
                    float gC = texture2D(sTexture, clamp(uv + vec2( 0.002,  0.002), 0.0, 1.0)).g;
                    float bC = texture2D(sTexture, clamp(uv + vec2( 0.014,  0.005), 0.0, 1.0)).b;
                    color = clamp(vec3(rC, gC, bC) * 1.06, 0.0, 1.0);
                } else if (uEffectMode == 18) {
                    color = vec3(
                        sin(3.1415926 * pow(clamp(smoothed.r, 0.0, 1.0), 0.88)) * 0.96 + 0.02,
                        sin(3.1415926 * pow(clamp(smoothed.g, 0.0, 1.0), 0.90)) * 0.95 + 0.03,
                        sin(3.1415926 * pow(clamp(smoothed.b, 0.0, 1.0), 0.92)) * 0.97 + 0.04
                    );
                } else if (uEffectMode == 19) {
                    float eGlow = clamp((edge - 0.07) * 3.8, 0.0, 1.0);
                    vec3 neon = mix(vec3(0.05, 0.92, 1.0), vec3(1.0, 0.18, 0.82), clamp(uv.x * 0.65 + uv.y * 0.35, 0.0, 1.0));
                    color = smoothed * 0.14 + vec3(0.02, 0.025, 0.06) + neon * eGlow;
                } else if (uEffectMode == 20) {
                    float t = luma(smoothed);
                    color = t < 0.25 ? mix(vec3(0.03, 0.02, 0.22), vec3(0.45, 0.05, 0.58), t / 0.25)
                          : (t < 0.52 ? mix(vec3(0.45, 0.05, 0.58), vec3(0.92, 0.18, 0.24), (t - 0.25) / 0.27)
                          : (t < 0.80 ? mix(vec3(0.92, 0.18, 0.24), vec3(0.99, 0.78, 0.06), (t - 0.52) / 0.28)
                          : mix(vec3(0.99, 0.78, 0.06), vec3(1.0, 1.0, 0.96), (t - 0.80) / 0.20)));
                } else if (uEffectMode == 21) {
                    float rC = texture2D(sTexture, clamp(uv + vec2(-0.008, 0.0), 0.0, 1.0)).r;
                    float bC = texture2D(sTexture, clamp(uv + vec2( 0.008, 0.0), 0.0, 1.0)).b;
                    float band = step(0.985, sin(uv.y * 12.0 + uTime * 4.0)) * 0.16;
                    color = vec3(rC, color.g, bC) + vec3(band);
                } else if (uEffectMode == 22) {
                    float scan = 0.78 + 0.22 * sin(uv.y * 420.0);
                    color *= scan;
                } else if (uEffectMode == 23) {
                    color = vec3(color.r * 1.10 + 0.03, color.g * 1.02 + 0.01, color.b * 0.84 - 0.02);
                } else if (uEffectMode == 24) {
                    float dist = length((uv - vec2(0.5, 0.45)) * vec2(1.0, 1.15));
                    color = mix(color, smoothSample(uv, 4.2), smoothstep(0.22, 0.56, dist));
                } else if (uEffectMode == 25) {
                    float band = abs(uv.y - 0.5);
                    color = mix(color, smoothSample(uv, 3.8), smoothstep(0.14, 0.38, band));
                } else if (uEffectMode == 26) {
                    float dist = length(uv - 0.5);
                    color *= clamp(1.08 - dist * 1.05, 0.25, 1.0);
                } else if (uEffectMode == 27) {
                    color += smoothSample(uv, 3.5) * 0.18;
                }

                if (uEffectMode > 0) {
                    color = mix(baseEffectColor, color, clamp(uEffectIntensity, 0.0, 1.0));
                }

                // Apply Brightness / Contrast / Saturation sliders
                float gFinal = luma(color);
                color = mix(vec3(gFinal), color, uAdjustments.z);
                color = (color - 0.5) * uAdjustments.y + 0.5 + uAdjustments.x;
                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """

        /**
         * Creates a CameraEffect configuring this SurfaceProcessor for Preview and VideoCapture
         */
        fun createCameraEffect(processor: OpenGLSurfaceProcessor, executor: Executor): CameraEffect {
            return object : CameraEffect(
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                executor,
                processor,
                Consumer { error: Throwable ->
                    Log.e(TAG, "CameraEffect processing error: ${error.message}", error)
                }
            ) {}
        }
    }
}
