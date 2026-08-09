plugins { kotlin("jvm") }


dependencies {
    implementation(project(":core:game"))
    implementation(project(":core:network"))
    testImplementation(kotlin("test"))
}
