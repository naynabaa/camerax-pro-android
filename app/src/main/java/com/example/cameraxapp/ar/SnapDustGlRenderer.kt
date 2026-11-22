package com.example.cameraxapp.ar

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SnapDustGlRenderer : GLSurfaceView.Renderer {

    enum class TargetRegion { FACE, BODY, FACE_AND_BODY }

    @Volatile var targetRegion: TargetRegion = TargetRegion.FACE_AND_BODY
    @Volatile var windDirection: Float = 1.0f
    @Volatile var windStrength: Float = 1.0f
    @Volatile var turbulence: Float = 1.15f
    @Volatile var emberRatio: Float = 0.06f
    @Volatile var pixelRatio: Float = 2.5f
    @Volatile var flowProgress: Float = 0.0f // Unbounded one-way timeline: 0.0 -> 1.0 (vanished) -> 2.4 (mist cleared)
    @Volatile var shockOriginX: Float = 0.70f
    @Volatile var shockOriginY: Float = 0.42f
    @Volatile var shockStartMs: Long = 0L

    private var programId: Int = 0
    private var vboId: Int = 0
    private var aOriginLoc = -1
    private var aVelocityLoc = -1
    private var aColorLoc = -1
    private var aMetaLoc = -1
    private var uTimeLoc = -1
    private var uFlowProgressLoc = -1
    private var uWindDirLoc = -1
    private var uWindStrengthLoc = -1
    private var uTurbulenceLoc = -1
    private var uPixelRatioLoc = -1
    private var uShockOriginLoc = -1
    private var uShockTimeLoc = -1

    @Volatile private var pendingBuffer: FloatBuffer? = null
    @Volatile private var pendingCount: Int = 0
    private var activeCount: Int = 0
    private var startTimeMs: Long = System.currentTimeMillis()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vs == 0 || fs == 0) return

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vs)
        GLES20.glAttachShader(programId, fs)
        GLES20.glLinkProgram(programId)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        aOriginLoc = GLES20.glGetAttribLocation(programId, "aOrigin")
        aVelocityLoc = GLES20.glGetAttribLocation(programId, "aVelocity")
        aColorLoc = GLES20.glGetAttribLocation(programId, "aColor")
        aMetaLoc = GLES20.glGetAttribLocation(programId, "aMeta")

        uTimeLoc = GLES20.glGetUniformLocation(programId, "uTime")
        uFlowProgressLoc = GLES20.glGetUniformLocation(programId, "uFlowProgress")
        uWindDirLoc = GLES20.glGetUniformLocation(programId, "uWindDir")
        uWindStrengthLoc = GLES20.glGetUniformLocation(programId, "uWindStrength")
        uTurbulenceLoc = GLES20.glGetUniformLocation(programId, "uTurbulence")
        uPixelRatioLoc = GLES20.glGetUniformLocation(programId, "uPixelRatio")
        uShockOriginLoc = GLES20.glGetUniformLocation(programId, "uShockOrigin")
        uShockTimeLoc = GLES20.glGetUniformLocation(programId, "uShockTime")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vboId = ids[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (programId == 0 || vboId == 0) return

        val upload = pendingBuffer
        if (upload != null) {
            pendingBuffer = null
            activeCount = pendingCount
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                upload.capacity() * 4,
                upload,
                GLES20.GL_DYNAMIC_DRAW
            )
        }

        if (activeCount <= 0 || flowProgress <= 0.002f) return

        val nowMs = System.currentTimeMillis()
        val nowSec = (nowMs - startTimeMs) / 1000f
        val shockSec = if (shockStartMs > 0L) ((nowMs - shockStartMs) / 1000f).coerceAtLeast(0f) else 9f

        GLES20.glUseProgram(programId)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)

        val stride = FLOATS_PER_VERTEX * 4
        if (aOriginLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aOriginLoc)
            GLES20.glVertexAttribPointer(aOriginLoc, 2, GLES20.GL_FLOAT, false, stride, 0)
        }
        if (aVelocityLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aVelocityLoc)
            GLES20.glVertexAttribPointer(aVelocityLoc, 2, GLES20.GL_FLOAT, false, stride, 2 * 4)
        }
        if (aColorLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aColorLoc)
            GLES20.glVertexAttribPointer(aColorLoc, 3, GLES20.GL_FLOAT, false, stride, 4 * 4)
        }
        if (aMetaLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aMetaLoc)
            GLES20.glVertexAttribPointer(aMetaLoc, 4, GLES20.GL_FLOAT, false, stride, 7 * 4)
        }

        GLES20.glUniform1f(uTimeLoc, nowSec)
        GLES20.glUniform1f(uFlowProgressLoc, flowProgress)
        GLES20.glUniform1f(uWindDirLoc, windDirection)
        GLES20.glUniform1f(uWindStrengthLoc, windStrength)
        GLES20.glUniform1f(uTurbulenceLoc, turbulence)
        GLES20.glUniform1f(uPixelRatioLoc, pixelRatio)
        GLES20.glUniform2f(uShockOriginLoc, shockOriginX, shockOriginY)
        GLES20.glUniform1f(uShockTimeLoc, shockSec)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, activeCount)
    }

    /**
     * Dynamically samples RGB colors & positions from detected Face/Body regions in the live frame
     * and queues up to 10,000 layered dust particles (mist + organic flakes + subtle embers) in 1 VBO upload.
     */
    fun spawnParticlesFromFrame(
        liveFrame: Bitmap?,
        poseLandmarks: List<PointF>,
        isFrontCamera: Boolean,
        particleCount: Int = 4800,
        snapOriginX: Float = 0.70f,
        snapOriginY: Float = 0.42f
    ) {
        val count = particleCount.coerceIn(1200, 10000)
        shockOriginX = snapOriginX
        shockOriginY = snapOriginY
        shockStartMs = System.currentTimeMillis()

        val sampleBmp = if (liveFrame != null && !liveFrame.isRecycled) {
            try { Bitmap.createScaledBitmap(liveFrame, 128, 168, true) } catch (_: Throwable) { null }
        } else null

        val hasPose = poseLandmarks.size >= 33
        val noseX = if (hasPose) (if (isFrontCamera) 1f - poseLandmarks[0].x else poseLandmarks[0].x) else 0.50f
        val noseY = if (hasPose) poseLandmarks[0].y else 0.34f
        val lShX = if (hasPose) (if (isFrontCamera) 1f - poseLandmarks[11].x else poseLandmarks[11].x) else 0.34f
        val rShX = if (hasPose) (if (isFrontCamera) 1f - poseLandmarks[12].x else poseLandmarks[12].x) else 0.66f
        val sw = kotlin.math.abs(rShX - lShX).coerceAtLeast(0.22f)

        val faceRx = (sw * 0.38f).coerceIn(0.08f, 0.24f)
        val faceRy = (sw * 0.48f).coerceIn(0.10f, 0.30f)
        val bodyMinX = (min(min(lShX, rShX), noseX) - 0.16f).coerceIn(0.04f, 0.70f)
        val bodyMaxX = (max(max(lShX, rShX), noseX) + 0.16f).coerceIn(bodyMinX + 0.20f, 0.96f)
        val bodyMinY = (noseY - 0.13f).coerceIn(0.04f, 0.55f)
        val bodyMaxY = if (hasPose) (max(poseLandmarks[23].y, poseLandmarks[24].y) + 0.34f).coerceIn(bodyMinY + 0.25f, 0.97f) else 0.94f
        val bodySpanX = (bodyMaxX - bodyMinX).coerceAtLeast(0.16f)
        val bodySpanY = (bodyMaxY - bodyMinY).coerceAtLeast(0.24f)
        val bodyCx = (bodyMinX + bodyMaxX) * 0.5f
        val bodyCy = (bodyMinY + bodyMaxY) * 0.5f

        val arr = FloatArray(count * FLOATS_PER_VERTEX)
        val mistRatio = 0.54f
        for (i in 0 until count) {
            val sampleFace = targetRegion == TargetRegion.FACE ||
                (targetRegion == TargetRegion.FACE_AND_BODY && i < count * 0.32f)
            var nx: Float
            var ny: Float
            if (sampleFace) {
                val ang = Math.random() * Math.PI * 2.0
                val rad = sqrt(Math.random()).toFloat() * (0.92f + Math.random().toFloat() * 0.16f)
                nx = (noseX + cos(ang).toFloat() * faceRx * rad).coerceIn(0.02f, 0.98f)
                ny = (noseY + sin(ang).toFloat() * faceRy * rad).coerceIn(0.02f, 0.98f)
            } else {
                nx = bodyMinX + Math.random().toFloat() * bodySpanX
                ny = bodyMinY + Math.random().toFloat() * bodySpanY
                val dx = (nx - bodyCx) / (bodySpanX * 0.50f)
                val dy = (ny - bodyCy) / (bodySpanY * 0.54f)
                if (dx * dx + dy * dy > 1.08f) {
                    nx = bodyCx + (nx - bodyCx) * (0.58f + Math.random().toFloat() * 0.18f)
                    ny = bodyCy + (ny - bodyCy) * (0.70f + Math.random().toFloat() * 0.14f)
                }
            }

            var r = 0.50f
            var g = 0.46f
            var b = 0.43f
            if (sampleBmp != null) {
                val px = (nx * (sampleBmp.width - 1)).toInt().coerceIn(0, sampleBmp.width - 1)
                val py = (ny * (sampleBmp.height - 1)).toInt().coerceIn(0, sampleBmp.height - 1)
                val c = sampleBmp.getPixel(px, py)
                r = (Color.red(c) * 0.74f + 68f * 0.26f) / 255f
                g = (Color.green(c) * 0.74f + 64f * 0.26f) / 255f
                b = (Color.blue(c) * 0.74f + 60f * 0.26f) / 255f
            }

            val regMinX = if (targetRegion == TargetRegion.FACE) noseX - faceRx else bodyMinX
            val regMaxX = if (targetRegion == TargetRegion.FACE) noseX + faceRx else bodyMaxX
            val regSpanX = (regMaxX - regMinX).coerceAtLeast(0.12f)
            val windAxis = if (windDirection >= 0f) (regMaxX - nx) / regSpanX else (nx - regMinX) / regSpanX
            val vertAxis = ((ny - bodyMinY) / bodySpanY).coerceIn(0f, 1f)
            val fbmNoise =
                ((sin(nx * 23.1 + ny * 15.7) * 0.5 + 0.5).toFloat() * 0.16f) +
                ((cos(nx * 51.3 - ny * 37.9) * 0.5 + 0.5).toFloat() * 0.10f) +
                Math.random().toFloat() * 0.19f
            val triggerAt = (windAxis * 0.54f + vertAxis * 0.12f + fbmNoise - 0.06f).coerceIn(0.0f, 0.92f)

            val roll = Math.random().toFloat()
            val kind: Float
            val sizePx: Float
            val vx: Float
            val vy: Float
            if (roll < emberRatio) {
                kind = 1.0f
                sizePx = 2.0f + Math.random().toFloat() * 3.0f
                vx = 0.15f + Math.random().toFloat() * 0.26f
                vy = -0.03f - Math.random().toFloat() * 0.09f
            } else if (roll < emberRatio + mistRatio) {
                kind = 0.0f
                sizePx = 1.8f + Math.random().toFloat() * 4.2f
                vx = 0.16f + Math.random().toFloat() * 0.28f
                vy = -0.045f - Math.random().toFloat() * 0.11f
            } else {
                kind = 0.5f
                sizePx = if (Math.random() < 0.22) 6.2f + Math.random().toFloat() * 4.2f else 3.0f + Math.random().toFloat() * 5.8f
                vx = 0.11f + Math.random().toFloat() * 0.22f
                vy = -0.03f - Math.random().toFloat() * 0.08f
            }

            val base = i * FLOATS_PER_VERTEX
            arr[base] = nx
            arr[base + 1] = ny
            arr[base + 2] = vx
            arr[base + 3] = vy
            arr[base + 4] = r
            arr[base + 5] = g
            arr[base + 6] = b
            arr[base + 7] = triggerAt
            arr[base + 8] = sizePx
            arr[base + 9] = Math.random().toFloat()
            arr[base + 10] = kind
        }

        if (sampleBmp != null && !sampleBmp.isRecycled) sampleBmp.recycle()

        val buf = ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(arr)
        buf.position(0)
        pendingCount = count
        pendingBuffer = buf
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "GLES Dust Shader error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "SnapDustGlRenderer"
        private const val FLOATS_PER_VERTEX = 11

        private const val VERTEX_SHADER = """
            precision highp float;
            attribute vec2 aOrigin;
            attribute vec2 aVelocity;
            attribute vec3 aColor;
            attribute vec4 aMeta;
            uniform float uTime;
            uniform float uFlowProgress;
            uniform float uWindDir;
            uniform float uWindStrength;
            uniform float uTurbulence;
            uniform float uPixelRatio;
            uniform vec2 uShockOrigin;
            uniform float uShockTime;
            varying vec3 vColor;
            varying float vAlpha;
            varying float vSpin;
            varying float vKind;
            varying float vAshFade;
            void main() {
                float triggerAt = aMeta.x;
                float baseSize  = aMeta.y;
                float seed      = aMeta.z;
                float kind      = aMeta.w;
                float rawElapsed = (uFlowProgress - triggerAt) * 1.45;
                float attached = step(0.0, rawElapsed);
                float activeT = clamp(rawElapsed, 0.0, 2.1);
                float lightness = kind < 0.25 ? 1.28 : (kind > 0.75 ? 1.15 : 0.92);
                float gust = 1.0 + 0.42 * sin(uTime * 1.7 + seed * 6.2831) + 0.18 * cos(uTime * 3.1 + aOrigin.y * 9.0);
                float curlX = (cos(uTime * 2.0 + aOrigin.y * 16.0 + seed * 11.0) * 0.024
                            + sin(uTime * 3.4 + aOrigin.x * 21.0 + seed * 5.0) * 0.011) * uTurbulence * lightness;
                float curlY = (sin(uTime * 2.3 + aOrigin.x * 15.0 + seed * 9.0) * 0.044
                            + cos(uTime * 3.8 + aOrigin.y * 18.0 + seed * 7.0) * 0.016) * uTurbulence * lightness;
                vec2 fromSnap = aOrigin - uShockOrigin;
                float snapDist = max(0.05, length(fromSnap));
                float shockKick = smoothstep(0.55, 0.0, uShockTime) * smoothstep(0.52, 0.04, snapDist) * 0.036;
                vec2 driftPos = aOrigin
                    + vec2(aVelocity.x * uWindDir * uWindStrength * gust * lightness + curlX,
                           aVelocity.y * uWindStrength * lightness + curlY) * activeT * 1.65
                    + normalize(fromSnap) * shockKick * activeT;
                driftPos.x += uWindDir * 0.075 * uWindStrength * lightness * activeT * activeT;
                driftPos.y -= 0.036 * lightness * activeT * activeT;
                vec2 clipPos = vec2(driftPos.x * 2.0 - 1.0, 1.0 - driftPos.y * 2.0);
                gl_Position = vec4(clipPos, 0.0, 1.0);
                float fadeIn  = smoothstep(0.0, 0.14, activeT);
                float fadeOut = 1.0 - smoothstep(0.52, 1.85, activeT);
                float kindOpacity = kind < 0.25 ? 0.62 : (kind > 0.75 ? 0.88 : 0.90);
                vAlpha = attached * fadeIn * fadeOut * kindOpacity;
                float sizeCurve = kind < 0.25 ? (0.95 + activeT * 0.45) : max(0.32, 1.0 - activeT * 0.42);
                gl_PointSize = max(1.3, baseSize * sizeCurve * uPixelRatio * attached);
                vColor = aColor;
                vSpin = seed * 6.2831 + activeT * (kind < 0.25 ? 2.5 : 8.2);
                vKind = kind;
                vAshFade = clamp(activeT * 0.72, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            varying float vAlpha;
            varying float vSpin;
            varying float vKind;
            varying float vAshFade;
            void main() {
                if (vAlpha <= 0.01) discard;
                vec2 uv = gl_PointCoord * 2.0 - 1.0;
                if (vKind < 0.25) {
                    float r = length(uv);
                    if (r > 1.0) discard;
                    float puff = exp(-r * r * 3.1) * smoothstep(1.0, 0.12, r);
                    vec3 mistCol = mix(vColor, vec3(0.36, 0.34, 0.32), 0.48 + vAshFade * 0.32);
                    gl_FragColor = vec4(mistCol, vAlpha * puff);
                    return;
                }
                if (vKind > 0.75) {
                    float r = length(uv);
                    if (r > 1.0) discard;
                    float core = exp(-r * r * 5.2);
                    vec3 emberCol = mix(vec3(0.95, 0.54, 0.14), vec3(1.0, 0.92, 0.74), core * 0.65);
                    gl_FragColor = vec4(emberCol, vAlpha * smoothstep(1.0, 0.15, r));
                    return;
                }
                float c = cos(vSpin);
                float s = sin(vSpin);
                vec2 rot = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
                float noiseEdge = 0.19 * sin(rot.x * 5.4 + rot.y * 4.1) + 0.10 * cos(rot.x * 8.2 - rot.y * 6.7);
                float flakeDist = max(abs(rot.x) * 1.04, abs(rot.y) * 1.44) + noiseEdge;
                if (flakeDist > 0.94) discard;
                float edgeSoft = smoothstep(0.94, 0.44, flakeDist);
                vec3 ashCol = mix(vColor, vec3(0.19, 0.18, 0.17), 0.24 + vAshFade * 0.56);
                gl_FragColor = vec4(ashCol, vAlpha * edgeSoft);
            }
        """
    }
}
