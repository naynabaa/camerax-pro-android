package com.example.cameraxapp.filter

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageGrayscaleFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSepiaToneFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * FilterManager: Production Real-Time GPUImage Filter & Multi-Pass Comic Shader Engine
 * 
 * Supports:
 * 1. Color Presets:
 *    - CINEMATIC (Teal & Orange split-toning via OpenGL ES fragment shader)
 *    - VINTAGE (Warm sepia film curve)
 *    - BLACK_AND_WHITE (ITU-R BT.601 high-contrast monochrome)
 *    - HDR (Dynamic tone mapping & saturation boost)
 *    - CYBERPUNK (Neon cyan & magenta dual-tone shift)
 * 2. Live Adjustments:
 *    - Brightness (-1.0f to 1.0f)
 *    - Contrast (0.0f to 2.0f)
 *    - Saturation (0.0f to 2.0f)
 * 3. Photo & Video Pipeline:
 *    - Real-time preview rendering via GPUImageView / OpenGL SurfaceProcessor
 *    - In-memory Bitmap processing for high-res photo capture
 */
class FilterManager(private val context: Context) {

    enum class FilterPreset {
        NORMAL,
        LUT_REC709,
        LUT_FLAT_LOG,
        LUT_TEAL_ORANGE,
        LUT_BLEACH_BYPASS,
        LUT_ANAMORPHIC_CYAN,
        LUT_MATTE_CINEMA,
        LUT_MIDNIGHT_NEON,
        LUT_WARM_DOCUMENTARY,
        LUT_NOIR_CINEMA,
        GOLDEN_NOSTALGIA,
        VELVIA_LANDSCAPE,
        NORDIC_SLATE,
        ARCTIC_MIST,
        CLASSIC_MONO,
        HIGH_CONTRAST_NOIR,
        ILFORD_GRAIN,
        SILVER_GELATIN,
        ANTIQUE_SEPIA,
        CINEMATIC,
        VINTAGE,
        BLACK_AND_WHITE,
        HDR,
        CYBERPUNK,
        COMIC_BOOK
    }

    data class LiveAdjustments(
        var brightness: Float = 0.0f, // -0.5f to 0.5f (0 is neutral)
        var contrast: Float = 1.0f,   // 0.5f to 2.0f (1.0 is neutral)
        var saturation: Float = 1.0f  // 0.0f to 2.0f (1.0 is neutral)
    )

    private val gpuImage = GPUImage(context)
    private val toonGpuImage = GPUImage(context)
    private var currentPreset = FilterPreset.NORMAL
    val adjustments = LiveAdjustments()

