plugins {
    id("com.android.application")
}

// Firebase is configured by dropping google-services.json into this folder (app/). Without it the app still works
// as a browser and keeps its activity queue locally. Build options:
//   -PFIREBASE_EMULATOR_HOST=127.0.0.1   talk to local Firebase emulators instead (testing; use with `adb reverse`)
//   -PGOOGLE_WEB_CLIENT_ID=...           only if you don't use the one generated from google-services.json
fun prop(name: String, default: String) = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "dev.wbrowser.nativeapp"
    // GeckoView 160 needs API 37.2+ (minor SDK version)
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "dev.wbrowser.nativeapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${prop("GOOGLE_WEB_CLIENT_ID", "")}\"")
        buildConfigField("String", "FIREBASE_EMULATOR_HOST", "\"${prop("FIREBASE_EMULATOR_HOST", "")}\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false   // GeckoView ships its own consumer rules; keep it simple
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:160.0.20261116130455")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // background sync with offline queue + retry/backoff
    implementation("androidx.work:work-runtime:2.10.0")
    // Firebase: Auth (anonymous + Google link) and Firestore (data)
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    // optional Google sign-in (official Credential Manager / Sign in with Google)
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
