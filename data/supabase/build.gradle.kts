plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

import java.util.Properties

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use(::load)
}
val configuredSupabaseUrl = localProperties.getProperty("supabase.url") ?: System.getenv("SUPABASE_URL") ?: ""
val configuredSupabaseAnonKey = localProperties.getProperty("supabase.anonKey") ?: System.getenv("SUPABASE_ANON_KEY") ?: ""

android {
    namespace = "com.example.othello.data.supabase"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "SUPABASE_URL", "\"${configuredSupabaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${configuredSupabaseAnonKey.replace("\"", "\\\"")}\"")
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(project(":core:game"))
    implementation(project(":feature:matchmaking"))
    implementation(project(":feature:match"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:records"))
    implementation(project(":feature:credential"))
    api(platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    api("io.github.jan-tennert.supabase:auth-kt")
    api("io.github.jan-tennert.supabase:postgrest-kt")
    api("io.github.jan-tennert.supabase:realtime-kt")
    api("io.github.jan-tennert.supabase:storage-kt")
    api("io.ktor:ktor-client-android:3.2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
