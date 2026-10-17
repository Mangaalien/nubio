package com.nuvio.tv.core.setup

/**
 * Shared bus between the per-flavor gate implementation and MainActivity
 * (main source set). The playstore flavor never posts, so its build is
 * never blocked.
 */
object UpdateGateBus {
    val state = kotlinx.coroutines.flow.MutableStateFlow(UpdateGateState())

    fun post(state: UpdateGateState) {
        this.state.value = state
    }
}
