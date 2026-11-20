plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Native Android admin app (Jetpack Compose). It talks to Firebase directly (Auth + Firestore);
// access is enforced by firebase/firestore.rules and the admin role claim, never by this app.
// Own applicationId, own icon/name, no shared code with the browser app.
fun prop(name: String, default: String) = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

android {
    namespace = "dev.wbrowser.admin"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "dev.wbrowser.admin"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
        // Firebase WEB identifiers (public, not secrets). Override with -P... for another project.
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("FIREBASE_API_KEY", "AIzaSyAUngkLG1uINPT8blpxEfoMFExse7lGHPA")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${prop("FIREBASE_APP_ID", "1:234164580335:web:3ceaf5f8d4e5ff9b0e901a")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${prop("FIREBASE_PROJECT_ID", "wbrowser-62d56")}\"")
    }
    buildFeatures { buildConfig = true; compose = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")

    val compose = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")

    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
}
