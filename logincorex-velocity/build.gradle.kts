import java.util.Base64
import java.security.MessageDigest

plugins {
    kotlin("jvm")
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven("https://repo.alessiodp.com/releases/") { content { includeGroup("net.byteflux") } }
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "paperPublic"
        content { includeGroup("com.velocitypowered") }
    }
    maven("https://nexus.syntaxdevteam.pl/repository/maven-snapshots/") {
        name = "syntaxSnapshots"
        content { includeGroup("pl.syntaxdevteam") }
    }
    maven("https://nexus.syntaxdevteam.pl/repository/maven-releases/") {
        name = "syntaxReleases"
        content { includeGroup("pl.syntaxdevteam") }
    }
}

dependencies {
    // Version override lets CI check source compatibility with every Velocity 4.x
    // release without relying on getSessionId (introduced only in 4.2.1).
    compileOnly("com.velocitypowered:velocity-api:" +
        providers.gradleProperty("velocityApiVersion").getOrElse(libs.versions.velocity.api.get()))
    implementation(project(":logincorex-domain"))
    implementation(project(":logincorex-storage-api"))
    implementation(project(":logincorex-storage-jdbc"))
    implementation(libs.hikari)
    implementation(libs.sqlite.jdbc)
    implementation(libs.mysql.jdbc)
    implementation(libs.mariadb.jdbc)
    implementation(libs.postgresql.jdbc)
    implementation(libs.gson)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.velocity.api)
    implementation(project(":logincorex-security"))
    implementation(project(":logincorex-integrations"))
    implementation(libs.syntaxcore.proxy)
    implementation(libs.messagehandler.velocity)
    implementation(libs.snakeyaml)

    implementation("net.byteflux:libby-velocity:1.3.2")
    implementation(libs.kotlin.stdlib)
}

abstract class GenerateRuntimeManifest : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifactFiles: ConfigurableFileCollection

    @get:Input
    abstract val coordinatesByPath: MapProperty<String, String>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val coordinates = coordinatesByPath.get()
        val entries = artifactFiles.files.map { file ->
            val coordinate = coordinates.getValue(file.absolutePath)
            val checksum = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            "$coordinate:$checksum"
        }.sorted()
        manifestFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(entries.joinToString("\n"))
        }
    }
}

val runtimeManifest = layout.buildDirectory.file("generated/runtime/velocity-libraries.txt")
val externalRuntimeArtifacts = configurations.runtimeClasspath.get().incoming.artifactView {
    componentFilter { id ->
        id is org.gradle.api.artifacts.component.ModuleComponentIdentifier && id.group != "net.byteflux"
    }
}.artifacts
val generateRuntimeManifest = tasks.register<GenerateRuntimeManifest>("generateRuntimeManifest") {
    artifactFiles.from(externalRuntimeArtifacts.artifactFiles)
    coordinatesByPath.set(externalRuntimeArtifacts.resolvedArtifacts.map { artifacts ->
        artifacts.associate { artifact ->
            val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
            artifact.file.absolutePath to "${id.group}:${id.module}:${id.version}"
        }
    })
    manifestFile.set(runtimeManifest)
}

tasks {
    jar {
        // Never let the plain JAR race with shadowJar for the same output path.
        // CI verifies and publishes only the shaded artifact.
        archiveClassifier.set("plain")
    }

    processResources {
        dependsOn(generateRuntimeManifest)
        from(runtimeManifest)
        val props = mapOf("version" to version.toString())
        inputs.properties(props)
        filesMatching("velocity-plugin.json") { expand(props) }
    }
    shadowJar {
        archiveBaseName.set("LoginCoreX-Velocity")
        archiveClassifier.set("")
        dependencies {
            exclude { it.moduleGroup != "net.byteflux" && !(it.moduleGroup == "pl.syntaxdevteam" && it.moduleName.startsWith("logincorex-")) }
        }
        relocate("net.byteflux.libby", "pl.syntaxdevteam.logincorex.velocity.libs.libby")
        mergeServiceFiles()
        filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    build { dependsOn(shadowJar) }
}

tasks.test { useJUnitPlatform() }
