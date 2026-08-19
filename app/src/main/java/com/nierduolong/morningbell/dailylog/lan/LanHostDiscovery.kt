package com.nierduolong.morningbell.dailylog.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

data class LanEndpoint(
    val host: InetAddress,
    val port: Int,
    val serviceName: String,
    val network: Network?,
)

/** 只发现本应用的 DNS-SD 服务，不扫描网段，不探测任意私网设备。 */
@Suppress("DEPRECATION")
class LanHostDiscovery(context: Context) : AutoCloseable {
    sealed interface State {
        data object Idle : State
        data object Searching : State
        data class Found(val endpoint: LanEndpoint) : State
        data class Failed(val message: String) : State
    }

    private val manager = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val fallbackExecutor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var started = false
    private var resolving = false
    private var stopFailure: String? = null

    private val resolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving = false
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolving = false
                val host = hostAddress(serviceInfo)
                if (host == null || serviceInfo.port <= 0 || !isPrivate(host)) return
                val serviceNetwork =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) serviceInfo.network else null
                mutableState.value =
                    State.Found(
                        LanEndpoint(
                            host = host,
                            port = serviceInfo.port,
                            serviceName = serviceInfo.serviceName,
                            // Android 12 及更早的 NSD 不返回 Network；按路由反查 Wi-Fi，确保后续
                            // socket 不会因为蜂窝网是默认网络而走运营商链路。
                            network = serviceNetwork ?: wifiNetworkFor(host),
                        ),
                    )
            }
        }

    private val discoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                mutableState.value = State.Searching
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.startsWith("_nierlog._tcp") || resolving) return
                resolving = true
                runCatching { manager.resolveService(serviceInfo, resolveListener) }.onFailure { resolving = false }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                started = false
                val message = stopFailure.also { stopFailure = null }
                if (mutableState.value !is State.Found) {
                    mutableState.value = message?.let(State::Failed) ?: State.Idle
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started = false
                mutableState.value = State.Failed("搜索附近 Log 失败（错误 $errorCode）")
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                started = false
            }
        }

    fun start() {
        if (started) return
        started = true
        stopFailure = null
        mutableState.value = State.Searching
        runCatching {
            manager.discoverServices(LanNsdAdvertiser.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            started = false
            mutableState.value = State.Failed(it.message ?: "无法搜索附近 Log")
        }
        probeWifiGateway()
    }

    fun stopWithFailure(message: String) {
        if (!started) {
            mutableState.value = State.Failed(message)
            return
        }
        stopFailure = message
        runCatching { manager.stopServiceDiscovery(discoveryListener) }.onFailure {
            started = false
            stopFailure = null
            mutableState.value = State.Failed(message)
        }
    }

    /**
     * 部分 ROM 不把 mDNS 从 LocalOnlyHotspot 转发给客户端。这里不扫描网段，只探测系统
     * DHCP 路由明确给出的 Wi-Fi 网关和本应用固定端口，仍然不会接触任意局域网设备。
     */
    private fun probeWifiGateway() {
        fallbackExecutor.execute {
            connectivity.allNetworks.forEach { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@forEach
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@forEach
                val gateways = connectivity.getLinkProperties(network)?.routes?.mapNotNull { it.gateway }?.distinct().orEmpty()
                gateways.filter(::isPrivate).forEach { gateway ->
                    val socket = runCatching { network.socketFactory.createSocket() }.getOrNull() ?: return@forEach
                    val found =
                        runCatching {
                            socket.connect(InetSocketAddress(gateway, LanHostServer.PREFERRED_PORT), 1_500)
                            socket.soTimeout = 1_500
                            socket.getOutputStream().write(
                                "GET /v1/health HTTP/1.1\r\nHost: gateway\r\nConnection: close\r\n\r\n"
                                    .toByteArray(),
                            )
                            socket.getOutputStream().flush()
                            val firstLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                            firstLine.contains(" 200 ")
                        }.getOrDefault(false)
                    runCatching { socket.close() }
                    if (found && mutableState.value !is State.Found) {
                        mutableState.value =
                            State.Found(
                                LanEndpoint(
                                    host = gateway,
                                    port = LanHostServer.PREFERRED_PORT,
                                    serviceName = "附近 Log（热点网关）",
                                    network = network,
                                ),
                            )
                        return@execute
                    }
                }
            }
        }
    }

    private fun wifiNetworkFor(host: InetAddress): Network? =
        connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            connectivity.getLinkProperties(network)?.routes?.any { route -> route.matches(host) } == true
        }

    @Suppress("DEPRECATION")
    private fun hostAddress(info: NsdServiceInfo): InetAddress? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull(::isPrivate)
        } else {
            info.host
        }

    override fun close() {
        stopFailure = null
        if (started) runCatching { manager.stopServiceDiscovery(discoveryListener) }
        started = false
        resolving = false
        fallbackExecutor.shutdownNow()
    }

    companion object {
        private fun isPrivate(address: InetAddress): Boolean =
            address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
                (address.address.firstOrNull()?.toInt()?.and(0xfe) == 0xfc)
    }
}
