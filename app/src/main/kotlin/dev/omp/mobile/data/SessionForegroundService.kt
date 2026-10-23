package dev.omp.mobile.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.omp.mobile.MainActivity
import dev.omp.mobile.R
import javax.inject.Inject
import javax.inject.Provider

/** Promotes before resolving the repository, so sticky restoration cannot delay promotion. */
@AndroidEntryPoint
class SessionForegroundService : Service() {
    @Inject lateinit var lifecycle: AndroidSessionLifecycle
    @Inject lateinit var repository: Provider<CollabRepository>
    private var promoted = false
    private var disconnecting = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Session connection", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, SessionForegroundService::class.java).setAction(DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("OMP session connection")
            .setContentText("Keeping your connection available in the background")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Open", open).build())
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            promoted = true
            lifecycle.onServiceStarted()
        } catch (_: RuntimeException) {
            // Do not claim background availability when Android refused promotion.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoted || disconnecting) return START_NOT_STICKY
        // On a null sticky intent this creates the repository and restores persisted pairing.
        // Only restoration's eventual setConnectionWanted(false) may stop an unneeded service.
        repository.get()
        if (intent?.action == DISCONNECT) {
            disconnecting = true
            lifecycle.disconnectRequested()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycle.onServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "session_connection"
        const val NOTIFICATION_ID = 1
        const val DISCONNECT = "dev.omp.mobile.DISCONNECT_SESSION"
    }
}
