package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Atomics-only revocation epoch owned by ONE configured retained-principal integration.
 * It is neither a session, a credential, a server permission nor a delivered acknowledgement. */
@OptIn(ExperimentalAtomicApi::class)
class PublicationDeliveryGeneration internal constructor(
    private val source: PostPublicationDeliveryIntegration,
    private val seal: Any,
    private val principalIssuer: PostPublicationPrincipalIntegration,
    private val retainedBinding: PublicationSessionBinding,
    private val retainedOwner: Any,
    private val refreshGeneration: Any,
    private val lease: SessionLease,
    private val boundary: SessionBoundary,
    private val origin: String,
    private val environment: String,
    private val account: String,
    private val maxPendingDeliveries: Int,
) {
    private sealed interface EpochState
    private class Active(val pending: List<PublicationDeliveryGate>) : EpochState
    private data object Revoked : EpochState
    private val epoch = AtomicReference<EpochState>(Active(emptyList()))
    internal var subscription: SessionInvalidationSubscription? = null // Identity dispatcher only.
    private val observers = mutableListOf<PublicationDeliveryRevocationSubscription>() // Identity dispatcher only.
    internal fun matches(source: PostPublicationDeliveryIntegration, seal: Any, principal: PublicationPrincipalSnapshot) =
        this.source === source && this.seal === seal && principalIssuer === principal.issuer &&
            retainedBinding.sameRetainedSession(principal.binding) &&
            retainedOwner === principal.retainedOwner && refreshGeneration === principal.refreshGeneration &&
            lease === principal.binding.lease && boundary === principal.binding.boundary && origin == principal.binding.origin &&
            environment == principal.binding.environment && account == principal.canonicalUserId
    internal fun owned(source: PostPublicationDeliveryIntegration, seal: Any) = this.source === source && this.seal === seal
    internal fun active(): Boolean = epoch.load() is Active
    internal fun revoke(source: PostPublicationDeliveryIntegration, seal: Any) {
        if (!owned(source, seal)) mealFail(FailureReason.STALE_SESSION)
        while (true) {
            val before = epoch.load()
            if (before === Revoked) break
            before as Active
            if (!epoch.compareAndSet(before, Revoked)) continue
            // The epoch CAS won over authorization/cancel/registration. Only gates still pending
            // in THIS exact predecessor are cancelled. Earlier authorization removed its gate;
            // this preserves historical authorization, NOT proof of actual public delivery.
            before.pending.forEach { it.complete(this, source, seal, false) }
            break
        }
        val notify = observers.toList(); observers.clear()
        notify.forEach { it.notifyRevoked() }
    }
    internal fun register(source: PostPublicationDeliveryIntegration, seal: Any): PublicationDeliveryGate {
        if (!owned(source, seal)) mealFail(FailureReason.STALE_SESSION)
        val gate = PublicationDeliveryGate(this, source, seal)
        while (true) {
            val before = epoch.load() as? Active ?: mealFail(FailureReason.STALE_SESSION)
            if (before.pending.size >= maxPendingDeliveries) mealFail(FailureReason.UNAVAILABLE)
            if (epoch.compareAndSet(before, Active(before.pending + gate))) return gate
        }
    }
    internal fun finish(gate: PublicationDeliveryGate, source: PostPublicationDeliveryIntegration, seal: Any,
        authorized: Boolean): Boolean {
        if (!owned(source, seal) || !gate.owned(this, source, seal)) return false
        while (true) {
            val before = epoch.load() as? Active ?: return false
            if (before.pending.none { it === gate }) return false
            // This ONE CAS linearizes authorization or our cancellation notification against
            // native epoch revocation and other gates. It is NOT atomic with Job private state.
            if (!epoch.compareAndSet(before, Active(before.pending.filterNot { it === gate }))) continue
            gate.complete(this, source, seal, authorized)
            return true
        }
    }
    internal fun observe(source: PostPublicationDeliveryIntegration, seal: Any,
        callback: () -> Unit): PublicationDeliveryRevocationSubscription {
        if (!owned(source, seal) || !active()) mealFail(FailureReason.STALE_SESSION)
        return PublicationDeliveryRevocationSubscription(callback) { observers.remove(it) }.also { observers += it }
    }
    override fun toString() = "PublicationDeliveryGeneration(<redacted>)"
}

/** Internal final operation gate. Constructed lookalikes are absent from the sealed epoch
 * registry and cannot complete. Registration is identity-dispatcher-only through the witness;
 * tryAuthorizeDelivery/cancel/wasAuthorized read or CAS atomics only and are caller safe.
 *
 * Registry absence is NEVER delivered proof. During the brief winning-CAS-to-local-outcome
 * interval wasAuthorized remains false; only the actual winner completes this final local result.
 * This gate grants no store, review, queue, recipe rights or receipt provenance. Actual controller
 * ownership plus real application evidence must separately bind it before retirement is possible.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PublicationDeliveryGate internal constructor(
    private val generation: PublicationDeliveryGeneration,
    private val source: PostPublicationDeliveryIntegration,
    private val seal: Any,
) {
    private enum class Completion { AUTHORIZED, CANCELLED }
    private val completion = AtomicReference<Completion?>(null)
    internal fun owned(generation: PublicationDeliveryGeneration, source: PostPublicationDeliveryIntegration, seal: Any) =
        this.generation === generation && this.source === source && this.seal === seal
    internal fun complete(generation: PublicationDeliveryGeneration, source: PostPublicationDeliveryIntegration,
        seal: Any, authorized: Boolean) {
        if (!owned(generation, source, seal) || !generation.owned(source, seal)) return
        // Called only by the exact epoch transition winner; duplicate native revoke never
        // changes a completed historical result. No caller-controlled completion setter exists.
        completion.compareAndSet(null, if (authorized) Completion.AUTHORIZED else Completion.CANCELLED)
    }
    fun tryAuthorizeDelivery(): Boolean = generation.finish(this, source, seal, authorized = true)
    fun cancel() { generation.finish(this, source, seal, authorized = false) }
    fun wasAuthorized(): Boolean = completion.load() == Completion.AUTHORIZED
    override fun toString() = "PublicationDeliveryGate(<redacted>)"
}

/** Internal identity-dispatcher subscription; unlike isCurrent, close is not a caller-thread API.
 * This is lifecycle redaction only, never a mutation or delivered-ACK callback. */
internal class PublicationDeliveryRevocationSubscription internal constructor(
    private var callback: (() -> Unit)?,
    private val remove: (PublicationDeliveryRevocationSubscription) -> Unit,
) {
    fun close() { callback = null; remove(this) }
    fun notifyRevoked() { val actual = callback; callback = null; actual?.invoke() }
    override fun toString() = "PublicationDeliveryRevocationSubscription(<redacted>)"
}

/** Safe to CHECK from any caller dispatcher. It reads only final fields and atomic revocation,
 * never SessionBoundary's dispatcher-confined state or a legacy isCurrent callback. A current
 * witness still grants no mutation, review consent, receipt provenance or final delivery ACK. */
class PublicationDeliveryWitness internal constructor(
    private val source: PostPublicationDeliveryIntegration,
    private val seal: Any,
    internal val binding: PublicationSessionBinding,
    internal val principal: PublicationPrincipalSnapshot,
    private val generation: PublicationDeliveryGeneration,
) {
    fun isCurrent(): Boolean = source.isCurrentWitness(this, generation)
    internal fun matches(source: PostPublicationDeliveryIntegration, seal: Any,
        binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
        this.source === source && this.seal === seal && this.binding === binding && this.principal === principal &&
            generation.matches(source, seal, principal)
    internal fun owned(source: PostPublicationDeliveryIntegration, seal: Any) = this.source === source && this.seal === seal
    internal fun observeRevocation(callback: () -> Unit) = source.observeRevocation(this, generation, callback)
    /** Only the actual purpose-fixed admission can register its one-use private capability.
     * A captured current witness alone cannot register after its composer operation expires. */
    internal fun registerAdmitted(admission: PrincipalDeliveryAdmission,
        permit: PublicationDeliveryRegistration): PublicationDeliveryGate =
        source.registerDelivery(admission, permit, this, generation)
    override fun toString() = "PublicationDeliveryWitness(<redacted>)"
}

/** Separately REQUIRED native/retained-owner integration for cross-dispatcher delivery. No
 * implementation is installed here and there is no accepting default. Use one instance for the
 * actual retained mapped-principal owner; publication and reviewed Save may share its epoch.
 *
 * capture/generationFor/witness/revokeCurrent run only on the serialized identity dispatcher.
 * capture is synchronous, does no I/O/refresh/activation, and consumes an already-current actual
 * mapped principal. It must not parse actorId/credentials or infer a verified canonical user.
 *
 * CRITICAL NATIVE CONTRACT: call revokeCurrent BEFORE changing the actual owner, refresh
 * generation, principal mapping, session/device lifecycle or permission state relevant to this
 * delivery. The atomic revoke is the cross-thread linearization point. SessionBoundary's
 * onInvalidated callback runs AFTER its confined fields change, so it is only a defensive
 * backstop, not a substitute for that pre-change native-owner discipline. A native integration
 * that cannot provide this ordering remains NOT_CONFIGURED for the new controller factory.
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class PostPublicationDeliveryIntegration(val principals: PostPublicationPrincipalIntegration,
    val maxPendingDeliveries: Int) {
    init { require(maxPendingDeliveries in 1..256) { "Invalid pending delivery capacity" } }
    private val seal = Any()
    private val current = AtomicReference<PublicationDeliveryGeneration?>(null)

    abstract fun capture(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<PublicationDeliveryWitness>

    /** Native owner keeps the returned epoch for its actual mapping; equal value tokens never
     * replace exact retained owner/generation identity. New mapping requires prior revocation. */
    protected fun generationFor(principal: PublicationPrincipalSnapshot): PublicationDeliveryGeneration {
        if (principal.issuer !== principals || !principal.binding.boundary.isCurrent(principal.binding.lease))
            mealFail(FailureReason.STALE_SESSION)
        val old = current.load()
        if (old != null && old.active()) {
            if (!old.matches(this, seal, principal)) mealFail(FailureReason.CONFLICT)
            return old
        }
        val next = PublicationDeliveryGeneration(this, seal, principal.issuer, principal.binding, principal.retainedOwner,
            principal.refreshGeneration, principal.binding.lease, principal.binding.boundary, principal.binding.origin,
            principal.binding.environment, principal.canonicalUserId, maxPendingDeliveries)
        if (!current.compareAndSet(old, next)) mealFail(FailureReason.CONFLICT)
        old?.subscription?.close(); old?.subscription = null
        val installed = principal.binding.boundary.onInvalidated(principal.binding.lease) { revokeExact(next) }
        if (!principal.binding.boundary.isCurrent(principal.binding.lease) || !next.active()) {
            installed.close(); revokeExact(next); mealFail(FailureReason.STALE_SESSION)
        }
        next.subscription = installed
        return next
    }

    protected fun witness(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot,
        generation: PublicationDeliveryGeneration): PublicationDeliveryWitness {
        if (principal.binding !== binding || principal.issuer !== principals || !binding.boundary.isCurrent(binding.lease) ||
            !generation.matches(this, seal, principal) || current.load() !== generation || !generation.active())
            mealFail(FailureReason.STALE_SESSION)
        return PublicationDeliveryWitness(this, seal, binding, principal, generation)
    }

    /** Invoke BEFORE actual native mutation, not lazily during the next capture. Idempotent. */
    protected fun revokeCurrent() { current.load()?.let(::revokeExact) }

    private fun revokeExact(generation: PublicationDeliveryGeneration) {
        generation.revoke(this, seal)
        // Native generation changes and boundary subscription disposal share one dispatcher.
        generation.subscription?.close(); generation.subscription = null
    }

    internal fun requireIssued(witness: PublicationDeliveryWitness, principal: PublicationPrincipalSnapshot) {
        if (!witness.matches(this, seal, principal.binding, principal) || !witness.isCurrent()) mealFail(FailureReason.STALE_SESSION)
    }

    internal fun observeRevocation(witness: PublicationDeliveryWitness, generation: PublicationDeliveryGeneration,
        callback: () -> Unit): PublicationDeliveryRevocationSubscription {
        if (!isCurrentWitness(witness, generation)) mealFail(FailureReason.STALE_SESSION)
        return generation.observe(this, seal, callback)
    }

    internal fun registerDelivery(admission: PrincipalDeliveryAdmission, permit: PublicationDeliveryRegistration,
        witness: PublicationDeliveryWitness,
        generation: PublicationDeliveryGeneration): PublicationDeliveryGate {
        admission.consumeRegistration(this, permit, witness)
        if (!isCurrentWitness(witness, generation)) mealFail(FailureReason.STALE_SESSION)
        return generation.register(this, seal)
    }

    /** Internal implementation of the arbitrary-caller check: ATOMICS + final identities only.
     * In particular this must never acquire a lock, call isCurrent(binding,principal), perform I/O
     * or read SessionBoundary.active/epoch from a caller dispatcher. */
    internal fun isCurrentWitness(witness: PublicationDeliveryWitness, generation: PublicationDeliveryGeneration): Boolean =
        witness.owned(this, seal) && generation.owned(this, seal) && generation.active() && current.load() === generation

    final override fun toString() = "PostPublicationDeliveryIntegration(<redacted>)"
}

/** Identity only; construction never inserts it in the actual admission's private registry. */
internal class PublicationDeliveryRegistration internal constructor() {
    override fun toString() = "PublicationDeliveryRegistration(<redacted>)"
}

/** No caller-supplied callback can stand in for actual purpose-fixed mapped admission. Both
 * constructors retain a concrete facade that checks its real composer permit/borrower/session.
 * Capture does not create a public controller or cure later caller cancellation by itself. */
internal class PrincipalDeliveryAdmission {
    private sealed class Owner {
        class Publish(val value: PublicationPrincipalAdmission) : Owner()
        class ReviewedSave(val value: ReviewedDraftPrincipalAdmission) : Owner()
    }
    private val owner: Owner
    private val delivery: PostPublicationDeliveryIntegration
    private class Registration(val token: PublicationDeliveryRegistration,
        val principal: PublicationPrincipalSnapshot, val witness: PublicationDeliveryWitness)
    private var registration: Registration? = null // one no-await identity-dispatcher turn only
    constructor(principal: PublicationPrincipalAdmission, delivery: PostPublicationDeliveryIntegration) {
        owner = Owner.Publish(principal); this.delivery = delivery
    }
    constructor(principal: ReviewedDraftPrincipalAdmission, delivery: PostPublicationDeliveryIntegration) {
        owner = Owner.ReviewedSave(principal); this.delivery = delivery
    }
    suspend fun capture(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot): PublicationDeliveryWitness {
        requireCurrent(permit, principal)
        if (delivery.principals !== principal.issuer) mealFail(FailureReason.NOT_CONFIGURED)
        val actual = try { delivery.capture(principal.binding, principal) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: MealFailure) { PortResult.Failure(failure.reason) }
        catch (_: Exception) { PortResult.Failure(FailureReason.UNAVAILABLE) }
        requireCurrent(permit, principal)
        val witness = mealValue(actual)
        delivery.requireIssued(witness, principal)
        currentCoroutineContext().ensureActive()
        return witness
    }
    /** Enforce live exact purpose/session/mapped-principal admission, not an epoch-only promise.
     * Register only after all suspension; there is no await between mint, consumption and CAS.
     * Later operation exit does not fabricate receipt or delivery: caller ticket still owns it.
     */
    suspend fun registerDelivery(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot,
        witness: PublicationDeliveryWitness): PublicationDeliveryGate {
        requireCurrent(permit, principal)
        if (delivery.principals !== principal.issuer) mealFail(FailureReason.NOT_CONFIGURED)
        delivery.requireIssued(witness, principal)
        currentCoroutineContext().ensureActive()
        if (registration != null) mealFail(FailureReason.CONFLICT)
        val capability = Registration(PublicationDeliveryRegistration(), principal, witness)
        registration = capability
        try { return witness.registerAdmitted(this, capability.token) }
        finally { registration = null }
    }
    /** Constructed/expired/stolen-purpose lookalikes are absent from this private registry.
     * Consumed before the final synchronous callback; reentry cannot reuse this capability. */
    internal fun consumeRegistration(source: PostPublicationDeliveryIntegration, token: PublicationDeliveryRegistration,
        witness: PublicationDeliveryWitness) {
        val actual = registration ?: mealFail(FailureReason.STALE_SESSION)
        if (source !== delivery || actual.token !== token || actual.witness !== witness ||
            actual.principal !== witness.principal) mealFail(FailureReason.STALE_SESSION)
        registration = null
        when (val current = owner) {
            is Owner.Publish -> current.value.requireCurrentNow(actual.principal)
            is Owner.ReviewedSave -> current.value.requireCurrentNow(actual.principal)
        }
        delivery.requireIssued(witness, actual.principal)
    }
    private suspend fun requireCurrent(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot) {
        currentCoroutineContext().ensureActive()
        when (val actual = owner) {
            is Owner.Publish -> actual.value.requireCurrent(permit, principal)
            is Owner.ReviewedSave -> actual.value.requireCurrent(permit, principal)
        }
        currentCoroutineContext().ensureActive()
    }
    override fun toString() = "PrincipalDeliveryAdmission(<redacted>)"
}
