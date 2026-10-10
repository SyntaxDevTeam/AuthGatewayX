plugins {
    kotlin("jvm")
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
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
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    implementation(project(":authgatewayx-api"))
    implementation(project(":authgatewayx-auth"))
    implementation(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-security"))
    implementation(project(":authgatewayx-storage-jdbc"))
    implementation(project(":authgatewayx-storage-api"))
    implementation(project(":authgatewayx-integrations"))
    implementation(libs.kotlin.stdlib)
    compileOnly(libs.syntaxcore)
    compileOnly(libs.messagehandler.paper)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.snakeyaml)
    testImplementation(libs.kotlin.test)
}

val runtimeLibraries = mapOf(
    "syntaxcore" to libs.syntaxcore.get().toString(),
    "messagehandler" to libs.messagehandler.paper.get().toString(),
    "hikari" to libs.hikari.get().toString(),
    "sqlite" to libs.sqlite.jdbc.get().toString(),
    "mysql" to libs.mysql.jdbc.get().toString(),
    "mariadb" to libs.mariadb.jdbc.get().toString(),
    "postgresql" to libs.postgresql.jdbc.get().toString(),
    "argon2" to libs.argon2.jvm.get().toString(),
    "gson" to libs.gson.get().toString(),
)
val pluginVersion = project.version.toString()

tasks {
    build { dependsOn(shadowJar) }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        runDirectory(rootProject.file("run"))
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val libraries = runtimeLibraries
        val props = mapOf("version" to pluginVersion)
        inputs.properties(props + libraries)
        filesMatching("paper-plugin.yml") { expand(props) }
        filesMatching("paper-libraries.yml") { expand(libraries) }
    }

    shadowJar {
        archiveBaseName.set("AuthGatewayX-Paper")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveClassifier.set("")
        mergeServiceFiles()
        filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        dependencies {
            // Paper PluginLoader supplies these libraries, including native JDBC/Argon2 resources.
            exclude(dependency("pl.syntaxdevteam:syntaxcore"))
            exclude(dependency("pl.syntaxdevteam:messageHandler-paper"))
            exclude(dependency("com.zaxxer:HikariCP"))
            exclude(dependency("org.xerial:sqlite-jdbc"))
            exclude(dependency("com.mysql:mysql-connector-j"))
            exclude(dependency("org.mariadb.jdbc:mariadb-java-client"))
            exclude(dependency("org.postgresql:postgresql"))
            exclude(dependency("de.mkammerer:.*"))
            exclude(dependency("com.google.code.gson:gson"))
        }
    }

    test { useJUnitPlatform() }
}
