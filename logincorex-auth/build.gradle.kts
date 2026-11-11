plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":logincorex-api"))
    implementation(project(":logincorex-domain"))
    implementation(project(":logincorex-integrations"))
    implementation(project(":logincorex-security"))
    implementation(project(":logincorex-storage-api"))
    compileOnly(libs.jetbrains.annotations)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.argon2.jvm)
}

tasks.test { useJUnitPlatform() }
