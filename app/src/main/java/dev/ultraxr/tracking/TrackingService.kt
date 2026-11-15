package dev.ultraxr.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.ultraxr.ui.LauncherActivity

/**
 * Keeps the tracking pipeline alive while another app (an OpenXR game) is in front.
 * Started by the launcher when a game is launched; stops itself when asked.
 */
class TrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    private var refused = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "XR tracking", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Ultra XR is tracking")
            .setContentText("Head and hand tracking is running for your game")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(1, n)
        } catch (e: Exception) {
            // Android 14+ refuses the connected-device type without USB access to an attached device.
            Tracking.log("foreground service refused: $e")
            refused = true
            stopSelf()
            return
        }
        Tracking.acquire(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!refused) Tracking.release()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tracking"
        const val ACTION_STOP = "dev.ultraxr.STOP_TRACKING"
        @Volatile var running = false; private set

        /** Starts the service if Android will allow it (needs USB access to the attached glasses). */
        fun start(c: Context) {
            val usb = c.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val ok = usb.deviceList.values.any { it.vendorId == dev.ultraxr.glasses.GlassesLink.VID && usb.hasPermission(it) }
            if (!ok) { Tracking.log("tracking service not started: no glasses with USB access"); return }
            try { c.startForegroundService(Intent(c, TrackingService::class.java)) } catch (e: Exception) { Tracking.log("tracking service not started: $e") }
        }
        fun stop(c: Context) { c.stopService(Intent(c, TrackingService::class.java)) }
    }
}
