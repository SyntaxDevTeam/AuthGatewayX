plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.kotlin.test)
}


tasks.test {
    useJUnitPlatform()
}
