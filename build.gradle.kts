import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.paperweight.userdev) apply false
    alias(libs.plugins.run.paper) apply false
}

group = "pl.syntaxdevteam"
version = "1.0.0-R0.1-SNAPSHOT"
description = "Uwierzytelnianie premium i offline dla Paper/Folia oraz Velocity."

val targetJavaVersion = libs.versions.java.get().toInt()
val modules = listOf(
    ":authgatewayx-domain", ":authgatewayx-api", ":authgatewayx-auth",
    ":authgatewayx-security", ":authgatewayx-integrations", ":authgatewayx-storage-api",
    ":authgatewayx-storage-jdbc", ":authgatewayx-paper", ":authgatewayx-velocity",
)
val releaseVersion = version.toString()
val paperDistribution = layout.projectDirectory.file("authgatewayx-paper/build/libs/AuthGatewayX-Paper-$releaseVersion.jar").asFile
val velocityDistribution = layout.projectDirectory.file("authgatewayx-velocity/build/libs/AuthGatewayX-Velocity-$releaseVersion.jar").asFile
val candidateMinecraft = providers.gradleProperty("paperDevBundleVersion")
    .getOrElse(libs.versions.paper.api.get()).substringBefore(".build.")
val expectedPaperLibraries = listOf(
    libs.kotlin.stdlib, libs.syntaxcore, libs.messagehandler.paper, libs.hikari, libs.sqlite.jdbc,
    libs.mysql.jdbc, libs.mariadb.jdbc, libs.postgresql.jdbc, libs.argon2.jvm, libs.gson,
).map { it.get().toString() }

subprojects {
    group = rootProject.group
    version = rootProject.version
    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencies.add("compileOnly", rootProject.libs.jetbrains.annotations)
        dependencies.add("testCompileOnly", rootProject.libs.jetbrains.annotations)
        extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(targetJavaVersion) }
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<ProcessResources>().configureEach { filteringCharset = "UTF-8" }
    tasks.withType<Jar>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // JLine FFM, SQLite JNI and Argon2/JNA execute native code from the classpath.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

tasks {
    named("clean") { dependsOn(modules.map { "$it:clean" }) }
    named("check") { dependsOn(modules.map { "$it:check" }) }
    named("build") { dependsOn(modules.map { "$it:build" }) }
    register("buildAll") {
        group = "build"
        description = "Builds and verifies all existing Paper/Folia and Velocity modules."
        dependsOn(named("build"), "verifyDistributions")
    }
    register<Sync>("stagePaperCandidate") {
        description = "Stages the Paper candidate distribution for compatibility testing."
        group = "distribution"
        dependsOn(":authgatewayx-paper:shadowJar")
        from(paperDistribution)
        into(layout.buildDirectory.dir("compatibility/paper"))
        inputs.property("minecraftVersion", candidateMinecraft)
        val candidateName = "AuthGatewayX-Paper-$releaseVersion-MC$candidateMinecraft.jar"
        rename { candidateName }
    }
    register<Sync>("stageVelocityCandidate") {
        description = "Stages the Velocity candidate distribution for compatibility testing."
        group = "distribution"
        dependsOn(":authgatewayx-velocity:shadowJar")
        from(velocityDistribution)
        into(layout.buildDirectory.dir("compatibility/velocity"))
        val candidateName = "AuthGatewayX-Velocity-$releaseVersion-Velocity4.jar"
        rename { candidateName }
    }
    register("verifyDistributions") {
        group = "verification"
        description = "Checks distribution descriptors, runtime catalog consistency and archive contents."
        dependsOn(":authgatewayx-paper:shadowJar", ":authgatewayx-velocity:shadowJar")
        val paper = paperDistribution
        val velocity = velocityDistribution
        val coordinates = expectedPaperLibraries
        val expectedVersion = releaseVersion
        inputs.files(paper, velocity)
        inputs.property("runtimeLibraries", coordinates)
        doLast {
            for (file in listOf(paper, velocity)) {
                java.util.jar.JarFile(file).use { jar ->
                    check(jar.getEntry("kotlin/Unit.class") == null || file == velocity) { "Paper bundles Kotlin" }
                    val names = jar.entries().asSequence().map { it.name }.toList()
                    check(names.size == names.toSet().size) { "Duplicate entries in ${file.name}" }
                    check(jar.getEntry("pl/syntaxdevteam/authgatewayx/domain/session/AuthSession.class") != null)
                    check(jar.getEntry("pl/syntaxdevteam/authgatewayx/storage/jdbc/JdbcAccountStorage.class") != null)
                    if (file == paper) {
                        val descriptor = jar.getInputStream(jar.getJarEntry("paper-plugin.yml")).bufferedReader().use { it.readText() }
                        check(descriptor.lineSequence().any {
                            it.trim().removePrefix("version:").trim().trim('\"', '\'') == expectedVersion && it.startsWith("version:")
                        }) { "Paper descriptor version differs from project" }
                        check("loader: pl.syntaxdevteam.authgatewayx.paper.loader.SyntaxLoader" in descriptor)
                        check("bootstrapper: pl.syntaxdevteam.authgatewayx.paper.bootstrap.AuthGatewayXBootstrap" in descriptor)
                        check("api-version: '26.1'" in descriptor) { "Paper minimum API version must be 26.1" }
                        val manifest = jar.getInputStream(jar.getJarEntry("paper-libraries.yml")).bufferedReader().use { it.readText() }
                        val actual = manifest.lineSequence().map { it.trim() }.filter { it.startsWith("- ") }
                            .map { it.removePrefix("- ") }.toList()
                        check(actual == coordinates) { "Paper runtime libraries differ from libs.versions.toml" }
                        for (external in listOf("com/zaxxer/hikari/HikariDataSource.class", "org/sqlite/JDBC.class",
                            "de/mkammerer/argon2/Argon2Factory.class", "pl/syntaxdevteam/core/SyntaxCore.class")) {
                            check(jar.getEntry(external) == null) { "Paper duplicates runtime library: $external" }
                        }
                    } else {
                        val descriptor = jar.getInputStream(jar.getJarEntry("velocity-plugin.json")).bufferedReader().use { it.readText() }
                        check("pl.syntaxdevteam.authgatewayx.velocity.loader.SyntaxLoader" in descriptor)
                        check(descriptor.lineSequence().any {
                            it.trim() == "\"version\": \"$expectedVersion\","
                        }) { "Velocity descriptor version differs from project" }
                        check(jar.getEntry("velocity-libraries.txt") != null)
                        check(jar.getEntry("kotlin/Unit.class") == null)
                        check(jar.getEntry("com/zaxxer/hikari/HikariDataSource.class") == null)
                        check(jar.getEntry("pl/syntaxdevteam/authgatewayx/velocity/loader/SyntaxLoader.class") != null)
                    }
                }
            }
        }
    }
}