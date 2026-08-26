import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningFile = rootProject.file(".signing/release.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.exists()) {
        releaseSigningFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "ai.techtroy.blockhold"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.techtroy.blockhold"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseSigningFile.exists()) {
            create("release") {
                storeFile = rootProject.file(".signing/blockhold-release.p12")
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
                storeType = "PKCS12"
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}
