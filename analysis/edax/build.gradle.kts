plugins { id("com.android.library") }

android { namespace = "com.example.othello.analysis.edax"; compileSdk = 36; defaultConfig { minSdk = 26 } }

dependencies {
    implementation(project(":analysis:api"))
    testImplementation(project(":core:game"))
    testImplementation("junit:junit:4.13.2")
}
