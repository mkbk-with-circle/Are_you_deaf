package com.nierduolong.morningbell.dailylog.lan

import java.util.concurrent.ConcurrentHashMap

/** 当前进程里由 NSD 验证过的端点。Network 对象不能持久化，重启后必须重新发现。 */
object LanEndpointRegistry {
    private val endpoints = ConcurrentHashMap<Long, LanEndpoint>()

    fun put(logId: Long, endpoint: LanEndpoint) {
        endpoints[logId] = endpoint
    }

    fun get(logId: Long): LanEndpoint? = endpoints[logId]

    fun remove(logId: Long) {
        endpoints.remove(logId)
    }
}
