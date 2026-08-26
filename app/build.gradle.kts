import java.util.zip.ZipFile

fun chanrivaGitOutput(vararg arguments: String): String? = runCatching {
    val output = providers.exec {
        commandLine("git", *arguments)
    }.standardOutput.asText.get().trim()
    output.takeIf { it.isNotEmpty() }
}.getOrNull()

val chanrivaGitShortSha = chanrivaGitOutput("rev-parse", "--short=8", "HEAD")
val chanrivaGitDirty = chanrivaGitShortSha?.let {
    chanrivaGitOutput("status", "--porcelain")?.isNotEmpty() == true
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val chanrivaSigningEnvironmentNames = listOf(
    "CHANRIVA_UPLOAD_KEYSTORE_PATH",
    "CHANRIVA_UPLOAD_KEY_ALIAS",
    "CHANRIVA_UPLOAD_STORE_PASSWORD",
    "CHANRIVA_UPLOAD_KEY_PASSWORD",
)
val chanrivaSigningEnvironmentValues = chanrivaSigningEnvironmentNames.associateWith { name ->
    System.getenv(name)?.takeIf { it.isNotEmpty() }
}
val chanrivaMissingSigningEnvironmentNames = chanrivaSigningEnvironmentValues
    .filterValues { it == null }
    .keys
val chanrivaConfiguredSigningEnvironmentCount = chanrivaSigningEnvironmentValues.count { it.value != null }
val chanrivaSigningConfigured = chanrivaConfiguredSigningEnvironmentCount == chanrivaSigningEnvironmentNames.size

if (chanrivaConfiguredSigningEnvironmentCount != 0 && !chanrivaSigningConfigured) {
    throw GradleException(
        "Incomplete CHANRIVA release signing configuration. Missing environment variables: " +
            chanrivaMissingSigningEnvironmentNames.joinToString(", ") +
            ". Set all four CHANRIVA_UPLOAD_* variables or leave all four unset. Secret values are not printed.",
    )
}

android {
    namespace = "com.example.othello"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shinpstudio.chanriva"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"
        buildConfigField("String", "CHANRIVA_GIT_SHA", "\"${chanrivaGitShortSha ?: "unknown"}\"")
        buildConfigField("boolean", "CHANRIVA_GIT_DIRTY", (chanrivaGitDirty == true).toString())
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    val chanrivaUploadSigningConfig = if (chanrivaSigningConfigured) {
        signingConfigs.create("chanrivaUpload") {
            storeFile = project.file(chanrivaSigningEnvironmentValues["CHANRIVA_UPLOAD_KEYSTORE_PATH"]!!)
            storePassword = chanrivaSigningEnvironmentValues["CHANRIVA_UPLOAD_STORE_PASSWORD"]
            keyAlias = chanrivaSigningEnvironmentValues["CHANRIVA_UPLOAD_KEY_ALIAS"]
            keyPassword = chanrivaSigningEnvironmentValues["CHANRIVA_UPLOAD_KEY_PASSWORD"]
        }
    } else {
        null
    }

    buildTypes {
        getByName("release") {
            chanrivaUploadSigningConfig?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("verifyPlayReleaseSigning") {
    group = "publishing"
    description = "Build and verify the signed AAB intended for Google Play upload."
    dependsOn("bundleRelease")

    doLast {
        if (!chanrivaSigningConfigured) {
            throw GradleException(
                "Play release verification requires all four CHANRIVA_UPLOAD_* environment variables. " +
                    "Missing environment variables: " + chanrivaMissingSigningEnvironmentNames.joinToString(", ") + ".",
            )
        }

        val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        if (!bundle.isFile) {
            throw GradleException("Signed Play release artifact was not found: ${bundle.name}")
        }

        val hasJarSignature = ZipFile(bundle).use { zip ->
            zip.entries().asSequence().any { entry ->
                val entryName = entry.name.uppercase()
                entryName.startsWith("META-INF/") &&
                    (entryName.endsWith(".RSA") || entryName.endsWith(".DSA") || entryName.endsWith(".EC"))
            }
        }
        if (!hasJarSignature) {
            throw GradleException("The Play release artifact is not signed: ${bundle.name}")
        }

        logger.lifecycle("Verified signed Play release artifact: ${bundle.name}")
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
    implementation(project(":feature:theory"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:research"))
    implementation(project(":analysis:api"))
    implementation(project(":analysis:edax"))
    implementation(project(":data:supabase"))
    implementation(project(":transport:webrtc"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
}
