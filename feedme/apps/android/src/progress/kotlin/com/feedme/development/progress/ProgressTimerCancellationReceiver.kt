package com.feedme.development.progress

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Disabled, non-exported identity for exact pending-alarm cleanup only. No alarm is scheduled
 * to it in this foreground preview and no incoming Intent can deliver or reconstruct a timer.
 */
class ProgressTimerCancellationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = Unit
}
