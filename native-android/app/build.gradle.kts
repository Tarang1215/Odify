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
        versionCode = 11
        versionName = "1.0.0-preview11"
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { applicationIdSuffix = ".debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
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
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
