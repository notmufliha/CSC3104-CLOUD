package com.example.scamdetect.data

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
import com.example.scamdetect.MainActivity
import com.example.scamdetect.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The optional weekly reminder (logic/WeeklyReminder): a local notification every Sunday around 10:00, set with
 * AlarmManager. No server, nothing uploaded. After a phone restart it is set again when the app next opens.
 */
object WeeklyReminderScheduler {
    private const val CHANNEL = "weekly_reminder"
    private const val ID = 7001

    fun permitted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun pending(context: Context) = PendingIntent.getBroadcast(context, ID, Intent(context, WeeklyReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The next Sunday 10:00 (today if it's Sunday before 10:00). */
    fun nextTrigger(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        var day: LocalDate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        if (day == now.toLocalDate() && now.toLocalTime() >= LocalTime.of(10, 0)) day = day.plusWeeks(1)
        return day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextTrigger(System.currentTimeMillis()), AlarmManager.INTERVAL_DAY * 7, pending(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
        NotificationManagerCompat.from(context).cancel(ID)
    }

    fun show(context: Context) {
        if (!permitted(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_weekly_title))
            .setContentText(context.getString(R.string.notif_weekly_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(ID, n) } catch (e: SecurityException) { }
    }

    /** MainActivity opens the weekly summary when the notification is tapped. */
    const val EXTRA_OPEN = "openWeeklySummary"
}

class WeeklyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppPrefs(context).weeklyReminder) WeeklyReminderScheduler.show(context)
    }
}
