plugins { id("com.android.library") }

android { namespace = "com.example.othello.transport.webrtc"; compileSdk = 36; defaultConfig { minSdk = 26 } }

dependencies {
    implementation(project(":core:network"))
    implementation("org.webrtc:google-webrtc:1.0.32006")
}
