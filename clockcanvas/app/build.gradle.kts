import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Compose preview screen is an optional layer: `kotlinc`-only/offline builds
// set -Pclockcanvas.offlineOnly=true and skip it. Everything else in the app is
// framework Views + Canvas, so the same sources build in both modes.
val offlineOnly = (findProperty("clockcanvas.offlineOnly") as String?)?.toBoolean() ?: false
if (!offlineOnly) {
    apply(plugin = "org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningFile = rootProject.file(".signing/release.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.exists()) releaseSigningFile.inputStream().use { load(it) }
}

android {
    namespace = "ai.techtroy.clockcanvas"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.techtroy.clockcanvas"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += setOf("en")
    }

    signingConfigs {
        if (releaseSigningFile.exists()) {
            create("release") {
                storeFile = rootProject.file(".signing/clockcanvas-release.p12")
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias") ?: "clockcanvas"
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
            signingConfig = if (project.hasProperty("skipSigning")) {
                null
            } else {
                signingConfigs.findByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        // Only meaningful when the Compose layer is applied (see above); harmless otherwise.
        compose = !offlineOnly
    }

    androidResources {
        // Fonts are already compressed payloads; aapt2 stores them verbatim.
        noCompress += listOf("ttf")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    if (!offlineOnly) {
        val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
        implementation(composeBom)
        implementation("androidx.activity:activity-compose:1.9.3")
        implementation("androidx.compose.ui:ui")
        implementation("androidx.compose.foundation:foundation")
        implementation("androidx.compose.material3:material3")
        implementation("androidx.core:core-ktx:1.13.1")
        debugImplementation("androidx.compose.ui:ui-tooling")
    }
}
