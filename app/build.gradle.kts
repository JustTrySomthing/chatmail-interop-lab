plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val auditPreview = providers.gradleProperty("p2pAuditPreview").map(String::toBoolean).orElse(false)

android {
    namespace = "app.p2p"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "app.p2p.interop"
        minSdk = 31
        targetSdk = 35
        versionCode = 81
        versionName = "0.7.23-rc45"
        manifestPlaceholders["p2pAppLabel"] = "P2P Interop"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs.create("p2pRelease") {
        storeFile = file(providers.environmentVariable("P2P_INTEROP_KEYSTORE").getOrElse("../../signing/p2p-interop.p12"))
        storePassword = providers.environmentVariable("P2P_INTEROP_KEY_PASSWORD").orNull
        keyAlias = "p2p"
        keyPassword = storePassword
    }
    buildTypes.getByName("release") {
        signingConfig = signingConfigs.getByName("p2pRelease")
        isMinifyEnabled = false
        isShrinkResources = false
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    buildTypes.getByName("debug") {
        if (auditPreview.get()) {
            applicationIdSuffix = ".audit"
            manifestPlaceholders["p2pAppLabel"] = "P2P Recovery Test"
        }
    }
    androidResources { noCompress += "voicepack" }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += setOf("**/libonklav_speech.so", "**/libsherpa-onnx-c-api.so", "**/libonnxruntime.so")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { property ->
                System.getProperty(property)?.let { value -> it.systemProperty(property, value) }
            }
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    sourceSets["main"].jniLibs.srcDirs(layout.buildDirectory.dir("rustJniLibs"), layout.buildDirectory.dir("chatmailJniLibs"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    ksp("androidx.room:room-compiler:2.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
