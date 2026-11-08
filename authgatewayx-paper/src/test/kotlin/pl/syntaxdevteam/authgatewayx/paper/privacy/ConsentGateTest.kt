package pl.syntaxdevteam.authgatewayx.paper.privacy

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ConsentGateTest {
    @Test fun staleConnectionCannotReleaseNewGate() {
        val id = UUID.randomUUID()
        val gate = ConsentGate()
        val old = gate.prepare(id)
        val current = gate.prepare(id)
        assertFalse(gate.release(id, old))
        assertFalse(gate.isCurrent(id, old))
        assertTrue(gate.release(id, current))
        assertFalse(gate.isPending(id))
    }

    @Test fun disconnectedPlayerDoesNotStayPending() {
        val id = UUID.randomUUID()
        val gate = ConsentGate()
        gate.prepare(id)
        gate.forget(id)
        assertFalse(gate.isPending(id))
    }
}
