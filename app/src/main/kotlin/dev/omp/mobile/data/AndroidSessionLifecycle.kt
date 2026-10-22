package dev.omp.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground-only session lifecycle.
 *
 * Registers exactly one default [ConnectivityManager.NetworkCallback] while
 * the process is started and unregisters it on stop. The first capabilities
 * change after a new default network gates the first connection attempt.
 */
@Singleton
class AndroidSessionLifecycle @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var host: SessionRuntimeHost? = null
    private var foreground = false
    private var callbackRegistered = false

    private var currentNetwork: Network? = null
    private var awaitingCapabilities = false
    private var lastTransport = -1
    private var lastValidated = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network != currentNetwork) {
                currentNetwork = network
                awaitingCapabilities = true
                lastTransport = -1
                lastValidated = false
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != currentNetwork) return
            val transport = transportMask(capabilities)
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val firstOrChanged = awaitingCapabilities || transport != lastTransport
            awaitingCapabilities = false
            lastTransport = transport
            if (firstOrChanged) {
                lastValidated = validated
                host?.onNetworkAvailable(changed = true)
            } else {
                if (validated && !lastValidated) host?.onNetworkValidated()
                lastValidated = validated
            }
        }

        override fun onLost(network: Network) {
            if (network != currentNetwork) return
            currentNetwork = null
            awaitingCapabilities = false
            lastTransport = -1
            lastValidated = false
            if (foreground) host?.onNetworkUnavailable()
        }
    }

    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_STOP -> stop()
            else -> Unit
        }
    }

    fun attach(host: SessionRuntimeHost) {
        this.host = host
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
    }

    private fun start() {
        if (foreground) return
        foreground = true
        currentNetwork = null
        awaitingCapabilities = true
        lastTransport = -1
        lastValidated = false
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            callbackRegistered = true
        }
        // Until the first capabilities change arrives, the repository waits.
        host?.onForeground(networkReady = false)
    }

    private fun stop() {
        if (!foreground) return
        foreground = false
        if (callbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            callbackRegistered = false
        }
        currentNetwork = null
        awaitingCapabilities = false
        lastTransport = -1
        lastValidated = false
        host?.onBackground()
    }

    private fun transportMask(capabilities: NetworkCapabilities): Int {
        var mask = 0
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) mask = mask or 1
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mask = mask or 2
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) mask = mask or 4
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) mask = mask or 8
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) mask = mask or 16
        return mask
    }
}
