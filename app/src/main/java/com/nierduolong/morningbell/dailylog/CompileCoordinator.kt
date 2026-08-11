package com.nierduolong.morningbell.dailylog

import android.content.Context
import com.nierduolong.morningbell.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 合成任务的单一入口与进度广播。
 * 任务跑在 App 作用域而不是页面作用域：用户点了「合成」再切 Tab 或退出页面，
 * 合成不会被取消，回来还能看到进度。
 */
object CompileCoordinator {
    sealed interface State {
        data object Idle : State

        data class Running(
            val dayEpoch: Long,
            val progress: Float,
        ) : State

        data class Success(
            val dayEpoch: Long,
        ) : State

        data class Failed(
            val dayEpoch: Long,
        ) : State
    }

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    /** 已经在合成时忽略重复点击，返回 false 让 UI 可以提示「正在合成」 */
    fun start(
        scope: CoroutineScope,
        context: Context,
        repo: AppRepository,
        logId: Long,
        dayEpoch: Long,
        force: Boolean = false,
    ): Boolean {
        if (stateFlow.value is State.Running) return false
        stateFlow.value = State.Running(dayEpoch, 0f)
        scope.launch {
            val result =
                runCatching {
                    DailyCompileManager.compileDayIfNeeded(
                        context = context,
                        repo = repo,
                        logId = logId,
                        dayEpoch = dayEpoch,
                        force = force,
                        onProgress = { p ->
                            val current = stateFlow.value
                            if (current is State.Running && current.dayEpoch == dayEpoch) {
                                stateFlow.value = current.copy(progress = p.coerceIn(0f, 1f))
                            }
                        },
                    )
                }
            stateFlow.value =
                if (result.isSuccess && result.getOrNull() != null) {
                    State.Success(dayEpoch)
                } else {
                    State.Failed(dayEpoch)
                }
        }
        return true
    }

    /** UI 消费完一次终态后调用，避免 Toast 反复弹 */
    fun consumeTerminalState() {
        val current = stateFlow.value
        if (current is State.Success || current is State.Failed) stateFlow.value = State.Idle
    }
}
