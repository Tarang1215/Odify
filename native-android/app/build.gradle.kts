import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.odify.nativeapp"
    compileSdk = 35

    defaultConfig {
        // Permanent Google OAuth identity. Never append version numbers here.
        // New versions increment versionCode/versionName only.
        applicationId = "com.odify.music.nativepreview.v11"
        minSdk = 26
        targetSdk = 35
        versionCode = 62
        versionName = "1.0.0-preview12.50"
        ndk {abiFilters += listOf("arm64-v8a","x86_64")}
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Always use the one permanent signing certificate restored by CI.
    // Gradle's implicit debug signer may otherwise generate a different key
    // even when the fixed keystore exists in the runner's home directory.
    signingConfigs {
        create("odifyFixedDebug") {
            val userHome = System.getenv("HOME") ?: System.getProperty("user.home")
            storeFile = file("$userHome/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("odifyFixedDebug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs {useLegacyPackaging=true}
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-session:1.9.4")
    implementation("androidx.media3:media3-common:1.9.4")
    implementation("androidx.media3:media3-datasource:1.9.4")
    implementation("androidx.media3:media3-database:1.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.gms:play-services-auth:21.5.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Enforce identity for local builds too. Never issue an incompatible APK just
// because a developer already has Android Studio's unrelated debug keystore.
tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    doLast {
        val config = android.signingConfigs.getByName("odifyFixedDebug")
        val keystore = KeyStore.getInstance(KeyStore.getDefaultType())
        config.storeFile!!.inputStream().use { keystore.load(it, "android".toCharArray()) }
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(keystore.getCertificate("androiddebugkey").encoded)
            .joinToString("") { "%02x".format(it) }
        check(sha1 == "656d465e5c729bdf21a4026c764dfb636f1899e5") {
            "Fixed Odify signing certificate required; refusing an update-incompatible APK."
        }
    }
}

