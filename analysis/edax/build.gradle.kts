plugins { id("com.android.library") }

android { namespace = "com.example.othello.analysis.edax"; compileSdk = 35; defaultConfig { minSdk = 26 } }

dependencies { implementation(project(":analysis:api")) }
