plugins {
    id("com.android.application")
}

// Separate app from the user browser: own applicationId, own icon/name, no shared code or credentials.
// It is a secure shell around the Admin web app served by tracking_backend (admin listener).
//   gradlew assembleRelease -PADMIN_URL=https://admin.example.com/admin/
fun prop(name: String, default: String) = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

android {
    namespace = "dev.wbrowser.admin"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.wbrowser.admin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // default = the live Firebase Hosting site; override with -PADMIN_URL for another project
        buildConfigField("String", "ADMIN_URL", "\"${prop("ADMIN_URL", "https://wbrowser-62d56.web.app/admin/")}\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { manifestPlaceholders["cleartext"] = "true" }
        release {
            manifestPlaceholders["cleartext"] = "false"   // release talks https only
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
}
