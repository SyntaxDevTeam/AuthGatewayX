plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    api(project(":logincorex-domain"))
    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }
