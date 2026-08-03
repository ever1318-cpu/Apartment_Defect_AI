plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.axlife.pinset"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.axlife.pinset"
        // Galaxy Note10 on Android 12 is API 31; camera and local-sync paths support it.
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
        val aiApiBaseUrl = (project.findProperty("PINSET_AI_API_BASE_URL") as String?)
            ?.trim()
            .orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "AI_API_BASE_URL", "\"$aiApiBaseUrl\"")

        ndk { abiFilters += listOf("arm64-v8a") }
        vectorDrawables { useSupportLibrary = true }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore/release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("PINSET_STORE_PASSWORD") ?: "pinset2026"
                keyAlias = System.getenv("PINSET_KEY_ALIAS") ?: "pinset"
                keyPassword = System.getenv("PINSET_KEY_PASSWORD") ?: "pinset2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    // Optional dependencies — kept out of the default build so Sync doesn't
    // hang on unavailable artifacts. Re-enable when needed:
    //   implementation("androidx.exifinterface:exifinterface:1.3.7")
    //   implementation(libs.arcore)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
