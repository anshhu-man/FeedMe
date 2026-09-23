package com.feedme.app.guest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class GuestEnergy { LOW, MEDIUM, HIGH }
enum class GuestCleanup { MINIMAL, SOME, ANY }
enum class GuestPreparation { ASSEMBLE, LITTLE, HAPPY }
enum class GuestCleanupLimit { ZERO_MINUTES, FIVE_MINUTES, TEN_MINUTES, UNLIMITED }

/** Local user choices only: text is not a catalog identity, verified pantry fact or recipe. */
data class GuestKitchenDraft(
    val ingredientsText: String = "",
    val minutes: Int = 20,
    val energy: GuestEnergy = GuestEnergy.MEDIUM,
    val cleanup: GuestCleanup = GuestCleanup.SOME,
    /** Explicit local choices, never inferred from the preserved generic energy/cleanup. */
    val preparation: GuestPreparation? = null,
    val cleanupLimit: GuestCleanupLimit? = null,
    val servings: Int? = null,
) {
    init {
        require(ingredientsText.length <= MAX_INGREDIENT_CHARACTERS &&
            ingredientsText.none { (it.code < 32 && it !in "\t\n\r") || it.code == 127 } &&
            validUtf8(ingredientsText) && minutes in ALLOWED_MINUTES &&
            (servings == null || servings in ALLOWED_SERVINGS)) { "Guest draft is invalid" }
    }
    /** Input completeness only; not recipe eligibility or permission to perform matching. */
    val hasExplicitEffort: Boolean get() = preparation != null && cleanupLimit != null && servings != null
    override fun toString() = "GuestKitchenDraft(<redacted>)"

    companion object {
        const val MAX_INGREDIENT_CHARACTERS = 1024
        const val MAX_INGREDIENT_BYTES = 4096
        val ALLOWED_MINUTES: List<Int> get() = listOf(10, 15, 20, 30, 45, 60)
        val ALLOWED_SERVINGS: IntRange get() = 1..6
        private fun validUtf8(text: String): Boolean = try {
            text.encodeToByteArray(throwOnInvalidSequence = true).size <= MAX_INGREDIENT_BYTES
        } catch (_: CharacterCodingException) { false }
    }
}

sealed interface GuestDraftLoad {
    data object Empty : GuestDraftLoad
    class Loaded(val draft: GuestKitchenDraft) : GuestDraftLoad {
        override fun toString() = "GuestDraftLoad.Loaded(<redacted>)"
    }
    data object Unavailable : GuestDraftLoad
    data object Corrupt : GuestDraftLoad
    data object FutureVersion : GuestDraftLoad
}

enum class GuestDraftSave { Saved, Unavailable, OutcomeUnknown }

/** One process-owned, exclusive local store. No account, credentials or server side effects.
 * Empty means positively absent, never a read error. Saved means durable acknowledgement.
 * Implementations must retain existing unreadable data and refuse writes after a failed load
 * or uncertain save until a fresh successful load. Never reset or overwrite on decode failure.
 * An Unavailable save must not imply success; use OutcomeUnknown if a write may have occurred.
 */
interface GuestKitchenDraftStore {
    suspend fun load(): GuestDraftLoad
    suspend fun save(draft: GuestKitchenDraft): GuestDraftSave
}

enum class GuestDraftPhase { NEW, LOADING, READY, SAVING, BLOCKED, CLOSED }
enum class GuestDraftIssue { UNAVAILABLE, CORRUPT, FUTURE_VERSION, OUTCOME_UNKNOWN }

class GuestKitchenDraftState internal constructor(
    val phase: GuestDraftPhase,
    val draft: GuestKitchenDraft?,
    val dirty: Boolean,
    val issue: GuestDraftIssue?,
    val hasSavedDraft: Boolean,
    internal val revision: Long = 0,
) {
    internal fun change(phase: GuestDraftPhase = this.phase, draft: GuestKitchenDraft? = this.draft,
        dirty: Boolean = this.dirty, issue: GuestDraftIssue? = this.issue,
        hasSavedDraft: Boolean = this.hasSavedDraft, revision: Long = this.revision) =
        GuestKitchenDraftState(phase, draft, dirty, issue, hasSavedDraft, revision)
    override fun toString() = "GuestKitchenDraftState(phase=$phase, dirty=$dirty, private=<redacted>)"
}

/** No I/O at construction. Port calls serialize on the supplied dispatcher; UI edits are
 * immediate atomic state changes, including during a suspended save. The process host owns
 * this lifetime, not a composition attachment. close retires delivery, not an in-flight write.
 */
