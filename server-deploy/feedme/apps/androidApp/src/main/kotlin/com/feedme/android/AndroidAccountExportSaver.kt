package com.feedme.android

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.exports.PreparedAccountExportDownload
import kotlinx.coroutines.*

internal class AndroidAccountExportSaver(val save: (PreparedAccountExportDownload) -> Unit,
    val busy: Boolean, val status: String?)

/** User-selected SAF destination only. No broad storage permission, background download,
 * browser handoff, persistent URL or automatic retry. Rotation cancels the RAM-only picker. */
@Composable
internal fun rememberAccountExportSaver(context: Context, hostIsCurrent: () -> Boolean): AndroidAccountExportSaver {
    val host by rememberUpdatedState(hostIsCurrent)
    val scope = rememberCoroutineScope()
    var attached by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<PreparedAccountExportDownload?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { attached = false; pending?.close(); pending = null } }
    fun current(ticket: PreparedAccountExportDownload) = attached && host() && ticket.isCurrent
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val ticket = pending
        pending = null
        if (ticket == null) { busy = false; status = "Choose Download again to save a fresh export." }
        else if (uri == null) { ticket.close(); busy = false; status = "Save cancelled. No export was downloaded." }
        else if (!current(ticket)) { ticket.close(); busy = false; status = "Download access expired. Check export status and try again." }
        else scope.launch {
            var wrote = false
            var completed = false
            try {
                status = "Downloading your private export…"
                when (val result = ticket.download()) {
                    is PortResult.Failure -> status = "Download unavailable. Check export status and try again."
                    is PortResult.Value -> {
                        val owner = result.value
                        try {
                            val bytes = owner.copyForSave() ?: error("Export no longer available")
                            try {
                                check(bytes.size in 1..4_194_304 && current(ticket))
                                status = "Saving to the location you chose…"
                                withContext(Dispatchers.IO) {
                                    check(withContext(Dispatchers.Main.immediate) { current(ticket) })
                                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                                        var offset = 0
                                        while (offset < bytes.size) {
                                            currentCoroutineContext().ensureActive()
                                            check(withContext(Dispatchers.Main.immediate) { current(ticket) })
                                            val count = minOf(65_536, bytes.size - offset)
                                            wrote = true
                                            output.write(bytes, offset, count)
                                            offset += count
                                        }
                                        output.flush()
                                    } ?: error("Export destination unavailable")
                                }
                                // Success is an acknowledged file write, never job readiness alone.
                                check(current(ticket))
                                completed = true
                                if (attached && host()) status = "Export saved to your chosen location. Keep this private file safe."
                            } finally { bytes.fill(0) }
                        } finally { owner.close() }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (attached && host()) status = if (wrote) "File save was not confirmed. The chosen file may be incomplete; check it before trying again."
                    else "Couldn’t save the export. Check its status and choose a location again."
            } finally {
                ticket.close()
                busy = false
                if (!completed && wrote && attached && host()) status = "File save was not confirmed. The chosen file may be incomplete."
            }
        }
    }
    return AndroidAccountExportSaver(save = { ticket ->
        if (!busy && current(ticket)) {
            pending = ticket; busy = true; status = "Choose where to save your private export."
            try { launcher.launch(ticket.filename) }
            catch (_: Exception) { ticket.close(); pending = null; busy = false; status = "The file picker is unavailable on this device." }
        } else ticket.close()
    }, busy = busy, status = status)
}
