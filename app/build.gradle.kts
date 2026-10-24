import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

android {
    namespace = "io.github.voreulch.honoradvanced"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.voreulch.honoradvanced"
        minSdk = 33
        targetSdk = 35
        versionCode = 46
        versionName = "2.0.0"
        // keep native filter consistent with the hand-built APK (no ABI split)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(21)
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            // Java hook sources stay Java (unchanged); new UI is Kotlin/Compose
            java.srcDirs("src/main/java", "src/main/kotlin")
            res.srcDirs("src/main/res")
            assets.srcDirs("src/main/assets")
        }
    }

    signingConfigs {
        create("release") {
            // credentials live in local.properties (NOT committed) — see
            // KEYSTORE.txt; fail loudly if missing so CI never signs with junk
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            storeFile = file("../voreulch.keystore")
            storePassword = props.getProperty("STORE_PASS")
                ?: throw GradleException("STORE_PASS missing in local.properties")
            keyAlias = "voreulch"
            keyPassword = props.getProperty("KEY_PASS")
                ?: throw GradleException("KEY_PASS missing in local.properties")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // Xposed API (compile-only; provided by LSPosed at runtime on the device)
    compileOnly(files(rootProject.file("../tools/api-82.jar")))

    // Compose Multiplatform accessors provided by the org.jetbrains.compose plugin
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("androidx.compose.material:material-icons-core:1.7.3")
    implementation(compose.runtime)
    implementation(compose.animation)

    // miuix — official MIUI/HyperOS component library (matches build stack)
    implementation("top.yukonga.miuix.kmp:miuix-core:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    // miuix-blur: Backdrop/LayerBackdrop/blur/lens/vibrancy (liquid-glass refraction)
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")

    // MaterialKolor — seed-color → M3 palette (Monet dynamic theming for the theme page)
    implementation("com.materialkolor:material-kolor:5.0.1")

    // Material icons (native replacement for MiuixIcons where extended set is needed)

    // DexKit — runtime dex search to locate obfuscated system classes (OTA-resilient hooking)
    implementation("org.luckypray:dexkit:2.2.0")

    // Compose activity host + lifecycle
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
}
