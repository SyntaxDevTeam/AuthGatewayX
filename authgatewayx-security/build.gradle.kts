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
tasks.test { useJUnitPlatform { excludeTags("benchmark") } }

tasks.register<Test>("securityBenchmark") {
    description = "Runs bounded, in-process security benchmarks without network traffic."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    maxParallelForks = 1
    maxHeapSize = "512m"
    systemProperty("agx.benchmark.samples", providers.gradleProperty("benchmarkSamples").getOrElse("8"))
    systemProperty("agx.benchmark.report", layout.buildDirectory.file("reports/benchmarks/security.json").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}
