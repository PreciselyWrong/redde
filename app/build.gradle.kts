plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "ch.nicolab.redde"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.nicolab.redde"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources {
        // The 18 languages carried over from the iOS String Catalogs.
        localeFilters += listOf(
            "en", "da", "de", "es", "fr", "it", "ja", "ko", "nb", "nl", "pl",
            "pt-rBR", "ru", "sv", "tr", "uk", "b+zh+Hans", "b+zh+Hant",
        )
        generateLocaleConfig = true
    }

    lint {
        // Many ported strings are not used by a screen yet; they are wired up phase by phase.
        disable += listOf("UnusedResources", "MissingTranslation")
    }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:transport"))
    implementation(project(":core:markdown"))
    implementation(project(":core:crypto"))
    implementation(project(":core:data"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:phonetools"))
    implementation(project(":feature:terminal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
}
