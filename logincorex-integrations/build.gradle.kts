plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }
dependencies {
    api(project(":logincorex-domain"))
    api(project(":logincorex-api"))
    implementation(project(":logincorex-security"))
    compileOnly(libs.gson)
    testImplementation(libs.gson)
    testImplementation(libs.kotlin.test)
}
tasks.test { useJUnitPlatform() }
