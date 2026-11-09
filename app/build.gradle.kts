import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Ключ подписи релизов: обновления ставятся поверх только с тем же ключом
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.andro6tube"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.andro6tube"
        minSdk = 23
        targetSdk = 36
        versionCode = (property("appVersionCode") as String).toInt()
        versionName = property("appVersionName") as String
        // Откуда приложение берёт обновления (GitHub Releases)
        buildConfigField("String", "UPDATE_REPO", "\"Tolib-N8/andro6tube\"")
        // ГУ на TI DRA74X — 32-bit ARM; arm64 — для эмулятора/других устройств
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.newpipe.extractor)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.dash)
    implementation(libs.media3.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.okhttp)
    implementation(libs.okhttp)
    implementation(libs.conscrypt)
    implementation(libs.coil)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.recyclerview)
    implementation(libs.activity)
    implementation(libs.core)
}

android {
    lint {
        // Media3 @UnstableApi используется осознанно (ForwardingSimpleBasePlayer, DASH-манифесты)
        disable += listOf("UnsafeOptInUsageError")
        checkReleaseBuilds = false
        abortOnError = false
    }
}
