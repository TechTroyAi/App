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
        applicationId = "ai.techtroy.jadex"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "1.3.5"
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
            // -PskipSigning=true produces an unsigned release (CI without secrets).
            signingConfig = if (project.hasProperty("skipSigning")) {
                null
            } else {
                signingConfigs.findByName("release")
            }
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

    // Best-effort Brotli precompression of the Pyodide payload before assets are
    // packaged. Requires node; if it is absent the build still succeeds and the
    // asset loader simply serves the uncompressed originals.
    tasks.register<Exec>("compressPyodide") {
        workingDir = rootProject.projectDir
        commandLine("node", "scripts/compress-pyodide.js")
        isIgnoreExitValue = true
        onlyIf { rootProject.file("scripts/compress-pyodide.js").exists() }
    }
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn("compressPyodide")
    }

    androidResources {
        // These are already Brotli-compressed on disk; let aapt store them as-is
        // instead of spending build time and APK bytes deflating them again.
        noCompress += listOf("br", "wasm", "zip")
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
