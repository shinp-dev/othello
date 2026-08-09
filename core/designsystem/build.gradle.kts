plugins {
    id("com.android.library")
}

android { namespace = "com.example.othello.core.designsystem"; compileSdk = 36 }

dependencies {
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-graphics:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
}
