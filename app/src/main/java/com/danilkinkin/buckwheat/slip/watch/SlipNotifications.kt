package com.danilkinkin.buckwheat.slip.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.slip.SLIP_IMPORT_SHEET
import com.danilkinkin.buckwheat.slip.SlipCandidate
import com.danilkinkin.buckwheat.util.numberFormat
import java.text.DateFormat

object SlipNotifications {
    private const val CHANNEL_ID = "newSlips"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.slip_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.slip_notification_channel_description)
            }
        )
    }

    /** Opens the app on the slip review screen */
    fun reviewIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SHEET, SLIP_IMPORT_SHEET)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(context: Context, action: String, mediaId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            // Unique per slip and action, so the extras don't get mixed up
            (mediaId * 2 + if (action == SlipActionReceiver.ACTION_ADD) 1 else 0).toInt(),
            Intent(context, SlipActionReceiver::class.java)
                .setAction(action)
                .putExtra(SlipActionReceiver.EXTRA_MEDIA_ID, mediaId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun show(context: Context, candidate: SlipCandidate, currency: ExtendCurrency) {
        val mediaId = candidate.mediaId ?: return
        if (!canNotify(context)) return
        ensureChannel(context)

        val amount = numberFormat(context, candidate.amount ?: return, currency = currency)
        val details = listOfNotNull(
            candidate.payee,
            candidate.tag.takeIf { it.isNotBlank() }?.let { "→ $it" },
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(candidate.date),
        ).joinToString(" · ")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_money)
            .setContentTitle(context.getString(R.string.slip_notification_title, amount))
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(reviewIntent(context))
            .setAutoCancel(true)
            .addAction(
                0, context.getString(R.string.slip_notification_add),
                actionIntent(context, SlipActionReceiver.ACTION_ADD, mediaId),
            )
            .addAction(
                0, context.getString(R.string.slip_notification_review),
                reviewIntent(context),
            )
            .addAction(
                0, context.getString(R.string.slip_notification_dismiss),
                actionIntent(context, SlipActionReceiver.ACTION_DISMISS, mediaId),
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId(mediaId), notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and now
        }
    }

    fun cancel(context: Context, mediaId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(mediaId))
    }

    private fun notificationId(mediaId: Long) = (mediaId % Int.MAX_VALUE).toInt()
}
