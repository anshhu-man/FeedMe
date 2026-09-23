package com.feedme.android

/** Main-owner lifecycle only. A replaced Activity cannot resume, stop or detach its successor.
 * Foreground is never restored merely by attachment; the exact host must explicitly resume. */
internal class EntryTimerForegroundGate(private val changed: (Any?, Boolean) -> Unit) {
    private var attachment: Any? = null
    private var resumed = false
    private var closed = false
    private var deliveryPaused = false
    fun attach(token: Any) {
        if (closed) return
        attachment = token; resumed = false; changed(token, false)
    }
    fun foreground(token: Any, value: Boolean) {
        if (closed || attachment !== token) return
        resumed = value; changed(token, value && !deliveryPaused)
    }
    fun detach(token: Any) {
        if (closed || attachment !== token) return
        attachment = null; resumed = false; changed(null, false)
    }
    /** Process-owned reversible confirmation pause; recreation/resume cannot bypass it. */
    fun pauseDelivery() { if (!closed) { deliveryPaused = true; reapply() } }
    fun resumeDelivery() { if (!closed) { deliveryPaused = false; reapply() } }
    fun reapply() { if (!closed) changed(attachment, resumed && !deliveryPaused) }
    fun close() {
        if (closed) return
        closed = true; attachment = null; resumed = false; changed(null, false)
    }
}
