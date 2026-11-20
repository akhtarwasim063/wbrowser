buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        // AGP 9 built-in Kotlin defaults to 2.2; dependencies need >= 2.4 metadata
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.google.gms.google-services") version "4.4.3" apply false
}
