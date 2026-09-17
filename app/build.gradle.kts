plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.edgepro.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edgepro.app"
        minSdk = 26          // Android 8.0. MediaPipe GPU delegate is reliable from here up.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // .tflite assets must not be compressed, or MediaPipe cannot mmap them.
    androidResources {
        noCompress += "tflite"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MediaPipe Tasks - Vision (Object Detector). Bundles LiteRT + CPU/GPU delegates.
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
}

// ---------------------------------------------------------------------------
// Model download. Fetches EfficientDet-Lite0 (INT8) into src/main/assets at
// build time so the model is NOT committed to git. Requires network at build.
// If your build runs offline, download the file manually to
//   app/src/main/assets/efficientdet-lite0.tflite
// ---------------------------------------------------------------------------
val modelUrl =
    "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite"
val modelFile = layout.projectDirectory.file("src/main/assets/efficientdet-lite0.tflite")

val downloadModel by tasks.registering {
    val out = modelFile.asFile
    outputs.file(out)
    doLast {
        if (!out.exists()) {
            out.parentFile.mkdirs()
            logger.lifecycle("Downloading model -> ${out.path}")
            uri(modelUrl).toURL().openStream().use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadModel) }
