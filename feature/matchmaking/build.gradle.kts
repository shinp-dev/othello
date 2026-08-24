plugins { kotlin("jvm") }


dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
