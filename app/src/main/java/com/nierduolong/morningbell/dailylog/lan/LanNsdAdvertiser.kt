package com.nierduolong.morningbell.dailylog.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** 用 mDNS 广播端口；邀请码和热点密码绝不进入可被同网段所有人读取的 TXT 记录。 */
class LanNsdAdvertiser(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(
        port: Int,
        remoteLogId: String,
    ) {
        close()
        val registration =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
        listener = registration
        val info =
            NsdServiceInfo().apply {
                serviceName = "你尔多龙吗-${remoteLogId.take(6)}"
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("protocol", "1")
                setAttribute("log", remoteLogId.take(36))
            }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    override fun close() {
        val current = listener ?: return
        listener = null
        runCatching { manager.unregisterService(current) }
    }

    companion object {
        const val SERVICE_TYPE = "_nierlog._tcp."
    }
}
