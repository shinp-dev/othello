plugins { id("com.android.library") }

android {
    namespace = "com.example.othello.transport.webrtc"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:network"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Maven Central WebRTC Android distribution; keep the exact version reproducible.
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
