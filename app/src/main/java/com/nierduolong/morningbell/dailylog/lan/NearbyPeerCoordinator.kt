package com.nierduolong.morningbell.dailylog.lan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NearbyPeerCoordinator {
    sealed interface State {
        data object Idle : State
        data class Ready(val logId: Long, val port: Int) : State
        data class Failed(val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    internal fun update(value: State) {
        mutableState.value = value
    }
}
