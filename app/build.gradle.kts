plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
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

dependencies {
    // CameraX Stable Release Dependencies
    // Note: camera-effects was published at 1.3.0 and 1.4.x on Google Maven;
    // version 1.3.4 was omitted for camera-effects, so it resolves to 1.3.0 if 1.3.4 is selected.
    val cameraxVersion = "1.3.0"
    val cameraxEffectsVersion = if (cameraxVersion == "1.3.4") "1.3.0" else cameraxVersion

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")
    implementation("androidx.camera:camera-effects:$cameraxEffectsVersion")

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
