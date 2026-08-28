package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.data.model.SyncSettingsEntity

enum class NetworkType(val label: String) {
    WIFI("Wi-Fi Connection"),
    CELLULAR_MOBILE("Mobile Data (Cellular)"),
    ETHERNET("Ethernet LAN"),
    VPN("VPN Connection"),
    OFFLINE("Offline (No Connection)")
}

object NetworkHelper {

    fun getNetworkType(context: Context): NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.OFFLINE
        val activeNetwork = cm.activeNetwork ?: return NetworkType.OFFLINE
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkType.OFFLINE

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR_MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OFFLINE
        }
    }

    fun isConnected(context: Context): Boolean {
        return getNetworkType(context) != NetworkType.OFFLINE
    }

    fun isMobileData(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun isWifiOrEthernet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Checks if sync is allowed according to the current network and mobile data settings.
     * Returns: (isAllowed, reasonIfNotAllowed)
     */
    fun checkSyncNetworkAllowed(
        context: Context,
        settings: SyncSettingsEntity,
        isManual: Boolean = false
    ): Pair<Boolean, String?> {
        val type = getNetworkType(context)
        if (type == NetworkType.OFFLINE) {
            return false to "No active network connection"
        }

        val allowsMobileData = settings.syncOnMobileData && !settings.syncOnWifiOnly

        if (!allowsMobileData && isMobileData(context)) {
            val message = if (isManual) {
                "Cannot sync: Connected via Mobile Data (Mobile Data sync is disabled in Settings)"
            } else {
                "Sync skipped: Connected via Mobile Data (waiting for Wi-Fi)"
            }
            return false to message
        }

        return true to null
    }
}
