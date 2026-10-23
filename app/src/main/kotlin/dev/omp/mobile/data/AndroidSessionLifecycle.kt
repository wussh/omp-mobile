package dev.omp.mobile.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns one main-thread network observer for the UI or a confirmed foreground service. */
@Singleton
class AndroidSessionLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var host: SessionRuntimeHost? = null
    private var uiStarted = false
    private var serviceRunning = false
    private var serviceRequested = false
    private var serviceStopping = false
    private var connectionWanted = false
    private var runtimeStarted = false
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
            if (runtimeStarted) host?.onNetworkUnavailable()
        }
    }

    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                uiStarted = true
                updateRuntime()
                requestServiceIfWanted()
            }
            Lifecycle.Event.ON_STOP -> {
                uiStarted = false
                updateRuntime()
            }
            else -> Unit
        }
    }

    fun attach(host: SessionRuntimeHost) {
        checkMainThread()
        check(this.host == null) { "Runtime host is already attached" }
        this.host = host
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        uiStarted = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val wasStarted = runtimeStarted
        updateRuntime()
        if (wasStarted) host.onRuntimeStarted(networkReady = currentNetwork != null && !awaitingCapabilities)
    }

    fun setConnectionWanted(wanted: Boolean) {
        checkMainThread()
        connectionWanted = wanted
        if (wanted) {
            requestServiceIfWanted()
        } else {
            serviceRequested = false
            serviceStopping = context.stopService(Intent(context, SessionForegroundService::class.java)) || serviceStopping
        }
    }

    internal fun onServiceStarted() {
        checkMainThread()
        serviceRequested = true
        serviceRunning = true
        updateRuntime()
    }

    internal fun onServiceStopped() {
        checkMainThread()
        val restartRequested = serviceStopping && connectionWanted
        serviceStopping = false
        serviceRequested = false
        serviceRunning = false
        updateRuntime()
        if (restartRequested) requestServiceIfWanted()
    }

    internal fun disconnectRequested() {
        checkMainThread()
        setConnectionWanted(false)
        host?.onDisconnectRequested()
    }

    private fun requestServiceIfWanted() {
        if (!connectionWanted || serviceRequested || serviceRunning) return
        // A rejected start is not a running runtime. Retry when the UI next starts.
        try {
            context.startForegroundService(Intent(context, SessionForegroundService::class.java))
            serviceRequested = true
        } catch (_: IllegalStateException) {
            serviceRequested = false
        } catch (_: SecurityException) {
            serviceRequested = false
        }
    }

    private fun updateRuntime() {
        val wanted = uiStarted || serviceRunning
        if (runtimeStarted == wanted) return
        runtimeStarted = wanted
        if (wanted) {
            awaitingCapabilities = true
            try {
                connectivity.registerDefaultNetworkCallback(callback, main)
                callbackRegistered = true
            } catch (_: RuntimeException) {
                callbackRegistered = false
            }
            host?.onRuntimeStarted(networkReady = false)
        } else {
            if (callbackRegistered) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                callbackRegistered = false
            }
            currentNetwork = null
            awaitingCapabilities = false
            lastTransport = -1
            lastValidated = false
            host?.onRuntimeStopped()
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
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
