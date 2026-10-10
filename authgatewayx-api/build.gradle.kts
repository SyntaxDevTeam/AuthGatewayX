plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    api(project(":authgatewayx-domain"))
    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }
