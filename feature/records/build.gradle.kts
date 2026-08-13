plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}


dependencies {
    implementation(project(":core:game"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
