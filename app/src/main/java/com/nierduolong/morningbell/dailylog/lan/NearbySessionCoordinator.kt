package com.nierduolong.morningbell.dailylog.lan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service 与 Compose 之间只通过不可变状态通信，避免页面重建时丢失热点 reservation。 */
object NearbySessionCoordinator {
    sealed interface State {
        data object Idle : State

        data class Starting(
            val logId: Long,
        ) : State

        data class Hosting(
            val logId: Long,
            val ssid: String,
            val passphrase: String,
            val inviteCode: String,
            val port: Int,
        ) : State

        data class Failed(
            val message: String,
        ) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    internal fun update(value: State) {
        mutableState.value = value
    }
}
