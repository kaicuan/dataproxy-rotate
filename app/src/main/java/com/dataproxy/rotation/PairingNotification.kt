package com.dataproxy.rotation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.dataproxy.MainActivity
import com.dataproxy.R

object PairingNotification {

    const val KEY_CODE = "pairing_code"
    const val ACTION_PAIR = "com.dataproxy.ACTION_PAIR_CODE"

    fun canPost(context: Context): Boolean {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (!mgr.areNotificationsEnabled()) return false
        val channel = mgr.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        ensureChannel(mgr)

        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("Pairing code")
            .build()
        val replyFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        val replyIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_PAIR),
            replyFlags,
        )
        val openIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentTitle("Set up IP rotation")
            .setContentText("Enter the Wireless debugging pairing code.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "In Wireless debugging, tap Pair device with pairing code, then enter " +
                        "the 6-digit code here. DataProxy uses it once to set up airplane " +
                        "mode control.",
                ),
            )
            .setContentIntent(openIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_proxy,
                    "Enter code",
                    replyIntent,
                ).addRemoteInput(remoteInput).build(),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()

        mgr.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(mgr: NotificationManager) {
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "IP rotation setup",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Wireless debugging pairing for IP rotation"
                setShowBadge(false)
            },
        )
    }

    private const val CHANNEL_ID = "dataproxy.rotation.setup"
    private const val NOTIFICATION_ID = 1002
    private const val REQUEST_CODE = 1002
}
