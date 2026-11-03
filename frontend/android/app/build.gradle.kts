plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.nexusai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexusai.app"
        minSdk = 34
        targetSdk = 36

        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    debugImplementation(libs.androidx.compose.ui.tooling)
}
