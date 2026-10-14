package pl.syntaxdevteam.authgatewayx.paper.scheduler

import org.bukkit.plugin.Plugin
import java.lang.reflect.Proxy
import kotlin.test.*

class PaperRuntimeGenerationTest {
    @Test fun `re-enabling same plugin does not run queued callbacks from retired generation`() {
        var enabled = true
        var oldCurrent = true
        var oldCalls = 0
        var newCalls = 0
        val plugin = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
            when (method.name) {
                "isEnabled" -> enabled
                else -> error("Unexpected platform access: ${method.name}")
            }
        } as Plugin
        val queuedOld = currentRuntimeTask(plugin, { oldCurrent }, Runnable { oldCalls++ })
        enabled = false
        oldCurrent = false
        queuedOld.run()
        enabled = true
        val queuedNew = currentRuntimeTask(plugin, { true }, Runnable { newCalls++ })
        queuedOld.run()
        queuedNew.run()
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
        enabled = false
        queuedNew.run()
        assertEquals(1, newCalls)
    }
}
