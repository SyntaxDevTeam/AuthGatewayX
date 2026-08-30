plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
}

repositories { mavenCentral() }
dependencies {
    compileOnly(libs.argon2.jvm)
    testRuntimeOnly(libs.argon2.jvm)
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }
