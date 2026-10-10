import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.paperweight.userdev) apply false
    alias(libs.plugins.run.paper) apply false
}

group = "pl.syntaxdevteam"
version = "1.0.0-WIP"
description = "Uwierzytelnianie premium i offline dla Paper/Folia oraz Velocity."

val targetJavaVersion = libs.versions.java.get().toInt()
subprojects {
    group = rootProject.group
    version = rootProject.version
    plugins.withId("org.jetbrains.kotlin.jvm") {
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

val modules = listOf(
    ":authgatewayx-domain", ":authgatewayx-api", ":authgatewayx-auth",
    ":authgatewayx-security", ":authgatewayx-integrations", ":authgatewayx-storage-api",
    ":authgatewayx-storage-jdbc", ":authgatewayx-paper", ":authgatewayx-velocity",
)

tasks.named("clean") { dependsOn(modules.map { "$it:clean" }) }
tasks.named("check") { dependsOn(modules.map { "$it:check" }) }
tasks.named("build") { dependsOn(modules.map { "$it:build" }) }
tasks.register("buildAll") {
    group = "build"
    description = "Builds and verifies all existing Paper/Folia and Velocity modules."
    dependsOn(tasks.named("build"), "verifyDistributions")
}

val releaseVersion = version.toString()
val paperDistribution = layout.projectDirectory.file("authgatewayx-paper/build/libs/AuthGatewayX-Paper-$releaseVersion.jar").asFile
val velocityDistribution = layout.projectDirectory.file("authgatewayx-velocity/build/libs/AuthGatewayX-Velocity-$releaseVersion.jar").asFile
val expectedPaperLibraries = listOf(
    libs.syntaxcore, libs.messagehandler.paper, libs.hikari, libs.sqlite.jdbc,
    libs.mysql.jdbc, libs.mariadb.jdbc, libs.postgresql.jdbc, libs.argon2.jvm, libs.gson,
).map { it.get().toString() }

tasks.register("verifyDistributions") {
    group = "verification"
    description = "Checks distribution descriptors, runtime catalog consistency and archive contents."
    dependsOn(":authgatewayx-paper:shadowJar", ":authgatewayx-velocity:shadowJar")
    val paper = paperDistribution
    val velocity = velocityDistribution
    val coordinates = expectedPaperLibraries
    inputs.files(paper, velocity)
    inputs.property("runtimeLibraries", coordinates)
    doLast {
        for (file in listOf(paper, velocity)) {
            java.util.jar.JarFile(file).use { jar ->
                val names = jar.entries().asSequence().map { it.name }.toList()
                check(names.size == names.toSet().size) { "Duplicate entries in ${file.name}" }
                check(jar.getEntry("pl/syntaxdevteam/authgatewayx/domain/session/AuthSession.class") != null)
                check(jar.getEntry("pl/syntaxdevteam/authgatewayx/storage/jdbc/JdbcAccountStorage.class") != null)
                if (file == paper) {
                    val descriptor = jar.getInputStream(jar.getJarEntry("paper-plugin.yml")).bufferedReader().use { it.readText() }
                    check("loader: pl.syntaxdevteam.authgatewayx.paper.loader.SyntaxLoader" in descriptor)
                    check("bootstrapper: pl.syntaxdevteam.authgatewayx.paper.bootstrap.AuthGatewayXBootstrap" in descriptor)
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
                    check("pl.syntaxdevteam.authgatewayx.velocity.AuthGatewayXVelocity" in descriptor)
                    for (required in listOf("com/zaxxer/hikari/HikariDataSource.class", "org/sqlite/JDBC.class",
                        "com/mysql/cj/jdbc/Driver.class", "org/mariadb/jdbc/Driver.class", "org/postgresql/Driver.class")) {
                        check(jar.getEntry(required) != null) { "Missing Velocity runtime library: $required" }
                    }
                    val services = jar.getInputStream(jar.getJarEntry("META-INF/services/java.sql.Driver"))
                        .bufferedReader().use { it.readText() }
                    for (driver in listOf("org.sqlite.JDBC", "com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver", "org.postgresql.Driver")) {
                        check(driver in services) { "Missing merged JDBC service: $driver" }
                    }
                }
            }
        }
    }
}
