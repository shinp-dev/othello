plugins { kotlin("jvm") }


dependencies {
    implementation(project(":core:game"))
    implementation(project(":analysis:api"))
    implementation(project(":feature:records"))
    testImplementation(kotlin("test"))
}
