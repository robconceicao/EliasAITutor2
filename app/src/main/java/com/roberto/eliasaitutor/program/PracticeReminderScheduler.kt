package com.roberto.eliasaitutor.program

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.roberto.eliasaitutor.MainActivity
import com.roberto.eliasaitutor.R
import java.util.Calendar

/**
 * F6 — daily practice reminder via AlarmManager (survives reboot with BootReceiver).
 * D2: only fixed reminder_time — no extra "streak at risk" notification.
 */
object PracticeReminderScheduler {
    const val CHANNEL_ID = "elias_program_reminder"
    const val NOTIF_ID = 2601
    const val ACTION_REMIND = "com.roberto.eliasaitutor.PROGRAM_REMIND"
    const val PREFS = "elias_reminder"
    const val KEY_HHMM = "hhmm"
    const val KEY_WEEK = "week"
    const val KEY_TITLE = "title"
    const val KEY_SKIP_TODAY = "skip_today"
    const val KEY_SKIP_DATE = "skip_date"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Lembrete do Programa",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Lembrete diário de prática de inglês"
            }
            mgr.createNotificationChannel(ch)
        }
    }

    fun schedule(
        context: Context,
        hhmm: String,
        week: Int,
        weekTitle: String,
        skipIfGoalMet: Boolean,
    ) {
        ensureChannel(context)
        val parts = hhmm.split(":")
        if (parts.size < 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_HHMM, hhmm)
            .putInt(KEY_WEEK, week)
            .putString(KEY_TITLE, weekTitle)
            .apply()

        if (skipIfGoalMet) {
            prefs.edit()
                .putBoolean(KEY_SKIP_TODAY, true)
                .putString(KEY_SKIP_DATE, java.time.LocalDate.now().toString())
                .apply()
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(context, PracticeReminderReceiver::class.java).setAction(ACTION_REMIND)
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    fun cancel(context: Context) {
        val intent = Intent(context, PracticeReminderReceiver::class.java).setAction(ACTION_REMIND)
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

class PracticeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            rescheduleFromPrefs(context)
            return
        }
        if (action != PracticeReminderScheduler.ACTION_REMIND) return

        val prefs = context.getSharedPreferences(PracticeReminderScheduler.PREFS, Context.MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()
        if (prefs.getBoolean(PracticeReminderScheduler.KEY_SKIP_TODAY, false) &&
            prefs.getString(PracticeReminderScheduler.KEY_SKIP_DATE, "") == today
        ) {
            // Goal already met — suppress (F6)
            rescheduleTomorrow(context)
            return
        }

        val week = prefs.getInt(PracticeReminderScheduler.KEY_WEEK, 1)
        val title = prefs.getString(PracticeReminderScheduler.KEY_TITLE, "") ?: ""
        PracticeReminderScheduler.ensureChannel(context)

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_program", true)
        }
        val pi = PendingIntent.getActivity(
            context,
            1,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (title.isNotBlank()) {
            "Hora do inglês! Semana $week: $title"
        } else {
            "Hora do inglês! Semana $week — 30 minutos de conversa"
        }

        val notif = NotificationCompat.Builder(context, PracticeReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Elias — Fluência em 6 Meses")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(PracticeReminderScheduler.NOTIF_ID, notif)

        rescheduleTomorrow(context)
    }

    private fun rescheduleFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PracticeReminderScheduler.PREFS, Context.MODE_PRIVATE)
        val hhmm = prefs.getString(PracticeReminderScheduler.KEY_HHMM, null) ?: return
        PracticeReminderScheduler.schedule(
            context,
            hhmm,
            prefs.getInt(PracticeReminderScheduler.KEY_WEEK, 1),
            prefs.getString(PracticeReminderScheduler.KEY_TITLE, "") ?: "",
            skipIfGoalMet = false,
        )
    }

    private fun rescheduleTomorrow(context: Context) {
        rescheduleFromPrefs(context)
    }
}
