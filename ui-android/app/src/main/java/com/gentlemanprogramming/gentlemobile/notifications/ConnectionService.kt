package com.gentlemanprogramming.gentlemobile.notifications

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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.gentlemanprogramming.gentlemobile.MainActivity
import com.gentlemanprogramming.gentlemobile.R

/**
 * T15 (S11): the Android foreground service that keeps an SSH connection
 * alive while the app is in the background, with a persistent low-noise
 * notification (`connected · <host>`) and a Disconnect action.
 *
 * S11 is explicit: **no relay, no push service** — the phone holds the SSH
 * link itself, and Android only kills background processes when nothing
 * foregrounds them. This service is that foreground; it stops the moment
 * the connection ends or the user disconnects ([ForegroundServiceGate]
 * owns the pairing, unit-tested).
 *
 * Battery/liveness (T15 rule 4): the service adds **no polling of its
 * own**. Liveness in the background is exactly what the core already does
 * in the foreground — russh keepalive probes every 5 s
 * (`KEEPALIVE_INTERVAL`, giving up after 2 unanswered probes) and the
 * watchdog's 1 s tick, which only *provokes* a health probe after 30 s of
 * quiet (`WATCHDOG_QUIET_AFTER`). The service's only job is to raise the
 * process priority so those existing timers keep firing; no wakeups are
 * added, no interval changed.
 *
 * Android 14 (targetSdk 34) requirements honoured: the `dataSync`
 * foreground-service type is declared in the manifest together with
 * `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions, and
 * `startForeground` names the same type ([ServiceCompat.startForeground])
 * inside `onStartCommand`, within the FGS time budget.
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            // The user tapped Disconnect on the notification: the app's own
            // disconnect path tears the SSH link down and returns to Pair.
            ForegroundHub.userDisconnect?.invoke()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: "host"
        // ServiceCompat names the FGS type explicitly, which Android 14
        // requires for dataSync — the manifest declares the same type.
        ServiceCompat.startForeground(
            this,
            CONNECTION_NOTIFICATION_ID,
            connectionNotification(this, host),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_DISCONNECT = "com.gentlemanprogramming.gentlemobile.DISCONNECT"
        const val EXTRA_HOST = "com.gentlemanprogramming.gentlemobile.CONNECTION_HOST"
        const val EXTRA_OPEN_AGENT_PANE = "com.gentlemanprogramming.gentlemobile.OPEN_AGENT_PANE"

        /** The one persistent connection notification; agent alerts key off
         *  [agentNotificationId], which never collides with this constant. */
        const val CONNECTION_NOTIFICATION_ID = 42

        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_AGENTS = "agents"

        fun connectionIntent(context: Context, host: String): Intent =
            Intent(context, ConnectionService::class.java).putExtra(EXTRA_HOST, host)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, ConnectionService::class.java).setAction(ACTION_DISCONNECT)
    }
}

/**
 * The bridge between the notification's Disconnect action and the screen
 * that owns the live connection. The app registers its disconnect while a
 * connection is live and clears it when teardown happens; the service runs
 * in the same process, so the call is direct.
 */
object ForegroundHub {
    @Volatile
    var userDisconnect: (() -> Unit)? = null
}

/** The two channels, created idempotently wherever a notification is first
 *  posted: `connection` is deliberately IMPORTANCE_LOW — persistent and
 *  quiet, never a heads-up — while `agents` carries the alerts that must
 *  actually be seen. */
fun ensureChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
        NotificationChannel(ConnectionService.CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a host connection is live in the background."
            setShowBadge(false)
        },
    )
    manager.createNotificationChannel(
        NotificationChannel(ConnectionService.CHANNEL_AGENTS, "Agent activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "An agent moved into needs you, or finished."
        },
    )
}

/** The persistent service notification: `connected · <host>`, silent, and
 *  tapping it opens the app. The Disconnect action tears the link down —
 *  the same path as the in-app Disconnect (ForegroundServiceGate owns the
 *  pairing, so the service stops exactly once). */
fun connectionNotification(context: Context, host: String): Notification {
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val disconnect = PendingIntent.getService(
        context,
        1,
        ConnectionService.disconnectIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, ConnectionService.CHANNEL_CONNECTION)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("connected · $host")
        .setContentText("Your agents are reachable. Tap to open.")
        .setContentIntent(open)
        .addAction(0, "Disconnect", disconnect)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()
}

/** Post one agent-alert notification. One notification per transition:
 *  the identity is stable per pane ([agentNotificationId]), so a repeat for
 *  the same agent updates in place instead of duplicating. Tapping it opens
 *  that agent's session. */
fun postAgentAlert(context: Context, alert: AgentAlert) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) {
        // Permission honesty: a denial is never a silent failure — the
        // Settings row says notifications are off and why. Posting without
        // the grant would only throw.
        return
    }
    ensureChannels(context)
    val open = Intent(context, MainActivity::class.java).apply {
        putExtra(ConnectionService.EXTRA_OPEN_AGENT_PANE, alert.paneId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val content = PendingIntent.getActivity(
        context,
        agentNotificationId(alert.paneId),
        open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val title = when (alert.kind) {
        AlertKind.NEEDS_YOU -> "${alert.name} needs you"
        AlertKind.DONE -> "${alert.name} is done"
    }
    val notification = NotificationCompat.Builder(context, ConnectionService.CHANNEL_AGENTS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(alert.cwd ?: alert.kind.word)
        .setStyle(NotificationCompat.BigTextStyle().bigText(alert.cwd ?: alert.kind.word))
        .setContentIntent(content)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .build()
    manager.notify(agentNotificationId(alert.paneId), notification)
}
