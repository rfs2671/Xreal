package dev.ultraxr.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/**
 * Replays a tracking recording through the trackers with the screen off and the phone locked: a foreground service
 * with a wake lock, so Android neither freezes the app nor needs the launcher on screen for the minutes it takes.
 * Started from the launcher's test switch ("--es replay NAME"); stops itself when the recording ends.
 */
class ReplayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    private var wake: PowerManager.WakeLock? = null
    private var holds = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra("replay"); val tag = intent?.getStringExtra("replay_tag") ?: "run"
        if (name == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tracking tests", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setContentTitle("Ultra XR is replaying a recording")
            .setContentText("Measuring tracking on recorded data. It stops by itself.").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(2, n)
        } catch (e: Exception) { Tracking.log("replay finished: the foreground service was refused: $e"); stopSelf(); return START_NOT_STICKY }
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultraxr:replay").apply { acquire(20 * 60 * 1000L) }
        } catch (_: Throwable) {}
        Tracking.acquire(this); holds = true
        Thread {
            Thread.sleep(4000)       // the trackers are created on a worker thread
            Tracking.replay(File(getExternalFilesDir("recordings"), name), getExternalFilesDir("replay")!!, tag) { note ->
                Tracking.log("replay finished: $note")
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Throwable) {}
        if (holds) Tracking.release()
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "replay" }
}
