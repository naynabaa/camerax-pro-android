package com.lumevra.camera.filter

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline Full-Resolution Multi-Pass GPU Photo Engine (OpenGL ES 2.0 + EGL14 Pbuffer).
 *
 * Hardware & Memory Safety:
 * - Runs on a single dedicated GL worker thread (zero contention with CameraX SurfaceProcessor).
 * - Uses Ping-Pong FBO reuse (max 3-4 working textures) and deletes every FBO/texture immediately
 *   in a finally block after glReadPixels, preventing OOM on 3-4 GB RAM devices.
 * - Queries GL_MAX_TEXTURE_SIZE and caps max dimension safely (3072px on standard phones, 2048px on low-RAM phones).
 * - Graceful null fallback if EGL fails on exotic emulators so capture never crashes.
 */
class ProGpuPhotoRenderer(private val context: Context) {

    data class FilmGradeParams(
        val filterId: String = "normal",
        val colorMatrix20: FloatArray? = null,
        val brightnessPercent: Int = 0,
        val contrastPercent: Int = 0,
        val saturationPercent: Int = 0,
        val clarityAmount: Float = 0.22f,
        val grainAmount: Float = 0f,
        val vignetteAmount: Float = 0f,
        val halationAmount: Float = 0f,
        val anamorphicFlareAmount: Float = 0f
    )

    private val glThread = HandlerThread("ProGpuPhotoGL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glReady = false
    private var maxTexSize = 2048

    private var progCopy = 0
    private var progSeparableBlur = 0
    private var progBilateral = 0
    private var progStructureTensor = 0
    private var progFlowDoG = 0
    private var progFlowSmooth = 0
    private var progAnisoKuwahara = 0
    private var progHighlightExtract = 0
    private var progFilmGrade = 0
    private var progOpticalComposite = 0
    private var progArtComposite = 0
    private var progToonComposite = 0

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }

    // Flipped Y coordinates used only on final readback pass so glReadPixels matches upright Android Bitmap
    private val quadTexCoordsFlipY: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
            position(0)
        }

    private val isLowRamDevice: Boolean by lazy {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memClass = am?.memoryClass ?: 256
            (am?.isLowRamDevice == true) || memClass < 192
        } catch (_: Throwable) {
            false
        }
    }

    private fun <T> runOnGlSync(timeoutMs: Long = 8000L, block: () -> T?): T? {
        if (!glThread.isAlive) return null
        val ref = AtomicReference<T?>(null)
        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                if (!glReady) initEglAndPrograms()
                if (glReady) {
                    ref.set(block())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "GPU photo render error: ${t.message}", t)
            } finally {
                latch.countDown()
            }
        }
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) ref.get() else null
        } catch (_: InterruptedException) {
            null
        }
    }

    private fun initEglAndPrograms() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
            return
        }
        val cfg = configs[0] ?: return
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return

        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfg, surfAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        val maxArr = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxArr, 0)
        val cap = if (isLowRamDevice) 2048 else 3072
        maxTexSize = maxArr[0].coerceIn(1024, cap)

        progCopy = createProgram(VS_QUAD, FS_COPY)
        progSeparableBlur = createProgram(VS_QUAD, FS_SEPARABLE_BLUR)
        progBilateral = createProgram(VS_QUAD, FS_BILATERAL)
        progStructureTensor = createProgram(VS_QUAD, FS_STRUCTURE_TENSOR)
        progFlowDoG = createProgram(VS_QUAD, FS_FLOW_DOG)
        progFlowSmooth = createProgram(VS_QUAD, FS_FLOW_SMOOTH)
        progAnisoKuwahara = createProgram(VS_QUAD, FS_ANISO_KUWAHARA)
        progHighlightExtract = createProgram(VS_QUAD, FS_HIGHLIGHT_EXTRACT)
        progFilmGrade = createProgram(VS_QUAD, FS_FILM_GRADE)
        progOpticalComposite = createProgram(VS_QUAD, FS_OPTICAL_COMPOSITE)
        progArtComposite = createProgram(VS_QUAD, FS_ART_COMPOSITE)
        progToonComposite = createProgram(VS_QUAD, FS_TOON_COMPOSITE)

        glReady = (progCopy != 0 && progFilmGrade != 0 && progOpticalComposite != 0 &&
            progArtComposite != 0 && progToonComposite != 0)
        Log.i(TAG, "ProGpuPhotoRenderer initialized (glReady=$glReady, maxTexSize=$maxTexSize, lowRam=$isLowRamDevice)")
    }

    fun release() {
        if (!glThread.isAlive) return
        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                val progs = intArrayOf(
                    progCopy, progSeparableBlur, progBilateral, progStructureTensor,
                    progFlowDoG, progFlowSmooth, progAnisoKuwahara, progHighlightExtract,
                    progFilmGrade, progOpticalComposite, progArtComposite, progToonComposite
                )
                for (p in progs) {
                    if (p != 0) GLES20.glDeleteProgram(p)
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (_: Throwable) {
            } finally {
                glReady = false
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
                latch.countDown()
            }
        }
        try {
            latch.await(1500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {}
        glThread.quitSafely()
    }

    // =========================================================================
    // PUBLIC API 1: PRO FILMIC & CINEMA FILTER ENGINE
    // =========================================================================
    fun renderFilmicGrade(sourceBitmap: Bitmap, params: FilmGradeParams): Bitmap? {
        return runOnGlSync {
            val (w, h, workBmp, ownsWork) = prepareWorkBitmap(sourceBitmap)
            val texIds = mutableListOf<Int>()
            val fboIds = mutableListOf<Int>()
            try {
                val texOrig = uploadBitmapTexture(workBmp).also { texIds.add(it) }
                val (fboA, texA) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }
                val (fboB, texB) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }

                // Pass 1-2: Half-radius separable blur for optical Clarity (micro-contrast) + optional Highlight Bloom
                runSeparableBlurPass(texOrig, fboA, fboB, texA, w, h, radiusScale = 2.2f)
                // Now texB holds smooth base for Clarity unsharp mask

                // Optional Highlight Bloom Pyramid for Halation or Anamorphic Flare
                var texBloom = texOrig
                val needsBloom = params.halationAmount > 0.01f || params.anamorphicFlareAmount > 0.01f
                if (needsBloom) {
                    val bw = (w / 2).coerceAtLeast(128)
                    val bh = (h / 2).coerceAtLeast(128)
                    val (fboH1, texH1) = createFboTexture(bw, bh).also { fboIds.add(it.first); texIds.add(it.second) }
                    val (fboH2, texH2) = createFboTexture(bw, bh).also { fboIds.add(it.first); texIds.add(it.second) }
                    val isAnamorphic = params.anamorphicFlareAmount > params.halationAmount
                    runHighlightExtractPass(
                        texOrig, fboH1, bw, bh,
                        threshold = if (isAnamorphic) 0.78f else 0.72f,
                        tintR = if (isAnamorphic) 0.35f else 1.35f,
                        tintG = if (isAnamorphic) 0.85f else 0.48f,
                        tintB = if (isAnamorphic) 1.35f else 0.22f
                    )
                    if (isAnamorphic) {
                        // Wide horizontal optical streak blur (3 horizontal passes)
                        runDirectionalBlurPass(texH1, fboH2, bw, bh, 4.5f, 0f)
                        runDirectionalBlurPass(texH2, fboH1, bw, bh, 9.5f, 0f)
                        runDirectionalBlurPass(texH1, fboH2, bw, bh, 16.0f, 0.4f)
                        texBloom = texH2
                    } else {
                        // Multi-scale isotropic film halation bloom
                        runSeparableBlurPass(texH1, fboH2, fboH1, texH2, bw, bh, radiusScale = 3.2f)
                        runSeparableBlurPass(texH1, fboH2, fboH1, texH2, bw, bh, radiusScale = 6.4f)
                        texBloom = texH1
                    }
                }

                // Final Pass: Non-Linear Filmic S-Curve + 3-Way Split Toning + Skin Protection + Clarity + 35mm Grain
                val (fboOut, _) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }
                val profile = resolveFilmProfile(params.filterId)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
                GLES20.glViewport(0, 0, w, h)
                GLES20.glUseProgram(progFilmGrade)
                bindTex(0, texOrig, progFilmGrade, "uTexOrig")
                bindTex(1, texB, progFilmGrade, "uTexSmooth")
                bindTex(2, texBloom, progFilmGrade, "uTexBloom")

                val useCustomMat = (profile.id == 0 && params.colorMatrix20 != null)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(progFilmGrade, "uUseColorMat"), if (useCustomMat) 1f else 0f)
                if (useCustomMat && params.colorMatrix20 != null && params.colorMatrix20.size >= 20) {
                    val m = params.colorMatrix20
                    val mat3 = floatArrayOf(
                        m[0], m[5], m[10],
                        m[1], m[6], m[11],
                        m[2], m[7], m[12]
                    )
                    val off = floatArrayOf(m[4] / 255f, m[9] / 255f, m[14] / 255f)
                    GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(progFilmGrade, "uColorMat"), 1, false, mat3, 0)
                    GLES20.glUniform3fv(GLES20.glGetUniformLocation(progFilmGrade, "uColorOff"), 1, off, 0)
                }

                // Pack Filmic Curve params: x=toeLift, y=contrastS, z=shoulderSoft, w=saturation
                val sliderSat = (1f + params.saturationPercent / 100f).coerceIn(0f, 2.2f)
                val sliderCon = (1f + params.contrastPercent / 100f).coerceIn(0.45f, 1.85f)
                val sliderBri = (params.brightnessPercent / 220f).coerceIn(-0.45f, 0.45f)
                GLES20.glUniform4f(
                    GLES20.glGetUniformLocation(progFilmGrade, "uCurveParams"),
                    profile.toeLift,
                    profile.sContrast * sliderCon,
                    profile.shoulderSoft,
                    if (profile.isMono) 0f else (profile.saturation * sliderSat)
                )
                GLES20.glUniform3f(GLES20.glGetUniformLocation(progFilmGrade, "uShadowTint"), profile.shadowR, profile.shadowG, profile.shadowB)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(progFilmGrade, "uMidTint"), profile.midR, profile.midG, profile.midB)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(progFilmGrade, "uHighTint"), profile.highR, profile.highG, profile.highB)
                GLES20.glUniform4f(
                    GLES20.glGetUniformLocation(progFilmGrade, "uDetailParams"),
                    (params.clarityAmount + profile.clarityBoost).coerceIn(0f, 0.65f),
                    (params.grainAmount + profile.grainBoost).coerceIn(0f, 0.45f),
                    (params.vignetteAmount + profile.vignetteBoost).coerceIn(0f, 0.85f),
                    sliderBri
                )
                GLES20.glUniform4f(
                    GLES20.glGetUniformLocation(progFilmGrade, "uExtraParams"),
                    if (profile.isMono) 1f else 0f,
                    profile.skinProtect,
                    params.halationAmount.coerceIn(0f, 1.2f),
                    params.anamorphicFlareAmount.coerceIn(0f, 1.2f)
                )
                GLES20.glUniform2f(GLES20.glGetUniformLocation(progFilmGrade, "uTexSize"), w.toFloat(), h.toFloat())
                GLES20.glUniform1f(GLES20.glGetUniformLocation(progFilmGrade, "uSeed"), ((System.nanoTime() and 0xFFFFL) / 65535f))

                drawQuad(progFilmGrade, flipY = true)
                readFboToBitmap(w, h)
            } finally {
                deleteGlResources(fboIds, texIds)
                if (ownsWork && !workBmp.isRecycled) workBmp.recycle()
            }
        }
    }

    // =========================================================================
    // PUBLIC API 2: PRO OPTICAL, FINE ART & STUDIO TOON MULTI-PASS ENGINE
    // =========================================================================
    fun renderCreativeEffect(
        sourceBitmap: Bitmap,
        effectId: String,
        intensity: Float = 0.90f,
        halftoneDensity: Float = 1.0f,
        halftoneDotSize: Float = 1.0f,
        subjectMaskBitmap: Bitmap? = null
    ): Bitmap? {
        val effInt = intensity.coerceIn(0f, 1f)
        if (effInt <= 0.01f) return null
        val lower = effectId.lowercase(Locale.ROOT)
        return when (lower) {
            // --- STUDIO TOON & COMIC (Multi-pass Bilateral + Coherent Flow FDoG + Studio Cel) ---
            "cel_shaded_studio", "comic_book", "comic", "cartoon",
            "graphic_novel_noir", "noir_graphic_novel",
            "manga_ink", "pop_art_print",
            "line_art_minimalist", "ligne_claire" ->
                renderProToon(sourceBitmap, lower, effInt, halftoneDensity, halftoneDotSize)

            // --- FINE ART (Structure Tensor + Flow-Aligned Anisotropic Kuwahara + Medium Physics) ---
            "oil_canvas", "watercolor_wash", "charcoal_sketch", "ink_and_line",
            "pastel_chalk", "woodcut_engraving", "lithograph", "cyanotype",
            "halftone_print", "clay_vinyl_3d", "neon_edge", "solarize", "thermal_vision" ->
                renderProArt(sourceBitmap, lower, effInt, halftoneDensity, halftoneDotSize)

            // --- OPTICAL & CINEMA EFFECTS (Pyramid Bloom + Specular Disc Bokeh + Analog Optics) ---
            "soft_halation", "anamorphic_flare", "portrait_bokeh", "video_bokeh", "tilt_shift",
            "prism_refraction", "vignette", "film_grain",
            "vintage_super8", "super8_film", "film_16mm", "vhs_tape", "crt_monitor" ->
                renderProOptical(sourceBitmap, lower, effInt, subjectMaskBitmap)

            else -> null
        }
    }

    // =========================================================================
    // ENGINE A: PRO STUDIO TOON (3-Pass Bilateral + Coherent Tangent-Flow FDoG)
    // =========================================================================
    private fun renderProToon(
        sourceBitmap: Bitmap,
        effectId: String,
        intensity: Float,
        halftoneDensity: Float,
        halftoneDotSize: Float
    ): Bitmap? = runOnGlSync {
        val (w, h, workBmp, ownsWork) = prepareWorkBitmap(sourceBitmap)
        val texIds = mutableListOf<Int>()
        val fboIds = mutableListOf<Int>()
        try {
            val texOrig = uploadBitmapTexture(workBmp).also { texIds.add(it) }
            val (fboA, texA) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }
            val (fboB, texB) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }
            val (fboTensor, texTensor) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }
            val (fboEdge, texEdge) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }

            val baseScale = (Math.min(w, h) / 900f).coerceIn(0.85f, 2.4f)

            // 1. 3-Pass Separable Edge-Preserving Bilateral Abstraction (silky skin & flat studio cel planes)
            runBilateralPass(texOrig, fboA, w, h, 1f, 0f, sigmaSpace = 2.6f * baseScale, sigmaColor = 0.14f)
            runBilateralPass(texA, fboB, w, h, 0f, 1f, sigmaSpace = 2.6f * baseScale, sigmaColor = 0.14f)
            runBilateralPass(texB, fboA, w, h, 1f, 0f, sigmaSpace = 2.0f * baseScale, sigmaColor = 0.11f)
            runBilateralPass(texA, fboB, w, h, 0f, 1f, sigmaSpace = 2.0f * baseScale, sigmaColor = 0.11f)
            // texB = silky bilateral-flattened image

            // 2. Structure Tensor + Smoothed Tangent Flow Field (coherent curve direction)
            runStructureTensorPass(texB, fboA, w, h, stepScale = 1.15f * baseScale)
            runSeparableBlurPass(texA, fboEdge, fboTensor, texEdge, w, h, radiusScale = 2.2f * baseScale)
            // texTensor = smoothed (Jx, Jy, Jxy, lum)

            // 3. Flow-Based Difference of Gaussians (FDoG) perpendicular to tangent, then smoothed ALONG tangent!
            runFlowDoGPass(texB, texTensor, fboA, w, h, stepScale = 1.1f * baseScale)
            runFlowSmoothPass(texA, texTensor, fboEdge, w, h, stepScale = 1.45f * baseScale)
            // texEdge.r = clean, continuous, calligraphic ink response (zero skin-pore noise!)

            // 4. Studio Cel Shading + Ink + Screentone Final Composite
            val styleIdx = when (effectId) {
                "cel_shaded_studio", "comic_book", "comic", "cartoon" -> 1
                "graphic_novel_noir", "noir_graphic_novel" -> 2
                "manga_ink" -> 3
                "pop_art_print" -> 4
                "line_art_minimalist" -> 5
                "ligne_claire" -> 6
                else -> 1
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(progToonComposite)
            bindTex(0, texOrig, progToonComposite, "uTexOrig")
            bindTex(1, texB, progToonComposite, "uTexSmooth")
            bindTex(2, texEdge, progToonComposite, "uTexEdge")
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progToonComposite, "uStyle"), styleIdx)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progToonComposite, "uIntensity"), intensity)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progToonComposite, "uTexSize"), w.toFloat(), h.toFloat())
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(progToonComposite, "uHalftoneParams"),
                halftoneDensity.coerceIn(0.4f, 2.2f),
                halftoneDotSize.coerceIn(0.4f, 2.0f)
            )
            drawQuad(progToonComposite, flipY = true)
            readFboToBitmap(w, h)
        } finally {
            deleteGlResources(fboIds, texIds)
            if (ownsWork && !workBmp.isRecycled) workBmp.recycle()
        }
    }

    // =========================================================================
    // ENGINE B: PRO FINE ART (Structure Tensor + Anisotropic Kuwahara + Medium Physics)
    // =========================================================================
    private fun renderProArt(
        sourceBitmap: Bitmap,
        effectId: String,
        intensity: Float,
        halftoneDensity: Float,
        halftoneDotSize: Float
    ): Bitmap? = runOnGlSync {
        val (w, h, workBmp, ownsWork) = prepareWorkBitmap(sourceBitmap)
        val texIds = mutableListOf<Int>()
        val fboIds = mutableListOf<Int>()
        try {
            val texOrig = uploadBitmapTexture(workBmp).also { texIds.add(it) }
            val (fboA, texA) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }
            val (fboB, texB) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }
            val (fboTensor, texTensor) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }
            val (fboEdge, texEdge) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }

            val baseScale = (Math.min(w, h) / 900f).coerceIn(0.85f, 2.3f)

            // 1. Structure Tensor + Smooth Flow Field (brushstrokes & hatching follow real contours)
            runStructureTensorPass(texOrig, fboA, w, h, stepScale = 1.2f * baseScale)
            runSeparableBlurPass(texA, fboEdge, fboTensor, texEdge, w, h, radiusScale = 2.6f * baseScale)

            val needsPainterly = effectId in setOf("oil_canvas", "watercolor_wash", "pastel_chalk", "lithograph")
            val needsBilateral = effectId in setOf("clay_vinyl_3d", "charcoal_sketch", "ink_and_line", "woodcut_engraving", "neon_edge", "thermal_vision", "solarize", "halftone_print", "cyanotype")

            // 2. Main Surface Pass (Anisotropic Flow-Aligned Kuwahara OR Multi-Pass Bilateral)
            if (needsPainterly) {
                val radius = when (effectId) {
                    "oil_canvas" -> 2.15f * baseScale
                    "watercolor_wash" -> 1.95f * baseScale
                    "pastel_chalk" -> 1.75f * baseScale
                    else -> 1.55f * baseScale
                }
                // Pass 1: Flow-aligned Anisotropic Kuwahara
                runAnisoKuwaharaPass(texOrig, texTensor, fboA, w, h, radius = radius)
                // Pass 2: Gentle bilateral finish to melt any residual micro-seams into lush brush pigment
                runBilateralPass(texA, fboB, w, h, 1f, 0f, sigmaSpace = 1.6f * baseScale, sigmaColor = 0.13f)
                runBilateralPass(texB, fboA, w, h, 0f, 1f, sigmaSpace = 1.6f * baseScale, sigmaColor = 0.13f)
                // Swap result into texB via lightweight copy
                runCopyPass(texA, fboB, w, h)
            } else if (needsBilateral) {
                val sp = if (effectId == "clay_vinyl_3d") 3.0f * baseScale else 2.2f * baseScale
                val sc = if (effectId == "clay_vinyl_3d") 0.16f else 0.12f
                runBilateralPass(texOrig, fboA, w, h, 1f, 0f, sigmaSpace = sp, sigmaColor = sc)
                runBilateralPass(texA, fboB, w, h, 0f, 1f, sigmaSpace = sp, sigmaColor = sc)
                if (effectId == "clay_vinyl_3d" || effectId == "charcoal_sketch") {
                    runBilateralPass(texB, fboA, w, h, 1f, 0f, sigmaSpace = sp * 0.85f, sigmaColor = sc)
                    runBilateralPass(texA, fboB, w, h, 0f, 1f, sigmaSpace = sp * 0.85f, sigmaColor = sc)
                }
            } else {
                runCopyPass(texOrig, fboB, w, h)
            }
            // texB = painterly / smoothed base

            // 3. Coherent Flow-Guided Edge / Wet-Edge Pooling Map
            runFlowDoGPass(texB, texTensor, fboA, w, h, stepScale = 1.15f * baseScale)
            runFlowSmoothPass(texA, texTensor, fboEdge, w, h, stepScale = 1.4f * baseScale)

            // 4. Medium Physics Composite (Impasto Relief / Watercolor Pooling / Color-Dodge Charcoal / etc.)
            val artStyle = when (effectId) {
                "oil_canvas" -> 1
                "watercolor_wash" -> 2
                "charcoal_sketch" -> 3
                "ink_and_line" -> 4
                "pastel_chalk" -> 5
                "woodcut_engraving" -> 6
                "lithograph" -> 7
                "cyanotype" -> 8
                "halftone_print" -> 9
                "clay_vinyl_3d" -> 10
                "neon_edge" -> 11
                "solarize" -> 12
                "thermal_vision" -> 13
                else -> 1
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(progArtComposite)
            bindTex(0, texOrig, progArtComposite, "uTexOrig")
            bindTex(1, texB, progArtComposite, "uTexPaint")
            bindTex(2, texTensor, progArtComposite, "uTexTensor")
            bindTex(3, texEdge, progArtComposite, "uTexEdge")
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progArtComposite, "uArtStyle"), artStyle)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progArtComposite, "uIntensity"), intensity)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progArtComposite, "uTexSize"), w.toFloat(), h.toFloat())
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(progArtComposite, "uHalftoneParams"),
                halftoneDensity.coerceIn(0.4f, 2.2f),
                halftoneDotSize.coerceIn(0.4f, 2.0f)
            )
            drawQuad(progArtComposite, flipY = true)
            readFboToBitmap(w, h)
        } finally {
            deleteGlResources(fboIds, texIds)
            if (ownsWork && !workBmp.isRecycled) workBmp.recycle()
        }
    }

    // =========================================================================
    // ENGINE C: PRO OPTICAL & CINEMA EFFECTS (Pyramid Bloom + Specular Disc Bokeh)
    // =========================================================================
    private fun renderProOptical(
        sourceBitmap: Bitmap,
        effectId: String,
        intensity: Float,
        subjectMaskBitmap: Bitmap?
    ): Bitmap? = runOnGlSync {
        val (w, h, workBmp, ownsWork) = prepareWorkBitmap(sourceBitmap)
        val texIds = mutableListOf<Int>()
        val fboIds = mutableListOf<Int>()
        try {
            val texOrig = uploadBitmapTexture(workBmp).also { texIds.add(it) }
            val (fboOut, _) = createFboTexture(w, h).also { fboIds.add(it.first); texIds.add(it.second) }

            val optMode = when (effectId) {
                "soft_halation" -> 1
                "anamorphic_flare" -> 2
                "portrait_bokeh", "video_bokeh" -> 3
                "tilt_shift" -> 4
                "prism_refraction" -> 5
                "vignette" -> 6
                "film_grain" -> 7
                "vintage_super8", "super8_film" -> 8
                "film_16mm" -> 9
                "vhs_tape" -> 10
                "crt_monitor" -> 11
                else -> 0
            }

            var texAux = texOrig
            var texMask = texOrig
            var hasSubjectMask = 0f

            if (subjectMaskBitmap != null && !subjectMaskBitmap.isRecycled && (optMode == 3)) {
                texMask = uploadBitmapTexture(subjectMaskBitmap).also { texIds.add(it) }
                hasSubjectMask = 1f
            }

            // Build Multi-Scale Highlight Bloom Pyramid for Halation / Anamorphic / Vintage Super 8
            if (optMode == 1 || optMode == 2 || optMode == 8) {
                val bw = (w / 2).coerceAtLeast(160)
                val bh = (h / 2).coerceAtLeast(160)
                val (fboH1, texH1) = createFboTexture(bw, bh).also { fboIds.add(it.first); texIds.add(it.second) }
                val (fboH2, texH2) = createFboTexture(bw, bh).also { fboIds.add(it.first); texIds.add(it.second) }

                if (optMode == 2) {
                    // Anamorphic Cinema Blue Streak Extraction + Multi-Tap Horizontal Stretch
                    runHighlightExtractPass(texOrig, fboH1, bw, bh, threshold = 0.78f, tintR = 0.25f, tintG = 0.84f, tintB = 1.40f)
                    runDirectionalBlurPass(texH1, fboH2, bw, bh, 5.0f, 0f)
                    runDirectionalBlurPass(texH2, fboH1, bw, bh, 11.0f, 0f)
                    runDirectionalBlurPass(texH1, fboH2, bw, bh, 18.0f, 0.35f)
                    texAux = texH2
                } else {
                    // Cinestill 800T / Super 8 Red-Orange Halation Pyramid
                    runHighlightExtractPass(texOrig, fboH1, bw, bh, threshold = 0.70f, tintR = 1.42f, tintG = 0.45f, tintB = 0.16f)
                    runSeparableBlurPass(texH1, fboH2, fboH1, texH2, bw, bh, radiusScale = 2.8f)
                    runSeparableBlurPass(texH1, fboH2, fboH1, texH2, bw, bh, radiusScale = 5.8f)
                    runSeparableBlurPass(texH1, fboH2, fboH1, texH2, bw, bh, radiusScale = 9.5f)
                    texAux = texH1
                }
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(progOpticalComposite)
            bindTex(0, texOrig, progOpticalComposite, "uTexOrig")
            bindTex(1, texAux, progOpticalComposite, "uTexAux")
            bindTex(2, texMask, progOpticalComposite, "uTexMask")
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progOpticalComposite, "uOptMode"), optMode)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progOpticalComposite, "uIntensity"), intensity)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progOpticalComposite, "uHasMask"), hasSubjectMask)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progOpticalComposite, "uTexSize"), w.toFloat(), h.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progOpticalComposite, "uSeed"), ((System.nanoTime() and 0xFFFFL) / 65535f))

            drawQuad(progOpticalComposite, flipY = true)
            readFboToBitmap(w, h)
        } finally {
            deleteGlResources(fboIds, texIds)
            if (ownsWork && !workBmp.isRecycled) workBmp.recycle()
        }
    }

    // =========================================================================
    // INTERNAL MULTI-PASS HELPERS
    // =========================================================================
    private data class WorkBitmapResult(val w: Int, val h: Int, val bmp: Bitmap, val ownsBmp: Boolean)

    private fun prepareWorkBitmap(src: Bitmap): WorkBitmapResult {
        val sw = src.width.coerceAtLeast(1)
        val sh = src.height.coerceAtLeast(1)
        val maxDim = Math.max(sw, sh)
        if (maxDim <= maxTexSize) {
            return WorkBitmapResult(sw, sh, src, false)
        }
        val scale = maxTexSize.toFloat() / maxDim.toFloat()
        val nw = (sw * scale).toInt().coerceAtLeast(64)
        val nh = (sh * scale).toInt().coerceAtLeast(64)
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        return WorkBitmapResult(nw, nh, scaled, scaled !== src)
    }

    private fun runCopyPass(texIn: Int, fboOut: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progCopy)
        bindTex(0, texIn, progCopy, "uTex")
        drawQuad(progCopy, flipY = false)
    }

    private fun runDirectionalBlurPass(texIn: Int, fboOut: Int, w: Int, h: Int, stepX: Float, stepY: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progSeparableBlur)
        bindTex(0, texIn, progSeparableBlur, "uTex")
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progSeparableBlur, "uDir"), stepX / w.toFloat(), stepY / h.toFloat())
        drawQuad(progSeparableBlur, flipY = false)
    }

    private fun runSeparableBlurPass(texIn: Int, fboMid: Int, fboOut: Int, texMid: Int, w: Int, h: Int, radiusScale: Float) {
        runDirectionalBlurPass(texIn, fboMid, w, h, radiusScale, 0f)
        runDirectionalBlurPass(texMid, fboOut, w, h, 0f, radiusScale)
    }

    private fun runBilateralPass(texIn: Int, fboOut: Int, w: Int, h: Int, dirX: Float, dirY: Float, sigmaSpace: Float, sigmaColor: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progBilateral)
        bindTex(0, texIn, progBilateral, "uTex")
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBilateral, "uDir"), dirX / w.toFloat(), dirY / h.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBilateral, "uSigmas"), sigmaSpace, sigmaColor)
        drawQuad(progBilateral, flipY = false)
    }

    private fun runStructureTensorPass(texIn: Int, fboOut: Int, w: Int, h: Int, stepScale: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progStructureTensor)
        bindTex(0, texIn, progStructureTensor, "uTex")
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progStructureTensor, "uTexel"), stepScale / w.toFloat(), stepScale / h.toFloat())
        drawQuad(progStructureTensor, flipY = false)
    }

    private fun runFlowDoGPass(texColor: Int, texTensor: Int, fboOut: Int, w: Int, h: Int, stepScale: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progFlowDoG)
        bindTex(0, texColor, progFlowDoG, "uTexColor")
        bindTex(1, texTensor, progFlowDoG, "uTexTensor")
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progFlowDoG, "uTexel"), stepScale / w.toFloat(), stepScale / h.toFloat())
        drawQuad(progFlowDoG, flipY = false)
    }

    private fun runFlowSmoothPass(texDog: Int, texTensor: Int, fboOut: Int, w: Int, h: Int, stepScale: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progFlowSmooth)
        bindTex(0, texDog, progFlowSmooth, "uTexDog")
        bindTex(1, texTensor, progFlowSmooth, "uTexTensor")
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progFlowSmooth, "uTexel"), stepScale / w.toFloat(), stepScale / h.toFloat())
        drawQuad(progFlowSmooth, flipY = false)
    }

    private fun runAnisoKuwaharaPass(texColor: Int, texTensor: Int, fboOut: Int, w: Int, h: Int, radius: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progAnisoKuwahara)
        bindTex(0, texColor, progAnisoKuwahara, "uTexColor")
        bindTex(1, texTensor, progAnisoKuwahara, "uTexTensor")
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progAnisoKuwahara, "uTexel"), 1f / w.toFloat(), 1f / h.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progAnisoKuwahara, "uRadius"), radius)
        drawQuad(progAnisoKuwahara, flipY = false)
    }

    private fun runHighlightExtractPass(
        texIn: Int, fboOut: Int, w: Int, h: Int,
        threshold: Float, tintR: Float, tintG: Float, tintB: Float
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progHighlightExtract)
        bindTex(0, texIn, progHighlightExtract, "uTex")
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progHighlightExtract, "uThreshold"), threshold)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(progHighlightExtract, "uTint"), tintR, tintG, tintB)
        drawQuad(progHighlightExtract, flipY = false)
    }

    private fun uploadBitmapTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return tex
    }

    private fun createFboTexture(w: Int, h: Int): Pair<Int, Int> {
        val fboArr = IntArray(1)
        val texArr = IntArray(1)
        GLES20.glGenFramebuffers(1, fboArr, 0)
        GLES20.glGenTextures(1, texArr, 0)
        val fbo = fboArr[0]
        val tex = texArr[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        return Pair(fbo, tex)
    }

    private fun bindTex(unit: Int, texId: Int, program: Int, uniformName: String) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        val loc = GLES20.glGetUniformLocation(program, uniformName)
        if (loc >= 0) GLES20.glUniform1i(loc, unit)
    }

    private fun drawQuad(program: Int, flipY: Boolean) {
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val uvLoc = GLES20.glGetAttribLocation(program, "aUv")
        if (posLoc >= 0) {
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        }
        if (uvLoc >= 0) {
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, if (flipY) quadTexCoordsFlipY else quadTexCoords)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        if (uvLoc >= 0) GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun readFboToBitmap(w: Int, h: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.copyPixelsFromBuffer(buf)
        return out
    }

    private fun deleteGlResources(fboIds: List<Int>, texIds: List<Int>) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (fboIds.isNotEmpty()) {
            val arr = fboIds.toIntArray()
            GLES20.glDeleteFramebuffers(arr.size, arr, 0)
        }
        if (texIds.isNotEmpty()) {
            val arr = texIds.toIntArray()
            GLES20.glDeleteTextures(arr.size, arr, 0)
        }
        GLES20.glFlush()
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    // =========================================================================
    // FILMIC STOCK PROFILES (Non-Linear Toe/S-Curve/Shoulder + 3-Way Split Toning)
    // =========================================================================
    private data class FilmStockProfile(
        val id: Int,
        val toeLift: Float,
        val sContrast: Float,
        val shoulderSoft: Float,
        val saturation: Float,
        val shadowR: Float, val shadowG: Float, val shadowB: Float,
        val midR: Float, val midG: Float, val midB: Float,
        val highR: Float, val highG: Float, val highB: Float,
        val clarityBoost: Float = 0.08f,
        val grainBoost: Float = 0.0f,
        val vignetteBoost: Float = 0.0f,
        val isMono: Boolean = false,
        val skinProtect: Float = 0.65f
    )

    private fun resolveFilmProfile(filterId: String): FilmStockProfile {
        return when (filterId.lowercase(Locale.ROOT)) {
            "portra_400", "filter_portra_400" -> FilmStockProfile(
                id = 1, toeLift = 0.032f, sContrast = 1.06f, shoulderSoft = 0.24f, saturation = 1.08f,
                shadowR = 0.01f, shadowG = 0.022f, shadowB = 0.028f,
                midR = 0.045f, midG = 0.018f, midB = -0.028f,
                highR = 0.04f, highG = 0.025f, highB = -0.015f,
                clarityBoost = 0.08f, grainBoost = 0.06f, vignetteBoost = 0.14f, skinProtect = 0.85f
            )
            "fuji_400h", "filter_fuji_400h" -> FilmStockProfile(
                id = 2, toeLift = 0.038f, sContrast = 1.04f, shoulderSoft = 0.26f, saturation = 1.04f,
                shadowR = -0.025f, shadowG = 0.035f, shadowB = 0.040f,
                midR = 0.010f, midG = 0.020f, midB = 0.005f,
                highR = 0.015f, highG = 0.028f, highB = 0.018f,
                clarityBoost = 0.07f, grainBoost = 0.055f, vignetteBoost = 0.12f, skinProtect = 0.80f
            )
            "lut_teal_orange", "filter_lut_blockbuster_teal_orange", "teal_orange", "filter_teal_orange", "cinematic" -> FilmStockProfile(
                id = 3, toeLift = 0.012f, sContrast = 1.24f, shoulderSoft = 0.22f, saturation = 1.22f,
                shadowR = -0.09f, shadowG = 0.035f, shadowB = 0.095f,
                midR = 0.055f, midG = 0.015f, midB = -0.045f,
                highR = 0.085f, highG = 0.035f, highB = -0.055f,
                clarityBoost = 0.16f, grainBoost = 0.045f, vignetteBoost = 0.24f, skinProtect = 0.82f
            )
            "lut_bleach_bypass", "filter_lut_bleach_bypass" -> FilmStockProfile(
                id = 4, toeLift = 0.0f, sContrast = 1.48f, shoulderSoft = 0.14f, saturation = 0.50f,
                shadowR = -0.02f, shadowG = 0.01f, shadowB = 0.035f,
                midR = 0.01f, midG = 0.01f, midB = 0.0f,
                highR = 0.03f, highG = 0.025f, highB = 0.01f,
                clarityBoost = 0.28f, grainBoost = 0.08f, vignetteBoost = 0.26f, skinProtect = 0.40f
            )
            "lut_matte_cinema", "filter_lut_matte_cinema" -> FilmStockProfile(
                id = 5, toeLift = 0.085f, sContrast = 0.94f, shoulderSoft = 0.30f, saturation = 0.86f,
                shadowR = 0.02f, shadowG = 0.022f, shadowB = 0.032f,
                midR = 0.025f, midG = 0.014f, midB = -0.018f,
                highR = 0.030f, highG = 0.020f, highB = -0.012f,
                clarityBoost = 0.06f, grainBoost = 0.06f, vignetteBoost = 0.18f, skinProtect = 0.78f
            )
            "lut_anamorphic_cyan", "filter_lut_anamorphic_cyan" -> FilmStockProfile(
                id = 6, toeLift = 0.014f, sContrast = 1.22f, shoulderSoft = 0.20f, saturation = 1.14f,
                shadowR = -0.08f, shadowG = 0.045f, shadowB = 0.11f,
                midR = -0.02f, midG = 0.025f, midB = 0.055f,
                highR = 0.03f, highG = 0.05f, highB = 0.07f,
                clarityBoost = 0.15f, grainBoost = 0.05f, vignetteBoost = 0.24f, skinProtect = 0.75f
            )
            "lut_midnight_neon", "filter_lut_midnight_neon", "cyberpunk", "cyber" -> FilmStockProfile(
                id = 7, toeLift = 0.008f, sContrast = 1.32f, shoulderSoft = 0.16f, saturation = 1.42f,
                shadowR = 0.03f, shadowG = -0.035f, shadowB = 0.14f,
                midR = 0.07f, midG = -0.025f, midB = 0.09f,
                highR = 0.02f, highG = 0.08f, highB = 0.12f,
                clarityBoost = 0.22f, grainBoost = 0.05f, vignetteBoost = 0.26f, skinProtect = 0.55f
            )
            "lut_warm_documentary", "filter_lut_warm_documentary", "golden_nostalgia", "filter_golden_nostalgia", "sunset_warmth", "filter_sunset_warmth" -> FilmStockProfile(
                id = 8, toeLift = 0.028f, sContrast = 1.12f, shoulderSoft = 0.24f, saturation = 1.18f,
                shadowR = 0.035f, shadowG = 0.010f, shadowB = -0.035f,
                midR = 0.065f, midG = 0.030f, midB = -0.055f,
                highR = 0.085f, highG = 0.048f, highB = -0.045f,
                clarityBoost = 0.12f, grainBoost = 0.055f, vignetteBoost = 0.20f, skinProtect = 0.85f
            )
            "velvia_landscape", "filter_velvia_landscape", "hdr" -> FilmStockProfile(
                id = 9, toeLift = 0.004f, sContrast = 1.28f, shoulderSoft = 0.20f, saturation = 1.38f,
                shadowR = -0.02f, shadowG = 0.015f, shadowB = 0.04f,
                midR = 0.02f, midG = 0.035f, midB = 0.02f,
                highR = 0.03f, highG = 0.02f, highB = 0.0f,
                clarityBoost = 0.26f, grainBoost = 0.02f, vignetteBoost = 0.16f, skinProtect = 0.72f
            )
            "nordic_slate", "filter_nordic_slate", "nordic_blue", "filter_nordic_mood", "arctic_mist", "filter_arctic_mist" -> FilmStockProfile(
                id = 10, toeLift = 0.035f, sContrast = 1.10f, shoulderSoft = 0.25f, saturation = 0.70f,
                shadowR = -0.055f, shadowG = 0.010f, shadowB = 0.085f,
                midR = -0.020f, midG = 0.012f, midB = 0.055f,
                highR = 0.01f, highG = 0.025f, highB = 0.050f,
                clarityBoost = 0.14f, grainBoost = 0.045f, vignetteBoost = 0.20f, skinProtect = 0.76f
            )
            "lut_noir_cinema", "filter_lut_noir_cinema", "high_contrast_noir", "filter_high_contrast_noir", "noir_contrast", "filter_noir_contrast" -> FilmStockProfile(
                id = 11, toeLift = 0.0f, sContrast = 1.62f, shoulderSoft = 0.14f, saturation = 0f,
                shadowR = -0.015f, shadowG = -0.008f, shadowB = 0.015f,
                midR = 0f, midG = 0f, midB = 0f,
                highR = 0.025f, highG = 0.022f, highB = 0.015f,
                clarityBoost = 0.26f, grainBoost = 0.10f, vignetteBoost = 0.32f, isMono = true
            )
            "ilford_grain", "filter_ilford_grain", "classic_mono", "filter_classic_mono", "bw", "black_and_white", "mono", "silver_gelatin", "filter_silver_gelatin" -> FilmStockProfile(
                id = 12, toeLift = 0.018f, sContrast = 1.28f, shoulderSoft = 0.22f, saturation = 0f,
                shadowR = 0.005f, shadowG = 0.005f, shadowB = 0.012f,
                midR = 0.008f, midG = 0.006f, midB = 0.0f,
                highR = 0.02f, highG = 0.016f, highB = 0.008f,
                clarityBoost = 0.20f, grainBoost = 0.12f, vignetteBoost = 0.22f, isMono = true
            )
            "antique_sepia", "filter_antique_sepia", "vintage", "super8_vintage", "filter_super8_retro" -> FilmStockProfile(
                id = 13, toeLift = 0.048f, sContrast = 1.12f, shoulderSoft = 0.26f, saturation = 0.62f,
                shadowR = 0.045f, shadowG = 0.018f, shadowB = -0.045f,
                midR = 0.075f, midG = 0.036f, midB = -0.065f,
                highR = 0.085f, highG = 0.055f, highB = -0.045f,
                clarityBoost = 0.10f, grainBoost = 0.10f, vignetteBoost = 0.28f, skinProtect = 0.70f
            )
            "matrix_code", "filter_matrix_code" -> FilmStockProfile(
                id = 14, toeLift = 0.006f, sContrast = 1.28f, shoulderSoft = 0.18f, saturation = 0.78f,
                shadowR = -0.06f, shadowG = 0.065f, shadowB = -0.03f,
                midR = -0.04f, midG = 0.08f, midB = -0.02f,
                highR = -0.02f, highG = 0.09f, highB = 0.01f,
                clarityBoost = 0.20f, grainBoost = 0.05f, vignetteBoost = 0.24f, skinProtect = 0.30f
            )
            "pastel_dream", "filter_pastel_dream" -> FilmStockProfile(
                id = 15, toeLift = 0.075f, sContrast = 0.90f, shoulderSoft = 0.32f, saturation = 0.84f,
                shadowR = 0.035f, shadowG = 0.020f, shadowB = 0.055f,
                midR = 0.040f, midG = 0.025f, midB = 0.045f,
                highR = 0.045f, highG = 0.035f, highB = 0.055f,
                clarityBoost = 0.04f, grainBoost = 0.04f, vignetteBoost = 0.10f, skinProtect = 0.85f
            )
            "lut_rec709", "filter_lut_rec709" -> FilmStockProfile(
                id = 16, toeLift = 0.008f, sContrast = 1.15f, shoulderSoft = 0.22f, saturation = 1.10f,
                shadowR = 0f, shadowG = 0f, shadowB = 0.01f,
                midR = 0.01f, midG = 0.005f, midB = 0f,
                highR = 0.01f, highG = 0.01f, highB = 0f,
                clarityBoost = 0.14f, grainBoost = 0.0f, vignetteBoost = 0.08f, skinProtect = 0.85f
            )
            "lut_flat_log", "filter_lut_flat_log" -> FilmStockProfile(
                id = 17, toeLift = 0.095f, sContrast = 0.72f, shoulderSoft = 0.35f, saturation = 0.65f,
                shadowR = 0f, shadowG = 0f, shadowB = 0f,
                midR = 0f, midG = 0f, midB = 0f,
                highR = 0f, highG = 0f, highB = 0f,
                clarityBoost = 0.0f, grainBoost = 0.0f, vignetteBoost = 0.0f, skinProtect = 0.90f
            )
            else -> FilmStockProfile(
                id = 0, toeLift = 0.0f, sContrast = 1.0f, shoulderSoft = 0.12f, saturation = 1.0f,
                shadowR = 0f, shadowG = 0f, shadowB = 0f,
                midR = 0f, midG = 0f, midB = 0f,
                highR = 0f, highG = 0f, highB = 0f,
                clarityBoost = 0.0f, grainBoost = 0.0f, vignetteBoost = 0.0f, skinProtect = 0.50f
            )
        }
    }

    companion object {
        private const val TAG = "ProGpuPhotoRenderer"

        private const val VS_QUAD = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUv = aUv;
            }
        """

        private const val FS_COPY = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vUv);
            }
        """

        // 9-tap Separable Gaussian Blur (used for Clarity, Structure Tensor smoothing, Pyramid Bloom)
        private const val FS_SEPARABLE_BLUR = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            void main() {
                vec4 acc = texture2D(uTex, vUv) * 0.227027;
                acc += texture2D(uTex, clamp(vUv + uDir * 1.384615, 0.0, 1.0)) * 0.316216;
                acc += texture2D(uTex, clamp(vUv - uDir * 1.384615, 0.0, 1.0)) * 0.316216;
                acc += texture2D(uTex, clamp(vUv + uDir * 3.230769, 0.0, 1.0)) * 0.070270;
                acc += texture2D(uTex, clamp(vUv - uDir * 3.230769, 0.0, 1.0)) * 0.070270;
                gl_FragColor = acc;
            }
        """

        // 11-tap Separable Edge-Preserving Bilateral Abstraction (Perceptual Luma + Chroma weighting)
        private const val FS_BILATERAL = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            uniform vec2 uSigmas;
            void main() {
                vec3 center = texture2D(uTex, vUv).rgb;
                float invTwoColor = 1.0 / max(2.0 * uSigmas.y * uSigmas.y, 0.001);
                vec3 acc = center;
                float wSum = 1.0;
                for (int k = -5; k <= 5; k++) {
                    if (k == 0) continue;
                    float fk = float(k);
                    vec3 s = texture2D(uTex, clamp(vUv + uDir * (fk * uSigmas.x * 0.65), 0.0, 1.0)).rgb;
                    vec3 diff = s - center;
                    // Perceptual weight emphasizes luminance + green fidelity for silky skin preservation
                    float dc2 = dot(diff * vec3(0.85, 1.15, 0.80), diff * vec3(0.85, 1.15, 0.80));
                    float w = exp(-fk * fk * 0.14 - dc2 * invTwoColor);
                    acc += s * w;
                    wSum += w;
                }
                gl_FragColor = vec4(acc / max(wSum, 0.001), 1.0);
            }
        """

        // Structure Tensor Pass: computes Sobel (gx, gy) -> packs (gx*gx, gy*gy, gx*gy + 0.5, luma)
        private const val FS_STRUCTURE_TENSOR = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            void main() {
                vec3 tl = texture2D(uTex, clamp(vUv + vec2(-uTexel.x, -uTexel.y), 0.0, 1.0)).rgb;
                vec3 tc = texture2D(uTex, clamp(vUv + vec2( 0.0,      -uTexel.y), 0.0, 1.0)).rgb;
                vec3 tr = texture2D(uTex, clamp(vUv + vec2( uTexel.x, -uTexel.y), 0.0, 1.0)).rgb;
                vec3 ml = texture2D(uTex, clamp(vUv + vec2(-uTexel.x,  0.0     ), 0.0, 1.0)).rgb;
                vec3 mc = texture2D(uTex, vUv).rgb;
                vec3 mr = texture2D(uTex, clamp(vUv + vec2( uTexel.x,  0.0     ), 0.0, 1.0)).rgb;
                vec3 bl = texture2D(uTex, clamp(vUv + vec2(-uTexel.x,  uTexel.y), 0.0, 1.0)).rgb;
                vec3 bc = texture2D(uTex, clamp(vUv + vec2( 0.0,       uTexel.y), 0.0, 1.0)).rgb;
                vec3 br = texture2D(uTex, clamp(vUv + vec2( uTexel.x,  uTexel.y), 0.0, 1.0)).rgb;
                vec3 gx = (-tl - 2.0 * ml - bl + tr + 2.0 * mr + br) * 0.25;
                vec3 gy = (-tl - 2.0 * tc - tr + bl + 2.0 * bc + br) * 0.25;
                float jx = clamp(dot(gx, gx) * 2.4, 0.0, 1.0);
                float jy = clamp(dot(gy, gy) * 2.4, 0.0, 1.0);
                float jxy = clamp(dot(gx, gy) * 2.4 * 0.5 + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(jx, jy, jxy, luma(mc));
            }
        """

        // Flow-Based Difference of Gaussians (FDoG Pass 1: Gradient-Normal DoG + Color Vector Edge)
        private const val FS_FLOW_DOG = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTexColor;
            uniform sampler2D uTexTensor;
            uniform vec2 uTexel;
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            void main() {
                vec4 t = texture2D(uTexTensor, vUv);
                float E = t.r;
                float G = t.g;
                float F = (t.b - 0.5) * 2.0;
                float disc = sqrt(max((E - G) * (E - G) + 4.0 * F * F, 0.0));
                float lambda1 = 0.5 * (E + G + disc);
                vec2 gradDir = vec2(lambda1 - G, F);
                float gl = length(gradDir);
                vec2 nDir = (gl > 0.0005) ? (gradDir / gl) : vec2(1.0, 0.0);

                // 1D Difference of Gaussians perpendicular to contour tangent (sharp ink stroke across edge)
                float c0 = luma(texture2D(uTexColor, vUv).rgb);
                float fineAcc = c0 * 0.44;
                float wideAcc = c0 * 0.24;
                float c1p = luma(texture2D(uTexColor, clamp(vUv + nDir * uTexel * 1.0, 0.0, 1.0)).rgb);
                float c1m = luma(texture2D(uTexColor, clamp(vUv - nDir * uTexel * 1.0, 0.0, 1.0)).rgb);
                float c2p = luma(texture2D(uTexColor, clamp(vUv + nDir * uTexel * 2.2, 0.0, 1.0)).rgb);
                float c2m = luma(texture2D(uTexColor, clamp(vUv - nDir * uTexel * 2.2, 0.0, 1.0)).rgb);
                float c3p = luma(texture2D(uTexColor, clamp(vUv + nDir * uTexel * 3.6, 0.0, 1.0)).rgb);
                float c3m = luma(texture2D(uTexColor, clamp(vUv - nDir * uTexel * 3.6, 0.0, 1.0)).rgb);

                fineAcc += (c1p + c1m) * 0.28;
                wideAcc += (c1p + c1m) * 0.19 + (c2p + c2m) * 0.13 + (c3p + c3m) * 0.06;

                // Dark-side contour response (DoG) + subtle color-boundary response for chrominance silhouettes
                float dogResp = max(0.0, (wideAcc - fineAcc * 0.985) * 8.5);
                vec3 colP = texture2D(uTexColor, clamp(vUv + nDir * uTexel * 1.25, 0.0, 1.0)).rgb;
                vec3 colM = texture2D(uTexColor, clamp(vUv - nDir * uTexel * 1.25, 0.0, 1.0)).rgb;
                float chromaEdge = length(colP - colM) * 0.85;
                float edgeStrength = clamp(max(dogResp, chromaEdge), 0.0, 1.0);
                gl_FragColor = vec4(edgeStrength, chromaEdge, dogResp, 1.0);
            }
        """

        // Flow-Based Coherent Line Integration (FDoG Pass 2: smooths ink ALONG contour tangent -> hand-inked strokes!)
        private const val FS_FLOW_SMOOTH = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTexDog;
            uniform sampler2D uTexTensor;
            uniform vec2 uTexel;
            vec2 tangentAt(vec2 uv) {
                vec4 t = texture2D(uTexTensor, uv);
                float E = t.r;
                float G = t.g;
                float F = (t.b - 0.5) * 2.0;
                float disc = sqrt(max((E - G) * (E - G) + 4.0 * F * F, 0.0));
                float lambda1 = 0.5 * (E + G + disc);
                vec2 v = vec2(F, -(lambda1 - G));
                float len = length(v);
                return (len > 0.0005) ? (v / len) : vec2(0.0, 1.0);
            }
            void main() {
                float acc = texture2D(uTexDog, vUv).r * 0.28;
                float wSum = 0.28;
                vec2 pPos = vUv;
                vec2 pNeg = vUv;
                vec2 tPos = tangentAt(vUv);
                vec2 tNeg = -tPos;
                for (int k = 1; k <= 4; k++) {
                    float w = exp(-float(k * k) * 0.14);
                    pPos = clamp(pPos + tPos * uTexel, 0.0, 1.0);
                    pNeg = clamp(pNeg + tNeg * uTexel, 0.0, 1.0);
                    vec2 nextP = tangentAt(pPos);
                    if (dot(nextP, tPos) < 0.0) nextP = -nextP;
                    tPos = normalize(mix(tPos, nextP, 0.55));
                    vec2 nextN = tangentAt(pNeg);
                    if (dot(nextN, tNeg) < 0.0) nextN = -nextN;
                    tNeg = normalize(mix(tNeg, nextN, 0.55));
                    acc += (texture2D(uTexDog, pPos).r + texture2D(uTexDog, pNeg).r) * w;
                    wSum += 2.0 * w;
                }
                float coherent = acc / max(wSum, 0.001);
                gl_FragColor = vec4(coherent, coherent, coherent, 1.0);
            }
        """

        // Anisotropic Flow-Aligned Kuwahara Filter (Oil Paint / Watercolor / Pastel curved brushstrokes!)
        private const val FS_ANISO_KUWAHARA = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTexColor;
            uniform sampler2D uTexTensor;
            uniform vec2 uTexel;
            uniform float uRadius;
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            void main() {
                vec4 t = texture2D(uTexTensor, vUv);
                float E = t.r;
                float G = t.g;
                float F = (t.b - 0.5) * 2.0;
                float disc = sqrt(max((E - G) * (E - G) + 4.0 * F * F, 0.0));
                float lambda1 = 0.5 * (E + G + disc);
                float lambda2 = max(0.0, 0.5 * (E + G - disc));
                float aniso = clamp((lambda1 - lambda2) / (lambda1 + lambda2 + 0.002), 0.0, 0.85);
                vec2 tang = vec2(F, -(lambda1 - G));
                float tl = length(tang);
                vec2 tDir = (tl > 0.0005) ? (tang / tl) : vec2(1.0, 0.0);
                vec2 nDir = vec2(-tDir.y, tDir.x);

                // Stretch ellipse along tangent flow and compress across edge -> crisp curved brushstrokes!
                float scaleT = uRadius * (1.0 + aniso * 0.95);
                float scaleN = uRadius * (1.0 - aniso * 0.42);

                vec3 m0 = vec3(0.0); vec3 m1 = vec3(0.0); vec3 m2 = vec3(0.0); vec3 m3 = vec3(0.0);
                float q0 = 0.0; float q1 = 0.0; float q2 = 0.0; float q3 = 0.0;
                for (int dy = -2; dy <= 0; dy++) {
                    for (int dx = -2; dx <= 0; dx++) {
                        float fx = float(dx);
                        float fy = float(dy);
                        vec2 off0 = (tDir * (fx * scaleT) + nDir * (fy * scaleN)) * uTexel;
                        vec2 off1 = (tDir * (-fx * scaleT) + nDir * (fy * scaleN)) * uTexel;
                        vec2 off2 = (tDir * (fx * scaleT) + nDir * (-fy * scaleN)) * uTexel;
                        vec2 off3 = (tDir * (-fx * scaleT) + nDir * (-fy * scaleN)) * uTexel;

                        vec3 c0 = texture2D(uTexColor, clamp(vUv + off0, 0.0, 1.0)).rgb;
                        vec3 c1 = texture2D(uTexColor, clamp(vUv + off1, 0.0, 1.0)).rgb;
                        vec3 c2 = texture2D(uTexColor, clamp(vUv + off2, 0.0, 1.0)).rgb;
                        vec3 c3 = texture2D(uTexColor, clamp(vUv + off3, 0.0, 1.0)).rgb;

                        m0 += c0; float l0 = luma(c0); q0 += l0 * l0;
                        m1 += c1; float l1 = luma(c1); q1 += l1 * l1;
                        m2 += c2; float l2 = luma(c2); q2 += l2 * l2;
                        m3 += c3; float l3 = luma(c3); q3 += l3 * l3;
                    }
                }
                m0 /= 9.0; m1 /= 9.0; m2 /= 9.0; m3 /= 9.0;
                float v0 = max(0.0, q0 / 9.0 - pow(luma(m0), 2.0));
                float v1 = max(0.0, q1 / 9.0 - pow(luma(m1), 2.0));
                float v2 = max(0.0, q2 / 9.0 - pow(luma(m2), 2.0));
                float v3 = max(0.0, q3 / 9.0 - pow(luma(m3), 2.0));
                float w0 = 1.0 / (1.0 + v0 * v0 * 3200.0);
                float w1 = 1.0 / (1.0 + v1 * v1 * 3200.0);
                float w2 = 1.0 / (1.0 + v2 * v2 * 3200.0);
                float w3 = 1.0 / (1.0 + v3 * v3 * 3200.0);
                vec3 painted = (m0 * w0 + m1 * w1 + m2 * w2 + m3 * w3) / max(w0 + w1 + w2 + w3, 0.001);
                gl_FragColor = vec4(painted, 1.0);
            }
        """

        // Highlight Specular Extractor for Halation & Anamorphic Optical Bloom Pyramid
        private const val FS_HIGHLIGHT_EXTRACT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uThreshold;
            uniform vec3 uTint;
            void main() {
                vec3 c = texture2D(uTex, vUv).rgb;
                float lum = dot(c, vec3(0.299, 0.587, 0.114));
                float bright = smoothstep(uThreshold, min(0.995, uThreshold + 0.22), lum);
                float energy = bright * bright;
                gl_FragColor = vec4(clamp(c * uTint * energy * 1.45, 0.0, 1.0), 1.0);
            }
        """

        // PILLAR 1: PRO FILMIC & CINEMA FILM STOCK SHADER
        private const val FS_FILM_GRADE = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTexOrig;
            uniform sampler2D uTexSmooth;
            uniform sampler2D uTexBloom;
            uniform float uUseColorMat;
            uniform mat3 uColorMat;
            uniform vec3 uColorOff;
            uniform vec4 uCurveParams;  // toeLift, sContrast, shoulderSoft, saturation
            uniform vec3 uShadowTint;
            uniform vec3 uMidTint;
            uniform vec3 uHighTint;
            uniform vec4 uDetailParams; // clarity, grain, vignette, brightness
            uniform vec4 uExtraParams;  // isMono, skinProtect, halationAmt, flareAmt
            uniform vec2 uTexSize;
            uniform float uSeed;

            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

            // Soft filmic S-curve with matte toe lift & creamy highlight shoulder roll-off
            vec3 filmCurve(vec3 x, float toe, float con, float shoulder) {
                vec3 c = clamp(x, 0.0, 1.0);
                // S-curve around perceptual midpoint 0.48
                vec3 centered = c - 0.48;
                vec3 s = clamp(0.48 + centered * con / (1.0 + abs(centered) * (con - 1.0) * 0.55), 0.0, 1.0);
                // Highlight shoulder compression (prevents digital harsh white clipping)
                vec3 softHi = s / (1.0 + shoulder * max(vec3(0.0), s - 0.55));
                softHi = softHi * (1.0 + shoulder * 0.45);
                // Matte film toe lift
                return clamp(toe + softHi * (1.0 - toe), 0.0, 1.0);
            }

            // Soft organic multi-frequency silver-halide film grain
            float analogGrain(vec2 uv, float seed) {
                vec2 p = uv * uTexSize * 0.48;
                float n1 = fract(sin(dot(p + seed * 13.7, vec2(127.1, 311.7))) * 43758.5453);
                float n2 = fract(sin(dot(p * 0.52 - seed * 7.3, vec2(269.5, 183.3))) * 43758.5453);
                return (n1 * 0.62 + n2 * 0.38) - 0.5;
            }

            void main() {
                vec3 orig = texture2D(uTexOrig, vUv).rgb;
                vec3 smoothC = texture2D(uTexSmooth, vUv).rgb;

                // 1. Optical Local Clarity (midtone-weighted micro-contrast without halos)
                float origLum = luma(orig);
                float midWeight = 4.0 * origLum * (1.0 - origLum);
                vec3 detail = orig - smoothC;
                vec3 c = clamp(orig + detail * (uDetailParams.x * (0.45 + 0.75 * midWeight)), 0.0, 1.0);

                // 2. Brightness lift before tone curve
                c = clamp(c + vec3(uDetailParams.w), 0.0, 1.0);

                if (uUseColorMat > 0.5) {
                    c = clamp(uColorMat * c + uColorOff, 0.0, 1.0);
                    c = filmCurve(c, 0.0, uCurveParams.y, 0.14);
                } else {
                    // Skin-tone protection mask (warm peach/orange band stays natural under teal/cool grades)
                    float skinScore = smoothstep(0.02, 0.20, c.r - c.b) *
                                      smoothstep(-0.02, 0.14, c.r - c.g) *
                                      smoothstep(0.14, 0.35, origLum) *
                                      (1.0 - smoothstep(0.76, 0.94, origLum));
                    float skinKeep = skinScore * uExtraParams.y;

                    // 3. Filmic Non-Linear Toe / S-Curve / Highlight Shoulder
                    c = filmCurve(c, uCurveParams.x, uCurveParams.y, uCurveParams.z);

                    // 4. 3-Way Perceptual Split Toning (Shadow / Midtone / Highlight)
                    float l = luma(c);
                    float wShadow = pow(clamp(1.0 - l * 1.45, 0.0, 1.0), 1.6);
                    float wHigh = pow(clamp((l - 0.35) * 1.54, 0.0, 1.0), 1.5);
                    float wMid = clamp(1.0 - wShadow - wHigh, 0.0, 1.0);
                    vec3 splitShift = uShadowTint * wShadow * (1.0 - skinKeep * 0.65)
                                    + uMidTint * wMid * (1.0 - skinKeep * 0.35)
                                    + uHighTint * wHigh * (1.0 - skinKeep * 0.45);
                    c = clamp(c + splitShift, 0.0, 1.0);

                    // 5. Saturation / Monochrome Silver-Gelatin Channel Mix
                    if (uExtraParams.x > 0.5) {
                        // Classic panchromatic red-weighted B&W mix (brightens skin, deepens blue skies!)
                        float bw = dot(c, vec3(0.36, 0.54, 0.10));
                        c = clamp(vec3(bw) + splitShift * 0.6, 0.0, 1.0);
                    } else {
                        float lg = luma(c);
                        float effSat = mix(uCurveParams.w, min(uCurveParams.w, 1.12), skinKeep * 0.6);
                        c = clamp(mix(vec3(lg), c, effSat), 0.0, 1.0);
                    }
                }

                // 6. Optional Real Highlight Bloom (Halation or Anamorphic Streak)
                if (uExtraParams.z > 0.01 || uExtraParams.w > 0.01) {
                    vec3 bloom = texture2D(uTexBloom, vUv).rgb;
                    float amt = max(uExtraParams.z, uExtraParams.w);
                    c = 1.0 - (1.0 - c) * (1.0 - clamp(bloom * amt * 1.25, 0.0, 0.92));
                }

                // 7. Optical Cosine^4 Lens Vignette (preserves corner color richness)
                if (uDetailParams.z > 0.01) {
                    vec2 d = (vUv - 0.5) * 1.32;
                    float cosTheta = 1.0 / sqrt(1.0 + dot(d, d));
                    float falloff = pow(cosTheta, 3.6);
                    float vMix = mix(1.0, falloff, uDetailParams.z);
                    c *= vMix;
                }

                // 8. Luminance-Weighted 35mm Analog Micro-Grain (peak in midtones, clean blacks/highlights)
                if (uDetailParams.y > 0.005) {
                    float gLum = luma(c);
                    float gMask = 0.25 + 0.95 * (4.0 * gLum * (1.0 - gLum));
                    float gVal = analogGrain(vUv, uSeed) * uDetailParams.y * gMask;
                    c = clamp(c + vec3(gVal), 0.0, 1.0);
                }

                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """

        // PILLAR 2: PRO OPTICAL & CINEMA EFFECTS COMPOSITE (37-Tap Specular Disc Bokeh + Pyramid Bloom)
        private const val FS_OPTICAL_COMPOSITE = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTexOrig;
            uniform sampler2D uTexAux;
            uniform sampler2D uTexMask;
            uniform int uOptMode;
            uniform float uIntensity;
            uniform float uHasMask;
            uniform vec2 uTexSize;
            uniform float uSeed;

            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

            float analogGrain(vec2 uv, float seed) {
                vec2 p = uv * uTexSize * 0.48;
                float n1 = fract(sin(dot(p + seed * 19.3, vec2(127.1, 311.7))) * 43758.5453);
                float n2 = fract(sin(dot(p * 0.54 - seed * 11.1, vec2(269.5, 183.3))) * 43758.5453);
                return (n1 * 0.62 + n2 * 0.38) - 0.5;
            }

            // 37-Tap Golden-Angle Optical Disc Bokeh with Specular Highlight Expansion (creamy f/1.4 bokeh orbs!)
            vec3 specularDiscBokeh(vec2 uv, float maxRadiusPx) {
                vec2 invSize = 1.0 / uTexSize;
                vec3 acc = vec3(0.0);
                float wSum = 0.0;
                float goldenAngle = 2.39996323;
                for (int i = 0; i < 37; i++) {
                    float fi = float(i);
                    float r = sqrt((fi + 0.5) / 37.0) * maxRadiusPx;
                    float ang = fi * goldenAngle;
                    vec2 off = vec2(cos(ang), sin(ang)) * r * invSize;
                    vec3 s = clamp(texture2D(uTexOrig, clamp(uv + off, 0.0, 1.0)).rgb, 0.001, 1.0);
                    // Power-law specular bloom makes bright background lights expand into true creamy bokeh discs
                    float specW = 1.0 + pow(luma(s), 4.0) * 4.2;
                    acc += pow(s, vec3(2.4)) * specW;
                    wSum += specW;
                }
                return pow(clamp(acc / max(wSum, 0.001), 0.0001, 1.0), vec3(1.0 / 2.4));
            }

            void main() {
                vec3 orig = texture2D(uTexOrig, vUv).rgb;
                vec3 outC = orig;

                if (uOptMode == 1) {
                    // Cinestill 800T Real Highlight Halation Bloom
                    vec3 halo = texture2D(uTexAux, vUv).rgb;
                    outC = 1.0 - (1.0 - orig) * (1.0 - clamp(halo * (1.35 * uIntensity), 0.0, 0.94));
                    // Subtle warm cinema lift
                    outC = clamp(outC + halo * vec3(0.18, 0.04, 0.01) * uIntensity, 0.0, 1.0);
                } else if (uOptMode == 2) {
                    // Optical Anamorphic Blue Lens Flare from real light sources
                    vec3 streak = texture2D(uTexAux, vUv).rgb;
                    outC = 1.0 - (1.0 - orig) * (1.0 - clamp(streak * (1.55 * uIntensity), 0.0, 0.95));
                } else if (uOptMode == 3) {
                    // Portrait / Video Optical Disc Bokeh (MediaPipe Subject Mask aware!)
                    float blurFactor;
                    if (uHasMask > 0.5) {
                        // Subject mask alpha/red = 1.0 on person, 0.0 on background
                        float subj = texture2D(uTexMask, vUv).a;
                        blurFactor = smoothstep(0.68, 0.22, subj);
                    } else {
                        float d = length((vUv - vec2(0.5, 0.44)) * vec2(1.0, 1.12));
                        blurFactor = smoothstep(0.24, 0.58, d);
                    }
                    float maxR = min(uTexSize.x, uTexSize.y) * 0.018 * uIntensity;
                    vec3 bokeh = specularDiscBokeh(vUv, maxR * blurFactor);
                    outC = mix(orig, bokeh, clamp(blurFactor * uIntensity, 0.0, 1.0));
                } else if (uOptMode == 4) {
                    // Optical Tilt-Shift Miniature Lens (Disc Bokeh + vibrant miniature saturation boost)
                    float band = abs(vUv.y - 0.5);
                    float blurFactor = smoothstep(0.13, 0.38, band);
                    float maxR = min(uTexSize.x, uTexSize.y) * 0.016 * uIntensity;
                    vec3 bokeh = specularDiscBokeh(vUv, maxR * blurFactor);
                    vec3 blended = mix(orig, bokeh, blurFactor);
                    float lg = luma(blended);
                    outC = clamp(mix(vec3(lg), blended, 1.0 + 0.22 * uIntensity), 0.0, 1.0);
                } else if (uOptMode == 5) {
                    // Real Corner-Weighted Radial Prism Dispersion (5-band optical spectrum)
                    vec2 d = vUv - 0.5;
                    float rad = length(d);
                    vec2 dir = d * (0.028 * uIntensity * (0.35 + rad * 1.4));
                    float r = texture2D(uTexOrig, clamp(vUv + dir * 1.15, 0.0, 1.0)).r;
                    float y = texture2D(uTexOrig, clamp(vUv + dir * 0.55, 0.0, 1.0)).r * 0.35 +
                              texture2D(uTexOrig, clamp(vUv + dir * 0.55, 0.0, 1.0)).g * 0.65;
                    float g = texture2D(uTexOrig, vUv).g;
                    float c = texture2D(uTexOrig, clamp(vUv - dir * 0.55, 0.0, 1.0)).g * 0.5 +
                              texture2D(uTexOrig, clamp(vUv - dir * 0.55, 0.0, 1.0)).b * 0.5;
                    float b = texture2D(uTexOrig, clamp(vUv - dir * 1.15, 0.0, 1.0)).b;
                    outC = clamp(vec3(r * 0.78 + y * 0.22, g * 0.72 + y * 0.14 + c * 0.14, b * 0.78 + c * 0.22) * 1.04, 0.0, 1.0);
                } else if (uOptMode == 6) {
                    // Cosine^4 Natural Optical Lens Vignette
                    vec2 d = (vUv - 0.5) * 1.42;
                    float cosT = 1.0 / sqrt(1.0 + dot(d, d));
                    float falloff = pow(cosT, 4.2);
                    outC = orig * mix(1.0, falloff, 0.85 * uIntensity);
                } else if (uOptMode == 7) {
                    // Real 35mm Silver-Halide Film Grain
                    float gLum = luma(orig);
                    float gMask = 0.28 + 0.95 * (4.0 * gLum * (1.0 - gLum));
                    float g = analogGrain(vUv, uSeed) * (0.20 * uIntensity) * gMask;
                    outC = clamp(orig + vec3(g), 0.0, 1.0);
                } else if (uOptMode == 8) {
                    // Authentic Super 8mm Vintage Film (Warm Kodachrome curve + Halation + Fine Grain + Rounded Gate)
                    vec3 halo = texture2D(uTexAux, vUv).rgb;
                    vec3 c = orig;
                    float l = luma(c);
                    c = mix(vec3(l), c, 1.0 - 0.14 * uIntensity);
                    c = clamp(c * vec3(1.08, 1.01, 0.86) + vec3(0.035, 0.018, -0.025) * uIntensity, 0.0, 1.0);
                    c = 1.0 - (1.0 - c) * (1.0 - clamp(halo * (0.85 * uIntensity), 0.0, 0.75));
                    // Organic warm edge light leak
                    float leak = exp(-length((vUv - vec2(0.95, 0.18)) * vec2(1.4, 0.9)) * 2.8) * 0.34 * uIntensity;
                    c = clamp(c + vec3(1.0, 0.44, 0.12) * leak, 0.0, 1.0);
                    // Analog grain
                    float gMask = 0.3 + 0.9 * (4.0 * l * (1.0 - l));
                    c = clamp(c + vec3(analogGrain(vUv, uSeed) * 0.16 * uIntensity * gMask), 0.0, 1.0);
                    // Soft optical Super 8 rounded film gate
                    vec2 q = abs(vUv - 0.5) - vec2(0.455, 0.465);
                    float gateDist = length(max(q, 0.0)) - 0.035;
                    float gateMask = 1.0 - smoothstep(0.0, 0.008, gateDist) * uIntensity;
                    outC = c * gateMask;
                } else if (uOptMode == 9) {
                    // 16mm Documentary Film (Filmic S-curve + cool shadows + fine 16mm grain + subtle vignette)
                    float l = luma(orig);
                    vec3 c = mix(vec3(l), orig, 1.0 - 0.14 * uIntensity);
                    c = clamp((c - 0.48) * (1.0 + 0.18 * uIntensity) + 0.50, 0.0, 1.0);
                    c = clamp(c + vec3(0.015, 0.010, -0.010) * uIntensity, 0.0, 1.0);
                    float gMask = 0.25 + 0.95 * (4.0 * l * (1.0 - l));
                    c = clamp(c + vec3(analogGrain(vUv, uSeed) * 0.14 * uIntensity * gMask), 0.0, 1.0);
                    vec2 d = (vUv - 0.5) * 1.3;
                    float v = pow(1.0 / sqrt(1.0 + dot(d, d)), 3.2);
                    outC = c * mix(1.0, v, 0.55 * uIntensity);
                } else if (uOptMode == 10) {
                    // Analog VHS Tape (Chroma bandwidth smear + tracking jitter + magnetic noise)
                    float jitter = (fract(sin(floor(vUv.y * 180.0) * 91.7 + uSeed * 41.0) * 43758.5453) - 0.5) * 0.004 * uIntensity;
                    vec2 u = clamp(vUv + vec2(jitter, 0.0), 0.0, 1.0);
                    float yVal = luma(texture2D(uTexOrig, u).rgb);
                    vec3 cL = texture2D(uTexOrig, clamp(u - vec2(0.008 * uIntensity, 0.0), 0.0, 1.0)).rgb;
                    vec3 cR = texture2D(uTexOrig, clamp(u + vec2(0.008 * uIntensity, 0.0), 0.0, 1.0)).rgb;
                    vec3 smeared = (cL + cR) * 0.5;
                    vec3 chroma = smeared - vec3(luma(smeared));
                    vec3 vhs = clamp(vec3(yVal) + chroma * 1.18, 0.0, 1.0);
                    float scan = 0.93 + 0.07 * sin(vUv.y * uTexSize.y * 1.4);
                    float trackBand = smoothstep(0.018, 0.0, abs(vUv.y - 0.88)) * (0.28 * uIntensity);
                    float snow = analogGrain(vUv, uSeed) * (0.12 + trackBand * 1.6) * uIntensity;
                    outC = clamp(vhs * scan + vec3(snow + trackBand * 0.35), 0.0, 1.0);
                } else if (uOptMode == 11) {
                    // Phosphor CRT Monitor (RGB aperture grille + subtle barrel curvature + phosphor bloom)
                    vec2 dc = vUv - 0.5;
                    vec2 curved = vUv + dc * dot(dc, dc) * (0.16 * uIntensity);
                    if (curved.x < 0.0 || curved.x > 1.0 || curved.y < 0.0 || curved.y > 1.0) {
                        outC = vec3(0.01, 0.015, 0.03);
                    } else {
                        vec3 c = texture2D(uTexOrig, curved).rgb;
                        float scan = 1.0 - (0.26 * uIntensity) * (0.5 + 0.5 * sin(curved.y * min(uTexSize.y, 720.0) * 2.4));
                        float rMask = 0.88 + 0.24 * sin(curved.x * min(uTexSize.x, 720.0) * 3.0);
                        float gMask = 0.88 + 0.24 * sin(curved.x * min(uTexSize.x, 720.0) * 3.0 + 2.094);
                        float bMask = 0.88 + 0.24 * sin(curved.x * min(uTexSize.x, 720.0) * 3.0 + 4.188);
                        vec3 mask = mix(vec3(1.0), vec3(rMask, gMask, bMask), 0.55 * uIntensity);
                        float vig = clamp(1.06 - dot(dc, dc) * 1.65, 0.15, 1.0);
                        outC = clamp(c * mask * scan * mix(1.0, vig, uIntensity), 0.0, 1.0);
                    }
                }

                gl_FragColor = vec4(clamp(outC, 0.0, 1.0), 1.0);
            }
        """

        // PILLAR 3: PRO FINE ART MEDIUM PHYSICS COMPOSITE (Oil Impasto / Watercolor Pooling / Charcoal Color-Dodge / etc.)
        private const val FS_ART_COMPOSITE = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTexOrig;
            uniform sampler2D uTexPaint;
            uniform sampler2D uTexTensor;
            uniform sampler2D uTexEdge;
            uniform int uArtStyle;
            uniform float uIntensity;
            uniform vec2 uTexSize;
            uniform vec2 uHalftoneParams;

            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

            // Procedural multi-octave paper / canvas weave
            float canvasWeave(vec2 uv) {
                vec2 p = uv * uTexSize * 0.28;
                float warp = sin(p.x * 1.6 + sin(p.y * 0.22) * 0.6);
                float weft = sin(p.y * 1.6 + sin(p.x * 0.22) * 0.6);
                return (warp * 0.5 + weft * 0.5) * 0.5;
            }

            float paperTooth(vec2 uv) {
                vec2 p = uv * uTexSize * 0.32;
                float a = fract(sin(dot(floor(p), vec2(127.1, 311.7))) * 43758.5453);
                float b = fract(sin(dot(floor(p * 0.45), vec2(269.5, 183.3))) * 43758.5453);
                return (a * 0.55 + b * 0.45) - 0.5;
            }

            float rotatedDot(vec2 uv, float darkness, float angleRad, float pitchPx) {
                vec2 p = uv * uTexSize / max(pitchPx, 2.0);
                float ca = cos(angleRad);
                float sa = sin(angleRad);
                vec2 rot = vec2(p.x * ca - p.y * sa, p.x * sa + p.y * ca);
                vec2 cell = fract(rot) - 0.5;
                float rad = sqrt(clamp(darkness, 0.0, 1.0)) * 0.52 * uHalftoneParams.y;
                return 1.0 - smoothstep(rad - 0.08, rad + 0.08, length(cell));
            }

            void main() {
                vec3 orig = texture2D(uTexOrig, vUv).rgb;
                vec3 paint = texture2D(uTexPaint, vUv).rgb;
                vec4 tensor = texture2D(uTexTensor, vUv);
                float edge = texture2D(uTexEdge, vUv).r;
                float pLum = luma(paint);
                vec2 invSize = 1.0 / uTexSize;

                // Extract local flow tangent for flow-aligned impasto bristles & cross-hatching
                float E = tensor.r;
                float G = tensor.g;
                float F = (tensor.b - 0.5) * 2.0;
                float disc = sqrt(max((E - G) * (E - G) + 4.0 * F * F, 0.0));
                float lambda1 = 0.5 * (E + G + disc);
                vec2 tang = vec2(F, -(lambda1 - G));
                float tl = length(tang);
                vec2 tDir = (tl > 0.0005) ? (tang / tl) : vec2(1.0, 0.0);
                vec2 nDir = vec2(-tDir.y, tDir.x);

                vec3 styled = paint;

                if (uArtStyle == 1) {
                    // MUSEUM OIL PAINTING: Flow-Aligned Kuwahara + 3D Impasto Relief Lighting + Linen Canvas
                    vec2 eps = invSize * 2.4;
                    float lL = luma(texture2D(uTexPaint, clamp(vUv - vec2(eps.x, 0.0), 0.0, 1.0)).rgb);
                    float lR = luma(texture2D(uTexPaint, clamp(vUv + vec2(eps.x, 0.0), 0.0, 1.0)).rgb);
                    float lU = luma(texture2D(uTexPaint, clamp(vUv - vec2(0.0, eps.y), 0.0, 1.0)).rgb);
                    float lD = luma(texture2D(uTexPaint, clamp(vUv + vec2(0.0, eps.y), 0.0, 1.0)).rgb);
                    // Flow-aligned bristle groove normal perturbation
                    float flowCoord = dot(vUv * uTexSize * 0.24, nDir);
                    float bristle = sin(flowCoord * 3.1 + sin(dot(vUv * uTexSize * 0.06, tDir)) * 2.2) * 0.022;
                    vec3 bumpN = normalize(vec3((lL - lR) * 4.8, (lU - lD) * 4.8, 0.65));
                    vec3 lightDir = normalize(vec3(-0.48, -0.58, 0.66));
                    vec3 halfDir = normalize(lightDir + vec3(0.0, 0.0, 1.0));
                    float impastoDiff = dot(bumpN, lightDir) * 0.16;
                    float impastoSpec = pow(max(0.0, dot(bumpN, halfDir)), 28.0) * 0.14;
                    float linen = canvasWeave(vUv) * 0.026;
                    // Rich gallery oil pigment saturation + warm linseed varnish tone
                    vec3 richOil = mix(vec3(pLum), paint, 1.32);
                    richOil = richOil * vec3(1.03, 1.01, 0.96) + vec3(impastoDiff + impastoSpec + bristle + linen);
                    styled = clamp(richOil, 0.0, 1.0);
                } else if (uArtStyle == 2) {
                    // WET-ON-WET WATERCOLOR: Anisotropic Pigment Wash + Darkened Wet-Edge Pooling + Cold-Pressed Paper
                    float paperBleach = smoothstep(0.76, 0.96, pLum) * 0.68;
                    vec3 paperCol = vec3(0.985, 0.974, 0.942);
                    vec3 vividPigment = mix(vec3(pLum), paint, 1.48);
                    // Subtle luminous lift so watercolors feel translucent, never muddy
                    vividPigment = clamp(vividPigment * 1.06 + vec3(0.035), 0.0, 1.0);
                    // Real watercolor wet-edge pigment pooling (pigment settles darker along edge ridges!)
                    float wetEdgePool = smoothstep(0.10, 0.34, edge);
                    vec3 pooled = vividPigment * (1.0 - wetEdgePool * 0.26) - vec3(0.02, 0.015, 0.005) * wetEdgePool;
                    // Soft graphite under-drawing hints + cold-pressed cotton tooth granulation
                    float granulation = paperTooth(vUv) * (0.048 * (1.0 - paperBleach * 0.65));
                    styled = clamp(mix(pooled, paperCol, paperBleach) + vec3(granulation), 0.0, 1.0);
                } else if (uArtStyle == 3) {
                    // MASTER CHARCOAL SKETCH: True Color-Dodge Tonal Shading + Flow-Aligned Cross-Hatching + Archival Paper
                    float oLum = luma(orig);
                    // True photographic Color-Dodge pencil shading: base / (1 - invertedSmooth)
                    float invBlur = 1.0 - clamp(pLum, 0.02, 0.98);
                    float dodge = clamp(oLum / max(1.0 - invBlur * 0.92, 0.05), 0.0, 1.0);
                    // Flow-aligned primary & secondary cross-hatching along real contours!
                    vec2 px = vUv * uTexSize;
                    float hFreq = 0.72 * (900.0 / max(min(uTexSize.x, uTexSize.y), 400.0));
                    float h1 = 0.5 + 0.5 * sin(dot(px, nDir) * hFreq * 2.2);
                    float h2 = 0.5 + 0.5 * sin(dot(px, normalize(tDir + nDir)) * hFreq * 2.0);
                    float midShadow = (1.0 - smoothstep(0.24, 0.64, pLum)) * (0.28 + 0.52 * h1);
                    float deepShadow = (1.0 - smoothstep(0.06, 0.34, pLum)) * (0.38 + 0.52 * h2);
                    float stroke = smoothstep(0.14, 0.38, edge) * 0.88;
                    float graphite = clamp(dodge * (1.0 - max(midShadow * 0.65, deepShadow * 0.82)) * (1.0 - stroke * 0.78), 0.06, 0.99);
                    float tooth = paperTooth(vUv) * 0.055;
                    vec3 paperTint = vec3(0.975, 0.962, 0.932);
                    styled = clamp(paperTint * graphite + vec3(tooth), 0.05, 0.98);
                } else if (uArtStyle == 4) {
                    // FOUNTAIN PEN & INK ILLUSTRATION: Coherent Calligraphic Nib + Flow Cross-Hatching
                    vec2 px = vUv * uTexSize;
                    float hScale = 900.0 / max(min(uTexSize.x, uTexSize.y), 400.0);
                    float hatch1 = smoothstep(0.55, 0.88, 0.5 + 0.5 * sin(dot(px, nDir) * 1.65 * hScale));
                    float hatch2 = smoothstep(0.58, 0.90, 0.5 + 0.5 * sin(dot(px, tDir) * 1.65 * hScale));
                    float nib = smoothstep(0.14, 0.34, edge);
                    float midH = (1.0 - smoothstep(0.22, 0.48, pLum)) * hatch1 * 0.82;
                    float deepH = (1.0 - smoothstep(0.08, 0.25, pLum)) * max(hatch2, 0.75);
                    float ink = clamp(max(nib, max(midH, deepH)), 0.0, 1.0);
                    vec3 ivoryPaper = vec3(0.982, 0.972, 0.946) + vec3(paperTooth(vUv) * 0.025);
                    styled = mix(ivoryPaper, vec3(0.045, 0.05, 0.075), ink);
                } else if (uArtStyle == 5) {
                    // SOFT CHALK PASTEL: Velvety Anisotropic Pigment + Chalk Lift + Textured Tooth Paper
                    float bands = 7.0;
                    float sc = pLum * bands;
                    float b = floor(sc);
                    float qLum = (b + smoothstep(0.26, 0.74, sc - b)) / bands;
                    vec3 chalk = mix(vec3(qLum), paint * (qLum / max(pLum, 0.03)), 0.92);
                    chalk = chalk * 0.80 + vec3(0.19, 0.185, 0.175);
                    float tooth = paperTooth(vUv) * 0.065;
                    float contour = smoothstep(0.18, 0.42, edge) * 0.22;
                    styled = clamp((chalk + vec3(tooth)) * (1.0 - contour), 0.10, 0.99);
                } else if (uArtStyle == 6) {
                    // WOODCUT ENGRAVING: Flow-Curved Burin Carving Lines + Hand-Cut Relief Borders
                    vec2 px = vUv * uTexSize;
                    float hScale = 900.0 / max(min(uTexSize.x, uTexSize.y), 400.0);
                    float wave = 0.5 + 0.5 * sin(dot(px, nDir) * 1.45 * hScale + sin(dot(px, tDir) * 0.08 * hScale) * 2.0);
                    float carve = smoothstep(pLum - 0.14, pLum + 0.14, wave);
                    float borderInk = smoothstep(0.16, 0.34, edge);
                    float ink = (pLum < 0.14) ? 1.0 : ((pLum > 0.88) ? borderInk : max(borderInk, carve));
                    vec3 ragPaper = vec3(0.96, 0.932, 0.875) + vec3(paperTooth(vUv) * 0.03);
                    styled = mix(ragPaper, vec3(0.065, 0.052, 0.045), ink);
                } else if (uArtStyle == 7) {
                    // STONE LITHOGRAPH: Muted Archival Pigment + Fine Limestone Grain + Crayon Outlines
                    float sc = pLum * 5.0;
                    float qLum = (floor(sc) + smoothstep(0.28, 0.72, sc - floor(sc))) / 5.0;
                    vec3 muted = mix(vec3(qLum), paint * (qLum / max(pLum, 0.03)), 0.46);
                    float stone = paperTooth(vUv) * 0.06;
                    float crayon = smoothstep(0.17, 0.38, edge) * 0.50;
                    styled = clamp((muted * 0.92 + vec3(0.06, 0.052, 0.042) + vec3(stone)) * (1.0 - crayon), 0.06, 0.96);
                } else if (uArtStyle == 8) {
                    // PRUSSIAN BLUE CYANOTYPE SUN PRINT: Filmic Ferric S-Curve + Cotton Paper Texture
                    float s = pLum * pLum * (3.0 - 2.0 * pLum);
                    float cLum = pow(clamp(s * 1.05, 0.0, 1.0), 0.86);
                    vec3 prussian = mix(vec3(0.025, 0.115, 0.295), vec3(0.94, 0.97, 0.995), cLum);
                    styled = clamp(prussian + vec3(paperTooth(vUv) * 0.038), 0.02, 0.99);
                } else if (uArtStyle == 9) {
                    // CMYK RISOGRAPH / OFFSET HALFTONE PRINT (Authentic 15 / 75 / 0 / 45 degree screens!)
                    float pitch = max(4.2, min(uTexSize.x, uTexSize.y) / (115.0 * uHalftoneParams.x));
                    float cDot = rotatedDot(vUv, 1.0 - paint.r, 0.2618, pitch);
                    float mDot = rotatedDot(vUv, 1.0 - paint.g, 1.3090, pitch);
                    float yDot = rotatedDot(vUv, 1.0 - paint.b, 0.0, pitch);
                    float kDot = rotatedDot(vUv, max(0.0, 0.35 - pLum) * 2.2, 0.7854, pitch);
                    vec3 paper = vec3(0.975, 0.962, 0.930);
                    vec3 cmy = paper - vec3(cDot * 0.82, mDot * 0.80, yDot * 0.78) - vec3(kDot * 0.45);
                    styled = clamp(mix(cmy, paint, 0.18), 0.04, 0.98);
                } else if (uArtStyle == 10) {
                    // SCULPTED 3D CLAY / VINYL TOY: Smooth Studio Lighting + Subsurface Bounce + Rim Glow
                    vec2 eps = invSize * 3.2;
                    float lL = luma(texture2D(uTexPaint, clamp(vUv - vec2(eps.x, 0.0), 0.0, 1.0)).rgb);
                    float lR = luma(texture2D(uTexPaint, clamp(vUv + vec2(eps.x, 0.0), 0.0, 1.0)).rgb);
                    float lU = luma(texture2D(uTexPaint, clamp(vUv - vec2(0.0, eps.y), 0.0, 1.0)).rgb);
                    float lD = luma(texture2D(uTexPaint, clamp(vUv + vec2(0.0, eps.y), 0.0, 1.0)).rgb);
                    vec3 n = normalize(vec3((lL - lR) * 4.0, (lU - lD) * 4.0, 0.70));
                    vec3 keyDir = normalize(vec3(-0.42, -0.54, 0.73));
                    vec3 halfDir = normalize(keyDir + vec3(0.0, 0.0, 1.0));
                    float diff = max(0.0, dot(n, keyDir));
                    float sss = pow(1.0 - abs(dot(n, keyDir)), 2.0) * 0.14;
                    float spec = pow(max(0.0, dot(n, halfDir)), 22.0) * 0.38;
                    float rim = pow(1.0 - max(0.0, n.z), 2.4) * 0.24;
                    vec3 vinyl = mix(vec3(pLum), paint, 1.24) * (0.64 + 0.50 * diff) + paint * sss + vec3(spec + rim * 0.9);
                    styled = clamp(vinyl, 0.0, 1.0);
                } else if (uArtStyle == 11) {
                    // NEON CYBER EDGE: Coherent Flow Glow Lines + Luminous Cyber Palette
                    float core = smoothstep(0.12, 0.36, edge);
                    float halo = smoothstep(0.04, 0.30, edge) * 0.55;
                    vec3 neonCol = mix(vec3(0.04, 0.94, 1.0), vec3(1.0, 0.16, 0.84), clamp(vUv.x * 0.6 + vUv.y * 0.4, 0.0, 1.0));
                    styled = clamp(paint * 0.14 + vec3(0.015, 0.02, 0.055) + neonCol * (core * 1.15 + halo * 0.45), 0.0, 1.0);
                } else if (uArtStyle == 12) {
                    // SABATTIER SILVER SOLARIZE: Smooth Archival Photographic Reversal Curve
                    vec3 s = vec3(
                        sin(3.1415926 * pow(clamp(paint.r, 0.0, 1.0), 0.88)),
                        sin(3.1415926 * pow(clamp(paint.g, 0.0, 1.0), 0.90)),
                        sin(3.1415926 * pow(clamp(paint.b, 0.0, 1.0), 0.92))
                    );
                    styled = clamp(s * 0.96 + vec3(0.02, 0.025, 0.04), 0.0, 1.0);
                } else if (uArtStyle == 13) {
                    // FLIR IRONBOW THERMAL VISION
                    float t = pLum;
                    vec3 th = t < 0.25 ? mix(vec3(0.03, 0.02, 0.22), vec3(0.46, 0.04, 0.60), t / 0.25)
                            : (t < 0.52 ? mix(vec3(0.46, 0.04, 0.60), vec3(0.94, 0.18, 0.22), (t - 0.25) / 0.27)
                            : (t < 0.80 ? mix(vec3(0.94, 0.18, 0.22), vec3(0.99, 0.80, 0.08), (t - 0.52) / 0.28)
                            : mix(vec3(0.99, 0.80, 0.08), vec3(1.0, 1.0, 0.97), (t - 0.80) / 0.20)));
                    styled = th;
                }

                gl_FragColor = vec4(mix(orig, styled, uIntensity), 1.0);
            }
        """

        // PILLAR 4: PRO STUDIO TOON & COMIC COMPOSITE (Coherent FDoG Ink + Skin-Safe Warm/Cool Cel Shading)
        private const val FS_TOON_COMPOSITE = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uTexOrig;
            uniform sampler2D uTexSmooth;
            uniform sampler2D uTexEdge;
            uniform int uStyle;
            uniform float uIntensity;
            uniform vec2 uTexSize;
            uniform vec2 uHalftoneParams;

            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

            float celBand(float lum, float bands) {
                float sc = clamp(lum, 0.0, 1.0) * bands;
                float b = floor(sc);
                float f = sc - b;
                return clamp((b + smoothstep(0.40, 0.60, f)) / bands * 0.90 + 0.07, 0.07, 0.98);
            }

            float screenDot(vec2 uv, float darkness, float pitchPx) {
                vec2 p = uv * uTexSize / max(pitchPx, 2.0);
                vec2 rot = vec2(p.x * 0.7071 - p.y * 0.7071, p.x * 0.7071 + p.y * 0.7071);
                vec2 cell = fract(rot) - 0.5;
                float rad = sqrt(clamp(darkness, 0.0, 1.0)) * 0.50 * uHalftoneParams.y;
                return 1.0 - smoothstep(rad - 0.08, rad + 0.08, length(cell));
            }

            void main() {
                vec3 orig = texture2D(uTexOrig, vUv).rgb;
                vec3 smoothC = texture2D(uTexSmooth, vUv).rgb;
                float edge = texture2D(uTexEdge, vUv).r;
                float lum = luma(smoothC);
                float pitch = max(3.6, min(uTexSize.x, uTexSize.y) / (118.0 * uHalftoneParams.x));
                vec3 styled = smoothC;

                if (uStyle == 1) {
                    // STUDIO CEL-SHADED ANIME / COMIC: Natural skin-safe hue + warm highlights / cool indigo shadows + coherent ink!
                    float bandL = celBand(lum, 4.0);
                    vec3 chrom = smoothC * (bandL / max(lum, 0.025));
                    // Protect skin tones from over-saturation while making clothes/sky pop
                    float skinMask = smoothstep(0.03, 0.18, smoothC.r - smoothC.b) * smoothstep(-0.02, 0.14, smoothC.r - smoothC.g);
                    float satBoost = mix(1.42, 1.16, skinMask);
                    vec3 cel = mix(vec3(bandL), chrom, satBoost);
                    // Studio color-temperature cel shading (cool shadow tint, warm rim highlight)
                    float shadowT = 1.0 - smoothstep(0.18, 0.44, bandL);
                    float highT = smoothstep(0.76, 0.92, bandL);
                    cel += vec3(-0.032 * shadowT, -0.010 * shadowT, 0.055 * shadowT);
                    cel += vec3(0.055 * highT, 0.042 * highT, 0.015 * highT);
                    // Subtle print halftone texture ONLY in deep midtone shadows
                    float htWeight = smoothstep(0.48, 0.18, lum) * 0.22;
                    if (htWeight > 0.01) {
                        float dotDark = screenDot(vUv, clamp(0.65 - lum, 0.0, 1.0), pitch * 1.15);
                        cel *= (1.0 - dotDark * htWeight);
                    }
                    float ink = smoothstep(0.17, 0.34, edge);
                    styled = mix(clamp(cel, 0.0, 1.0), vec3(0.035, 0.038, 0.065), ink);
                } else if (uStyle == 2) {
                    // GRAPHIC NOVEL NOIR (Sin City / Mignola): Bold Ink Pools + Crisp Halftone Midtones + Coherent Ink
                    float ink = smoothstep(0.15, 0.31, edge);
                    float deepBlack = 1.0 - smoothstep(0.20, 0.30, lum);
                    float midZone = (1.0 - smoothstep(0.45, 0.62, lum)) * (1.0 - deepBlack);
                    float midDot = screenDot(vUv, clamp(0.75 - lum, 0.0, 1.0), pitch * 1.1);
                    float tone = mix(0.985, 0.56 - midDot * 0.24, midZone);
                    tone = mix(tone, 0.025, deepBlack);
                    tone = mix(tone, 0.02, ink);
                    // Subtle selective dramatic crimson accent on vivid reds (Sin City signature!)
                    float redAccent = smoothstep(0.22, 0.42, smoothC.r - max(smoothC.g, smoothC.b)) * (1.0 - deepBlack) * (1.0 - ink);
                    vec3 bwCol = vec3(tone * 0.985, tone * 0.988, tone);
                    styled = mix(bwCol, vec3(0.86, 0.08, 0.14) * (0.45 + 0.55 * tone), redAccent * 0.85);
                } else if (uStyle == 3) {
                    // JAPANESE MANGA INK: Sumi-Ink Calligraphic Lines + Multi-Layer Screentone Strata
                    float ink = smoothstep(0.14, 0.30, edge);
                    float toneDark = screenDot(vUv, 0.72, pitch * 0.92);
                    float toneMid = screenDot(vUv, 0.34, pitch * 1.08);
                    float paper = 0.985;
                    if (lum < 0.19) {
                        paper = 0.035;
                    } else if (lum < 0.40) {
                        paper = mix(0.96, 0.12, toneDark);
                    } else if (lum < 0.64) {
                        paper = mix(0.98, 0.32, toneMid);
                    }
                    float finalV = mix(paper, 0.025, ink);
                    styled = vec3(finalV * 0.992, finalV * 0.985, finalV * 0.968);
                } else if (uStyle == 4) {
                    // POP ART PRINT (Lichtenstein / Warhol): Vibrant Screenprint Planes + Bold Ben-Day Dots + Thick Ink
                    float chroma = max(max(smoothC.r, smoothC.g), smoothC.b) - min(min(smoothC.r, smoothC.g), smoothC.b);
                    vec3 pop = lum > 0.77 ? vec3(0.99, 0.94, 0.38)
                             : (lum < 0.22 ? vec3(0.05, 0.08, 0.24)
                             : (chroma < 0.10 ? vec3(0.20, 0.75, 0.98)
                             : (smoothC.r >= smoothC.g && smoothC.r >= smoothC.b
                                 ? (smoothC.r > smoothC.g + 0.15 ? vec3(0.94, 0.14, 0.24) : vec3(0.99, 0.76, 0.58))
                                 : (smoothC.b >= smoothC.r && smoothC.b >= smoothC.g ? vec3(0.12, 0.40, 0.94) : vec3(0.06, 0.74, 0.52)))));
                    float benDay = screenDot(vUv, clamp(0.85 - lum * 0.75, 0.0, 1.0), pitch * 1.35);
                    vec3 shadedPop = mix(pop, pop * 0.68, benDay * 0.75);
                    float ink = smoothstep(0.16, 0.32, edge);
                    styled = mix(shadedPop, vec3(0.025, 0.025, 0.045), ink);
                } else if (uStyle == 5) {
                    // MINIMALIST STUDIO LINE ART: Ultra-Clean Coherent Ink Drawing on Warm Archival Paper
                    float ink = smoothstep(0.15, 0.31, edge);
                    styled = mix(vec3(0.988, 0.980, 0.958), vec3(0.045, 0.052, 0.075), ink);
                } else if (uStyle == 6) {
                    // EUROPEAN LIGNE CLAIRE (Tintin / Moebius): Luminous Flat Matte Planes + Uniform Hairline Ink
                    float bandL = celBand(lum, 3.0) * 0.78 + 0.19;
                    vec3 clean = mix(vec3(bandL), smoothC * (bandL / max(lum, 0.025)), 1.22);
                    float ink = smoothstep(0.16, 0.31, edge) * 0.95;
                    styled = mix(clamp(clean, 0.0, 1.0), vec3(0.065, 0.06, 0.052), ink);
                }

                gl_FragColor = vec4(mix(orig, styled, uIntensity), 1.0);
            }
        """
    }
}
