package com.feedme.session

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.work.WorkManager
import androidx.work.await
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Exact-ticket native cancellation only. The registry must durably fence the origin before use.
 * A successful cancellation command is NOT callback quiescence or rollback of a remote effect.
 *
 * Work tickets must be independent requests, or have only same-origin dependent requests:
 * WorkManager propagates cancellation to dependent work. Never create cross-origin chains.
 * The injected WorkManager must belong to this application and be initialized by trusted startup
 * composition after establishing the recovery gate; this adapter never initializes it.
 */
class AndroidNativeWorkCancellation private constructor(
    private val context: Context,
    private val receiver: ComponentName,
    private val workManager: WorkManager,
    private val alarms: AlarmManager,
    private val notifications: NotificationManager,
) : NativeWorkCancellationPort {
    /** Exact identity for trusted scheduler integration; not authorization to schedule a ticket. */
    fun timerIdentity(ticket: NativeWorkTicket): PortResult<AndroidNativeTimerIdentity> = try {
        requireCredentialUuid(ticket.id)
        if (ticket.kind != NativeWorkKind.TIMER) PortResult.Failure(FailureReason.INVALID_DATA)
        else PortResult.Value(AndroidNativeTimerIdentity(receiver, ticket.id))
    } catch (_: IllegalArgumentException) {
        PortResult.Failure(FailureReason.INVALID_DATA)
    }

    override suspend fun cancel(ticket: NativeWorkTicket): PortResult<Unit> {
        try { requireCredentialUuid(ticket.id) } catch (_: IllegalArgumentException) {
            return PortResult.Failure(FailureReason.INVALID_DATA)
        }
        return try {
            withContext(Dispatchers.IO) {
                when (ticket.kind) {
                    NativeWorkKind.TIMER -> {
                        val identity = AndroidNativeTimerIdentity(receiver, ticket.id)
                        val pending = PendingIntent.getBroadcast(context, identity.requestCode,
                            identity.intent(), identity.pendingIntentFlags or PendingIntent.FLAG_NO_CREATE)
                        if (pending != null) {
                            alarms.cancel(pending)
                            pending.cancel()
                        }
                        // Do this even when the alarm PendingIntent is already absent: delivery
                        // may have occurred before cancellation or before a previous process died.
                        notifications.cancel(identity.notificationTag, identity.notificationId)
                    }
                    NativeWorkKind.WORKER -> {
                        val acknowledged = withTimeoutOrNull(WORK_CANCEL_TIMEOUT_MILLIS) {
                            workManager.cancelWorkById(UUID.fromString(ticket.id)).await()
                            true
                        }
                        // The command may have committed before its acknowledgment arrived.
                        if (acknowledged != true) return@withContext PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                    }
                }
                PortResult.Value(Unit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PortResult.Failure(FailureReason.UNAVAILABLE)
        } catch (_: LinkageError) {
            PortResult.Failure(FailureReason.UNAVAILABLE)
        }
    }

    override fun toString() = "AndroidNativeWorkCancellation(<redacted>)"

    companion object {
        private const val WORK_CANCEL_TIMEOUT_MILLIS = 15_000L

        /** No default receiver and no implicit WorkManager initialization. */
        fun create(
            context: Context,
            alarmReceiver: ComponentName,
            workManager: WorkManager,
        ): PortResult<AndroidNativeWorkCancellation> = try {
            val application = context.applicationContext
            require(alarmReceiver.packageName == application.packageName)
            // A disabled receiver can still have a previously issued PendingIntent to cancel.
            val info = application.packageManager.getReceiverInfo(alarmReceiver, PackageManager.MATCH_DISABLED_COMPONENTS)
            require(info.packageName == application.packageName && checkNotNull(info.applicationInfo).uid == Process.myUid())
            require(!info.exported)
            val alarms = checkNotNull(application.getSystemService(AlarmManager::class.java))
            val notifications = checkNotNull(application.getSystemService(NotificationManager::class.java))
            PortResult.Value(AndroidNativeWorkCancellation(application, alarmReceiver, workManager, alarms, notifications))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PortResult.Failure(FailureReason.NOT_CONFIGURED)
        } catch (_: LinkageError) {
            PortResult.Failure(FailureReason.NOT_CONFIGURED)
        }
    }
}

/** Contains only an opaque ticket identity. Each intent() call returns an independent instance. */
class AndroidNativeTimerIdentity internal constructor(
    private val receiver: ComponentName,
    private val ticketId: String,
) {
    val requestCode: Int get() = 0
    val pendingIntentFlags: Int get() = PendingIntent.FLAG_IMMUTABLE
    val notificationTag: String get() = "com.feedme.session.timer.$ticketId"
    val notificationId: Int get() = 1

    init { requireCredentialUuid(ticketId) }

    fun intent(): Intent = Intent(ACTION)
        .setComponent(receiver)
        .setPackage(receiver.packageName)
        .setData(Uri.Builder().scheme("feedme-native").authority("timer").appendPath(ticketId).build())

    override fun toString() = "AndroidNativeTimerIdentity(<redacted>)"

    companion object {
        private const val ACTION = "com.feedme.session.action.TIMER"
    }
}