    /**
     * Multi-Pass GPUImage Comic-Book & Cartoon Shader Filter.
     *
     * Executes 4 real OpenGL ES 2.0 passes using offscreen Framebuffer Objects (FBOs):
     *   - Pass 1 (FBO 0): Horizontal 9-tap edge-preserving Bilateral Color Flattening
     *   - Pass 2 (FBO 1): Vertical 9-tap edge-preserving Bilateral Color Flattening
     *   - Pass 3 (FBO 2): Dual-Scale Multi-Channel 3x3 Sobel Edge Detection (Gx/Gy on Luma + RGB Color Vectors)
     *   - Pass 4 (Output): Quantized Comic Color Banding (HSV/Luma discrete steps + chroma pop + optional 45deg Ben-Day dots)
     *                      composited with crisp black Sobel ink outlines.
     */
    class GPUImageComicBookFilter(
        var style: ComicStyle = ComicStyle.COMIC_BOOK,
        var quantizeLevels: Float = 4.0f,
        var edgeThreshold: Float = 0.17f,
        var intensity: Float = 0.92f,
        var halftoneDensity: Float = 1.0f,
        var halftoneDotSize: Float = 1.0f
    ) : GPUImageFilter() {

        enum class ComicStyle(val modeId: Int) {
            COMIC_BOOK(0),        // Vibrant American comic / anime cel-fill + bold Sobel ink + subtle Ben-Day shadow dots
            GRAPHIC_NOVEL(1),     // Sin City / Mignola high-contrast noir ink + crisp halftone midtones
            MANGA_INK(2),         // Warm cream paper + Japanese 45-deg screentones + fine Sobel G-pen ink
            POP_ART(3),           // Lichtenstein primary color blocks + heavy Ben-Day dot matrix + bold comic outlines
            LINE_ART(4),          // Pure minimalist Sobel ink line drawing on ivory stock
            LIGNE_CLAIRE(5)       // Herge / Moebius 3-band flat pastel/satin comic colors + uniform clear ink lines
        }

        private val fboIds = IntArray(3)
        private val fboTextures = IntArray(3)
        private var fboWidth = 0
        private var fboHeight = 0

        private var progBilatH = 0
        private var progBilatV = 0
        private var progSobel = 0
        private var progComposite = 0

        private val identityCubeCoords = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        private val identityTexCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        private val identityCubeBuffer: FloatBuffer = ByteBuffer.allocateDirect(32)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(identityCubeCoords); position(0) }
        private val identityTexBuffer: FloatBuffer = ByteBuffer.allocateDirect(32)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(identityTexCoords); position(0) }

        override fun onInit() {
            super.onInit()
            progBilatH = buildProgram(PASS_VS, BILATERAL_SHADER)
            progBilatV = buildProgram(PASS_VS, BILATERAL_SHADER)
            progSobel = buildProgram(PASS_VS, SOBEL_MULTIPASS_SHADER)
            progComposite = buildProgram(COMPOSITE_VS, COMIC_COMPOSITE_SHADER)
        }

        override fun onOutputSizeChanged(width: Int, height: Int) {
            super.onOutputSizeChanged(width, height)
            if (width <= 0 || height <= 0) return
            if (fboWidth != width || fboHeight != height || fboIds[0] == 0) {
                destroyFbos()
                fboWidth = width
                fboHeight = height
                GLES20.glGenFramebuffers(3, fboIds, 0)
                GLES20.glGenTextures(3, fboTextures, 0)
                for (i in 0 until 3) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextures[i])
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        fboWidth, fboHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                    )
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
                    GLES20.glFramebufferTexture2D(
                        GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, fboTextures[i], 0
                    )
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
        }

        override fun onDraw(textureId: Int, cubeBuffer: FloatBuffer, textureBuffer: FloatBuffer) {
            runPendingOnDrawTasks()
            if (!isInitialized || textureId == -1 || fboIds[0] == 0 || fboWidth <= 0 || fboHeight <= 0) {
                return
            }

            val prevFbo = IntArray(1)
            val prevViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)

            val texelX = 1.0f / fboWidth.toFloat()
            val texelY = 1.0f / fboHeight.toFloat()

            GLES20.glViewport(0, 0, fboWidth, fboHeight)

            // PASS 1: Horizontal 9-tap Bilateral Color Flattening (input -> FBO 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
            GLES20.glUseProgram(progBilatH)
            drawTexturedQuad(progBilatH, textureId, identityCubeBuffer, textureBuffer)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progBilatH, "uTexelStep"), texelX * 1.45f, 0.0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // PASS 2: Vertical 9-tap Bilateral Color Flattening (FBO 0 -> FBO 1)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1])
            GLES20.glUseProgram(progBilatV)
            drawTexturedQuad(progBilatV, fboTextures[0], identityCubeBuffer, identityTexBuffer)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progBilatV, "uTexelStep"), 0.0f, texelY * 1.45f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // PASS 3: Dual-Scale 3x3 Sobel Edge Detection on Flattened Image (FBO 1 -> FBO 2)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[2])
            GLES20.glUseProgram(progSobel)
            drawTexturedQuad(progSobel, fboTextures[1], identityCubeBuffer, identityTexBuffer)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progSobel, "uTexelSize"), texelX, texelY)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // PASS 4: Quantized Color Banding + Comic Ink Composite (FBO 1 + FBO 2 + Orig -> prevFbo)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
            GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
            GLES20.glUseProgram(progComposite)

            val posLoc = GLES20.glGetAttribLocation(progComposite, "position")
            val texLoc = GLES20.glGetAttribLocation(progComposite, "inputTextureCoordinate")
            val origTexLoc = GLES20.glGetAttribLocation(progComposite, "origTextureCoordinate")

            cubeBuffer.position(0)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, cubeBuffer)
            GLES20.glEnableVertexAttribArray(posLoc)

            identityTexBuffer.position(0)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, identityTexBuffer)
            GLES20.glEnableVertexAttribArray(texLoc)

            textureBuffer.position(0)
            GLES20.glVertexAttribPointer(origTexLoc, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
            GLES20.glEnableVertexAttribArray(origTexLoc)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextures[1])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progComposite, "smoothedTexture"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextures[2])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progComposite, "edgeTexture"), 1)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progComposite, "origTexture"), 2)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(progComposite, "uStyleMode"), style.modeId)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progComposite, "uQuantizeLevels"), quantizeLevels)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progComposite, "uEdgeThreshold"), edgeThreshold)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progComposite, "uIntensity"), intensity)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progComposite, "uHalftoneDensity"), halftoneDensity)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progComposite, "uHalftoneDotSize"), halftoneDotSize)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(progComposite, "uResolution"),
                fboWidth.toFloat(),
                fboHeight.toFloat()
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(texLoc)
            GLES20.glDisableVertexAttribArray(origTexLoc)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        private fun drawTexturedQuad(
            prog: Int,
            texId: Int,
            cubeBuf: FloatBuffer,
            texBuf: FloatBuffer
        ) {
            val posLoc = GLES20.glGetAttribLocation(prog, "position")
            val texLoc = GLES20.glGetAttribLocation(prog, "inputTextureCoordinate")
            cubeBuf.position(0)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, cubeBuf)
            GLES20.glEnableVertexAttribArray(posLoc)
            texBuf.position(0)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "inputImageTexture"), 0)
        }

        override fun onDestroy() {
            super.onDestroy()
            destroyFbos()
            if (progBilatH != 0) { GLES20.glDeleteProgram(progBilatH); progBilatH = 0 }
            if (progBilatV != 0) { GLES20.glDeleteProgram(progBilatV); progBilatV = 0 }
            if (progSobel != 0) { GLES20.glDeleteProgram(progSobel); progSobel = 0 }
            if (progComposite != 0) { GLES20.glDeleteProgram(progComposite); progComposite = 0 }
        }

        private fun destroyFbos() {
            if (fboTextures[0] != 0) {
                GLES20.glDeleteTextures(3, fboTextures, 0)
                fboTextures.fill(0)
            }
            if (fboIds[0] != 0) {
                GLES20.glDeleteFramebuffers(3, fboIds, 0)
                fboIds.fill(0)
            }
        }

        private fun buildProgram(vsSrc: String, fsSrc: String): Int {
            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
                GLES20.glShaderSource(it, vsSrc)
                GLES20.glCompileShader(it)
            }
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
                GLES20.glShaderSource(it, fsSrc)
                GLES20.glCompileShader(it)
            }
            return GLES20.glCreateProgram().also { prog ->
                GLES20.glAttachShader(prog, vs)
                GLES20.glAttachShader(prog, fs)
                GLES20.glLinkProgram(prog)
                GLES20.glDeleteShader(vs)
                GLES20.glDeleteShader(fs)
            }
        }

        companion object {
            private const val PASS_VS = """
                attribute vec4 position;
                attribute vec2 inputTextureCoordinate;
                varying vec2 textureCoordinate;
                void main() {
                    gl_Position = position;
                    textureCoordinate = inputTextureCoordinate;
                }
            """

            private const val COMPOSITE_VS = """
                attribute vec4 position;
                attribute vec2 inputTextureCoordinate;
                attribute vec2 origTextureCoordinate;
                varying vec2 textureCoordinate;
                varying vec2 origCoordinate;
                void main() {
                    gl_Position = position;
                    textureCoordinate = inputTextureCoordinate;
                    origCoordinate = origTextureCoordinate;
                }
            """

            // Pass 1 & 2: Separable 9-Tap Edge-Preserving Bilateral Color Flattening
            private const val BILATERAL_SHADER = """
                precision mediump float;
                varying vec2 textureCoordinate;
                uniform sampler2D inputImageTexture;
                uniform vec2 uTexelStep;

                void main() {
                    vec3 center = texture2D(inputImageTexture, textureCoordinate).rgb;
                    vec3 acc = center * 0.22;
                    float wSum = 0.22;

                    for (int k = 1; k <= 4; k++) {
                        float fk = float(k);
                        float spatial = exp(-(fk * fk) / 10.5);
                        vec2 off = uTexelStep * fk;

                        vec3 sPos = texture2D(inputImageTexture, clamp(textureCoordinate + off, 0.0, 1.0)).rgb;
                        vec3 dPos = sPos - center;
                        float wPos = spatial * exp(-dot(dPos, dPos) * 24.0);
                        acc += sPos * wPos;
                        wSum += wPos;

                        vec3 sNeg = texture2D(inputImageTexture, clamp(textureCoordinate - off, 0.0, 1.0)).rgb;
                        vec3 dNeg = sNeg - center;
                        float wNeg = spatial * exp(-dot(dNeg, dNeg) * 24.0);
                        acc += sNeg * wNeg;
                        wSum += wNeg;
                    }

                    gl_FragColor = vec4(acc / max(wSum, 0.001), 1.0);
                }
            """

            // Pass 3: Dual-Scale 3x3 Sobel Filter Edge Detector (Luminance + RGB Color Vector Sobel + Structural Contour)
            private const val SOBEL_MULTIPASS_SHADER = """
                precision mediump float;
                varying vec2 textureCoordinate;
                uniform sampler2D inputImageTexture;
                uniform vec2 uTexelSize;

                float luma(vec3 c) {
                    return dot(c, vec3(0.299, 0.587, 0.114));
                }

                vec2 sobelAtScale(vec2 uv, vec2 o) {
                    vec3 tl = texture2D(inputImageTexture, clamp(uv + vec2(-o.x, -o.y), 0.0, 1.0)).rgb;
                    vec3 tc = texture2D(inputImageTexture, clamp(uv + vec2( 0.0, -o.y), 0.0, 1.0)).rgb;
                    vec3 tr = texture2D(inputImageTexture, clamp(uv + vec2( o.x, -o.y), 0.0, 1.0)).rgb;
                    vec3 ml = texture2D(inputImageTexture, clamp(uv + vec2(-o.x,  0.0), 0.0, 1.0)).rgb;
                    vec3 mr = texture2D(inputImageTexture, clamp(uv + vec2( o.x,  0.0), 0.0, 1.0)).rgb;
                    vec3 bl = texture2D(inputImageTexture, clamp(uv + vec2(-o.x,  o.y), 0.0, 1.0)).rgb;
                    vec3 bc = texture2D(inputImageTexture, clamp(uv + vec2( 0.0,  o.y), 0.0, 1.0)).rgb;
                    vec3 br = texture2D(inputImageTexture, clamp(uv + vec2( o.x,  o.y), 0.0, 1.0)).rgb;

                    // 1. Luminance Sobel Gx / Gy
                    float gxL = (-luma(tl) - 2.0 * luma(ml) - luma(bl)) + (luma(tr) + 2.0 * luma(mr) + luma(br));
                    float gyL = (-luma(tl) - 2.0 * luma(tc) - luma(tr)) + (luma(bl) + 2.0 * luma(bc) + luma(br));
                    float lumEdge = sqrt(gxL * gxL + gyL * gyL) * 0.55;

                    // 2. Multi-channel RGB Color Vector Sobel (catches isoluminant comic color borders)
                    vec3 gxRGB = (-tl - 2.0 * ml - bl) + (tr + 2.0 * mr + br);
                    vec3 gyRGB = (-tl - 2.0 * tc - tr) + (bl + 2.0 * bc + br);
                    float vecEdge = sqrt(dot(gxRGB, gxRGB) * 0.36 + dot(gyRGB, gyRGB) * 0.36);

                    return vec2(lumEdge, vecEdge);
                }

                void main() {
                    vec2 fine = sobelAtScale(textureCoordinate, uTexelSize * 1.15);
                    vec2 wide = sobelAtScale(textureCoordinate, uTexelSize * 2.15);
                    float combinedFine = max(fine.x, fine.y);
                    float combinedWide = max(wide.x, wide.y);
                    float sobelStrength = max(combinedFine, combinedWide * 0.86);
                    float centerLum = luma(texture2D(inputImageTexture, textureCoordinate).rgb);
                    gl_FragColor = vec4(combinedFine, combinedWide, sobelStrength, centerLum);
                }
            """

            // Pass 4: Quantized Comic Color Banding + Ben-Day Shadow Halftones + Crisp Sobel Ink Outlines
            private const val COMIC_COMPOSITE_SHADER = """
                precision mediump float;
                varying vec2 textureCoordinate;
                varying vec2 origCoordinate;
                uniform sampler2D smoothedTexture;
                uniform sampler2D edgeTexture;
                uniform sampler2D origTexture;
                uniform int uStyleMode;
                uniform float uQuantizeLevels;
                uniform float uEdgeThreshold;
                uniform float uIntensity;
                uniform float uHalftoneDensity;
                uniform float uHalftoneDotSize;
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
                    vec3 sm = texture2D(smoothedTexture, textureCoordinate).rgb;
                    vec4 edgeSample = texture2D(edgeTexture, textureCoordinate);
                    vec4 orig = texture2D(origTexture, origCoordinate);

                    float lum = luma(sm);
                    float sobelMag = edgeSample.b;
                    float inkMask = smoothstep(uEdgeThreshold, uEdgeThreshold + 0.13, sobelMag);
                    vec2 px = textureCoordinate * uResolution;
                    float htDensity = clamp(uHalftoneDensity, 0.4, 2.2);
                    float htDotSize = clamp(uHalftoneDotSize, 0.3, 1.8);

                    vec3 comicRgb;
                    if (uStyleMode == 1) {
                        // GRAPHIC_NOVEL: Noir black shadow crush + Ben-Day midtone halftone + heavy Sobel ink
                        float shadow = smoothstep(0.30, 0.21, lum);
                        float mid = smoothstep(0.62, 0.46, lum);
                        float dot = halftoneDot(px, mid * (0.40 + 0.16 * htDensity), max(2.1, max(4.0, uResolution.x / 88.0) / htDensity), htDotSize);
                        float g = mix(0.99, mix(0.72, 0.09, dot), mid);
                        g = mix(g, 0.03, shadow);
                        comicRgb = vec3(mix(g, 0.02, inkMask));
                    } else if (uStyleMode == 2) {
                        // MANGA_INK: Warm ivory paper + stepped Japanese screentone + G-pen Sobel contours
                        float darkFill = smoothstep(0.22, 0.11, lum);
                        float tone = lum < 0.44 ? 0.54 : (lum < 0.68 ? 0.28 : 0.0);
                        float dot = halftoneDot(px, tone * (0.7 + 0.3 * htDensity), max(2.0, max(3.8, uResolution.x / 94.0) / htDensity), htDotSize) * 0.88;
                        float shade = max(inkMask, max(darkFill, dot));
                        comicRgb = mix(vec3(0.99, 0.985, 0.96), vec3(0.04, 0.045, 0.07), shade);
                    } else if (uStyleMode == 3) {
                        // POP_ART: Quantized primary comic palette + bold Ben-Day dots + thick Sobel outlines
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
                    } else if (uStyleMode == 4) {
                        // LINE_ART: Minimalist crisp Sobel contour lines on cream paper
                        comicRgb = mix(vec3(0.99, 0.985, 0.96), vec3(0.04, 0.05, 0.08), inkMask);
                    } else if (uStyleMode == 5) {
                        // LIGNE_CLAIRE: 3-level flat matte comic bands + uniform clean charcoal lines
                        vec3 hsv = rgb2hsv(sm);
                        float qVal = quantizeStep(hsv.z, 3.0, 0.045) * 0.78 + 0.20;
                        float qSat = clamp(hsv.y * 1.18, 0.0, 1.0);
                        vec3 flatCol = hsv2rgb(vec3(hsv.x, qSat, qVal));
                        comicRgb = mix(flatCol, vec3(0.065, 0.06, 0.055), inkMask * 0.94);
                    } else {
                        // COMIC_BOOK (default): Multi-pass HSV quantized color banding + luminance-driven newspaper halftone dot screen + bold Sobel ink
                        vec3 hsv = rgb2hsv(sm);
                        float qVal = quantizeStep(hsv.z, uQuantizeLevels, 0.045) * 0.88 + 0.10;
                        float qSat = clamp(quantizeStep(hsv.y, 4.0, 0.12) * 0.35 + hsv.y * 1.15, 0.0, 1.0);
                        // Gentle hue snapping (18 comic print hue sectors) to eliminate photographic muddy hue drift
                        float qHue = fract(floor(hsv.x * 18.0 + 0.5) / 18.0);
                        float crispHue = mix(hsv.x, qHue, 0.42);
                        vec3 banded = hsv2rgb(vec3(crispHue, qSat, qVal));

                        // Warm newsprint paper stock cast + cool comic shadow / cel-specular accents
                        float shadowZone = smoothstep(0.42, 0.18, qVal);
                        float hiZone = smoothstep(0.80, 0.94, qVal);
                        banded *= mix(vec3(1.0), vec3(1.0, 0.972, 0.915), 0.65);
                        banded += vec3(-0.035 * shadowZone, -0.015 * shadowZone, 0.055 * shadowZone) + vec3(hiZone * 0.06);

                        // Luminance-channel newspaper halftone dot overlay (45-deg CMYK key screen across midtones & shadows, clean highlights)
                        float lumDensity = smoothstep(0.90, 0.12, lum);
                        float newsPitch = max(2.2, max(4.2, uResolution.x / 92.0) / htDensity);
                        float newsDot = halftoneDot(px, lumDensity * (0.50 + 0.18 * htDensity), newsPitch, htDotSize);
                        float dotLuminanceDrop = newsDot * (0.14 + 0.32 * lumDensity);
                        float bandedLum = max(0.001, luma(banded));
                        float targetLum = bandedLum * (1.0 - dotLuminanceDrop);
                        banded *= (targetLum / bandedLum);

                        comicRgb = mix(clamp(banded, 0.0, 1.0), vec3(0.03, 0.035, 0.065), inkMask);
                    }

                    gl_FragColor = vec4(mix(orig.rgb, comicRgb, clamp(uIntensity, 0.0, 1.0)), orig.a);
                }
            """
        }
    }

    // 1. Cinematic Fragment Shader (Teal & Orange Split-Toning)
    private val CINEMATIC_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 textureCoordinate;
        uniform sampler2D inputImageTexture;

        void main() {
            vec4 color = texture2D(inputImageTexture, textureCoordinate);
            float lum = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            
            // Teal shadows, amber/orange highlights
            vec3 tealShadows = vec3(0.05, 0.35, 0.42);
            vec3 amberHighlights = vec3(1.0, 0.78, 0.45);
            vec3 graded = mix(tealShadows, amberHighlights, lum);
            
            vec3 finalRgb = mix(color.rgb, graded * color.rgb * 1.4, 0.45);
            gl_FragColor = vec4(finalRgb, color.a);
        }
    """.trimIndent()

    // 2. Cyberpunk Fragment Shader (Neon Cyan / Magenta Shift)
    private val CYBERPUNK_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 textureCoordinate;
        uniform sampler2D inputImageTexture;

        void main() {
            vec4 color = texture2D(inputImageTexture, textureCoordinate);
            float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
            
            // Neon cyan & hot magenta
            vec3 neonCyan = vec3(0.0, 0.92, 1.0);
            vec3 neonPink = vec3(1.0, 0.08, 0.58);
            vec3 cyber = mix(neonCyan, neonPink, smoothstep(0.25, 0.75, lum));
            
            vec3 finalRgb = mix(color.rgb, cyber, 0.42);
            gl_FragColor = vec4(finalRgb * 1.12, color.a);
        }
    """.trimIndent()

    /**
     * Builds and chains the active preset filter with live adjustment filters
     */
    fun buildActiveFilterGroup(): GPUImageFilterGroup {
        val filters = mutableListOf<GPUImageFilter>()

        // Step 1: Base Preset Filter
        when (currentPreset) {
            FilterPreset.NORMAL -> {
                // Passthrough
            }
            FilterPreset.LUT_REC709 -> {
                filters.add(GPUImageContrastFilter(1.12f))
                filters.add(GPUImageSaturationFilter(1.08f))
            }
            FilterPreset.LUT_FLAT_LOG -> {
                filters.add(GPUImageContrastFilter(0.68f))
                filters.add(GPUImageSaturationFilter(0.62f))
                filters.add(GPUImageBrightnessFilter(0.08f))
            }
            FilterPreset.LUT_TEAL_ORANGE -> {
                filters.add(GPUImageFilter(GPUImageFilter.NO_FILTER_VERTEX_SHADER, CINEMATIC_FRAGMENT_SHADER))
                filters.add(GPUImageContrastFilter(1.30f))
                filters.add(GPUImageSaturationFilter(1.34f))
            }
            FilterPreset.LUT_BLEACH_BYPASS -> {
                filters.add(GPUImageSaturationFilter(0.52f))
                filters.add(GPUImageContrastFilter(1.52f))
                filters.add(GPUImageBrightnessFilter(-0.04f))
                filters.add(GPUImageSharpenFilter(0.35f))
            }
            FilterPreset.LUT_ANAMORPHIC_CYAN -> {
                filters.add(GPUImageFilter(GPUImageFilter.NO_FILTER_VERTEX_SHADER, CINEMATIC_FRAGMENT_SHADER))
                filters.add(GPUImageContrastFilter(1.34f))
                filters.add(GPUImageSaturationFilter(1.18f))
            }
            FilterPreset.LUT_MATTE_CINEMA -> {
                filters.add(GPUImageContrastFilter(0.84f))
                filters.add(GPUImageSaturationFilter(0.86f))
                filters.add(GPUImageBrightnessFilter(0.10f))
                filters.add(GPUImageSepiaToneFilter(0.20f))
            }
            FilterPreset.LUT_MIDNIGHT_NEON -> {
                filters.add(GPUImageFilter(GPUImageFilter.NO_FILTER_VERTEX_SHADER, CYBERPUNK_FRAGMENT_SHADER))
                filters.add(GPUImageContrastFilter(1.36f))
                filters.add(GPUImageSaturationFilter(1.56f))
            }
            FilterPreset.LUT_WARM_DOCUMENTARY -> {
                filters.add(GPUImageSepiaToneFilter(0.22f))
                filters.add(GPUImageContrastFilter(1.10f))
                filters.add(GPUImageSaturationFilter(1.14f))
                filters.add(GPUImageBrightnessFilter(0.03f))
            }
            FilterPreset.LUT_NOIR_CINEMA -> {
                filters.add(GPUImageGrayscaleFilter())
                filters.add(GPUImageContrastFilter(1.72f))
                filters.add(GPUImageBrightnessFilter(-0.05f))
            }
            FilterPreset.GOLDEN_NOSTALGIA -> {
                filters.add(GPUImageSepiaToneFilter(0.42f))
                filters.add(GPUImageBrightnessFilter(0.05f))
                filters.add(GPUImageContrastFilter(0.94f))
                filters.add(GPUImageSaturationFilter(1.16f))
            }
            FilterPreset.VELVIA_LANDSCAPE -> {
                filters.add(GPUImageSaturationFilter(1.52f))
                filters.add(GPUImageContrastFilter(1.24f))
                filters.add(GPUImageSharpenFilter(0.45f))
            }
            FilterPreset.NORDIC_SLATE -> {
                filters.add(GPUImageSaturationFilter(0.64f))
                filters.add(GPUImageContrastFilter(1.24f))
                filters.add(GPUImageBrightnessFilter(-0.03f))
            }
            FilterPreset.ARCTIC_MIST -> {
                filters.add(GPUImageSaturationFilter(0.60f))
                filters.add(GPUImageContrastFilter(0.90f))
                filters.add(GPUImageBrightnessFilter(0.08f))
            }
            FilterPreset.CLASSIC_MONO -> {
                filters.add(GPUImageGrayscaleFilter())
                filters.add(GPUImageContrastFilter(1.14f))
            }
            FilterPreset.HIGH_CONTRAST_NOIR -> {
                filters.add(GPUImageGrayscaleFilter())
                filters.add(GPUImageContrastFilter(1.68f))
                filters.add(GPUImageBrightnessFilter(-0.04f))
            }
            FilterPreset.ILFORD_GRAIN -> {
                filters.add(GPUImageGrayscaleFilter())
                filters.add(GPUImageContrastFilter(1.30f))
            }
            FilterPreset.SILVER_GELATIN -> {
                filters.add(GPUImageGrayscaleFilter())
                filters.add(GPUImageSepiaToneFilter(0.18f))
                filters.add(GPUImageContrastFilter(1.22f))
            }
            FilterPreset.ANTIQUE_SEPIA -> {
                filters.add(GPUImageSepiaToneFilter(0.88f))
                filters.add(GPUImageContrastFilter(1.18f))
                filters.add(GPUImageSaturationFilter(0.75f))
            }
            FilterPreset.CINEMATIC -> {
                filters.add(GPUImageFilter(GPUImageFilter.NO_FILTER_VERTEX_SHADER, CINEMATIC_FRAGMENT_SHADER))
                filters.add(GPUImageContrastFilter(1.22f))
                filters.add(GPUImageSaturationFilter(1.18f))
            }
            FilterPreset.VINTAGE -> {
                filters.add(GPUImageSepiaToneFilter(0.48f))
                filters.add(GPUImageContrastFilter(1.1f))
                filters.add(GPUImageBrightnessFilter(0.04f))
            }
            FilterPreset.BLACK_AND_WHITE -> {
                filters.add(GPUImageGrayscaleFilter())
                filters.add(GPUImageContrastFilter(1.35f))
            }
            FilterPreset.HDR -> {
                filters.add(GPUImageContrastFilter(1.3f))
                filters.add(GPUImageSaturationFilter(1.45f))
                filters.add(GPUImageSharpenFilter(0.6f))
            }
            FilterPreset.CYBERPUNK -> {
                filters.add(GPUImageFilter(GPUImageFilter.NO_FILTER_VERTEX_SHADER, CYBERPUNK_FRAGMENT_SHADER))
                filters.add(GPUImageSaturationFilter(1.85f))
                filters.add(GPUImageContrastFilter(1.25f))
            }
            FilterPreset.COMIC_BOOK -> {
                filters.add(
                    GPUImageComicBookFilter(
                        style = GPUImageComicBookFilter.ComicStyle.COMIC_BOOK,
                        quantizeLevels = 4.0f,
                        edgeThreshold = 0.17f,
                        intensity = 0.92f
                    )
                )
            }
        }

        // Step 2: User Live Adjustments
        if (adjustments.brightness != 0.0f) {
            filters.add(GPUImageBrightnessFilter(adjustments.brightness))
        }
        if (adjustments.contrast != 1.0f) {
            filters.add(GPUImageContrastFilter(adjustments.contrast))
        }
        if (adjustments.saturation != 1.0f) {
            filters.add(GPUImageSaturationFilter(adjustments.saturation))
        }

        // Return empty passthrough filter if none applied
        if (filters.isEmpty()) {
            return GPUImageFilterGroup(listOf(GPUImageFilter()))
        }

        return GPUImageFilterGroup(filters)
    }

    fun setPreset(preset: FilterPreset) {
        currentPreset = preset
        gpuImage.setFilter(buildActiveFilterGroup())
    }

    fun setPreset(presetName: String) {
        applyPreset(presetName)
    }

    fun applyPreset(presetName: String) {
        val preset = when (presetName.lowercase(Locale.ROOT)) {
            "lut_rec709", "filter_lut_rec709" -> FilterPreset.LUT_REC709
            "lut_flat_log", "filter_lut_flat_log" -> FilterPreset.LUT_FLAT_LOG
            "lut_teal_orange", "filter_lut_blockbuster_teal_orange" -> FilterPreset.LUT_TEAL_ORANGE
            "lut_bleach_bypass", "filter_lut_bleach_bypass" -> FilterPreset.LUT_BLEACH_BYPASS
            "lut_anamorphic_cyan", "filter_lut_anamorphic_cyan" -> FilterPreset.LUT_ANAMORPHIC_CYAN
            "lut_matte_cinema", "filter_lut_matte_cinema" -> FilterPreset.LUT_MATTE_CINEMA
            "lut_midnight_neon", "filter_lut_midnight_neon" -> FilterPreset.LUT_MIDNIGHT_NEON
            "lut_warm_documentary", "filter_lut_warm_documentary" -> FilterPreset.LUT_WARM_DOCUMENTARY
            "lut_noir_cinema", "filter_lut_noir_cinema" -> FilterPreset.LUT_NOIR_CINEMA
            "golden_nostalgia", "filter_golden_nostalgia" -> FilterPreset.GOLDEN_NOSTALGIA
            "velvia_landscape", "filter_velvia_landscape" -> FilterPreset.VELVIA_LANDSCAPE
            "nordic_slate", "filter_nordic_slate" -> FilterPreset.NORDIC_SLATE
            "arctic_mist", "filter_arctic_mist" -> FilterPreset.ARCTIC_MIST
            "classic_mono", "filter_classic_mono" -> FilterPreset.CLASSIC_MONO
            "high_contrast_noir", "filter_high_contrast_noir" -> FilterPreset.HIGH_CONTRAST_NOIR
            "ilford_grain", "filter_ilford_grain" -> FilterPreset.ILFORD_GRAIN
            "silver_gelatin", "filter_silver_gelatin" -> FilterPreset.SILVER_GELATIN
            "antique_sepia", "filter_antique_sepia" -> FilterPreset.ANTIQUE_SEPIA
            "cinematic" -> FilterPreset.CINEMATIC
            "vintage" -> FilterPreset.VINTAGE
            "bw", "black_and_white", "mono" -> FilterPreset.BLACK_AND_WHITE
            "hdr" -> FilterPreset.HDR
            "cyberpunk", "cyber" -> FilterPreset.CYBERPUNK
            "comic_book", "comic", "cartoon" -> FilterPreset.COMIC_BOOK
            else -> FilterPreset.NORMAL
        }
        setPreset(preset)
    }

    fun applyPreset(preset: FilterPreset) {
        setPreset(preset)
    }

    fun getPreset(): FilterPreset = currentPreset

    fun setBrightnessPercent(percent: Int) {
        // Map -100..100 to -0.5f..0.5f
        adjustments.brightness = percent / 200.0f
        gpuImage.setFilter(buildActiveFilterGroup())
    }

    fun setContrastPercent(percent: Int) {
        // Map -100..100 to 0.5f..2.0f (0% -> 1.0f)
        adjustments.contrast = if (percent >= 0) {
            1.0f + (percent / 100.0f)
        } else {
            1.0f + (percent / 200.0f) // down to 0.5f
        }
        gpuImage.setFilter(buildActiveFilterGroup())
    }

    fun setSaturationPercent(percent: Int) {
        // Map -100..100 to 0.0f..2.0f (0% -> 1.0f)
        adjustments.saturation = 1.0f + (percent / 100.0f)
        gpuImage.setFilter(buildActiveFilterGroup())
    }

    fun resetAdjustments() {
        adjustments.brightness = 0.0f
        adjustments.contrast = 1.0f
        adjustments.saturation = 1.0f
        gpuImage.setFilter(buildActiveFilterGroup())
    }

    /**
     * Bakes the active filter directly into a high-res captured Bitmap
     */
    @Synchronized
    fun applyFilterToBitmap(sourceBitmap: Bitmap): Bitmap {
        if (currentPreset == FilterPreset.NORMAL && 
            adjustments.brightness == 0.0f && 
            adjustments.contrast == 1.0f && 
            adjustments.saturation == 1.0f) {
            return sourceBitmap
        }

        val filterGroup = buildActiveFilterGroup()
        gpuImage.setImage(sourceBitmap)
        gpuImage.setFilter(filterGroup)
        return gpuImage.bitmapWithFilterApplied
    }

    /**
     * Renders any Cartoon / Comic-Book Toon effect directly through the multi-pass GPUImage Sobel + Quantized Color Banding engine.
     */
    @Synchronized
    fun applyToonEffectToBitmap(
        sourceBitmap: Bitmap,
        effectId: String,
        intensity: Float = 0.90f
    ): Bitmap? {
        val style = when (effectId) {
            "cel_shaded_studio", "comic_book" -> GPUImageComicBookFilter.ComicStyle.COMIC_BOOK
            "graphic_novel_noir", "noir_graphic_novel" -> GPUImageComicBookFilter.ComicStyle.GRAPHIC_NOVEL
            "manga_ink" -> GPUImageComicBookFilter.ComicStyle.MANGA_INK
            "pop_art_print" -> GPUImageComicBookFilter.ComicStyle.POP_ART
            "line_art_minimalist" -> GPUImageComicBookFilter.ComicStyle.LINE_ART
            "ligne_claire" -> GPUImageComicBookFilter.ComicStyle.LIGNE_CLAIRE
            else -> return null
        }
        val levels = when (style) {
            GPUImageComicBookFilter.ComicStyle.LIGNE_CLAIRE -> 3.0f
            GPUImageComicBookFilter.ComicStyle.POP_ART -> 3.0f
            else -> 4.0f
        }
        val threshold = when (style) {
            GPUImageComicBookFilter.ComicStyle.LINE_ART,
            GPUImageComicBookFilter.ComicStyle.MANGA_INK -> 0.15f
            else -> 0.17f
        }
        return try {
            val comicFilter = GPUImageComicBookFilter(
                style = style,
                quantizeLevels = levels,
                edgeThreshold = threshold,
                intensity = intensity.coerceIn(0f, 1f)
            )
            toonGpuImage.setImage(sourceBitmap)
            toonGpuImage.setFilter(comicFilter)
            toonGpuImage.bitmapWithFilterApplied
        } catch (_: Throwable) {
            null
        }
    }
}