class GuestKitchenDraftController(private val store: GuestKitchenDraftStore,
    private val dispatcher: CoroutineDispatcher) {
    private val mutable = MutableStateFlow(GuestKitchenDraftState(GuestDraftPhase.NEW, null, false, null, false))
    val states: StateFlow<GuestKitchenDraftState> = mutable.asStateFlow()
    private val io = Mutex()

    fun update(draft: GuestKitchenDraft) {
        change { current ->
            if (current.phase !in setOf(GuestDraftPhase.READY, GuestDraftPhase.SAVING) || current.draft == draft) current
            else current.change(draft = draft, dirty = true, issue = null, revision = current.revision + 1)
        }
    }

    /** Only initial load or explicit blocked-state retry. Dirty edits survive a retry. */
    suspend fun load() = withContext(dispatcher) {
        io.withLock {
            val before = mutable.value
            if (before.phase !in setOf(GuestDraftPhase.NEW, GuestDraftPhase.BLOCKED)) return@withLock
            if (!mutable.compareAndSet(before, before.change(phase = GuestDraftPhase.LOADING, issue = null))) return@withLock
            val result = try {
                currentCoroutineContext().ensureActive()
                store.load().also { currentCoroutineContext().ensureActive() }
            } catch (cancelled: CancellationException) {
                blocked(GuestDraftIssue.UNAVAILABLE); throw cancelled
            } catch (_: Exception) { GuestDraftLoad.Unavailable }
            when (result) {
                GuestDraftLoad.Empty, is GuestDraftLoad.Loaded -> change { current ->
                    if (current.phase != GuestDraftPhase.LOADING) current
                    else current.change(phase = GuestDraftPhase.READY,
                        draft = if (before.dirty && before.draft != null) before.draft
                            else (result as? GuestDraftLoad.Loaded)?.draft ?: GuestKitchenDraft(),
                        dirty = before.dirty, issue = null, hasSavedDraft = result is GuestDraftLoad.Loaded)
                }
                GuestDraftLoad.Unavailable -> blocked(GuestDraftIssue.UNAVAILABLE)
                GuestDraftLoad.Corrupt -> blocked(GuestDraftIssue.CORRUPT)
                GuestDraftLoad.FutureVersion -> blocked(GuestDraftIssue.FUTURE_VERSION)
            }
        }
    }

    /** True acknowledges only the captured edit revision, never a later UI edit. */
    suspend fun save(): Boolean = withContext(dispatcher) {
        io.withLock {
            var captured: GuestKitchenDraftState
            while (true) {
                captured = mutable.value
                if (captured.phase != GuestDraftPhase.READY || captured.draft == null) return@withLock false
                if (!captured.dirty) return@withLock captured.hasSavedDraft
                if (mutable.compareAndSet(captured, captured.change(phase = GuestDraftPhase.SAVING, issue = null))) break
            }
            val result = try {
                currentCoroutineContext().ensureActive()
                store.save(checkNotNull(captured.draft)).also { currentCoroutineContext().ensureActive() }
            } catch (cancelled: CancellationException) {
                blocked(GuestDraftIssue.OUTCOME_UNKNOWN); throw cancelled
            } catch (_: Exception) { GuestDraftSave.OutcomeUnknown }
            when (result) {
                GuestDraftSave.Saved -> {
                    change { current ->
                        if (current.phase != GuestDraftPhase.SAVING) current
                        else current.change(phase = GuestDraftPhase.READY, dirty = current.revision != captured.revision,
                            issue = null, hasSavedDraft = true)
                    }
                    val current = mutable.value
                    current.phase == GuestDraftPhase.READY && current.revision == captured.revision && !current.dirty
                }
                GuestDraftSave.Unavailable -> { blocked(GuestDraftIssue.UNAVAILABLE); false }
                GuestDraftSave.OutcomeUnknown -> { blocked(GuestDraftIssue.OUTCOME_UNKNOWN); false }
            }
        }
    }

    fun close() { change { it.change(phase = GuestDraftPhase.CLOSED, draft = null, dirty = false, issue = null, hasSavedDraft = false) } }

    private fun blocked(issue: GuestDraftIssue) {
        change { if (it.phase == GuestDraftPhase.CLOSED) it else it.change(phase = GuestDraftPhase.BLOCKED, issue = issue) }
    }
    private inline fun change(transform: (GuestKitchenDraftState) -> GuestKitchenDraftState) {
        while (true) {
            val before = mutable.value
            val after = transform(before)
            if (after === before || mutable.compareAndSet(before, after)) return
        }
    }
}
