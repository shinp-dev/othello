plugins { id("com.android.library") }

android { namespace = "com.example.othello.transport.webrtc"; compileSdk = 35; defaultConfig { minSdk = 26 } }

dependencies { implementation(project(":core:network")) }
