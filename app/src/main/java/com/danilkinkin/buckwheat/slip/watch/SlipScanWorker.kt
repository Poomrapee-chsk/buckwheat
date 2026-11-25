package com.danilkinkin.buckwheat.slip.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.slip.SlipCandidate
import com.danilkinkin.buckwheat.slip.SlipCandidateFactory
import com.danilkinkin.buckwheat.slip.SlipParser
import com.danilkinkin.buckwheat.slip.SlipTextRecognizer
import com.danilkinkin.buckwheat.slip.SlipWarning
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reads the slips of chosen folders in the background, with a progress notification, so a long
 * scan doesn't need the app open. Slips found are saved to the waiting list in batches, where the
 * import screen picks them up for review, even while the scan is still running.
 */
class SlipScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = SlipWatchEntryPoint.get(applicationContext)
        val settings = deps.watchSettings()
        val buckets = inputData.getLongArray(KEY_BUCKETS)?.toList().orEmpty()
        val from = inputData.getLong(KEY_FROM, 0)
        val omitImported = inputData.getBoolean(KEY_OMIT_IMPORTED, true)
        val force = inputData.getBoolean(KEY_FORCE, false)

        try {
            setForeground(foregroundInfo(0, 0, 0))
        } catch (e: Exception) {
            // Can't run in the foreground now (e.g. started from the background): run quietly
            Log.w(TAG, "No foreground", e)
        }

        val allImages = SlipMedia.queryImages(applicationContext, buckets, from / 1000 - 1)
        if (force) {
            val ids = allImages.map { it.mediaId }
            settings.removePending(ids)
            ids.forEach { SlipNotifications.cancel(applicationContext, it) }
        }
        val importedMediaIds = if (omitImported) deps.spendsRepository().getUsedSlipMediaIds() else emptySet()
        val (alreadyImported, images) = allImages.partition { it.mediaId in importedMediaIds }
        val factory = SlipCandidateFactory.load(deps.spendsRepository(), deps.tagMemory())
        val found = mutableListOf<SlipCandidate>()
        val batch = mutableListOf<SlipCandidate>()
        var read = 0
        var skipped = 0
        var omitted = alreadyImported.size

        try {
            if (images.isNotEmpty()) {
                SlipTextRecognizer(applicationContext).use { recognizer ->
                    for (image in images) {
                        if (isStopped) break

                        val text = try {
                            recognizer.recognize(image.uri)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.w(TAG, "Can't read ${image.uri}", e)
                            ""
                        }
                        val imageDate = SlipMedia.imageDate(applicationContext, image.uri)
                            ?: LocalDateTime.ofInstant(Instant.ofEpochSecond(image.dateAdded), ZoneId.systemDefault())

                        if (SlipParser.looksLikeSlip(text, imageDate)) {
                            val candidate = factory.build(found.size, text, found, image.mediaId, imageDate = imageDate)
                            if (omitImported && SlipWarning.DUPLICATE in candidate.warnings) {
                                omitted++
                            } else {
                                found += candidate
                                batch += candidate
                            }
                        } else {
                            skipped++
                        }
                        read++

                        if (batch.size >= BATCH || read == images.size) {
                            settings.addPending(batch.toList())
                            batch.clear()
                        }
                        setProgress(
                            workDataOf(
                                KEY_READ to read,
                                KEY_TOTAL to images.size,
                                KEY_FOUND to found.size,
                                KEY_SKIPPED to skipped,
                                KEY_OMITTED to omitted,
                            )
                        )
                        notify(foregroundNotification(read, images.size, found.size))
                    }
                }
            }
        } finally {
            // Stopped early: keep what was found so far
            if (batch.isNotEmpty()) settings.addPending(batch.toList())
            showDone(found.size, read, images.size, omitted)
        }

        return Result.success(
            workDataOf(
                KEY_READ to read,
                KEY_TOTAL to images.size,
                KEY_FOUND to found.size,
                KEY_SKIPPED to skipped,
                KEY_OMITTED to omitted,
            )
        )
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.slip_scan_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun foregroundNotification(read: Int, total: Int, found: Int) = run {
        ensureChannel()
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_money)
            .setContentTitle(applicationContext.getString(R.string.slip_scan_title))
            .setContentText(
                if (total == 0) null
                else applicationContext.getString(R.string.slip_scan_progress, read, total, found)
            )
            .setProgress(total, read, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(SlipNotifications.reviewIntent(applicationContext))
            .addAction(
                0,
                applicationContext.getString(R.string.slip_scan_stop),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
    }

    private fun foregroundInfo(read: Int, total: Int, found: Int): ForegroundInfo {
        val notification = foregroundNotification(read, total, found)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    private fun notify(notification: android.app.Notification) {
        if (!SlipNotifications.canNotify(applicationContext)) return
        try {
            NotificationManagerCompat.from(applicationContext).notify(PROGRESS_ID, notification)
        } catch (e: SecurityException) {
        }
    }

    private fun showDone(found: Int, read: Int, total: Int, omitted: Int) {
        if (!SlipNotifications.canNotify(applicationContext)) return
        ensureChannel()
        val text = when {
            found == 0 && omitted > 0 ->
                applicationContext.resources.getQuantityString(R.plurals.slip_scan_all_imported, omitted, omitted)
            omitted > 0 ->
                applicationContext.resources.getQuantityString(R.plurals.slip_scan_done_omitted, found, found, omitted)
            else ->
                applicationContext.resources.getQuantityString(R.plurals.slip_scan_done, found, found, read, total)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_money)
            .setContentTitle(applicationContext.getString(R.string.slip_scan_done_title))
            .setContentText(text)
            .setContentIntent(SlipNotifications.reviewIntent(applicationContext))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(DONE_ID, notification)
        } catch (e: SecurityException) {
        }
    }

    companion object {
        private const val TAG = "SlipScan"
        const val WORK_NAME = "slipScan"
        private const val CHANNEL_ID = "slipScan"
        private const val PROGRESS_ID = 4201
        private const val DONE_ID = 4202
        private const val BATCH = 10

        private const val KEY_BUCKETS = "buckets"
        private const val KEY_FROM = "from"
        private const val KEY_OMIT_IMPORTED = "omitImported"
        private const val KEY_FORCE = "force"
        const val KEY_READ = "read"
        const val KEY_TOTAL = "total"
        const val KEY_FOUND = "found"
        const val KEY_SKIPPED = "skipped"
        const val KEY_OMITTED = "omitted"

        /** Starts a scan of [buckets] for images added since [fromMillis]; replaces a running one */
        fun start(
            context: Context,
            buckets: Collection<Long>,
            fromMillis: Long,
            omitImported: Boolean = true,
            force: Boolean = false,
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SlipScanWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_BUCKETS to buckets.toLongArray(),
                            KEY_FROM to fromMillis,
                            KEY_OMIT_IMPORTED to omitImported,
                            KEY_FORCE to force,
                        )
                    )
                    .build(),
            )
            NotificationManagerCompat.from(context).cancel(DONE_ID)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun progress(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME)
    }
}
