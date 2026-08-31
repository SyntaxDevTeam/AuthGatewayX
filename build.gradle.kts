plugins {
    base
    kotlin("jvm") version libs.versions.kotlin.get() apply false
}

val modules = listOf(
    ":authgatewayx-domain", ":authgatewayx-api", ":authgatewayx-auth",
    ":authgatewayx-security", ":authgatewayx-integrations", ":authgatewayx-storage-api",
    ":authgatewayx-storage-jdbc", ":authgatewayx-paper", ":authgatewayx-velocity",
)

tasks.named("clean") { dependsOn(modules.map { "$it:clean" }) }
tasks.named("check") { dependsOn(modules.map { "$it:check" }) }
tasks.named("build") { dependsOn(modules.map { "$it:build" }) }
