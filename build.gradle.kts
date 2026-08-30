plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    implementation(project(":authgatewayx-api"))
    implementation(project(":authgatewayx-auth"))
    implementation(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-security"))
    implementation(libs.kotlin.stdlib)
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}
