package com.lumevra.camera.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GlbArModelEngine:
 * Parses binary glTF 2.0 (.glb) 3D models (from app assets or on-device storage),
 * normalizes geometry to unit size, decodes embedded PNG/JPEG textures, and renders
 * hardware-accelerated depth-buffered PBR-lit frames into a transparent AR Bitmap.
 */
class GlbArModelEngine(private val appContext: Context) {

    data class GlbPose(
        val normAnchorX: Float,
        val normAnchorY: Float,
        val normFaceWidth: Float,
        val modelScale: Float,
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
        val enableHeadOcclusion: Boolean = false,
        val normFaceCenterX: Float = 0.5f,
        val normFaceCenterY: Float = 0.5f,
        val normFaceHeight: Float = 0.35f
    )

    // Reusable depth-only unit sphere occluder buffers (allocated once on GL thread)
    private var occPosBuffer: FloatBuffer? = null
    private var occNormBuffer: FloatBuffer? = null
    private var occUvBuffer: FloatBuffer? = null
    private var occVertexCount: Int = 0

    private fun ensureHeadOccluderBuffers() {
        if (occPosBuffer != null && occVertexCount > 0) return
        val stacks = 12
        val slices = 16
        val triCount = stacks * slices * 2
        val pos = FloatArray(triCount * 3 * 3)
        val norm = FloatArray(triCount * 3 * 3)
        val uv = FloatArray(triCount * 3 * 2)
        var pi = 0
        var ui = 0
        fun putV(x: Float, y: Float, z: Float) {
            pos[pi] = x; norm[pi] = x; pi++
            pos[pi] = y; norm[pi] = y; pi++
            pos[pi] = z; norm[pi] = z; pi++
            uv[ui++] = 0f; uv[ui++] = 0f
        }
        for (i in 0 until stacks) {
            val phi0 = Math.PI * i / stacks
            val phi1 = Math.PI * (i + 1) / stacks
            val y0 = cos(phi0).toFloat(); val r0 = sin(phi0).toFloat()
            val y1 = cos(phi1).toFloat(); val r1 = sin(phi1).toFloat()
            for (j in 0 until slices) {
                val th0 = 2.0 * Math.PI * j / slices
                val th1 = 2.0 * Math.PI * (j + 1) / slices
                val x00 = (r0 * cos(th0)).toFloat(); val z00 = (r0 * sin(th0)).toFloat()
                val x01 = (r0 * cos(th1)).toFloat(); val z01 = (r0 * sin(th1)).toFloat()
                val x10 = (r1 * cos(th0)).toFloat(); val z10 = (r1 * sin(th0)).toFloat()
                val x11 = (r1 * cos(th1)).toFloat(); val z11 = (r1 * sin(th1)).toFloat()
                putV(x00, y0, z00); putV(x10, y1, z10); putV(x11, y1, z11)
                putV(x00, y0, z00); putV(x11, y1, z11); putV(x01, y0, z01)
            }
        }
        occPosBuffer = ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(pos); position(0) }
        occNormBuffer = ByteBuffer.allocateDirect(norm.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(norm); position(0) }
        occUvBuffer = ByteBuffer.allocateDirect(uv.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(uv); position(0) }
        occVertexCount = triCount * 3
    }

    private data class ParsedPrimitive(
        val positions: FloatArray,
        val normals: FloatArray,
        val uvs: FloatArray,
        val vertexCount: Int,
        val baseColor: FloatArray,
        val emissive: FloatArray,
        val metallic: Float,
        val roughness: Float,
        val textureBitmap: Bitmap?
    )

    private data class GpuPrimitive(
        val posBuffer: FloatBuffer,
        val normBuffer: FloatBuffer,
        val uvBuffer: FloatBuffer,
        val vertexCount: Int,
        val baseColor: FloatArray,
        val emissive: FloatArray,
        val metallic: Float,
        val roughness: Float,
        val textureId: Int
    )

    private val glExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GlbArModelEngine-GL").apply { isDaemon = true }
    }
    private val renderInFlight = AtomicBoolean(false)

    @Volatile
    private var loadedPrimitives: List<ParsedPrimitive> = emptyList()
    @Volatile
    private var gpuUploadNeeded: Boolean = false
    @Volatile
    private var isReleased: Boolean = false
    @Volatile
    var lastLoadError: String = ""
        private set

    // EGL / GLES20 state (owned exclusively by glExecutor thread)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var surfaceW: Int = 0
    private var surfaceH: Int = 0
    private var programId: Int = 0
    private var gpuPrimitives: List<GpuPrimitive> = emptyList()
    private var pixelReadBuffer: ByteBuffer? = null

    fun hasModelLoaded(): Boolean = loadedPrimitives.isNotEmpty()

    fun validateFile(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) return "File not found"
            val bytes = file.readBytes()
            val prevErr = lastLoadError
            lastLoadError = ""
            val parsed = parseGlb(bytes)
            val err = if (parsed.isEmpty()) lastLoadError.ifEmpty { "No readable 3D triangles found in .glb" } else null
            if (err == null) lastLoadError = prevErr
            err
        } catch (t: Throwable) {
            "Parse error: ${t.localizedMessage ?: t.javaClass.simpleName}"
        }
    }

    fun loadFromAsset(assetPath: String): Boolean {
        lastLoadError = ""
        return try {
            val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
            loadFromBytes(bytes)
        } catch (t: Throwable) {
            lastLoadError = t.localizedMessage ?: "Asset read error"
            Log.e(TAG, "Failed to load GLB asset: $assetPath", t)
            false
        }
    }

    fun loadFromFile(filePath: String): Boolean {
        lastLoadError = ""
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                lastLoadError = "Model file missing on device"
                return false
            }
            val bytes = file.readBytes()
            loadFromBytes(bytes)
        } catch (t: Throwable) {
            lastLoadError = t.localizedMessage ?: "File read error"
            Log.e(TAG, "Failed to load GLB file: $filePath", t)
            false
        }
    }

    fun loadFromBytes(bytes: ByteArray): Boolean {
        lastLoadError = ""
        return try {
            val parsed = parseGlb(bytes)
            if (parsed.isEmpty()) {
                if (lastLoadError.isEmpty()) lastLoadError = "No valid 3D mesh triangles found"
                return false
            }
            loadedPrimitives = parsed
            gpuUploadNeeded = true
            true
        } catch (t: Throwable) {
            lastLoadError = t.localizedMessage ?: t.javaClass.simpleName
            Log.e(TAG, "Failed to parse GLB binary payload", t)
            false
        }
    }

    fun renderAsync(width: Int, height: Int, pose: GlbPose, onResult: (Bitmap?) -> Unit) {
        if (isReleased || loadedPrimitives.isEmpty()) return
        if (!renderInFlight.compareAndSet(false, true)) return
        glExecutor.execute {
            try {
                val bmp = renderInternalOnGlThread(width, height, pose)
                onResult(bmp)
            } catch (t: Throwable) {
                Log.w(TAG, "Async GLB render warning", t)
            } finally {
                renderInFlight.set(false)
            }
        }
    }

    fun renderSync(width: Int, height: Int, pose: GlbPose): Bitmap? {
        if (isReleased || loadedPrimitives.isEmpty()) return null
        return try {
            glExecutor.submit(Callable { renderInternalOnGlThread(width, height, pose) })
                .get(350L, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "Sync GLB render fallback", t)
            null
        }
    }

    fun release() {
        isReleased = true
        try {
            glExecutor.execute {
                cleanupGpuPrimitives()
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            glExecutor.shutdown()
        } catch (_: Throwable) {}
    }

    private fun ensureEglSurface(width: Int, height: Int): Boolean {
        val w = width.coerceIn(64, 1024)
        val h = height.coerceIn(64, 1024)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false
            val cfgAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numCfg = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, numCfg, 0) || numCfg[0] <= 0) {
                return false
            }
            eglConfig = configs[0]
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false
        }

        if (eglSurface == EGL14.EGL_NO_SURFACE || surfaceW != w || surfaceH != h) {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            val surfAttribs = intArrayOf(
                EGL14.EGL_WIDTH, w,
                EGL14.EGL_HEIGHT, h,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false
            surfaceW = w
            surfaceH = h
            pixelReadBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        if (programId == 0) {
            programId = buildShaderProgram()
            if (programId == 0) return false
        }
        return true
    }

    private fun cleanupGpuPrimitives() {
        val texIds = gpuPrimitives.mapNotNull { if (it.textureId != 0) it.textureId else null }.toIntArray()
        if (texIds.isNotEmpty()) {
            GLES20.glDeleteTextures(texIds.size, texIds, 0)
        }
        gpuPrimitives = emptyList()
    }

    private fun uploadGpuPrimitivesIfNeeded() {
        if (!gpuUploadNeeded) return
        cleanupGpuPrimitives()
        val src = loadedPrimitives
        val list = ArrayList<GpuPrimitive>(src.size)
        for (p in src) {
            val posBuf = ByteBuffer.allocateDirect(p.positions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            posBuf.put(p.positions).position(0)
            val normBuf = ByteBuffer.allocateDirect(p.normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            normBuf.put(p.normals).position(0)
            val uvBuf = ByteBuffer.allocateDirect(p.uvs.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            uvBuf.put(p.uvs).position(0)

            var texId = 0
            if (p.textureBitmap != null && !p.textureBitmap.isRecycled) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                texId = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, p.textureBitmap, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            }
            list.add(
                GpuPrimitive(
                    posBuffer = posBuf,
                    normBuffer = normBuf,
                    uvBuffer = uvBuf,
                    vertexCount = p.vertexCount,
                    baseColor = p.baseColor,
                    emissive = p.emissive,
                    metallic = p.metallic,
                    roughness = p.roughness,
                    textureId = texId
                )
            )
        }
        gpuPrimitives = list
        gpuUploadNeeded = false
    }

    private fun renderInternalOnGlThread(width: Int, height: Int, pose: GlbPose): Bitmap? {
        if (!ensureEglSurface(width, height)) return null
        uploadGpuPrimitivesIfNeeded()
        if (gpuPrimitives.isEmpty()) return null

        val w = surfaceW
        val h = surfaceH
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val aspect = w.toFloat() / h.toFloat()
        val fovDeg = 42f
        val camZ = 5.0f
        val proj = FloatArray(16)
        val view = FloatArray(16)
        val model = FloatArray(16)
        val mv = FloatArray(16)
        val mvp = FloatArray(16)

        Matrix.perspectiveM(proj, 0, fovDeg, aspect, 0.05f, 100f)
        Matrix.setLookAtM(view, 0, 0f, 0f, camZ, 0f, 0f, 0f, 0f, 1f, 0f)

        val visH = (2.0 * kotlin.math.tan(Math.toRadians((fovDeg * 0.5f).toDouble())) * camZ).toFloat()
        val visW = visH * aspect
        val worldX = (pose.normAnchorX - 0.5f) * visW
        val worldY = (0.5f - pose.normAnchorY) * visH
        val finalScale = (pose.normFaceWidth * visW * pose.modelScale).coerceAtLeast(0.04f)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, worldX, worldY, 0f)
        Matrix.rotateM(model, 0, pose.rollDeg, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, pose.yawDeg, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, pose.pitchDeg, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, finalScale, finalScale, finalScale)

        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        GLES20.glUseProgram(programId)
        val locMvp = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
        val locMv = GLES20.glGetUniformLocation(programId, "uMvMatrix")
        val locColor = GLES20.glGetUniformLocation(programId, "uBaseColor")
        val locEmissive = GLES20.glGetUniformLocation(programId, "uEmissive")
        val locPbr = GLES20.glGetUniformLocation(programId, "uPbrParams")
        val locHasTex = GLES20.glGetUniformLocation(programId, "uHasTex")
        val locTex = GLES20.glGetUniformLocation(programId, "uTex")
        val locPos = GLES20.glGetAttribLocation(programId, "aPosition")
        val locNorm = GLES20.glGetAttribLocation(programId, "aNormal")
        val locUv = GLES20.glGetAttribLocation(programId, "aTexCoord")

        // Depth-only 3D head ellipsoid occlusion pass (masks rear geometry of crowns/glasses behind head)
        if (pose.enableHeadOcclusion) {
            ensureHeadOccluderBuffers()
            val oPos = occPosBuffer
            val oNorm = occNormBuffer
            val oUv = occUvBuffer
            if (oPos != null && oNorm != null && oUv != null && occVertexCount > 0) {
                val faceWorldW = (pose.normFaceWidth * visW).coerceAtLeast(0.05f)
                val faceWorldH = (pose.normFaceHeight * visH).coerceAtLeast(0.05f)
                val faceCenterWorldX = (pose.normFaceCenterX - 0.5f) * visW
                val faceCenterWorldY = (0.5f - pose.normFaceCenterY) * visH
                val occModel = FloatArray(16)
                val occMv = FloatArray(16)
                val occMvp = FloatArray(16)
                Matrix.setIdentityM(occModel, 0)
                Matrix.translateM(occModel, 0, faceCenterWorldX, faceCenterWorldY, -faceWorldW * 0.34f)
                Matrix.rotateM(occModel, 0, pose.rollDeg, 0f, 0f, 1f)
                Matrix.scaleM(occModel, 0, faceWorldW * 0.45f, faceWorldH * 0.56f, faceWorldW * 0.42f)
                Matrix.multiplyMM(occMv, 0, view, 0, occModel, 0)
                Matrix.multiplyMM(occMvp, 0, proj, 0, occMv, 0)

                GLES20.glColorMask(false, false, false, false)
                GLES20.glUniformMatrix4fv(locMvp, 1, false, occMvp, 0)
                GLES20.glUniformMatrix4fv(locMv, 1, false, occMv, 0)
                GLES20.glUniform1i(locHasTex, 0)
                GLES20.glEnableVertexAttribArray(locPos)
                GLES20.glVertexAttribPointer(locPos, 3, GLES20.GL_FLOAT, false, 0, oPos)
                GLES20.glEnableVertexAttribArray(locNorm)
                GLES20.glVertexAttribPointer(locNorm, 3, GLES20.GL_FLOAT, false, 0, oNorm)
                GLES20.glEnableVertexAttribArray(locUv)
                GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 0, oUv)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, occVertexCount)
                GLES20.glDisableVertexAttribArray(locPos)
                GLES20.glDisableVertexAttribArray(locNorm)
                GLES20.glDisableVertexAttribArray(locUv)
                GLES20.glColorMask(true, true, true, true)
            }
        }

        GLES20.glUniformMatrix4fv(locMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(locMv, 1, false, mv, 0)

        for (p in gpuPrimitives) {
            GLES20.glUniform4fv(locColor, 1, p.baseColor, 0)
            GLES20.glUniform3fv(locEmissive, 1, p.emissive, 0)
            GLES20.glUniform2f(locPbr, p.metallic, p.roughness)
            if (p.textureId != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.textureId)
                GLES20.glUniform1i(locTex, 0)
                GLES20.glUniform1i(locHasTex, 1)
            } else {
                GLES20.glUniform1i(locHasTex, 0)
            }

            GLES20.glEnableVertexAttribArray(locPos)
            GLES20.glVertexAttribPointer(locPos, 3, GLES20.GL_FLOAT, false, 0, p.posBuffer)
            GLES20.glEnableVertexAttribArray(locNorm)
            GLES20.glVertexAttribPointer(locNorm, 3, GLES20.GL_FLOAT, false, 0, p.normBuffer)
            GLES20.glEnableVertexAttribArray(locUv)
            GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 0, p.uvBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, p.vertexCount)

            GLES20.glDisableVertexAttribArray(locPos)
            GLES20.glDisableVertexAttribArray(locNorm)
            GLES20.glDisableVertexAttribArray(locUv)
        }

        val readBuf = pixelReadBuffer ?: return null
        readBuf.rewind()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
        readBuf.rewind()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(readBuf)
        return bmp
    }

    private fun buildShaderProgram(): Int {
        // Note: gl_Position.y is negated so GLES bottom-up glReadPixels directly yields top-down Android Bitmap pixels
        val vs = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uMvMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec2 aTexCoord;
            varying vec3 vNormal;
            varying vec3 vViewPos;
            varying vec2 vUv;
            void main() {
                vec4 clip = uMvpMatrix * vec4(aPosition, 1.0);
                gl_Position = vec4(clip.x, -clip.y, clip.z, clip.w);
                vNormal = normalize(mat3(uMvMatrix) * aNormal);
                vViewPos = -(uMvMatrix * vec4(aPosition, 1.0)).xyz;
                vUv = aTexCoord;
            }
        """.trimIndent()

        val fs = """
            precision mediump float;
            uniform vec4 uBaseColor;
            uniform vec3 uEmissive;
            uniform vec2 uPbrParams;
            uniform int uHasTex;
            uniform sampler2D uTex;
            varying vec3 vNormal;
            varying vec3 vViewPos;
            varying vec2 vUv;
            void main() {
                vec4 albedo = uBaseColor;
                if (uHasTex == 1) {
                    albedo *= texture2D(uTex, vUv);
                }
                if (albedo.a < 0.02) discard;

                vec3 N = normalize(vNormal);
                if (!gl_FrontFacing) N = -N;
                vec3 V = normalize(vViewPos);
                vec3 Lkey = normalize(vec3(0.55, 0.75, 0.85));
                vec3 Lrim = normalize(vec3(-0.65, 0.45, -0.60));

                float metallic = clamp(uPbrParams.x, 0.0, 1.0);
                float roughness = clamp(uPbrParams.y, 0.08, 1.0);

                float diffKey = max(dot(N, Lkey), 0.0);
                float hemi = N.y * 0.5 + 0.5;
                vec3 skyFill = mix(vec3(0.22, 0.25, 0.34), vec3(0.75, 0.88, 1.0), hemi) * 0.52;

                vec3 H = normalize(Lkey + V);
                float specPow = mix(84.0, 8.0, roughness);
                float spec = pow(max(dot(N, H), 0.0), specPow) * mix(0.35, 1.25, metallic);
                vec3 specColor = mix(vec3(1.0, 0.97, 0.92), albedo.rgb, metallic);

                float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * max(dot(N, Lrim) * 0.5 + 0.5, 0.0) * 0.45;
                vec3 diffuse = albedo.rgb * (vec3(1.0, 0.96, 0.90) * diffKey * (1.0 - metallic * 0.45) + skyFill);
                vec3 col = diffuse + specColor * spec + vec3(0.45, 0.82, 1.0) * rim + uEmissive;

                gl_FragColor = vec4(clamp(col, 0.0, 1.0), albedo.a);
            }
        """.trimIndent()

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        if (vShader == 0 || fShader == 0) return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        if (linked[0] == 0) {
            Log.e(TAG, "GLB shader link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "GLB shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    // =========================================================================
    // Pure Kotlin glTF 2.0 Binary (.glb) Parser (Quantization, Strip/Fan, Downsampled Textures, Poly Cap)
    // =========================================================================
    private fun decodeDownsampledBitmap(src: ByteArray, offset: Int, length: Int, maxDim: Int = 512): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(src, offset, length, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while ((w / sample) > maxDim || (h / sample) > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(src, offset, length, opts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeDataUriBytes(uri: String): ByteArray? {
        if (!uri.startsWith("data:")) return null
        val comma = uri.indexOf(',')
        if (comma < 0 || comma + 1 >= uri.length) return null
        return try {
            Base64.decode(uri.substring(comma + 1), Base64.DEFAULT)
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseGlb(bytes: ByteArray): List<ParsedPrimitive> {
        if (bytes.size < 20) {
            lastLoadError = "File too small to be a valid .glb"
            return emptyList()
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        val version = buf.int
        val totalLen = buf.int
        if (magic != 0x46546C67 || version != 2 || totalLen < 20) {
            lastLoadError = "Not a valid glTF 2.0 binary (.glb)"
            return emptyList()
        }

        var jsonStr: String? = null
        var binBytes: ByteArray? = null
        var binOffset = 0
        var binLength = 0

        var pos = 12
        val limit = min(bytes.size, if (totalLen > 0) totalLen else bytes.size)
        while (pos + 8 <= limit) {
            buf.position(pos)
            val chunkLen = buf.int
            val chunkType = buf.int
            val dataStart = pos + 8
            if (chunkLen < 0 || dataStart > bytes.size) break
            val safeLen = min(chunkLen, bytes.size - dataStart)
            when (chunkType) {
                0x4E4F534A -> jsonStr = String(bytes, dataStart, safeLen, Charsets.UTF_8).trimEnd(0.toChar(), ' ')
                0x004E4942 -> {
                    binBytes = bytes
                    binOffset = dataStart
                    binLength = safeLen
                }
            }
            pos = dataStart + chunkLen
        }

        if (jsonStr == null) {
            lastLoadError = "Missing glTF JSON chunk"
            return emptyList()
        }
        val root = JSONObject(jsonStr)
        val buffersArr = root.optJSONArray("buffers")
        if (binBytes == null && buffersArr != null && buffersArr.length() > 0) {
            val uri = buffersArr.optJSONObject(0)?.optString("uri", "") ?: ""
            val decoded = decodeDataUriBytes(uri)
            if (decoded != null) {
                binBytes = decoded
                binOffset = 0
                binLength = decoded.size
            }
        }
        if (binBytes == null) {
            lastLoadError = "Missing binary mesh chunk (external .bin not bundled)"
            return emptyList()
        }
        val binData = binBytes!!

        val extUsed = root.optJSONArray("extensionsUsed")
        var hasDracoExt = false
        if (extUsed != null) {
            for (ei in 0 until extUsed.length()) {
                if (extUsed.optString(ei) == "KHR_draco_mesh_compression") {
                    hasDracoExt = true
                    break
                }
            }
        }

        val accessors = root.optJSONArray("accessors") ?: run {
            lastLoadError = "GLB contains no geometry accessors"
            return emptyList()
        }
        val bufferViews = root.optJSONArray("bufferViews") ?: run {
            lastLoadError = "GLB contains no bufferViews"
            return emptyList()
        }
        val meshes = root.optJSONArray("meshes") ?: run {
            lastLoadError = "GLB contains no 3D meshes"
            return emptyList()
        }
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        val materials = root.optJSONArray("materials") ?: JSONArray()
        val textures = root.optJSONArray("textures") ?: JSONArray()
        val images = root.optJSONArray("images") ?: JSONArray()

        // Decode embedded PNG/JPEG textures once per image index (downsampled <= 512px to avoid 4K OOM)
        val decodedImages = HashMap<Int, Bitmap>()
        for (i in 0 until images.length()) {
            val imgObj = images.optJSONObject(i) ?: continue
            val bvIdx = imgObj.optInt("bufferView", -1)
            if (bvIdx >= 0 && bvIdx < bufferViews.length()) {
                val bv = bufferViews.getJSONObject(bvIdx)
                val off = binOffset + bv.optInt("byteOffset", 0)
                val len = bv.optInt("byteLength", 0)
                if (off >= binOffset && off + len <= binOffset + binLength && len > 0) {
                    val bmp = decodeDownsampledBitmap(binData, off, len, 512)
                    if (bmp != null) decodedImages[i] = bmp
                }
            } else {
                val imgUri = imgObj.optString("uri", "")
                val imgBytes = decodeDataUriBytes(imgUri)
                if (imgBytes != null && imgBytes.isNotEmpty()) {
                    val bmp = decodeDownsampledBitmap(imgBytes, 0, imgBytes.size, 512)
                    if (bmp != null) decodedImages[i] = bmp
                }
            }
        }

        val bb = ByteBuffer.wrap(binData).order(ByteOrder.LITTLE_ENDIAN)

        fun readFloatAccessor(accIdx: Int, expectedComps: Int): FloatArray? {
            if (accIdx < 0 || accIdx >= accessors.length()) return null
            val acc = accessors.getJSONObject(accIdx)
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return null
            val bv = bufferViews.getJSONObject(bvIdx)
            val count = acc.optInt("count", 0)
            if (count <= 0) return null
            val compType = acc.optInt("componentType", 5126)
            val normalized = acc.optBoolean("normalized", false)
            val compSize = when (compType) {
                5120, 5121 -> 1
                5122, 5123 -> 2
                5126 -> 4
                else -> return null
            }
            val packedSize = expectedComps * compSize
            val rawStride = bv.optInt("byteStride", 0)
            val stride = if (rawStride > 0) max(rawStride, packedSize) else packedSize
            val base = binOffset + bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val out = FloatArray(count * expectedComps)
            for (i in 0 until count) {
                val elemOff = base + i * stride
                if (elemOff < 0 || elemOff + packedSize > binData.size) break
                for (c in 0 until expectedComps) {
                    val p = elemOff + c * compSize
                    val v = when (compType) {
                        5126 -> bb.getFloat(p)
                        5120 -> {
                            val raw = bb.get(p).toFloat()
                            if (normalized) max(raw / 127f, -1f) else raw
                        }
                        5121 -> {
                            val raw = (bb.get(p).toInt() and 0xFF).toFloat()
                            if (normalized) raw / 255f else raw
                        }
                        5122 -> {
                            val raw = bb.getShort(p).toFloat()
                            if (normalized) max(raw / 32767f, -1f) else raw
                        }
                        5123 -> {
                            val raw = (bb.getShort(p).toInt() and 0xFFFF).toFloat()
                            if (normalized) raw / 65535f else raw
                        }
                        else -> 0f
                    }
                    out[i * expectedComps + c] = if (v.isFinite()) v else 0f
                }
            }
            return out
        }

        fun readIndexAccessor(accIdx: Int): IntArray? {
            if (accIdx < 0 || accIdx >= accessors.length()) return null
            val acc = accessors.getJSONObject(accIdx)
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return null
            val bv = bufferViews.getJSONObject(bvIdx)
            val count = acc.optInt("count", 0)
            if (count <= 0) return null
            val compType = acc.optInt("componentType", 5123)
            val elemSize = when (compType) {
                5121 -> 1
                5123 -> 2
                5125 -> 4
                else -> return null
            }
            val rawStride = bv.optInt("byteStride", 0)
            val stride = if (rawStride > 0) max(rawStride, elemSize) else elemSize
            val base = binOffset + bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val out = IntArray(count)
            for (i in 0 until count) {
                val off = base + i * stride
                if (off < 0 || off + elemSize > binData.size) break
                out[i] = when (compType) {
                    5121 -> bb.get(off).toInt() and 0xFF
                    5123 -> bb.getShort(off).toInt() and 0xFFFF
                    else -> bb.getInt(off)
                }
            }
            return out
        }

        val collected = ArrayList<ParsedPrimitive>()
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
        var sawDracoPrimitive = false
        var totalTrianglesSoFar = 0
        val maxTotalTriangles = 45000

        fun appendMesh(meshIdx: Int, worldMat: FloatArray) {
            if (meshIdx < 0 || meshIdx >= meshes.length()) return
            if (totalTrianglesSoFar >= maxTotalTriangles) return
            val mesh = meshes.getJSONObject(meshIdx)
            val prims = mesh.optJSONArray("primitives") ?: return
            for (pi in 0 until prims.length()) {
                val prim = prims.getJSONObject(pi)
                if (prim.optJSONObject("extensions")?.has("KHR_draco_mesh_compression") == true) {
                    sawDracoPrimitive = true
                }
                val mode = prim.optInt("mode", 4)
                if (mode != 4 && mode != 5 && mode != 6) continue // TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN
                val attrs = prim.optJSONObject("attributes") ?: continue
                val rawPos = readFloatAccessor(attrs.optInt("POSITION", -1), 3) ?: continue
                val rawNorm = readFloatAccessor(attrs.optInt("NORMAL", -1), 3)
                val rawUv = readFloatAccessor(attrs.optInt("TEXCOORD_0", -1), 2)
                val rawIndices = if (prim.has("indices")) readIndexAccessor(prim.optInt("indices", -1)) else null

                val srcCount = rawIndices?.size ?: (rawPos.size / 3)
                if (srcCount < 3) continue

                // Expand TRIANGLES / TRIANGLE_STRIP / TRIANGLE_FAN into triangle index triples
                val triIndices: IntArray = when (mode) {
                    4 -> {
                        val triCount = srcCount / 3
                        val arr = IntArray(triCount * 3)
                        for (i in 0 until triCount * 3) {
                            arr[i] = if (rawIndices != null) rawIndices[i] else i
                        }
                        arr
                    }
                    5 -> {
                        val triCount = (srcCount - 2).coerceAtLeast(0)
                        val arr = IntArray(triCount * 3)
                        for (t in 0 until triCount) {
                            val i0 = if (rawIndices != null) rawIndices[t] else t
                            val i1 = if (rawIndices != null) rawIndices[t + 1] else (t + 1)
                            val i2 = if (rawIndices != null) rawIndices[t + 2] else (t + 2)
                            if (t % 2 == 0) {
                                arr[t * 3] = i0; arr[t * 3 + 1] = i1; arr[t * 3 + 2] = i2
                            } else {
                                arr[t * 3] = i1; arr[t * 3 + 1] = i0; arr[t * 3 + 2] = i2
                            }
                        }
                        arr
                    }
                    else -> {
                        val triCount = (srcCount - 2).coerceAtLeast(0)
                        val arr = IntArray(triCount * 3)
                        val rootIdx = if (rawIndices != null) rawIndices[0] else 0
                        for (t in 0 until triCount) {
                            arr[t * 3] = rootIdx
                            arr[t * 3 + 1] = if (rawIndices != null) rawIndices[t + 1] else (t + 1)
                            arr[t * 3 + 2] = if (rawIndices != null) rawIndices[t + 2] else (t + 2)
                        }
                        arr
                    }
                }

                val availTris = triIndices.size / 3
                if (availTris <= 0) continue
                val remainingBudget = (maxTotalTriangles - totalTrianglesSoFar).coerceAtLeast(0)
                if (remainingBudget <= 0) break
                // Stride-decimate ultra-high-poly meshes smoothly so Sketchfab models never OOM
                val triStep = if (availTris > remainingBudget) max(1, (availTris + remainingBudget - 1) / remainingBudget) else 1
                val keptTris = min(remainingBudget, (availTris + triStep - 1) / triStep)
                val vertCount = keptTris * 3
                totalTrianglesSoFar += keptTris

                val outPos = FloatArray(vertCount * 3)
                val outNorm = FloatArray(vertCount * 3)
                val outUv = FloatArray(vertCount * 2)

                val inV = FloatArray(4)
                val outV = FloatArray(4)
                val inN = FloatArray(4)
                val outN = FloatArray(4)

                var outI = 0
                var srcTri = 0
                while (srcTri < availTris && outI + 2 < vertCount) {
                    for (k in 0..2) {
                        val vi = triIndices[srcTri * 3 + k]
                        if (vi >= 0 && vi * 3 + 2 < rawPos.size) {
                            inV[0] = rawPos[vi * 3]
                            inV[1] = rawPos[vi * 3 + 1]
                            inV[2] = rawPos[vi * 3 + 2]
                            inV[3] = 1f
                            Matrix.multiplyMV(outV, 0, worldMat, 0, inV, 0)
                            val vx = if (outV[0].isFinite()) outV[0] else 0f
                            val vy = if (outV[1].isFinite()) outV[1] else 0f
                            val vz = if (outV[2].isFinite()) outV[2] else 0f
                            outPos[outI * 3] = vx
                            outPos[outI * 3 + 1] = vy
                            outPos[outI * 3 + 2] = vz
                            minX = min(minX, vx); minY = min(minY, vy); minZ = min(minZ, vz)
                            maxX = max(maxX, vx); maxY = max(maxY, vy); maxZ = max(maxZ, vz)

                            if (rawNorm != null && vi * 3 + 2 < rawNorm.size) {
                                inN[0] = rawNorm[vi * 3]
                                inN[1] = rawNorm[vi * 3 + 1]
                                inN[2] = rawNorm[vi * 3 + 2]
                                inN[3] = 0f
                                Matrix.multiplyMV(outN, 0, worldMat, 0, inN, 0)
                                val len = sqrt(outN[0] * outN[0] + outN[1] * outN[1] + outN[2] * outN[2]).coerceAtLeast(1e-5f)
                                outNorm[outI * 3] = outN[0] / len
                                outNorm[outI * 3 + 1] = outN[1] / len
                                outNorm[outI * 3 + 2] = outN[2] / len
                            }
                            if (rawUv != null && vi * 2 + 1 < rawUv.size) {
                                outUv[outI * 2] = rawUv[vi * 2]
                                outUv[outI * 2 + 1] = rawUv[vi * 2 + 1]
                            }
                        }
                        outI++
                    }
                    srcTri += triStep
                }

                // Auto-compute flat triangle normals if model omitted NORMAL attribute
                if (rawNorm == null) {
                    var t = 0
                    while (t + 2 < vertCount) {
                        val ax = outPos[t * 3]; val ay = outPos[t * 3 + 1]; val az = outPos[t * 3 + 2]
                        val bx = outPos[(t + 1) * 3]; val by = outPos[(t + 1) * 3 + 1]; val bz = outPos[(t + 1) * 3 + 2]
                        val cx = outPos[(t + 2) * 3]; val cy = outPos[(t + 2) * 3 + 1]; val cz = outPos[(t + 2) * 3 + 2]
                        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
                        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
                        var nx = e1y * e2z - e1z * e2y
                        var ny = e1z * e2x - e1x * e2z
                        var nz = e1x * e2y - e1y * e2x
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                        nx /= nLen; ny /= nLen; nz /= nLen
                        for (k in 0..2) {
                            outNorm[(t + k) * 3] = nx
                            outNorm[(t + k) * 3 + 1] = ny
                            outNorm[(t + k) * 3 + 2] = nz
                        }
                        t += 3
                    }
                }

                val baseColor = floatArrayOf(0.88f, 0.88f, 0.92f, 1.0f)
                val emissive = floatArrayOf(0f, 0f, 0f)
                var metallic = 0.25f
                var roughness = 0.55f
                var texBmp: Bitmap? = null

                val matIdx = prim.optInt("material", -1)
                if (matIdx >= 0 && matIdx < materials.length()) {
                    val mat = materials.getJSONObject(matIdx)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness")
                    if (pbr != null) {
                        val bcf = pbr.optJSONArray("baseColorFactor")
                        if (bcf != null && bcf.length() >= 4) {
                            for (c in 0..3) baseColor[c] = bcf.optDouble(c, 1.0).toFloat()
                        }
                        metallic = pbr.optDouble("metallicFactor", 0.25).toFloat()
                        roughness = pbr.optDouble("roughnessFactor", 0.55).toFloat()
                        val bct = pbr.optJSONObject("baseColorTexture")
                        if (bct != null) {
                            val texIdx = bct.optInt("index", -1)
                            if (texIdx >= 0 && texIdx < textures.length()) {
                                val srcImgIdx = textures.getJSONObject(texIdx).optInt("source", -1)
                                texBmp = decodedImages[srcImgIdx]
                            }
                        }
                    }
                    val emf = mat.optJSONArray("emissiveFactor")
                    if (emf != null && emf.length() >= 3) {
                        for (c in 0..2) emissive[c] = emf.optDouble(c, 0.0).toFloat()
                    }
                }

                collected.add(
                    ParsedPrimitive(
                        positions = outPos,
                        normals = outNorm,
                        uvs = outUv,
                        vertexCount = vertCount,
                        baseColor = baseColor,
                        emissive = emissive,
                        metallic = metallic,
                        roughness = roughness,
                        textureBitmap = texBmp
                    )
                )
            }
        }

        fun nodeLocalMatrix(node: JSONObject): FloatArray {
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            val matArr = node.optJSONArray("matrix")
            if (matArr != null && matArr.length() == 16) {
                for (i in 0 until 16) m[i] = matArr.optDouble(i, 0.0).toFloat()
                return m
            }
            val t = node.optJSONArray("translation")
            if (t != null && t.length() >= 3) {
                Matrix.translateM(m, 0, t.optDouble(0, 0.0).toFloat(), t.optDouble(1, 0.0).toFloat(), t.optDouble(2, 0.0).toFloat())
            }
            val q = node.optJSONArray("rotation")
            if (q != null && q.length() >= 4) {
                val qx = q.optDouble(0, 0.0).toFloat()
                val qy = q.optDouble(1, 0.0).toFloat()
                val qz = q.optDouble(2, 0.0).toFloat()
                val qw = q.optDouble(3, 1.0).toFloat()
                val rMat = floatArrayOf(
                    1f - 2f * (qy * qy + qz * qz), 2f * (qx * qy + qz * qw), 2f * (qx * qz - qy * qw), 0f,
                    2f * (qx * qy - qz * qw), 1f - 2f * (qx * qx + qz * qz), 2f * (qy * qz + qx * qw), 0f,
                    2f * (qx * qz + qy * qw), 2f * (qy * qz - qx * qw), 1f - 2f * (qx * qx + qy * qy), 0f,
                    0f, 0f, 0f, 1f
                )
                val tmp = FloatArray(16)
                Matrix.multiplyMM(tmp, 0, m, 0, rMat, 0)
                System.arraycopy(tmp, 0, m, 0, 16)
            }
            val s = node.optJSONArray("scale")
            if (s != null && s.length() >= 3) {
                Matrix.scaleM(m, 0, s.optDouble(0, 1.0).toFloat(), s.optDouble(1, 1.0).toFloat(), s.optDouble(2, 1.0).toFloat())
            }
            return m
        }

        fun visitNode(nodeIdx: Int, parentMat: FloatArray) {
            if (nodeIdx < 0 || nodeIdx >= nodes.length()) return
            val node = nodes.getJSONObject(nodeIdx)
            val local = nodeLocalMatrix(node)
            val world = FloatArray(16)
            Matrix.multiplyMM(world, 0, parentMat, 0, local, 0)
            if (node.has("mesh")) {
                appendMesh(node.optInt("mesh", -1), world)
            }
            val children = node.optJSONArray("children")
            if (children != null) {
                for (i in 0 until children.length()) {
                    visitNode(children.optInt(i, -1), world)
                }
            }
        }

        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val scenes = root.optJSONArray("scenes")
        val sceneIdx = root.optInt("scene", 0)
        if (scenes != null && sceneIdx >= 0 && sceneIdx < scenes.length()) {
            val rootNodes = scenes.getJSONObject(sceneIdx).optJSONArray("nodes")
            if (rootNodes != null) {
                for (i in 0 until rootNodes.length()) {
                    visitNode(rootNodes.optInt(i, -1), identity)
                }
            }
        }
        if (collected.isEmpty()) {
            for (mi in 0 until meshes.length()) appendMesh(mi, identity)
        }
        if (collected.isEmpty()) {
            lastLoadError = if (hasDracoExt || sawDracoPrimitive) {
                "Draco-compressed GLB — re-export uncompressed (disable Draco in Blender / gltf.report)"
            } else {
                "GLB has no readable triangle geometry"
            }
            return emptyList()
        }

        // Normalize all primitives to centered unit 1.0 bounding box
        if (!minX.isFinite() || !maxX.isFinite()) {
            minX = -0.5f; maxX = 0.5f; minY = -0.5f; maxY = 0.5f; minZ = -0.5f; maxZ = 0.5f
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val extent = max(max(maxX - minX, maxY - minY), maxZ - minZ).coerceAtLeast(1e-4f)
        val invScale = 1.0f / extent

        for (p in collected) {
            var i = 0
            while (i + 2 < p.positions.size) {
                p.positions[i] = (p.positions[i] - cx) * invScale
                p.positions[i + 1] = (p.positions[i + 1] - cy) * invScale
                p.positions[i + 2] = (p.positions[i + 2] - cz) * invScale
                i += 3
            }
        }
        return collected
    }

    companion object {
        private const val TAG = "GlbArModelEngine"
    }
}
