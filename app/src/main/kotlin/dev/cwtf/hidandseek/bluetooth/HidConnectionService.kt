package dev.cwtf.hidandseek.bluetooth

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.cwtf.hidandseek.MainActivity
import dev.cwtf.hidandseek.R

/**
 * Keeps the HID connection alive while the app is backgrounded.
 *
 * The notification is not decoration: a phone silently acting as a keyboard for
 * another machine is exactly the kind of thing that should be visible and
 * one tap from being stopped.
 */
class HidConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "a device"
        createChannel()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(deviceName),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        return START_STICKY
    }

    private fun buildNotification(deviceName: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HidConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Acting as a keyboard")
            .setContentText("Connected to $deviceName")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_connection),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_connection_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "hid_connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.cwtf.hidandseek.STOP_HID"
        private const val EXTRA_DEVICE_NAME = "device_name"

        fun start(context: Context, deviceName: String) {
            context.startForegroundService(
                Intent(context, HidConnectionService::class.java)
                    .putExtra(EXTRA_DEVICE_NAME, deviceName),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HidConnectionService::class.java))
        }
    }
}
