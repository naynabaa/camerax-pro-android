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
            uniform vec3 uAdjustments;
            uniform float uNightBoost;
            uniform float uTime;
            uniform vec2 uTexelSize;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            vec3 biTap(vec2 uv, vec2 off, vec3 center, inout float wSum) {
                vec3 s = texture2D(sTexture, clamp(uv + off, 0.0, 1.0)).rgb;
                float dc = length(s - center);
                float w = exp(-dc * dc * 28.0);
                wSum += w;
                return s * w;
            }

            vec3 smoothSample(vec2 uv, float spread) {
                vec2 o = uTexelSize * spread;
                vec3 center = texture2D(sTexture, uv).rgb;
                vec3 acc = center * 4.0;
                float wSum = 4.0;
                acc += biTap(uv, vec2(-o.x, -o.y), center, wSum);
                acc += biTap(uv, vec2( 0.0, -o.y), center, wSum);
                acc += biTap(uv, vec2( o.x, -o.y), center, wSum);
                acc += biTap(uv, vec2(-o.x,  0.0), center, wSum);
                acc += biTap(uv, vec2( o.x,  0.0), center, wSum);
                acc += biTap(uv, vec2(-o.x,  o.y), center, wSum);
                acc += biTap(uv, vec2( 0.0,  o.y), center, wSum);
                acc += biTap(uv, vec2( o.x,  o.y), center, wSum);
                return acc / max(wSum, 0.001);
            }

            float sobelEdge(vec2 uv, float spread) {
                vec2 o = uTexelSize * spread;
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
                float vecEdge = sqrt(dot(gx, gx) * 0.45 + dot(gy, gy) * 0.45);
                float crease = max(0.0, luma((tc + bc + ml + mr) * 0.25) - luma(mc)) * 1.4;
                return vecEdge + crease;
            }

            float softCelBand(float lum, float bands) {
                float scaled = clamp(lum, 0.0, 1.0) * bands;
                float b = floor(scaled);
                float f = scaled - b;
                return clamp((b + smoothstep(0.36, 0.64, f)) / bands * 0.94 + 0.04, 0.05, 0.99);
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

                float edge = sobelEdge(uv, 1.6);
                vec3 smoothed = smoothSample(uv, 2.2);
                float lum = luma(smoothed);

                if (uEffectMode == 1) {
                    float bandL = softCelBand(lum, 5.0);
                    vec3 vib = mix(vec3(lum), smoothed, 1.34) * (bandL / max(lum, 0.01));
                    float shadowT = smoothstep(0.42, 0.16, bandL);
                    vib += vec3(-0.03 * shadowT, -0.01 * shadowT, 0.04 * shadowT);
                    float ink = smoothstep(0.18, 0.36, edge);
                    color = mix(clamp(vib, 0.0, 1.0), vec3(0.04, 0.05, 0.09), ink);
                } else if (uEffectMode == 2) {
                    float ink = smoothstep(0.18, 0.34, edge);
                    float shadow = smoothstep(0.34, 0.24, lum);
                    float mid = smoothstep(0.60, 0.48, lum);
                    float baseG = mix(0.97, 0.48, mid);
                    baseG = mix(baseG, 0.04, shadow);
                    color = vec3(mix(baseG, 0.03, ink));
                } else if (uEffectMode == 3) {
                    float gx = floor(uv.x * 260.0);
                    float gy = floor(uv.y * 260.0);
                    float dotMask = step(0.5, mod(gx + mod(gy, 2.0) * 2.0, 3.0)) * step(0.5, mod(gy, 2.0));
                    float ink = smoothstep(0.17, 0.33, edge);
                    float shade = max(ink, (lum < 0.17 || (lum < 0.52 && dotMask < 0.5)) ? 0.85 : 0.0);
                    color = mix(vec3(0.99, 0.98, 0.96), vec3(0.05, 0.06, 0.08), shade);
                } else if (uEffectMode == 4) {
                    vec3 pop = smoothed.r > smoothed.g && smoothed.r > smoothed.b ? vec3(0.95, 0.15, 0.32)
                             : (smoothed.b > smoothed.r ? vec3(0.14, 0.44, 0.95) : vec3(0.98, 0.84, 0.14));
                    float ink = smoothstep(0.18, 0.34, edge);
                    color = mix(pop, vec3(0.04, 0.04, 0.06), ink);
                } else if (uEffectMode == 5) {
                    float ink = smoothstep(0.15, 0.31, edge);
                    color = mix(vec3(0.99, 0.98, 0.96), vec3(0.05, 0.06, 0.08), ink);
                } else if (uEffectMode == 6) {
                    float bandL = softCelBand(lum, 5.0) * 0.78 + 0.20;
                    vec3 clean = mix(vec3(lum), smoothed, 0.92) * (bandL / max(lum, 0.01));
                    float ink = smoothstep(0.18, 0.35, edge);
                    color = mix(clamp(clean, 0.0, 1.0), vec3(0.09, 0.09, 0.08), ink);
                } else if (uEffectMode == 7) {
                    float rim = pow(clamp(1.0 - lum, 0.0, 1.0), 2.0) * 0.18;
                    float edgeRim = smoothstep(0.22, 0.50, edge) * 0.24;
                    color = clamp(smoothSample(uv, 2.8) * 1.06 + vec3(rim), 0.0, 1.0) * (1.0 - edgeRim);
                } else if (uEffectMode == 8) {
                    color = floor(smoothSample(uv, 2.6) * 5.5 + 0.3) / 5.5;
                } else if (uEffectMode == 9) {
                    float hatch = mod(floor((uv.x + uv.y) * 220.0), 3.0) < 1.0 ? 0.36 : 0.90;
                    color = vec3(clamp(mix(lum * 0.4 + 0.58, hatch, step(lum, 0.35)) - edge * 0.9, 0.08, 0.96));
                } else if (uEffectMode == 10) {
                    color = mix(smoothSample(uv, 3.0), vec3(0.96, 0.94, 0.90), 0.18);
                } else if (uEffectMode == 11) {
                    color = (edge > 0.20 || lum < 0.16) ? vec3(0.07) : vec3(0.98, 0.97, 0.93);
                } else if (uEffectMode == 12) {
                    float r = step(0.35, sin(uv.x * 380.0) * sin(uv.y * 380.0) + lum * 1.4 - 0.5);
                    color = mix(vec3(0.08), vec3(0.96, 0.94, 0.88), r);
                } else if (uEffectMode == 13) {
                    color = mix(vec3(0.03, 0.13, 0.31), vec3(0.88, 0.95, 1.0), luma(color));
                } else if (uEffectMode == 14) {
                    float groove = sin(uv.y * 520.0) * 0.08;
                    color = (luma(color) + groove) > 0.46 ? vec3(0.95, 0.93, 0.88) : vec3(0.05, 0.04, 0.03);
                } else if (uEffectMode == 15) {
                    color = mix(vec3(lum * 0.96, lum * 0.93, lum * 0.86), smoothed * 0.9, 0.26);
                } else if (uEffectMode == 16) {
                    float rC = texture2D(sTexture, clamp(uv + vec2(-0.012, -0.004), 0.0, 1.0)).r;
                    float bC = texture2D(sTexture, clamp(uv + vec2( 0.012,  0.004), 0.0, 1.0)).b;
                    color = vec3(rC, color.g, bC);
                } else if (uEffectMode == 18) {
                    color = vec3(
                        color.r > 0.5 ? (1.0 - (color.r - 0.5) * 1.9) : color.r,
                        color.g > 0.5 ? (1.0 - (color.g - 0.5) * 1.85) : color.g,
                        color.b > 0.5 ? (1.0 - (color.b - 0.5) * 1.75) : color.b
                    );
                } else if (uEffectMode == 19) {
                    float eGlow = clamp((edge - 0.08) * 3.4, 0.0, 1.0);
                    vec3 neon = vec3(0.1 + uv.x * 0.9, 0.95 - uv.y * 0.55, 0.98);
                    color = vec3(0.02, 0.03, 0.07) + neon * eGlow;
                } else if (uEffectMode == 20) {
                    float t = luma(color);
                    color = t < 0.35 ? mix(vec3(0.06, 0.04, 0.34), vec3(0.86, 0.14, 0.42), t / 0.35)
                          : (t < 0.72 ? mix(vec3(0.86, 0.14, 0.42), vec3(0.99, 0.74, 0.08), (t - 0.35) / 0.37)
                          : mix(vec3(0.99, 0.74, 0.08), vec3(1.0, 1.0, 0.96), (t - 0.72) / 0.28));
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
