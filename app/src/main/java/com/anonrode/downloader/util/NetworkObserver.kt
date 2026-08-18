package com.anonrode.downloader.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkStatus(
    val isConnected: Boolean = true,
    val isWifi: Boolean = false,
    val isMetered: Boolean = false
)

class NetworkObserver(context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val _status = MutableStateFlow(getCurrentStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _status.value = getCurrentStatus()
        }

        override fun onLost(network: Network) {
            _status.value = getCurrentStatus()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _status.value = getCurrentStatus()
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (_: Exception) {}
    }

    fun getCurrentStatus(): NetworkStatus {
        return try {
            val active = connectivityManager?.activeNetwork ?: return NetworkStatus(isConnected = false)
            val caps = connectivityManager.getNetworkCapabilities(active) ?: return NetworkStatus(isConnected = false)
            val isOnline = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            NetworkStatus(isConnected = isOnline, isWifi = isWifi, isMetered = isMetered)
        } catch (_: Exception) {
            NetworkStatus(isConnected = true, isWifi = false, isMetered = true)
        }
    }
}
