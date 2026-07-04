package com.mxrider.sysmon.data.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.mxrider.sysmon.core.NetTransport
import com.mxrider.sysmon.core.NetworkMonitor
import com.mxrider.sysmon.core.NetworkSnapshot
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Network sampler. Stateful: keeps the previous [TrafficStats] counters and
 * timestamp so [sample] returns byte-per-second rates (first call returns 0
 * rates). Transport/IP details come from [ConnectivityManager] and degrade to
 * `OTHER`/`null` on any exception.
 */
class DefaultNetworkMonitor(context: Context) : NetworkMonitor {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val lock = Any()
    private var prevElapsedMs = -1L
    private var prevRx = 0L
    private var prevTx = 0L
    private var prevMobileRx = 0L
    private var prevMobileTx = 0L

    override suspend fun sample(): NetworkSnapshot {
        val now = SystemClock.elapsedRealtime()
        val rx = counter { TrafficStats.getTotalRxBytes() }
        val tx = counter { TrafficStats.getTotalTxBytes() }
        val mobileRx = counter { TrafficStats.getMobileRxBytes() }
        val mobileTx = counter { TrafficStats.getMobileTxBytes() }

        var rxRate = 0.0
        var txRate = 0.0
        var mobileRxRate = 0.0
        var mobileTxRate = 0.0
        synchronized(lock) {
            if (prevElapsedMs >= 0) {
                val deltaSec = (now - prevElapsedMs) / 1000.0
                if (deltaSec > 0) {
                    rxRate = (rx - prevRx).coerceAtLeast(0L) / deltaSec
                    txRate = (tx - prevTx).coerceAtLeast(0L) / deltaSec
                    mobileRxRate = (mobileRx - prevMobileRx).coerceAtLeast(0L) / deltaSec
                    mobileTxRate = (mobileTx - prevMobileTx).coerceAtLeast(0L) / deltaSec
                }
            }
            prevElapsedMs = now
            prevRx = rx
            prevTx = tx
            prevMobileRx = mobileRx
            prevMobileTx = mobileTx
        }

        val transport = currentTransport()
        val (ipv4, ipv6) = currentAddresses()

        return NetworkSnapshot(
            rxBytesPerSec = rxRate,
            txBytesPerSec = txRate,
            totalRxBytes = rx,
            totalTxBytes = tx,
            mobileRxBytesPerSec = mobileRxRate,
            mobileTxBytesPerSec = mobileTxRate,
            transport = transport,
            networkName = networkName(transport),
            ipv4 = ipv4,
            ipv6 = ipv6,
        )
    }

    /** TrafficStats.UNSUPPORTED (-1) and other negatives are treated as 0. */
    private inline fun counter(read: () -> Long): Long {
        val v = try {
            read()
        } catch (_: Exception) {
            0L
        }
        return if (v < 0L) 0L else v
    }

    private fun currentTransport(): NetTransport = try {
        val cm = connectivityManager
        val network = cm?.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null
        when {
            caps == null -> NetTransport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetTransport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetTransport.ETHERNET
            else -> NetTransport.OTHER
        }
    } catch (_: SecurityException) {
        NetTransport.OTHER
    } catch (_: Exception) {
        NetTransport.OTHER
    }

    private fun networkName(transport: NetTransport): String? = when (transport) {
        // Reading the SSID needs location permission; keep the generic name.
        NetTransport.WIFI -> "Wi-Fi"
        NetTransport.CELLULAR -> {
            val operator = try {
                telephonyManager?.networkOperatorName
            } catch (_: Exception) {
                null
            }
            if (operator.isNullOrBlank()) "Mobile" else operator
        }
        NetTransport.ETHERNET -> "Ethernet"
        NetTransport.VPN -> "VPN"
        NetTransport.OTHER -> "Other"
        NetTransport.NONE -> null
    }

    /** First non-loopback IPv4 and first non-loopback, non-link-local IPv6. */
    private fun currentAddresses(): Pair<String?, String?> {
        var ipv4: String? = null
        var ipv6: String? = null
        try {
            val cm = connectivityManager ?: return null to null
            val network = cm.activeNetwork ?: return null to null
            val linkProperties = cm.getLinkProperties(network) ?: return null to null
            for (linkAddress in linkProperties.linkAddresses) {
                val address = linkAddress.address ?: continue
                when {
                    address is Inet4Address && !address.isLoopbackAddress && ipv4 == null ->
                        ipv4 = address.hostAddress

                    address is Inet6Address && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress && ipv6 == null ->
                        ipv6 = address.hostAddress?.substringBefore('%')
                }
                if (ipv4 != null && ipv6 != null) break
            }
        } catch (_: Exception) {
            // Degrade to whatever was collected before the failure.
        }
        return ipv4 to ipv6
    }
}
