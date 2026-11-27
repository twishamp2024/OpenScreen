package io.github.openscreen.sender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import io.github.openscreen.sender.MainActivity
import io.github.openscreen.sender.OpenScreenApp
import io.github.openscreen.sender.R

/**
 * Foreground service of type `mediaProjection` (required on Android 10+ to
 * capture the screen, and on 14+ it must be started *after* consent). It
 * holds the MediaProjection; the session itself lives in [SenderController].
 */
class StreamingService : Service() {
    private val controller get() = (application as OpenScreenApp).controller
    private var projection: MediaProjection? = null
    private val main = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User tapped "Stop sharing" in the status bar, or the system revoked it.
            main.post { stopStreaming("projection-revoked") }
        }

        override fun onCapturedContentResize(width: Int, height: Int) = Unit
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopStreaming("user")
            else -> stopSelf()
        }
        return START_NOT_STICKY // a projection token cannot be reused after process death
    }

    private fun start(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        if (data == null) return stopStreaming(null)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: return stopStreaming(null)
        projection = p
        p.registerCallback(projectionCallback, main) // must precede createVirtualDisplay on Android 14+
        try {
            controller.startCapture(p)
        } catch (e: RuntimeException) {
            stopStreaming("encoder-error")
        }
    }

    private fun stopStreaming(reason: String?) {
        controller.stopCapture(reason)
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null) stopStreaming("app-backgrounded")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_streaming)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop_streaming), stop)
            .build()
    }

    companion object {
        private const val ACTION_START = "io.github.openscreen.action.START"
        private const val ACTION_STOP = "io.github.openscreen.action.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1

        /** Call with the result of the MediaProjection consent dialog. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, StreamingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamingService::class.java).setAction(ACTION_STOP))
        }
    }
}
