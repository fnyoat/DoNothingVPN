plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fnyoat.donothingvpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fnyoat.donothingvpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/repack.p12")
            storePassword = "android"
            keyAlias = "repack"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}