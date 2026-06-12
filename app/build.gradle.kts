plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.littleone.dailycutreport"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.littleone.dailycutreport"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/dailycut-debug.jks")
            storePassword = "dailycutdebug"
            keyAlias = "dailycut"
            keyPassword = "dailycutdebug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
}

apply(from = "camera" + "x.gradle.kts")
apply(from = "ml" + "kit.gradle.kts")
apply(from = "con" + "current.gradle.kts")
