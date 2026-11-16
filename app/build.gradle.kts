import java.io.InputStream
import java.io.OutputStream
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.cameraxapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.cameraxapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

// Auto-fetch official Google MediaPipe 468-landmark model if missing (non-blocking offline fallback)
tasks.register("downloadFaceLandmarkerTask") {
    doLast {
        val assetDir = file("src/main/assets")
        val modelFile = file("src/main/assets/face_landmarker.task")
        if (!modelFile.exists() || modelFile.length() < 100_000L) {
            try {
                assetDir.mkdirs()
                val url = URI.create("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task").toURL()
                url.openStream().use { input: InputStream ->
                    modelFile.outputStream().use { output: OutputStream ->
                        input.copyTo(output)
                    }
                }
                println("Downloaded MediaPipe face_landmarker.task (${modelFile.length()} bytes)")
            } catch (t: Throwable) {
                if (modelFile.exists()) modelFile.delete()
                println("Notice: Offline/skipped face_landmarker.task download — runtime will use built-in FaceDetector fallback.")
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadFaceLandmarkerTask")
}

dependencies {
    // CameraX Stable Release Dependencies
    val cameraxVersion = "1.3.0"

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")

    // Jetpack Compose Dependencies
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // GPUImage & OpenGL Shader Pipeline
    implementation("jp.co.cyberagent.android:gpuimage:2.1.0")

    // Google MediaPipe Tasks Vision for Real-Time 468 Face Mesh AR Lenses
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.guava:guava:33.0.0-android")
}
