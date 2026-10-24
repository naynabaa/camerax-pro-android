package com.example.cameraxapp.filter

import android.content.Context
import android.graphics.Bitmap
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageGrayscaleFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSepiaToneFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter

/**
 * FilterManager: Production Real-Time Filter Engine
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
        CINEMATIC,
        VINTAGE,
        BLACK_AND_WHITE,
        HDR,
        CYBERPUNK
    }

    data class LiveAdjustments(
        var brightness: Float = 0.0f, // -0.5f to 0.5f (0 is neutral)
        var contrast: Float = 1.0f,   // 0.5f to 2.0f (1.0 is neutral)
        var saturation: Float = 1.0f  // 0.0f to 2.0f (1.0 is neutral)
    )

    private val gpuImage = GPUImage(context)
    private var currentPreset = FilterPreset.NORMAL
    val adjustments = LiveAdjustments()

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
}
