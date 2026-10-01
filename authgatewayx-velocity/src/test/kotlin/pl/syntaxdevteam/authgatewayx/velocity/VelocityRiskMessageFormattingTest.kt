package pl.syntaxdevteam.authgatewayx.velocity

import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VelocityRiskMessageFormattingTest {
    private val miniMessage = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    @Suppress("UNCHECKED_CAST")
    private fun riskMessages(): Map<String, String> {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("lang/messages_pl.yml"))
        resource.use {
            val root = Yaml().load<Map<String, Any>>(it)
            return root.getValue("risk") as Map<String, String>
        }
    }

    @Test
    fun `official proxy alert messages use valid MiniMessage and a readable hierarchy`() {
        val risk = riskMessages()
        risk.values.forEach { miniMessage.deserialize(it) }

        val header = plain.serialize(miniMessage.deserialize(risk.getValue("announcement_header")))
        val status = plain.serialize(miniMessage.deserialize(risk.getValue("connection_alert")))
        val action = plain.serialize(miniMessage.deserialize(risk.getValue("advisory")))

        assertContains(header, "OFICJALNY KOMUNIKAT")
        assertContains(header, "AuthGatewayX")
        assertContains(status, "POŁĄCZENIE WYMAGA WERYFIKACJI")
        assertContains(status, "Gracz:")
        assertContains(action, "DALSZE DZIAŁANIE")
        assertContains(action, "/authgatewayx alts {username}")
    }

    @Test
    fun `usage inserts angle-bracket argument as text instead of an encoded entity`() {
        val template = miniMessage.deserialize(riskMessages().getValue("usage"))
        val rendered = template.replaceText(
            TextReplacementConfig.builder()
                .matchLiteral("{argument}")
                .replacement(net.kyori.adventure.text.Component.text("<nick>"))
                .build(),
        )

        val output = plain.serialize(rendered)
        assertEquals("/authgatewayx alts <nick>", output)
        assertFalse(output.contains("&lt;"))
    }
}
