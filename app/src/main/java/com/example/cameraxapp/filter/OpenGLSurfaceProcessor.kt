package com.example.cameraxapp.filter

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
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
import com.example.cameraxapp.stabilization.GyroStabilizer
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
    private var progOesBilatH = 0
    private var progBilatV = 0
    private var progSobelEdge = 0
    private var progComicComposite = 0
    private var progOverlay = 0

    private val comicFboIds = IntArray(3)
    private val comicFboTexIds = IntArray(3)
    private var comicFboW = 0
    private var comicFboH = 0

    // Cached OpenGL attribute & uniform locations (eliminates JNI lookup overhead per frame)
    private var locMainAPos = -1
    private var locMainATex = -1
    private var locMainSTex = -1
    private var locMainMvp = -1
    private var locMainTexMtx = -1
    private var locMainFilterMode = -1
    private var locMainEffectMode = -1
    private var locMainEffectInt = -1
    private var locMainAdj = -1
    private var locMainNight = -1
    private var locMainTime = -1
    private var locMainTexelSize = -1
    private var locMainMotion = -1
    private var locMainExtraFlags = -1
    private var locMainCustomFilter = -1

    private var locBilatHAPos = -1
    private var locBilatHATex = -1
    private var locBilatHSTex = -1
    private var locBilatHMvp = -1
    private var locBilatHTexMtx = -1
    private var locBilatHFilter = -1
    private var locBilatHNight = -1
    private var locBilatHStep = -1

    private var locBilatVAPos = -1
    private var locBilatVATex = -1
    private var locBilatVTex2D = -1
    private var locBilatVStep = -1

    private var locSobelAPos = -1
    private var locSobelATex = -1
    private var locSobelTex2D = -1
    private var locSobelTexelSize = -1

    private var locCompAPos = -1
    private var locCompATex = -1
    private var locCompSmooth = -1
    private var locCompEdge = -1
    private var locCompOes = -1
    private var locCompMvp = -1
    private var locCompTexMtx = -1
    private var locCompMode = -1
    private var locCompEffInt = -1
    private var locCompHtDen = -1
    private var locCompHtDot = -1
    private var locCompNight = -1
    private var locCompAdj = -1
    private var locCompRes = -1

    private var locOvlAPos = -1
    private var locOvlATex = -1
    private var locOvlTex = -1

    private var oesTextureId = 0
    private var overlayTextureId = 0
    private var hasOverlayTexture = false
    private val overlayLock = Any()
    private var pendingOverlayBitmap: Bitmap? = null
    private var overlayBitmapDirty = false
    private var activeSurfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val holderTexMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
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
        "cel_shaded_studio" to 85f / 100f,
        "graphic_novel_noir" to 88f / 100f,
        "manga_ink" to 86f / 100f,
        "pop_art_print" to 86f / 100f,
        "line_art_minimalist" to 90f / 100f,
        "ligne_claire" to 84f / 100f,
        "clay_vinyl_3d" to 82f / 100f,
        "film_16mm" to 75f / 100f,
        "vhs_tape" to 82f / 100f,
        "crt_monitor" to 78f / 100f,
        "stop_motion" to 80f / 100f,
        "neon_edge" to 85f / 100f,
        "noir_graphic_novel" to 86f / 100f,
        "thermal_vision" to 88f / 100f,
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
        "video_bokeh" to 75f / 100f,
        "slow_motion_120_240" to 80f / 100f,
        "time_lapse" to 80f / 100f,
        "hyperlapse" to 75f / 100f,
        "anamorphic_flare" to 75f / 100f,
        "dolly_zoom" to 70f / 100f,
        "motion_blur" to 70f / 100f,
        "action_stabilization" to 85f / 100f,
        "vintage_super8" to 75f / 100f,
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
        "vignette" to 45f / 100f
    )

    @Volatile
    private var brightnessPct: Int = 0

    @Volatile
    private var contrastPct: Int = 0

    @Volatile
    private var saturationPct: Int = 0

    @Volatile
    private var nightBoost: Boolean = false

    @Volatile
    private var halftoneDensity: Float = 1.0f

    @Volatile
    private var halftoneDotSize: Float = 1.0f

    @Volatile
    private var motionBlurDirX: Float = 1.0f

    @Volatile
    private var motionBlurDirY: Float = 0.0f

    @Volatile
    private var motionBlurMagnitude: Float = 0.18f

    @Volatile
    private var eisEnabled: Boolean = false

    @Volatile
    private var eisOverscanZoom: Float = 1.0f

    @Volatile
    private var eisTransX: Float = 0.0f

    @Volatile
    private var eisTransY: Float = 0.0f

    @Volatile
    private var eisRollDeg: Float = 0.0f

    fun getExecutor(): Executor = glExecutor

    fun setGyroEisTransform(
        enabled: Boolean,
        overscanZoom: Float,
        transXNorm: Float,
        transYNorm: Float,
        rollDeg: Float
    ) {
        eisEnabled = enabled
        eisOverscanZoom = overscanZoom.coerceIn(1.0f, 1.45f)
        eisTransX = transXNorm.coerceIn(-0.2f, 0.2f)
        eisTransY = transYNorm.coerceIn(-0.2f, 0.2f)
        eisRollDeg = rollDeg.coerceIn(-15f, 15f)
    }

    fun setComicHalftoneParams(density: Float, dotSize: Float) {
        halftoneDensity = density.coerceIn(0.4f, 2.2f)
        halftoneDotSize = dotSize.coerceIn(0.3f, 1.8f)
    }

    /**
     * Receives smoothed directional motion vectors computed on the CameraX ImageAnalysis
     * background thread and injects them into the real-time OpenGL ES video recording shader.
     */
    fun setMotionBlurVector(dirX: Float, dirY: Float, magnitude: Float) {
        motionBlurDirX = dirX.coerceIn(-1f, 1f)
        motionBlurDirY = dirY.coerceIn(-1f, 1f)
        motionBlurMagnitude = magnitude.coerceIn(0f, 1f)
    }

    @Volatile
    private var extraFilmGrainFlag: Boolean = false

    @Volatile
    private var extraVignetteFlag: Boolean = false

    @Volatile
    private var extraCineFlareFlag: Boolean = false

    @Volatile
    private var customFilterEnabled: Boolean = false

    @Volatile
    private var customFilterBri: Float = 0f

    @Volatile
    private var customFilterCon: Float = 1f

    @Volatile
    private var customFilterSat: Float = 1f

    @Volatile
    private var customFilterGray: Boolean = false

    private var lastStopMotionFrameMs: Long = 0L

    fun setFilterPreset(preset: FilterManager.FilterPreset) {
        activePreset = preset
    }

    fun setCustomFilterParams(enabled: Boolean, brightnessPct: Int, contrastPct: Int, saturationPct: Int, grayscale: Boolean) {
        customFilterEnabled = enabled
        customFilterBri = (brightnessPct / 100f) * 0.45f
        customFilterCon = (1.0f + contrastPct / 100f).coerceIn(0.4f, 2.2f)
        customFilterSat = (1.0f + saturationPct / 100f).coerceIn(0f, 2.5f)
        customFilterGray = grayscale
    }

    fun setOverlayFlags(filmGrain: Boolean, vignette: Boolean, cineFlare: Boolean) {
        extraFilmGrainFlag = filmGrain
        extraVignetteFlag = vignette
        extraCineFlareFlag = cineFlare
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

    /**
     * Uploads an RGBA overlay bitmap (containing custom dragged AR text & active AR lenses)
     * so both Preview and VideoCapture MP4 frames composite it cleanly in OpenGL ES.
     */
    fun updateOverlayBitmap(bitmap: Bitmap?) {
        synchronized(overlayLock) {
            pendingOverlayBitmap = bitmap
            overlayBitmapDirty = true
        }
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
        locMainAPos = GLES20.glGetAttribLocation(glProgram, "aPosition")
        locMainATex = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        locMainSTex = GLES20.glGetUniformLocation(glProgram, "sTexture")
        locMainMvp = GLES20.glGetUniformLocation(glProgram, "uMvpMatrix")
        locMainTexMtx = GLES20.glGetUniformLocation(glProgram, "uTexMatrix")
        locMainFilterMode = GLES20.glGetUniformLocation(glProgram, "uFilterMode")
        locMainEffectMode = GLES20.glGetUniformLocation(glProgram, "uEffectMode")
        locMainEffectInt = GLES20.glGetUniformLocation(glProgram, "uEffectIntensity")
        locMainAdj = GLES20.glGetUniformLocation(glProgram, "uAdjustments")
        locMainNight = GLES20.glGetUniformLocation(glProgram, "uNightBoost")
        locMainTime = GLES20.glGetUniformLocation(glProgram, "uTime")
        locMainTexelSize = GLES20.glGetUniformLocation(glProgram, "uTexelSize")
        locMainMotion = GLES20.glGetUniformLocation(glProgram, "uMotionVector")
        locMainExtraFlags = GLES20.glGetUniformLocation(glProgram, "uExtraFlags")
        locMainCustomFilter = GLES20.glGetUniformLocation(glProgram, "uCustomFilter")

        progOesBilatH = createProgram(VERTEX_SHADER, OES_BILAT_H_FRAGMENT_SHADER)
        locBilatHAPos = GLES20.glGetAttribLocation(progOesBilatH, "aPosition")
        locBilatHATex = GLES20.glGetAttribLocation(progOesBilatH, "aTexCoord")
        locBilatHSTex = GLES20.glGetUniformLocation(progOesBilatH, "sTexture")
        locBilatHMvp = GLES20.glGetUniformLocation(progOesBilatH, "uMvpMatrix")
        locBilatHTexMtx = GLES20.glGetUniformLocation(progOesBilatH, "uTexMatrix")
        locBilatHFilter = GLES20.glGetUniformLocation(progOesBilatH, "uFilterMode")
        locBilatHNight = GLES20.glGetUniformLocation(progOesBilatH, "uNightBoost")
        locBilatHStep = GLES20.glGetUniformLocation(progOesBilatH, "uTexelStep")

        progBilatV = createProgram(PASS_VERTEX_SHADER, BILAT_V_FRAGMENT_SHADER)
        locBilatVAPos = GLES20.glGetAttribLocation(progBilatV, "aPosition")
        locBilatVATex = GLES20.glGetAttribLocation(progBilatV, "aTexCoord")
        locBilatVTex2D = GLES20.glGetUniformLocation(progBilatV, "uTexture2D")
        locBilatVStep = GLES20.glGetUniformLocation(progBilatV, "uTexelStep")

        progSobelEdge = createProgram(PASS_VERTEX_SHADER, SOBEL_2D_FRAGMENT_SHADER)
        locSobelAPos = GLES20.glGetAttribLocation(progSobelEdge, "aPosition")
        locSobelATex = GLES20.glGetAttribLocation(progSobelEdge, "aTexCoord")
        locSobelTex2D = GLES20.glGetUniformLocation(progSobelEdge, "uTexture2D")
        locSobelTexelSize = GLES20.glGetUniformLocation(progSobelEdge, "uTexelSize")

        progComicComposite = createProgram(VERTEX_SHADER, COMIC_COMPOSITE_FRAGMENT_SHADER)
        locCompAPos = GLES20.glGetAttribLocation(progComicComposite, "aPosition")
        locCompATex = GLES20.glGetAttribLocation(progComicComposite, "aTexCoord")
        locCompSmooth = GLES20.glGetUniformLocation(progComicComposite, "uSmoothedTex")
        locCompEdge = GLES20.glGetUniformLocation(progComicComposite, "uEdgeTex")
        locCompOes = GLES20.glGetUniformLocation(progComicComposite, "sTexture")
        locCompMvp = GLES20.glGetUniformLocation(progComicComposite, "uMvpMatrix")
        locCompTexMtx = GLES20.glGetUniformLocation(progComicComposite, "uTexMatrix")
        locCompMode = GLES20.glGetUniformLocation(progComicComposite, "uComicMode")
        locCompEffInt = GLES20.glGetUniformLocation(progComicComposite, "uEffectIntensity")
        locCompHtDen = GLES20.glGetUniformLocation(progComicComposite, "uHalftoneDensity")
        locCompHtDot = GLES20.glGetUniformLocation(progComicComposite, "uHalftoneDotSize")
        locCompNight = GLES20.glGetUniformLocation(progComicComposite, "uNightBoost")
        locCompAdj = GLES20.glGetUniformLocation(progComicComposite, "uAdjustments")
        locCompRes = GLES20.glGetUniformLocation(progComicComposite, "uResolution")

        progOverlay = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        locOvlAPos = GLES20.glGetAttribLocation(progOverlay, "aPosition")
        locOvlATex = GLES20.glGetAttribLocation(progOverlay, "aTexCoord")
        locOvlTex = GLES20.glGetUniformLocation(progOverlay, "uOverlayTex")

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        oesTextureId = textures[0]
        overlayTextureId = textures[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun ensureComicFbos(targetW: Int, targetH: Int) {
        val maxDim = 960f
        val scale = if (Math.max(targetW, targetH) > maxDim) maxDim / Math.max(targetW, targetH).toFloat() else 1f
        val w = Math.max(128, (targetW * scale).toInt())
        val h = Math.max(128, (targetH * scale).toInt())
        if (comicFboIds[0] != 0 && comicFboW == w && comicFboH == h) return
        destroyComicFbos()
        comicFboW = w
        comicFboH = h
        GLES20.glGenFramebuffers(3, comicFboIds, 0)
        GLES20.glGenTextures(3, comicFboTexIds, 0)
        for (i in 0 until 3) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, comicFboTexIds[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                comicFboW, comicFboH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, comicFboIds[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, comicFboTexIds[i], 0
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun destroyComicFbos() {
        if (comicFboTexIds[0] != 0) {
            GLES20.glDeleteTextures(3, comicFboTexIds, 0)
            comicFboTexIds.fill(0)
        }
        if (comicFboIds[0] != 0) {
            GLES20.glDeleteFramebuffers(3, comicFboIds, 0)
            comicFboIds.fill(0)
        }
    }

    override fun onInputSurface(surfaceRequest: SurfaceRequest) {
        glExecutor.execute {
            if (released) {
                surfaceRequest.willNotProvideSurface()
                return@execute
            }
            try {
                ensureGlInitialized()
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                inputWidth = surfaceRequest.resolution.width.coerceAtLeast(1)
                inputHeight = surfaceRequest.resolution.height.coerceAtLeast(1)

                // Allocate a dedicated OES texture per SurfaceRequest so rebinds never collide with an older SurfaceTexture
                val newTexArr = IntArray(1)
                GLES20.glGenTextures(1, newTexArr, 0)
                val reqOesTexId = newTexArr[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, reqOesTexId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                oesTextureId = reqOesTexId

                val st = SurfaceTexture(reqOesTexId)
                st.setDefaultBufferSize(inputWidth, inputHeight)
                st.setOnFrameAvailableListener(this, glHandler)
                activeSurfaceTexture = st

                val inputSurface = Surface(st)
                surfaceRequest.provideSurface(inputSurface, glExecutor) {
                    st.setOnFrameAvailableListener(null)
                    if (activeSurfaceTexture == st) activeSurfaceTexture = null
                    inputSurface.release()
                    st.release()
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY && reqOesTexId != 0) {
                        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                        GLES20.glDeleteTextures(1, intArrayOf(reqOesTexId), 0)
                        if (oesTextureId == reqOesTexId) oesTextureId = 0
                    }
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
        if (released || surfaceTexture == null || surfaceTexture != activeSurfaceTexture || oesTextureId == 0) return
        try {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            val timestampNs = surfaceTexture.timestamp

            val nowMs = System.currentTimeMillis()
            val uTimeSec = (nowMs - startTimeMs) / 1000f
            val filterCode = mapFilterCode(activePreset)
            val effectCode = mapEffectCode(activeEffectId)
            // Stop-motion cadence: step rendered frames at ~7fps
            if (effectCode == 37) {
                if (nowMs - lastStopMotionFrameMs < 140L) return
                lastStopMotionFrameMs = nowMs
            }
            val briVal = brightnessPct / 100f * 0.45f
            val conVal = if (contrastPct >= 0) 1f + contrastPct / 100f else 1f + contrastPct / 200f
            val satVal = (1f + saturationPct / 100f).coerceIn(0f, 2.5f)
            val effectZoom = when (activeEffectId) {
                "macro_focus" -> 1.26f
                "action_stabilization" -> 1.15f
                "hyperlapse" -> 1.10f
                "dolly_zoom" -> 1.0f + ((Math.sin(uTimeSec * 2.2) + 1.0) * 0.5).toFloat() * 0.34f
                else -> 1.0f
            }

            val isMultiPassComic = (filterCode == 7 || effectCode in 1..6)

            synchronized(overlayLock) {
                if (overlayBitmapDirty) {
                    val bmp = pendingOverlayBitmap
                    if (bmp != null && !bmp.isRecycled && overlayTextureId != 0) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                        hasOverlayTexture = true
                    } else {
                        hasOverlayTexture = false
                    }
                    overlayBitmapDirty = false
                }
            }

            for (holder in outputSurfaces) {
                val out = holder.surfaceOutput
                EGL14.eglMakeCurrent(eglDisplay, holder.eglSurface, holder.eglSurface, eglContext)

                val effectiveZoom = (if (eisEnabled) eisOverscanZoom else 1.0f) * effectZoom
                GyroStabilizer.buildMvpMatrix(
                    outMatrix = mvpMatrix,
                    surfaceWidth = out.size.width,
                    surfaceHeight = out.size.height,
                    enabled = eisEnabled || effectZoom > 1.001f,
                    overscanZoom = effectiveZoom,
                    transXNorm = if (eisEnabled) eisTransX else 0f,
                    transYNorm = if (eisEnabled) eisTransY else 0f,
                    rollDeg = if (eisEnabled) eisRollDeg else 0f
                )
                out.updateTransformMatrix(holderTexMatrix, texMatrix)

                if (isMultiPassComic) {
                    val comicMode = when (effectCode) {
                        1 -> 0 // Cel-Shaded / Comic Book
                        2 -> 1 // Graphic Novel Noir
                        3 -> 2 // Manga Ink
                        4 -> 3 // Pop-Art Print
                        5 -> 4 // Line Art Minimalist
                        6 -> 5 // Ligne Claire
                        else -> 0 // FilterPreset.COMIC_BOOK
                    }
                    val comicIntensity = if (effectCode in 1..6) activeEffectIntensity else 0.92f

                    ensureComicFbos(out.size.width, out.size.height)
                    val fboTexelX = 1f / comicFboW.toFloat()
                    val fboTexelY = 1f / comicFboH.toFloat()
                    val fboTexelScale = (Math.min(comicFboW, comicFboH).toFloat() / 720f).coerceIn(0.85f, 2.2f)

                    GLES20.glViewport(0, 0, comicFboW, comicFboH)

                    // PASS 1: OES Camera -> FBO 0 (NightBoost + Filter + Horizontal 9-tap Bilateral Flatten)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, comicFboIds[0])
                    GLES20.glUseProgram(progOesBilatH)
                    bindQuadAttributes(locBilatHAPos, locBilatHATex)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                    GLES20.glUniform1i(locBilatHSTex, 0)
                    GLES20.glUniformMatrix4fv(locBilatHMvp, 1, false, identityMatrix, 0)
                    GLES20.glUniformMatrix4fv(locBilatHTexMtx, 1, false, holderTexMatrix, 0)
                    GLES20.glUniform1i(locBilatHFilter, filterCode)
                    GLES20.glUniform1f(locBilatHNight, if (nightBoost) 1f else 0f)
                    GLES20.glUniform2f(locBilatHStep, fboTexelX * 1.45f * fboTexelScale, 0f)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    // PASS 2: FBO 0 -> FBO 1 (Vertical 9-tap Bilateral Flatten)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, comicFboIds[1])
                    GLES20.glUseProgram(progBilatV)
                    bindQuadAttributes(locBilatVAPos, locBilatVATex)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, comicFboTexIds[0])
                    GLES20.glUniform1i(locBilatVTex2D, 0)
                    GLES20.glUniform2f(locBilatVStep, 0f, fboTexelY * 1.45f * fboTexelScale)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    // PASS 3: FBO 1 -> FBO 2 (Dual-Scale 3x3 Sobel Filter Edge Detector on Flattened Image)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, comicFboIds[2])
                    GLES20.glUseProgram(progSobelEdge)
                    bindQuadAttributes(locSobelAPos, locSobelATex)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, comicFboTexIds[1])
                    GLES20.glUniform1i(locSobelTex2D, 0)
                    GLES20.glUniform2f(locSobelTexelSize, fboTexelX * fboTexelScale, fboTexelY * fboTexelScale)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    // PASS 4: FBO 1 + FBO 2 + OES -> Window EGLSurface (Quantized Color Banding + Comic Ink Composite)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, out.size.width, out.size.height)
                    GLES20.glUseProgram(progComicComposite)
                    bindQuadAttributes(locCompAPos, locCompATex)

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, comicFboTexIds[1])
                    GLES20.glUniform1i(locCompSmooth, 0)

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, comicFboTexIds[2])
                    GLES20.glUniform1i(locCompEdge, 1)

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                    GLES20.glUniform1i(locCompOes, 2)

                    GLES20.glUniformMatrix4fv(locCompMvp, 1, false, mvpMatrix, 0)
                    GLES20.glUniformMatrix4fv(locCompTexMtx, 1, false, holderTexMatrix, 0)
                    GLES20.glUniform1i(locCompMode, comicMode)
                    GLES20.glUniform1f(locCompEffInt, comicIntensity)
                    GLES20.glUniform1f(locCompHtDen, halftoneDensity)
                    GLES20.glUniform1f(locCompHtDot, halftoneDotSize)
                    GLES20.glUniform1f(locCompNight, if (nightBoost) 1f else 0f)
                    GLES20.glUniform3f(locCompAdj, briVal, conVal, satVal)
                    GLES20.glUniform2f(locCompRes, comicFboW.toFloat(), comicFboH.toFloat())
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                } else {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, out.size.width, out.size.height)

                    GLES20.glUseProgram(glProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                    if (locMainSTex >= 0) GLES20.glUniform1i(locMainSTex, 0)
                    bindQuadAttributes(locMainAPos, locMainATex)

                    GLES20.glUniformMatrix4fv(locMainMvp, 1, false, mvpMatrix, 0)
                    GLES20.glUniformMatrix4fv(locMainTexMtx, 1, false, holderTexMatrix, 0)
                    GLES20.glUniform1i(locMainFilterMode, filterCode)
                    GLES20.glUniform1i(locMainEffectMode, effectCode)
                    GLES20.glUniform1f(locMainEffectInt, activeEffectIntensity)
                    GLES20.glUniform3f(locMainAdj, briVal, conVal, satVal)
                    GLES20.glUniform1f(locMainNight, if (nightBoost) 1f else 0f)
                    GLES20.glUniform1f(locMainTime, uTimeSec)
                    GLES20.glUniform2f(
                        locMainTexelSize,
                        1f / inputWidth.toFloat(),
                        1f / inputHeight.toFloat()
                    )
                    GLES20.glUniform3f(
                        locMainMotion,
                        motionBlurDirX,
                        motionBlurDirY,
                        motionBlurMagnitude
                    )
                    if (locMainExtraFlags >= 0) {
                        GLES20.glUniform3f(
                            locMainExtraFlags,
                            if (extraFilmGrainFlag) 1f else 0f,
                            if (extraVignetteFlag) 1f else 0f,
                            if (extraCineFlareFlag) 1f else 0f
                        )
                    }
                    if (locMainCustomFilter >= 0) {
                        if (customFilterEnabled) {
                            GLES20.glUniform4f(
                                locMainCustomFilter,
                                customFilterBri,
                                customFilterCon,
                                if (customFilterGray) 0f else customFilterSat,
                                1f
                            )
                        } else {
                            GLES20.glUniform4f(locMainCustomFilter, 0f, 1f, 1f, 0f)
                        }
                    }

                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }

                // Alpha-composite freehand AR Text & AR Lens overlay into recorded video & preview frames
                if (hasOverlayTexture && progOverlay != 0 && overlayTextureId != 0) {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    GLES20.glUseProgram(progOverlay)
                    bindQuadAttributes(locOvlAPos, locOvlATex)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
                    GLES20.glUniform1i(locOvlTex, 0)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisable(GLES20.GL_BLEND)
                }

                EGLExt.eglPresentationTimeANDROID(eglDisplay, holder.eglSurface, timestampNs)
                EGL14.eglSwapBuffers(eglDisplay, holder.eglSurface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame render error: ${e.message}", e)
        }
    }

    private fun bindQuadAttributes(aPos: Int, aTex: Int) {
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPos)
        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
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
            destroyComicFbos()
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
            if (overlayTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(overlayTextureId), 0)
                overlayTextureId = 0
            }
            synchronized(overlayLock) {
                pendingOverlayBitmap = null
                hasOverlayTexture = false
            }
            if (glProgram != 0) { GLES20.glDeleteProgram(glProgram); glProgram = 0 }
            if (progOesBilatH != 0) { GLES20.glDeleteProgram(progOesBilatH); progOesBilatH = 0 }
            if (progBilatV != 0) { GLES20.glDeleteProgram(progBilatV); progBilatV = 0 }
            if (progSobelEdge != 0) { GLES20.glDeleteProgram(progSobelEdge); progSobelEdge = 0 }
            if (progComicComposite != 0) { GLES20.glDeleteProgram(progComicComposite); progComicComposite = 0 }
            if (progOverlay != 0) { GLES20.glDeleteProgram(progOverlay); progOverlay = 0 }
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
        FilterManager.FilterPreset.NOIR_CONTRAST,
        FilterManager.FilterPreset.LUT_NOIR_CINEMA -> 1
        FilterManager.FilterPreset.VINTAGE,
        FilterManager.FilterPreset.ANTIQUE_SEPIA,
        FilterManager.FilterPreset.SILVER_GELATIN,
        FilterManager.FilterPreset.GOLDEN_NOSTALGIA,
        FilterManager.FilterPreset.PORTRA_400,
        FilterManager.FilterPreset.SUNSET_WARMTH,
        FilterManager.FilterPreset.SUPER8_VINTAGE -> 2
        FilterManager.FilterPreset.CINEMATIC,
        FilterManager.FilterPreset.LUT_TEAL_ORANGE,
        FilterManager.FilterPreset.TEAL_ORANGE -> 3
        FilterManager.FilterPreset.CYBERPUNK,
        FilterManager.FilterPreset.LUT_MIDNIGHT_NEON -> 4
        FilterManager.FilterPreset.HDR,
        FilterManager.FilterPreset.VELVIA_LANDSCAPE,
        FilterManager.FilterPreset.LUT_REC709 -> 5
        FilterManager.FilterPreset.LUT_FLAT_LOG,
        FilterManager.FilterPreset.LUT_MATTE_CINEMA,
        FilterManager.FilterPreset.FUJI_400H,
        FilterManager.FilterPreset.PASTEL_DREAM -> 6
        FilterManager.FilterPreset.COMIC_BOOK -> 7
        FilterManager.FilterPreset.MATRIX_CODE -> 8
        FilterManager.FilterPreset.NORDIC_BLUE,
        FilterManager.FilterPreset.NORDIC_SLATE,
        FilterManager.FilterPreset.ARCTIC_MIST,
        FilterManager.FilterPreset.LUT_ANAMORPHIC_CYAN -> 9
        else -> 0
    }

    private fun mapEffectCode(effectId: String): Int = when (effectId) {
        "cel_shaded_studio", "comic_book" -> 1
        "graphic_novel_noir", "noir_graphic_novel" -> 2
        "manga_ink" -> 3
        "pop_art_print" -> 4
        "line_art_minimalist" -> 5
        "ligne_claire" -> 6
        "clay_vinyl_3d" -> 7
        "oil_canvas" -> 8
        "charcoal_sketch" -> 9
        "watercolor_wash" -> 10
        "pastel_chalk" -> 29
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
        "soft_halation" -> 27
        "motion_blur" -> 28
        "double_exposure" -> 30
        "film_grain" -> 31
        "anamorphic_flare" -> 32
        "long_exposure" -> 33
        "light_painting" -> 34
        "star_trails" -> 35
        "panorama" -> 36
        "stop_motion" -> 37
        else -> 0
    }

    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "OpenGL program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "OpenGL shader compile failed (type=$type): ${GLES20.glGetShaderInfoLog(shader)}")
        }
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
            varying vec2 vScreenUv;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                vScreenUv = aTexCoord;
            }
        """

        private const val PASS_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val OES_BILAT_H_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform int uFilterMode;
            uniform float uNightBoost;
            uniform vec2 uTexelStep;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            vec3 sampleFiltered(vec2 uv) {
                vec3 col = texture2D(sTexture, clamp(uv, 0.0, 1.0)).rgb;
                if (uNightBoost > 0.5) {
                    col = pow(max(col, vec3(0.001)), vec3(0.62)) + vec3(0.03);
                }
                if (uFilterMode == 1) {
                    float g = luma(col);
                    col = vec3((g - 0.5) * 1.28 + 0.5);
                } else if (uFilterMode == 2) {
                    float g = luma(col);
                    col = mix(col, vec3(g * 1.08 + 0.05, g * 0.94 + 0.02, g * 0.72), 0.72);
                } else if (uFilterMode == 3) {
                    col = vec3(col.r * 1.16, col.g * 1.02, col.b * 0.90 + 0.05);
                } else if (uFilterMode == 4) {
                    col = vec3(col.r * 1.24, col.g * 0.92, col.b * 1.32);
                } else if (uFilterMode == 5) {
                    col = (col - 0.5) * 1.22 + 0.5;
                } else if (uFilterMode == 6) {
                    col = col * 0.72 + 0.12;
                } else if (uFilterMode == 8) {
                    float gM = luma(col);
                    col = vec3(gM * 0.25, clamp(gM * 1.38 + 0.05, 0.0, 1.0), gM * 0.35);
                } else if (uFilterMode == 9) {
                    col = vec3(col.r * 0.88, col.g * 1.04, col.b * 1.28 + 0.04);
                }
                return col;
            }

            void main() {
                vec3 center = sampleFiltered(vTexCoord);
                vec3 acc = center * 0.22;
                float wSum = 0.22;
                for (int k = 1; k <= 4; k++) {
                    float fk = float(k);
                    float spatial = exp(-(fk * fk) / 10.5);
                    vec2 off = uTexelStep * fk;
                    vec3 s1 = sampleFiltered(vTexCoord + off);
                    vec3 d1 = s1 - center;
                    float w1 = spatial * exp(-dot(d1, d1) * 24.0);
                    acc += s1 * w1; wSum += w1;

                    vec3 s2 = sampleFiltered(vTexCoord - off);
                    vec3 d2 = s2 - center;
                    float w2 = spatial * exp(-dot(d2, d2) * 24.0);
                    acc += s2 * w2; wSum += w2;
                }
                gl_FragColor = vec4(acc / max(wSum, 0.001), 1.0);
            }
        """

        private const val BILAT_V_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture2D;
            uniform vec2 uTexelStep;

            void main() {
                vec3 center = texture2D(uTexture2D, clamp(vTexCoord, 0.0, 1.0)).rgb;
                vec3 acc = center * 0.22;
                float wSum = 0.22;
                for (int k = 1; k <= 4; k++) {
                    float fk = float(k);
                    float spatial = exp(-(fk * fk) / 10.5);
                    vec2 off = uTexelStep * fk;
                    vec3 s1 = texture2D(uTexture2D, clamp(vTexCoord + off, 0.0, 1.0)).rgb;
                    vec3 d1 = s1 - center;
                    float w1 = spatial * exp(-dot(d1, d1) * 24.0);
                    acc += s1 * w1; wSum += w1;

                    vec3 s2 = texture2D(uTexture2D, clamp(vTexCoord - off, 0.0, 1.0)).rgb;
                    vec3 d2 = s2 - center;
                    float w2 = spatial * exp(-dot(d2, d2) * 24.0);
                    acc += s2 * w2; wSum += w2;
                }
                gl_FragColor = vec4(acc / max(wSum, 0.001), 1.0);
            }
        """

        private const val SOBEL_2D_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture2D;
            uniform vec2 uTexelSize;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            vec2 sobelAtScale(vec2 uv, vec2 o) {
                vec3 tl = texture2D(uTexture2D, clamp(uv + vec2(-o.x, -o.y), 0.0, 1.0)).rgb;
                vec3 tc = texture2D(uTexture2D, clamp(uv + vec2( 0.0, -o.y), 0.0, 1.0)).rgb;
                vec3 tr = texture2D(uTexture2D, clamp(uv + vec2( o.x, -o.y), 0.0, 1.0)).rgb;
                vec3 ml = texture2D(uTexture2D, clamp(uv + vec2(-o.x,  0.0), 0.0, 1.0)).rgb;
                vec3 mr = texture2D(uTexture2D, clamp(uv + vec2( o.x,  0.0), 0.0, 1.0)).rgb;
                vec3 bl = texture2D(uTexture2D, clamp(uv + vec2(-o.x,  o.y), 0.0, 1.0)).rgb;
                vec3 bc = texture2D(uTexture2D, clamp(uv + vec2( 0.0,  o.y), 0.0, 1.0)).rgb;
                vec3 br = texture2D(uTexture2D, clamp(uv + vec2( o.x,  o.y), 0.0, 1.0)).rgb;

                float gxL = (-luma(tl) - 2.0 * luma(ml) - luma(bl)) + (luma(tr) + 2.0 * luma(mr) + luma(br));
                float gyL = (-luma(tl) - 2.0 * luma(tc) - luma(tr)) + (luma(bl) + 2.0 * luma(bc) + luma(br));
                float lumEdge = sqrt(gxL * gxL + gyL * gyL) * 0.55;

                vec3 gxRGB = (-tl - 2.0 * ml - bl) + (tr + 2.0 * mr + br);
                vec3 gyRGB = (-tl - 2.0 * tc - tr) + (bl + 2.0 * bc + br);
                float vecEdge = sqrt(dot(gxRGB, gxRGB) * 0.36 + dot(gyRGB, gyRGB) * 0.36);
                return vec2(lumEdge, vecEdge);
            }

            void main() {
                vec2 fine = sobelAtScale(vTexCoord, uTexelSize * 1.15);
                vec2 wide = sobelAtScale(vTexCoord, uTexelSize * 2.15);
                float cFine = max(fine.x, fine.y);
                float cWide = max(wide.x, wide.y);
                float strength = max(cFine, cWide * 0.86);
                gl_FragColor = vec4(cFine, cWide, strength, 1.0);
            }
        """

        private const val COMIC_COMPOSITE_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            varying vec2 vScreenUv;
            uniform sampler2D uSmoothedTex;
            uniform sampler2D uEdgeTex;
            uniform samplerExternalOES sTexture;
            uniform int uComicMode;
            uniform float uEffectIntensity;
            uniform float uHalftoneDensity;
            uniform float uHalftoneDotSize;
            uniform float uNightBoost;
            uniform vec3 uAdjustments;
            uniform vec2 uResolution;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            float quantizeStep(float val, float levels, float feather) {
                float s = clamp(val, 0.0, 1.0) * levels;
                float b = floor(s);
                float f = s - b;
                return clamp((b + smoothstep(0.5 - feather, 0.5 + feather, f)) / levels, 0.0, 1.0);
            }

            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-5;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }

            vec3 hsv2rgb(vec3 c) {
                vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            float halftoneDot(vec2 px, float darkness, float pitch, float dotSize) {
                vec2 rot = vec2(px.x * 0.7071 - px.y * 0.7071, px.x * 0.7071 + px.y * 0.7071) / pitch;
                vec2 cell = fract(rot) - 0.5;
                float rad = min(0.76, sqrt(clamp(darkness, 0.0, 1.0)) * 0.50 * clamp(dotSize, 0.25, 2.0));
                return 1.0 - smoothstep(rad - 0.08, rad + 0.08, length(cell));
            }

            void main() {
                vec2 uv = clamp(vScreenUv, 0.0, 1.0);
                vec3 sm = texture2D(uSmoothedTex, uv).rgb;
                float sobelMag = texture2D(uEdgeTex, uv).b;
                vec3 orig = texture2D(sTexture, clamp(vTexCoord, 0.0, 1.0)).rgb;
                if (uNightBoost > 0.5) {
                    orig = pow(max(orig, vec3(0.001)), vec3(0.62)) + vec3(0.03);
                }

                float lum = luma(sm);
                float thresh = (uComicMode == 2 || uComicMode == 4) ? 0.15 : 0.17;
                float inkMask = smoothstep(thresh, thresh + 0.13, sobelMag);
                vec2 px = uv * uResolution;
                float htDensity = clamp(uHalftoneDensity, 0.4, 2.2);
                float htDotSize = clamp(uHalftoneDotSize, 0.3, 1.8);

                vec3 comicRgb;
                if (uComicMode == 1) {
                    float shadow = smoothstep(0.30, 0.21, lum);
                    float mid = smoothstep(0.62, 0.46, lum);
                    float dot = halftoneDot(px, mid * (0.40 + 0.16 * htDensity), max(2.1, max(4.0, uResolution.x / 88.0) / htDensity), htDotSize);
                    float g = mix(0.99, mix(0.72, 0.09, dot), mid);
                    g = mix(g, 0.03, shadow);
                    comicRgb = vec3(mix(g, 0.02, inkMask));
                } else if (uComicMode == 2) {
                    float darkFill = smoothstep(0.22, 0.11, lum);
                    float tone = lum < 0.44 ? 0.54 : (lum < 0.68 ? 0.28 : 0.0);
                    float dot = halftoneDot(px, tone * (0.7 + 0.3 * htDensity), max(2.0, max(3.8, uResolution.x / 94.0) / htDensity), htDotSize) * 0.88;
                    float shade = max(inkMask, max(darkFill, dot));
                    comicRgb = mix(vec3(0.99, 0.985, 0.96), vec3(0.04, 0.045, 0.07), shade);
                } else if (uComicMode == 3) {
                    float chroma = max(max(sm.r, sm.g), sm.b) - min(min(sm.r, sm.g), sm.b);
                    float qLum = quantizeStep(lum, 3.0, 0.04);
                    vec3 basePop = qLum > 0.72 ? vec3(0.99, 0.95, 0.44)
                                 : (qLum < 0.24 ? vec3(0.06, 0.09, 0.25)
                                 : (chroma < 0.10 ? vec3(0.22, 0.74, 0.97)
                                 : (sm.r >= sm.g && sm.r >= sm.b
                                     ? (sm.r > sm.g + 0.14 ? vec3(0.94, 0.14, 0.24) : vec3(0.99, 0.75, 0.56))
                                     : (sm.b >= sm.r && sm.b >= sm.g ? vec3(0.14, 0.39, 0.92) : vec3(0.06, 0.72, 0.51)))));
                    float dot = halftoneDot(px, (lum < 0.68 ? 0.42 : 0.20) * (0.72 + 0.28 * htDensity), max(2.3, max(4.4, uResolution.x / 72.0) / htDensity), htDotSize);
                    vec3 shaded = mix(basePop, basePop * 0.72 + vec3(0.12, 0.02, 0.05), dot * 0.85);
                    comicRgb = mix(shaded, vec3(0.03, 0.03, 0.05), inkMask);
                } else if (uComicMode == 4) {
                    comicRgb = mix(vec3(0.99, 0.985, 0.96), vec3(0.04, 0.05, 0.08), inkMask);
                } else if (uComicMode == 5) {
                    vec3 hsv = rgb2hsv(sm);
                    float qVal = quantizeStep(hsv.z, 3.0, 0.045) * 0.78 + 0.20;
                    float qSat = clamp(hsv.y * 1.18, 0.0, 1.0);
                    vec3 flatCol = hsv2rgb(vec3(hsv.x, qSat, qVal));
                    comicRgb = mix(flatCol, vec3(0.065, 0.06, 0.055), inkMask * 0.94);
                } else {
                    vec3 hsv = rgb2hsv(sm);
                    float qVal = quantizeStep(hsv.z, 4.0, 0.045) * 0.88 + 0.10;
                    float qSat = clamp(quantizeStep(hsv.y, 4.0, 0.12) * 0.35 + hsv.y * 1.15, 0.0, 1.0);
                    float qHue = fract(floor(hsv.x * 18.0 + 0.5) / 18.0);
                    float crispHue = mix(hsv.x, qHue, 0.42);
                    vec3 banded = hsv2rgb(vec3(crispHue, qSat, qVal));

                    float shadowZone = smoothstep(0.42, 0.18, qVal);
                    float hiZone = smoothstep(0.80, 0.94, qVal);
                    banded *= mix(vec3(1.0), vec3(1.0, 0.972, 0.915), 0.65);
                    banded += vec3(-0.035 * shadowZone, -0.015 * shadowZone, 0.055 * shadowZone) + vec3(hiZone * 0.06);

                    // Luminance-channel newspaper comic-book halftone dot pattern overlay (clean bright highlights, crisp shadows)
                    float lumDensity = smoothstep(0.82, 0.14, lum);
                    float newsPitch = max(2.2, max(4.2, uResolution.x / 92.0) / htDensity);
                    float newsDot = halftoneDot(px, lumDensity * (0.46 + 0.16 * htDensity), newsPitch, htDotSize);
                    float dotLuminanceDrop = newsDot * (0.08 + 0.30 * lumDensity);
                    float bandedLum = max(0.001, luma(banded));
                    float targetLum = bandedLum * (1.0 - dotLuminanceDrop);
                    banded *= (targetLum / bandedLum);

                    comicRgb = mix(clamp(banded, 0.0, 1.0), vec3(0.03, 0.035, 0.065), inkMask);
                }

                vec3 color = mix(orig, comicRgb, clamp(uEffectIntensity, 0.0, 1.0));
                float gFinal = luma(color);
                color = mix(vec3(gFinal), color, uAdjustments.z);
                color = (color - 0.5) * uAdjustments.y + 0.5 + uAdjustments.x;
                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
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
            uniform vec3 uMotionVector;
            uniform vec3 uExtraFlags;
            uniform vec4 uCustomFilter;

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

            float toonCelBand(float lum, float bands) {
                float scaled = clamp(lum, 0.0, 1.0) * bands;
                float b = floor(scaled);
                float f = scaled - b;
                return clamp((b + smoothstep(0.44, 0.56, f)) / bands * 0.91 + 0.07, 0.07, 0.99);
            }

            float softCelBand(float lum, float bands) {
                float scaled = clamp(lum, 0.0, 1.0) * bands;
                float b = floor(scaled);
                float f = scaled - b;
                return clamp((b + smoothstep(0.28, 0.72, f)) / bands * 0.94 + 0.04, 0.05, 0.99);
            }

            float halftone45(vec2 uv, float lum, float pitch) {
                vec2 p = uv * pitch;
                vec2 rot = vec2(p.x * 0.7071 - p.y * 0.7071, p.x * 0.7071 + p.y * 0.7071);
                vec2 cell = fract(rot) - 0.5;
                float radius = sqrt(clamp(1.0 - lum, 0.0, 1.0)) * 0.50;
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
                float v0 = max(0.0, q0 / 9.0 - pow(luma(m0), 2.0));
                float v1 = max(0.0, q1 / 9.0 - pow(luma(m1), 2.0));
                float v2 = max(0.0, q2 / 9.0 - pow(luma(m2), 2.0));
                float v3 = max(0.0, q3 / 9.0 - pow(luma(m3), 2.0));
                // Smooth inverse-variance weighting avoids axis-aligned box grid seams
                float w0 = 1.0 / (1.0 + v0 * v0 * 2400.0);
                float w1 = 1.0 / (1.0 + v1 * v1 * 2400.0);
                float w2 = 1.0 / (1.0 + v2 * v2 * 2400.0);
                float w3 = 1.0 / (1.0 + v3 * v3 * 2400.0);
                return (m0 * w0 + m1 * w1 + m2 * w2 + m3 * w3) / max(w0 + w1 + w2 + w3, 0.001);
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
                } else if (uFilterMode == 8) {
                    float gM = luma(color);
                    color = vec3(gM * 0.25, clamp(gM * 1.38 + 0.05, 0.0, 1.0), gM * 0.35);
                } else if (uFilterMode == 9) {
                    color = vec3(color.r * 0.88, color.g * 1.04, color.b * 1.28 + 0.04);
                }

                vec3 baseEffectColor = color;
                if (uEffectMode == 17) {
                    baseEffectColor = texture2D(sTexture, clamp(vTexCoord, 0.0, 1.0)).rgb;
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
                    } else if (uFilterMode == 8) {
                        float gM0 = luma(baseEffectColor);
                        baseEffectColor = vec3(gM0 * 0.25, clamp(gM0 * 1.38 + 0.05, 0.0, 1.0), gM0 * 0.35);
                    } else if (uFilterMode == 9) {
                        baseEffectColor = vec3(baseEffectColor.r * 0.88, baseEffectColor.g * 1.04, baseEffectColor.b * 1.28 + 0.04);
                    }
                }

                // Lazy-gate expensive 13-tap bilateral & 13-tap XDoG edge sampling so normal camera & lightweight effects stay 60fps
                float edge = 0.0;
                if ((uEffectMode >= 1 && uEffectMode <= 6) || (uEffectMode >= 9 && uEffectMode <= 11) || uEffectMode == 14 || uEffectMode == 15 || uEffectMode == 19 || uEffectMode == 29) {
                    edge = xdogEdge(uv, 1.75);
                }
                vec3 smoothed = color;
                float lum = luma(color);
                if ((uEffectMode >= 1 && uEffectMode <= 15) || (uEffectMode >= 18 && uEffectMode <= 20) || uEffectMode == 29) {
                    smoothed = smoothSample(uv, 2.65);
                    lum = luma(smoothed);
                }

                if (uEffectMode == 1) {
                    float bandL = toonCelBand(lum, 4.0);
                    vec3 vib = mix(vec3(lum), smoothed, 1.52) * (bandL / max(lum, 0.02));
                    float shadowT = smoothstep(0.42, 0.18, bandL);
                    float spec = smoothstep(0.80, 0.92, bandL) * 0.07;
                    vib += vec3(-0.035 * shadowT, -0.012 * shadowT, 0.055 * shadowT) + vec3(spec);
                    float ink = smoothstep(0.16, 0.31, edge);
                    color = mix(clamp(vib, 0.0, 1.0), vec3(0.03, 0.035, 0.07), ink);
                } else if (uEffectMode == 2) {
                    float ink = smoothstep(0.16, 0.31, edge);
                    float shadow = smoothstep(0.32, 0.22, lum);
                    float mid = smoothstep(0.62, 0.48, lum);
                    float baseG = mix(0.99, 0.50, mid);
                    baseG = mix(baseG, 0.03, shadow);
                    color = vec3(mix(baseG, 0.02, ink));
                } else if (uEffectMode == 3) {
                    float st = halftone45(uv, lum, 180.0);
                    float ink = smoothstep(0.15, 0.30, edge);
                    float paper = lum > 0.65 ? 0.985 : (lum < 0.22 ? 0.04 : st);
                    float finalVal = mix(paper, 0.03, ink);
                    color = vec3(finalVal * 0.99, finalVal * 0.98, finalVal * 0.96);
                } else if (uEffectMode == 4) {
                    float chroma = max(max(smoothed.r, smoothed.g), smoothed.b) - min(min(smoothed.r, smoothed.g), smoothed.b);
                    vec3 basePop = lum > 0.75 ? vec3(0.99, 0.95, 0.44)
                                 : (lum < 0.23 ? vec3(0.06, 0.09, 0.25)
                                 : (chroma < 0.10 ? vec3(0.22, 0.74, 0.97)
                                 : (smoothed.r >= smoothed.g && smoothed.r >= smoothed.b
                                     ? (smoothed.r > smoothed.g + 0.14 ? vec3(0.94, 0.14, 0.24) : vec3(0.99, 0.73, 0.55))
                                     : (smoothed.b >= smoothed.r && smoothed.b >= smoothed.g ? vec3(0.14, 0.39, 0.92) : vec3(0.06, 0.72, 0.51)))));
                    float dotMask = halftone45(uv, clamp(lum * 1.15, 0.0, 1.0), 125.0);
                    vec3 shadedPop = mix(basePop * 0.74, basePop, dotMask);
                    float ink = smoothstep(0.17, 0.32, edge);
                    color = mix(shadedPop, vec3(0.03, 0.03, 0.05), ink);
                } else if (uEffectMode == 5) {
                    float ink = smoothstep(0.15, 0.30, edge);
                    color = mix(vec3(0.99, 0.98, 0.96), vec3(0.045, 0.055, 0.08), ink);
                } else if (uEffectMode == 6) {
                    float bandL = toonCelBand(lum, 3.0) * 0.80 + 0.18;
                    vec3 clean = mix(vec3(lum), smoothed, 1.18) * (bandL / max(lum, 0.02));
                    float ink = smoothstep(0.16, 0.31, edge) * 0.94;
                    color = mix(clamp(clean, 0.0, 1.0), vec3(0.07, 0.065, 0.055), ink);
                } else if (uEffectMode == 7) {
                    vec2 eps = uTexelSize * 3.4;
                    float lL = luma(texture2D(sTexture, clamp(uv - vec2(eps.x, 0.0), 0.0, 1.0)).rgb);
                    float lR = luma(texture2D(sTexture, clamp(uv + vec2(eps.x, 0.0), 0.0, 1.0)).rgb);
                    float lU = luma(texture2D(sTexture, clamp(uv - vec2(0.0, eps.y), 0.0, 1.0)).rgb);
                    float lD = luma(texture2D(sTexture, clamp(uv + vec2(0.0, eps.y), 0.0, 1.0)).rgb);
                    vec3 n = normalize(vec3((lL - lR) * 3.2, (lU - lD) * 3.2, 0.72));
                    vec3 lightDir = normalize(vec3(-0.45, -0.55, 0.70));
                    vec3 halfDir = normalize(lightDir + vec3(0.0, 0.0, 1.0));
                    float diff = max(0.0, dot(n, lightDir));
                    float spec = pow(max(0.0, dot(n, halfDir)), 24.0) * 0.36;
                    float rim = pow(1.0 - max(0.0, n.z), 2.2) * 0.20;
                    vec3 clay = mix(vec3(lum), smoothed, 1.24) * (0.66 + 0.50 * diff) + vec3(spec + rim);
                    color = clamp(clay, 0.0, 1.0);
                } else if (uEffectMode == 8) {
                    vec3 kuw = mix(kuwaharaOil(uv, 2.2), smoothed, 0.32);
                    float sLum = luma(kuw);
                    vec2 gOff = uTexelSize * 2.8;
                    float dxL = luma(texture2D(sTexture, clamp(uv + vec2(gOff.x, 0.0), 0.0, 1.0)).rgb) - luma(texture2D(sTexture, clamp(uv - vec2(gOff.x, 0.0), 0.0, 1.0)).rgb);
                    float dyL = luma(texture2D(sTexture, clamp(uv + vec2(0.0, gOff.y), 0.0, 1.0)).rgb) - luma(texture2D(sTexture, clamp(uv - vec2(0.0, gOff.y), 0.0, 1.0)).rgb);
                    float bristle = sin((uv.x * 240.0 + uv.y * 210.0) + sin((uv.x - uv.y) * 52.0) * 1.8) * 0.016;
                    float relief = clamp((-dxL * 0.45 - dyL * 0.55) * 0.18 + bristle, -0.05, 0.075);
                    vec3 oil = mix(vec3(sLum), kuw, 1.32) + vec3(relief + 0.012, relief + 0.004, relief - 0.014);
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
                    vec3 wash = mix(kuwaharaOil(uv, 2.0), smoothed, 0.44);
                    float wLum = luma(wash);
                    float bleach = smoothstep(0.75, 0.96, wLum) * 0.62;
                    vec3 vibrant = mix(mix(vec3(wLum), wash, 1.46) + vec3(0.045), vec3(0.985, 0.975, 0.945), bleach);
                    float pool = 1.0 - smoothstep(0.18, 0.44, edge) * 0.24;
                    float tooth = (sin(uv.x * 260.0 + uv.y * 140.0) + cos(uv.y * 220.0 - uv.x * 110.0)) * 0.010;
                    color = clamp(vibrant * pool + vec3(tooth), 0.0, 1.0);
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
                    vec3 lithBase = mix(kuwaharaOil(uv, 1.8), smoothed, 0.42);
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
                } else if (uEffectMode == 28) {
                    vec2 dir = normalize(vec2(uMotionVector.x, uMotionVector.y) + vec2(0.0001, 0.0));
                    float spread = (4.0 + 18.0 * clamp(uMotionVector.z, 0.12, 1.0)) * clamp(uEffectIntensity, 0.15, 1.0);
                    vec2 stepUv = dir * uTexelSize * (spread * 0.25);
                    vec3 acc = vec3(0.0);
                    float wSum = 0.0;
                    for (int k = -4; k <= 4; k++) {
                        float fk = float(k);
                        float w = 1.0 - (abs(fk) / 5.2);
                        acc += texture2D(sTexture, clamp(uv + stepUv * fk, 0.0, 1.0)).rgb * w;
                        wSum += w;
                    }
                    color = acc / max(wSum, 0.001);
                } else if (uEffectMode == 29) {
                    float softL = softCelBand(lum, 7.0);
                    vec3 pastel = mix(vec3(softL), smoothed * (softL / max(lum, 0.02)), 0.88) * 0.78 + vec3(0.22);
                    float tooth = (sin(uv.x * 165.0 + uv.y * 125.0) * 0.014 + cos(uv.y * 280.0) * 0.009);
                    float outline = smoothstep(0.22, 0.50, edge) * 0.16;
                    color = clamp((pastel + vec3(tooth)) * (1.0 - outline), 0.10, 0.99);
                } else if (uEffectMode == 30) {
                    // Double Exposure: rotated & shifted translucent ghost exposure
                    vec2 d = (uv - 0.5) * 0.89;
                    float ca = 0.9984;
                    float sa = 0.0558;
                    vec2 gUv = clamp(vec2(d.x * ca - d.y * sa, d.x * sa + d.y * ca) + vec2(0.54, 0.48), 0.0, 1.0);
                    vec3 ghost = texture2D(sTexture, gUv).rgb;
                    color = clamp(mix(color, ghost, 0.42) * 1.08, 0.0, 1.0);
                } else if (uEffectMode == 31) {
                    // 35mm Film Grain
                    float grain = (paperHash(uv * 512.0 + vec2(uTime * 19.7, uTime * 31.3)) - 0.5) * 0.22;
                    color = clamp(color + vec3(grain), 0.0, 1.0);
                } else if (uEffectMode == 32) {
                    // Anamorphic Flare streaks
                    float s1 = exp(-abs(uv.y - 0.44) * 38.0) * (1.0 - abs(uv.x - 0.5) * 1.35);
                    float s2 = exp(-abs(uv.y - 0.56) * 52.0) * (1.0 - abs(uv.x - 0.52) * 1.45);
                    color = clamp(color + vec3(0.0, 0.82, 1.0) * max(0.0, s1) * 0.55 + vec3(1.0, 0.68, 0.15) * max(0.0, s2) * 0.38, 0.0, 1.0);
                } else if (uEffectMode == 33) {
                    // Long Exposure streak + warm light trail
                    vec3 sL = texture2D(sTexture, clamp(uv - vec2(0.014, 0.0), 0.0, 1.0)).rgb;
                    vec3 sR = texture2D(sTexture, clamp(uv + vec2(0.014, 0.0), 0.0, 1.0)).rgb;
                    float trail = exp(-abs(uv.y - 0.65) * 65.0) * (1.0 - abs(uv.x - 0.5) * 1.2);
                    color = clamp(color * 0.54 + (sL + sR) * 0.23 + vec3(0.98, 0.65, 0.12) * max(0.0, trail) * 0.52, 0.0, 1.0);
                } else if (uEffectMode == 34) {
                    // Light Painting luminous ribbon
                    float waveY = 0.48 + 0.18 * sin(uv.x * 7.5 + uTime * 1.8);
                    float dRib = abs(uv.y - waveY);
                    float core = exp(-dRib * 75.0);
                    float halo = exp(-dRib * 18.0);
                    color = clamp(color + vec3(0.95, 0.28, 0.62) * halo * 0.42 + vec3(0.25, 0.82, 1.0) * core * 0.78, 0.0, 1.0);
                } else if (uEffectMode == 35) {
                    // Star Trails concentric arcs
                    vec2 dp = uv - vec2(0.5, 0.22);
                    float r = length(dp);
                    float ring = pow(max(0.0, sin(r * 68.0)), 14.0) * smoothstep(0.10, 0.18, r) * (0.45 + 0.55 * sin(atan(dp.y, dp.x) * 3.0 + uTime * 0.6));
                    color = clamp(color + vec3(1.0, 0.96, 0.82) * ring * 0.55, 0.0, 1.0);
                } else if (uEffectMode == 36) {
                    // Panorama cinematic letterbox
                    if (uv.y < 0.18 || uv.y > 0.82) {
                        color = vec3(0.0);
                    }
                } else if (uEffectMode == 37) {
                    // Stop-Motion vivid color lift
                    float smL = luma(color);
                    color = clamp(mix(vec3(smL), color, 1.18), 0.0, 1.0);
                }

                if (uEffectMode > 0) {
                    color = mix(baseEffectColor, color, clamp(uEffectIntensity, 0.0, 1.0));
                }

                // Apply Custom Filter preset adjustments if active
                if (uCustomFilter.w > 0.5) {
                    float gCust = luma(color);
                    color = mix(vec3(gCust), color, uCustomFilter.z);
                    color = (color - 0.5) * uCustomFilter.y + 0.5 + uCustomFilter.x;
                }

                // Apply Extra Toggled Overlays (Film Grain / Vignette / Cine Flare)
                if (uExtraFlags.x > 0.5 && uEffectMode != 31) {
                    float gNoise = (paperHash(uv * 512.0 + vec2(uTime * 17.3, uTime * 29.1)) - 0.5) * 0.18;
                    color += vec3(gNoise);
                }
                if (uExtraFlags.y > 0.5 && uEffectMode != 26) {
                    float vDist = length(uv - 0.5);
                    color *= clamp(1.08 - vDist * 1.02, 0.28, 1.0);
                }
                if (uExtraFlags.z > 0.5 && uEffectMode != 32) {
                    float f1 = exp(-abs(uv.y - 0.48) * 42.0) * (1.0 - abs(uv.x - 0.5) * 1.35);
                    color += vec3(0.0, 0.82, 1.0) * max(0.0, f1) * 0.48;
                }

                // Apply Brightness / Contrast / Saturation sliders
                float gFinal = luma(color);
                color = mix(vec3(gFinal), color, uAdjustments.z);
                color = (color - 0.5) * uAdjustments.y + 0.5 + uAdjustments.x;
                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """

        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """

        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uOverlayTex;
            void main() {
                gl_FragColor = texture2D(uOverlayTex, vTexCoord);
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

        /**
         * Creates a VideoCapture-only CameraEffect fallback for HALs that reject simultaneous Preview + Video SurfaceProcessor sharing
         */
        fun createVideoOnlyCameraEffect(processor: OpenGLSurfaceProcessor, executor: Executor): CameraEffect {
            return object : CameraEffect(
                CameraEffect.VIDEO_CAPTURE,
                executor,
                processor,
                Consumer { error: Throwable ->
                    Log.e(TAG, "Video-only CameraEffect processing error: ${error.message}", error)
                }
            ) {}
        }

        /**
         * Creates a Preview-only CameraEffect configuring OpenGLSurfaceProcessor for live Preview EIS
         */
        fun createPreviewCameraEffect(processor: OpenGLSurfaceProcessor, executor: Executor): CameraEffect {
            return object : CameraEffect(
                CameraEffect.PREVIEW,
                executor,
                processor,
                Consumer { error: Throwable ->
                    Log.e(TAG, "Preview CameraEffect processing error: ${error.message}", error)
                }
            ) {}
        }
    }
}
