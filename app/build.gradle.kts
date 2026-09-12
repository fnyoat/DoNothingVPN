import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val repackProps = Properties().apply {
    val f = rootProject.file("keystore-local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
            storePassword = repackProps.getProperty("repack.storePassword", "android")
            keyAlias = repackProps.getProperty("repack.keyAlias", "repack")
            keyPassword = repackProps.getProperty("repack.keyPassword", "android")
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