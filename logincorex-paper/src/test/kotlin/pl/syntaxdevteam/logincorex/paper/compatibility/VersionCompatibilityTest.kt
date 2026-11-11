package pl.syntaxdevteam.logincorex.paper.compatibility

import java.lang.reflect.Proxy
import org.bukkit.Server
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class VersionCompatibilityTest {
    @Test fun gameVersionIsIndependentOfPaperApiBuild() {
        val server = Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
            when (method.name) {
                "getMinecraftVersion" -> "26.3"
                "getBukkitVersion" -> "26.3.build.248-stable"
                else -> error("Unexpected server access: ${method.name}")
            }
        } as Server
        val version = VersionChecker.getServerVersion(server)
        assertEquals("26.3", version)
        assertEquals(SemanticVersion(26, 3, 0), SemanticVersion.parse(version))
        assertTrue(VersionChecker.isVersionSupported(version))
        for (flag in VersionCompatibility.CompatibilityFlag.entries) {
            assertTrue(VersionCompatibility(SemanticVersion.parse(version)).supports(flag))
        }
    }

    @Test fun parsesBukkitVersionsWithoutBuildNumbers() {
        assertEquals(SemanticVersion(26, 1, 0), SemanticVersion.parse("26.1-R0.1-SNAPSHOT"))
        assertEquals(SemanticVersion(26, 1, 2), SemanticVersion.parse(" 26.1.2+build.129 "))
        assertTrue(SemanticVersion.parse("26.3") > SemanticVersion.parse("1.21.10"))
        assertTrue(SemanticVersion.parse("1.21.10") > SemanticVersion.parse("1.21.7"))
    }

    @Test fun malformedVersionsNeverGainSupport() {
        for (raw in listOf("", "snapshot", "Paper 26.3", "26.3.0.1", "999999999999.2", "26")) {
            assertEquals(null, SemanticVersion.parseOrNull(raw), raw)
            assertFalse(VersionChecker.isVersionSupported(raw), raw)
            assertFailsWith<IllegalArgumentException> { SemanticVersion.parse(raw) }
            for (flag in VersionCompatibility.CompatibilityFlag.entries) {
                assertFalse(VersionCompatibility(SemanticVersion.parseOrNull(raw)).supports(flag))
            }
        }
    }

    @Test fun targetListDoesNotPromiseFutureOrLegacySupport() {
        for (raw in listOf("26.1", "26.1.1", "26.1.2-R0.1-SNAPSHOT", "26.2", "26.3")) {
            assertTrue(VersionChecker.isVersionSupported(raw), raw)
        }
        for (raw in listOf("1.21.7", "26.1.3", "26.4", "27.1")) {
            assertFalse(VersionChecker.isVersionSupported(raw), raw)
        }
    }

    @Test fun featuresHaveExplicitIntroductionThresholds() {
        for (flag in VersionCompatibility.CompatibilityFlag.entries) {
            assertFalse(VersionCompatibility(SemanticVersion.parse("1.21.6")).supports(flag))
            assertTrue(VersionCompatibility(SemanticVersion.parse("1.21.7")).supports(flag))
            assertTrue(VersionCompatibility(SemanticVersion.parse("26.3")).supports(flag))
        }
    }

    @Test fun versionRangeIncludesBothBoundaries() {
        val version = SemanticVersion.parse("26.2")
        assertTrue(version.isBetween(version, version))
        assertTrue(version.isBetween(version, null))
        assertFalse(version.isBetween(SemanticVersion.parse("26.3"), null))
        assertFalse(version.isBetween(SemanticVersion.parse("26.1"), SemanticVersion.parse("26.1.2")))
    }
}
