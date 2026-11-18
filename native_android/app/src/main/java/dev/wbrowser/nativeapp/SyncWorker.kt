package dev.wbrowser.nativeapp

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Schedules automatic background synchronisation. There is no manual sync button anywhere in the app. */
object SyncScheduler {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private fun wm() = WorkManager.getInstance(App.instance)

    /** Safety net: every 15 min while there is anything to send (also covers app restarts and reboots). */
    fun ensurePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "wb-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        )
    }

    /** Debounced: new events batch up for 5 s; if a run is already queued this is a no-op. Waits for connectivity. */
    fun kick() = enqueue(Remote.intervalSec.toLong())

    /** Flush soon (app going to background, consent/profile change, erasure). */
    fun kickNow() = enqueue(0)

    private fun enqueue(delaySec: Long) {
        if (Account.userId == null) return
        wm().enqueueUniqueWork(
            if (delaySec == 0L) "wb-sync-now" else "wb-sync-kick", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        )
    }
}


/**
 * Uploads the offline outbox to Firestore. Offline = WorkManager waits for a network; failures return retry() so
 * WorkManager re-runs with exponential backoff. The outbox is only trimmed after Firestore acknowledged the writes.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
        run()
    } catch (e: Exception) {
        Account.lastError = when {
            e is java.util.concurrent.TimeoutException || e.cause is IOException || e is IOException -> "Offline or cloud unreachable; will retry"
            e.message?.contains("ADMIN_ONLY_OPERATION", true) == true || e.message?.contains("OPERATION_NOT_ALLOWED", true) == true ->
                "Enable Anonymous sign-in in the Firebase console (Authentication > Sign-in method)"
            else -> e.message?.take(160)
        }
        Result.retry()
    }

    private fun run(): Result {
        if (!Cloud.ready) { Account.lastError = "Cloud not configured (add google-services.json)"; return Result.success() }
        if (Account.userId == null) return Result.success()

        // 1. right to erasure takes priority over everything else
        if (Account.erasePending) {
            val existing = Cloud.currentUid()
            if (existing != null) Cloud.erase(existing)
            Account.finishErasure(); return Result.success()
        }
        if (!Account.sharing) {
            // consent withdrawn: tell the cloud once (blocks further uploads at the rules level), then stay quiet
            val existing = Cloud.currentUid()
            if (Account.consentDirty && Account.registered && existing != null) { runCatching { Cloud.pushProfile(existing); Account.clearConsentDirty() } }
            return Result.success()
        }
        val uid = Cloud.uid()
        runCatching { Cloud.fetchConfig() }   // remote settings from the admin console
        if (!Remote.trackingEnabled) { Account.lastError = "Collection paused by administrator"; ActivityDb.clear(); return Result.success() }

        // 2. profile: create on first run, detect removal by an administrator afterwards
        if (!Account.registered) { Cloud.register(uid); Account.markRegistered(); Account.clearConsentDirty() }
        else when (Cloud.state(uid)) {
            // An admin erased this person's data: drop the old sign-in and quietly start over as a fresh user
            // (same name/consent, new ID, empty history). No dialog, and nothing links back to the erased record.
            Cloud.State.MISSING -> { runCatching { Cloud.deleteOwnAuth() }; Account.reEnrollAfterErase(); return Result.retry() }
            Cloud.State.SUSPENDED -> { Account.lastError = "Uploads paused by administrator"; return Result.success() }  // keep queue
            Cloud.State.ACTIVE -> {}
        }
        if (Account.consentDirty) { Cloud.pushProfile(uid); Account.clearConsentDirty() }

        // 3. drain the outbox in batches
        while (true) {
            val rows = ActivityDb.peek(100)
            if (rows.isEmpty()) break
            val done = try { Cloud.pushEvents(uid, rows) }
            catch (e: Exception) {
                if (Cloud.isDenied(e)) { Account.lastError = "Uploads paused by administrator"; return Result.success() }
                throw e
            }
            if (done.isEmpty()) break
            ActivityDb.delete(done)
            Account.lastSyncAt = System.currentTimeMillis(); Account.lastError = null; Account.changed()
        }
        runCatching { Cloud.pushProfile(uid) }   // refresh "last seen"
        return Result.success()
    }
}
