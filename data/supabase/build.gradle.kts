plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

import java.util.Properties
import java.net.URI

val chanrivaSupabaseProductionProjectRef = providers.gradleProperty("chanriva.supabase.productionProjectRef")
    .orElse("zgzllmaoyymoeiqtybck")
    .get()
val chanrivaSupabaseProductionUrl = providers.gradleProperty("chanriva.supabase.productionUrl")
    .orElse("https://zgzllmaoyymoeiqtybck.supabase.co")
    .get()

fun supabaseProjectRefFromUrl(url: String): String? = runCatching {
    val parsed = URI(url.trim())
    val host = parsed.host?.lowercase() ?: return@runCatching null
    if (parsed.scheme.lowercase() != "https" ||
        parsed.userInfo != null ||
        parsed.port != -1 ||
        parsed.path !in setOf("", "/") ||
        parsed.query != null ||
        parsed.fragment != null
    ) {
        return@runCatching null
    }
    Regex("^([a-z0-9-]+)\\.supabase\\.co$").matchEntire(host)?.groupValues?.get(1)
}.getOrNull()

fun validateReleaseSupabaseConfiguration(url: String, anonKey: String) {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isEmpty()) {
        throw GradleException("Release Supabase configuration is missing SUPABASE_URL.")
    }
    val projectRef = supabaseProjectRefFromUrl(normalizedUrl)
    if (projectRef != chanrivaSupabaseProductionProjectRef || normalizedUrl.trimEnd('/') != chanrivaSupabaseProductionUrl) {
        throw GradleException("Release Supabase URL must target the configured CHANRIVA production project over HTTPS.")
    }

    val normalizedKey = anonKey.trim()
    if (normalizedKey.isEmpty()) {
        throw GradleException("Release Supabase configuration is missing SUPABASE_ANON_KEY.")
    }
    val lowerKey = normalizedKey.lowercase()
    val obviousPlaceholder = normalizedKey.length < 20 ||
        normalizedKey.any(Char::isWhitespace) ||
        listOf("placeholder", "changeme", "your-anon-key", "your_anon_key", "dummy", "example-key", "test-key")
            .any(lowerKey::contains)
    if (obviousPlaceholder) {
        throw GradleException("Release Supabase anon key is empty, a placeholder, or clearly malformed.")
    }
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use(::load)
}
val configuredSupabaseUrl = localProperties.getProperty("supabase.url") ?: System.getenv("SUPABASE_URL") ?: ""
val configuredSupabaseAnonKey = localProperties.getProperty("supabase.anonKey") ?: System.getenv("SUPABASE_ANON_KEY") ?: ""
val configuredSupabaseProjectRef = supabaseProjectRefFromUrl(configuredSupabaseUrl) ?: "unconfigured"
val configuredSupabaseEnvironment = if (configuredSupabaseProjectRef == chanrivaSupabaseProductionProjectRef) "production" else "custom"

val validateReleaseSupabaseConfig = tasks.register("validateReleaseSupabaseConfig") {
    group = "verification"
    description = "Fail closed when a release build is not configured for CHANRIVA production Supabase."
    doLast {
        validateReleaseSupabaseConfiguration(configuredSupabaseUrl, configuredSupabaseAnonKey)
    }
}

val testReleaseSupabaseConfig = tasks.register("testReleaseSupabaseConfig") {
    group = "verification"
    description = "Tests release Supabase configuration validation without using a real credential."
    doLast {
        val validShapeKey = "sb_publishable_" + "a".repeat(32)
        check(runCatching {
            validateReleaseSupabaseConfiguration(chanrivaSupabaseProductionUrl, validShapeKey)
        }.isSuccess) { "A valid-shaped production configuration must pass." }
        val rejected = listOf(
            "" to validShapeKey,
            chanrivaSupabaseProductionUrl to "",
            "http://${chanrivaSupabaseProductionProjectRef}.supabase.co" to validShapeKey,
            "not a URL" to validShapeKey,
            "https://other-project.supabase.co" to validShapeKey,
            chanrivaSupabaseProductionUrl to "placeholder-anon-key-value",
        )
        rejected.forEach { (url, key) ->
            check(runCatching {
                validateReleaseSupabaseConfiguration(url, key)
            }.isFailure) { "Invalid release configuration was accepted." }
        }
        val releaseMetadata = rootProject.file("app/src/release/assets/chanriva-release-metadata.properties")
            .takeIf { it.isFile }
            ?.readText()
            ?: error("Release artifact metadata fixture is missing.")
        check("application_id=com.shinpstudio.chanriva" in releaseMetadata)
        check("variant=release" in releaseMetadata)
        check("supabase_project_ref=$chanrivaSupabaseProductionProjectRef" in releaseMetadata)
        check("supabase_environment=production" in releaseMetadata)
        check("supabase_url=$chanrivaSupabaseProductionUrl" in releaseMetadata)
        check("anon_key" !in releaseMetadata.lowercase())
    }
}

android {
    namespace = "com.example.othello.data.supabase"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "SUPABASE_URL", "\"${configuredSupabaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${configuredSupabaseAnonKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_PROJECT_REF", "\"$configuredSupabaseProjectRef\"")
        buildConfigField("String", "SUPABASE_ENVIRONMENT", "\"$configuredSupabaseEnvironment\"")
    }
    buildFeatures { buildConfig = true }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSupabaseConfig)
}

dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(project(":core:game"))
    implementation(project(":feature:matchmaking"))
    implementation(project(":feature:match"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:records"))
    implementation(project(":feature:research"))
    implementation(platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    // Supabase Realtime requires a WebSocket-capable Ktor engine; AndroidEngine has none.
    implementation("io.ktor:ktor-client-okhttp:3.2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
