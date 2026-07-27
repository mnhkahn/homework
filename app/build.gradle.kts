plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.homeworkbuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.homeworkbuddy"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Trello documents application keys as public application identifiers.
        // User tokens are never placed in BuildConfig and stay encrypted on the device.
        buildConfigField("String", "TRELLO_API_KEY", "\"4a716dce9dd7f9920377cf16bb355c94\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {
            storeFile = file(providers.environmentVariable("HOMEWORK_RELEASE_STORE_FILE").orNull ?: "missing-release-keystore")
            storePassword = providers.environmentVariable("HOMEWORK_RELEASE_STORE_PASSWORD").orNull
            keyAlias = "homeworkbuddy"
            keyPassword = providers.environmentVariable("HOMEWORK_RELEASE_STORE_PASSWORD").orNull
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.biometric)
    implementation(libs.okhttp)
    implementation(libs.zxing.embedded)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
