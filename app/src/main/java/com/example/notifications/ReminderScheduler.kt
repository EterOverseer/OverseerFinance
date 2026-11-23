package com.example.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object ReminderScheduler {
    const val CHANNEL_ID = "overseer_finance_reminders"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_MESSAGE = "extra_message"
    const val EXTRA_NOTIF_ID = "extra_notif_id"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "แจ้งเตือนรายจ่ายประจำและหนี้สิน",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "การแจ้งเตือนออฟไลน์สำหรับบิลประจำและกำหนดชำระหนี้"
        }
        manager.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun scheduleReminder(
        context: Context,
        requestCode: Int,
        triggerAtMillis: Long,
        title: String,
        message: String
    ) {
        if (triggerAtMillis <= System.currentTimeMillis()) return
        ensureChannel(context)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_NOTIF_ID, requestCode)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    fun showImmediateNotification(
        context: Context,
        notifId: Int,
        title: String,
        message: String
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)
        val openAppIntent = Intent(context, MainActivity::class.java)
        val contentPi = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "แจ้งเตือน OverseerFinance"
        val message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE) ?: "มีรายการครบกำหนดชำระวันนี้"
        val notifId = intent.getIntExtra(ReminderScheduler.EXTRA_NOTIF_ID, 1001)
        ReminderScheduler.showImmediateNotification(context, notifId, title, message)
    }
}
