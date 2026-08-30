package pl.syntaxdevteam.authgatewayx.paper.lifecycle

import java.util.concurrent.atomic.AtomicReference

enum class RuntimeState {
    STARTING,
    READY,
    DEGRADED,
    FAILED,
    STOPPING,
}

class RuntimeReadiness(initial: RuntimeState = RuntimeState.STARTING) {
    private val state = AtomicReference(initial)

    fun current(): RuntimeState = state.get()

    fun transition(expected: RuntimeState, target: RuntimeState): Boolean =
        state.compareAndSet(expected, target)

    fun force(target: RuntimeState) {
        state.set(target)
    }

    fun acceptsAuthentication(): Boolean = current() == RuntimeState.READY
}
