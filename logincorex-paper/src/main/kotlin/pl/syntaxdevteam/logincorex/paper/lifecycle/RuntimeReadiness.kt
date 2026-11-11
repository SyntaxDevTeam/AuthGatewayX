package pl.syntaxdevteam.logincorex.paper.lifecycle

import java.util.concurrent.atomic.AtomicReference

enum class RuntimeState {
    STARTING,
    READY,
    // Retained as part of the documented public contract.
    @Suppress("unused")
    DEGRADED,
    FAILED,
    STOPPING,
}

class RuntimeReadiness(initial: RuntimeState = RuntimeState.STARTING) {
    private val state = AtomicReference(initial)

    fun current(): RuntimeState = state.get()

    // Compare-and-set lifecycle API retained for external callers.
    @Suppress("unused")
    fun transition(expected: RuntimeState, target: RuntimeState): Boolean =
        state.compareAndSet(expected, target)

    fun force(target: RuntimeState) {
        state.set(target)
    }

    fun acceptsAuthentication(): Boolean = current() == RuntimeState.READY
}
