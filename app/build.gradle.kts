plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.dormail.gdsviewer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dormail.gdsviewer"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "0.8"
    }

    // The release key is read from ~/.gradle/gradle.properties (gdsviewer.storeFile etc.), never
    // from the repository. Without it, release builds fall back to this computer's debug key, so
    // anyone can build the app; only APKs signed with the release key update the published app.
    val releaseStoreFile = providers.gradleProperty("gdsviewer.storeFile").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("gdsviewer.storePassword").get()
                keyAlias = providers.gradleProperty("gdsviewer.keyAlias").get()
                keyPassword = providers.gradleProperty("gdsviewer.keyPassword").get()
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Optimized, non-debuggable build: big layouts are several times faster than in a debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
