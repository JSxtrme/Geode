package com.js.geode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

object Notifications {
    const val CHANNEL_ID = "geode_summaries"
    const val PROGRESS_ID = 1001
    const val DONE_BASE_ID = 2000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Summary updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Tells you when a reel summary is ready"
            }
        )
    }

    fun openSummaryIntent(context: Context, summaryId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SUMMARY_ID, summaryId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, summaryId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun openSettingsIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_API_KEY, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun openLibraryIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Large icon for the notification shade: the full G logo (dark tile +
     * white arcs + purple crossbar), rendered from the vector. The small
     * (status-bar) icon is always the monochrome G below — a notification
     * small icon must be white-on-transparent, which is exactly what
     * ic_launcher_monochrome is.
     */
    private fun largeIcon(context: Context): Bitmap? {
        return runCatching {
            ContextCompat.getDrawable(context, R.drawable.geode_logo)?.toBitmap(192, 192)
        }.getOrNull()
    }

    fun progressNotification(context: Context): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Processing reel")
            .setContentText("Your summary is being made…")
            .setOngoing(true)
            .build()
    }

    fun doneNotification(context: Context, id: Int, title: String, summaryId: Long): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setLargeIcon(largeIcon(context))
            .setContentTitle("Summary ready in Geode")
            .setContentText(title)
            .setContentIntent(openSummaryIntent(context, summaryId))
            .setAutoCancel(true)
            .build()
    }

    fun failedNotification(context: Context, id: Int, message: String): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setLargeIcon(largeIcon(context))
            .setContentTitle("Couldn't summarise this reel")
            .setContentText(message)
            .setContentIntent(openLibraryIntent(context))
            .setAutoCancel(true)
            .build()
    }

    fun needKeyNotification(context: Context, id: Int): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Add your Gemini API key")
            .setContentText("Tap to add your free key, then retry this reel.")
            .setContentIntent(openSettingsIntent(context))
            .setAutoCancel(true)
            .build()
    }

    /**
     * Confirmed private reel only (see GeminiClient.PRIVATE_REEL_MESSAGE).
     * Notification lines are SHORT on purpose — they must show fully in
     * the notification panel. The longer explanation lives on the Library
     * error card only, never in the notification.
     */
    fun privateNotification(context: Context, id: Int): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setLargeIcon(largeIcon(context))
            .setContentTitle("This reel is private")
            .setContentText("Geode couldn't see it")
            .setContentIntent(openLibraryIntent(context))
            .setAutoCancel(true)
            .build()
    }

    fun post(manager: NotificationManager, id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }
}
