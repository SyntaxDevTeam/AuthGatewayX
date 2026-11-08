package pl.syntaxdevteam.authgatewayx.velocity

import org.junit.jupiter.api.io.TempDir
import pl.syntaxdevteam.message.PluginMetaProvider
import pl.syntaxdevteam.message.SyntaxMessages
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VelocityStartupTest {
    @TempDir lateinit var directory: Path

    @Test fun `message handler initializes from main configuration without extra config`() {
        Files.writeString(directory.resolve("authgatewayx.yml"), "language: PL\n")
        val resources = VelocityMessageResources(directory, javaClass.classLoader)
        try {
            SyntaxMessages.configure(resources, object : PluginMetaProvider { override val name = "AuthGatewayX" })
            assertTrue(Files.exists(directory.resolve("lang/messages_pl.yml")))
            assertFalse(Files.exists(directory.resolve("config.yml")))
            assertEquals("PL", resources.getConfigValue("language", "EN"))
        } finally { SyntaxMessages.reset() }
    }

    @Test fun `old language is read without modification and main config takes precedence`() {
        val old = directory.resolve("config.yml")
        Files.writeString(old, "language: DE\n")
        assertEquals("DE", VelocityMessageResources(directory, javaClass.classLoader).getConfigValue("language", "EN"))
        Files.writeString(directory.resolve("authgatewayx.yml"), "language: PL\n")
        assertEquals("PL", VelocityMessageResources(directory, javaClass.classLoader).getConfigValue("language", "EN"))
        assertEquals("language: DE\n", Files.readString(old))
    }

    @Test fun `database diagnostics distinguish causes without exposing driver secrets`() {
        val failures = listOf(
            SQLException("password=secret jdbc:mysql://private", "42S02", 1146) to "migration v5",
            SQLException("password=secret", "28000", 1045) to "authentication failed",
            SQLException("password=secret", "42501") to "SELECT",
            SQLException("No suitable driver found for jdbc:mariadb://private?password=secret", "08001") to "JDBC driver is unavailable",
            SQLException("password=secret", "08001") to "Cannot reach",
        )
        failures.forEach { (failure, expected) ->
            val diagnostic = StorageStartupDiagnostic.describe(CompletionException(failure))
            assertTrue(expected in diagnostic)
            assertFalse("secret" in diagnostic)
            assertFalse("jdbc:" in diagnostic)
        }
    }
}
