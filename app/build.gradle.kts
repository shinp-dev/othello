plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.othello"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.othello"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:game"))
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:match"))
    implementation(project(":feature:matchmaking"))
    implementation(project(":feature:records"))
    implementation(project(":feature:review"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:credential"))
    implementation(project(":feature:research"))
    implementation(project(":analysis:api"))
    implementation(project(":analysis:edax"))
    implementation(project(":data:supabase"))
    implementation(project(":transport:webrtc"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
}
