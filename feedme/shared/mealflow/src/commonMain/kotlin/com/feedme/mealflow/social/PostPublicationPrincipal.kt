package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Actual retained composition identity, internally constructed; never a screen-supplied scope.
 * The private store/access pins are not an additional public raw-store escape. This object is
 * local binding data, not credentials, verified account mapping or backend authorization.
 */
class PublicationSessionBinding internal constructor(
    private val access: AuthenticatedMealPlanningAccess,
    val boundary: SessionBoundary,
) {
    val lease: SessionLease = access.lease
    val origin: String = access.origin
    val environment: String = access.lease.scope.environment
    private val store: PrivateStateStore = access.store

    internal fun matches(actual: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary): Boolean =
        access === actual && lease === actual.lease && store === actual.store && boundary === actualBoundary &&
            origin == actual.origin && environment == actual.lease.scope.environment

    /** Pure exact retained-session equality only. No lifecycle read, currentness, admission,
     * issuer/generation or operation proof; safe to compare immutable private pins. */
    internal fun sameRetainedSession(other: PublicationSessionBinding): Boolean =
        access === other.access && store === other.store && lease === other.lease &&
            boundary === other.boundary && origin == other.origin && environment == other.environment

    override fun toString() = "PublicationSessionBinding(<redacted>)"
}

/** Canonical account comparison data plus private live issuer/owner/generation pins.
 * Cannot be constructed by a screen, deserialized, or used as queue/consent/server authority.
 */
class PublicationPrincipalSnapshot internal constructor(
    internal val issuer: PostPublicationPrincipalIntegration,
    internal val binding: PublicationSessionBinding,
    val canonicalUserId: String,
    internal val retainedOwner: Any,
    internal val refreshGeneration: Any,
) {
    override fun toString() = "PublicationPrincipalSnapshot(<redacted>)"
}

/** Required trusted native/account integration; NO default implementation or factory.
 * The implementation must consume its actual verified FeedMe owner/bootstrap mapping and
 * exact retained owner/refresh generation. StorageScope.actorId, bearer syntax, email, a
 * Profile ID, arbitrary strings and a bare HTTP success are not that mapping.
 *
 * All methods and generation updates run on the same serialized identity dispatcher as the
 * supplied composition. resolve/requireCurrent may suspend, but never mutate session lifetime
 * or re-enter public controllers/composition. isCurrent MUST be a synchronous, side-effect-free
 * check of the actual retained owner/generation; it does no I/O, refresh or session activation.
 * Missing mapping is Failure(NOT_CONFIGURED), never a guessed identity or accepting fallback.
 * A successful prerequisite is not backend account/device/terms/publication permission.
 */
abstract class PostPublicationPrincipalIntegration {
    abstract suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot>
    abstract suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<Unit>
    abstract fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): Boolean

    /** Only a configured integration can mint a snapshot from its already verified mapping.
     * These arguments are integration-owned facts, not publication screen controls. */
    protected fun mappedPrincipal(binding: PublicationSessionBinding, canonicalUserId: String,
        retainedOwner: Any, refreshGeneration: Any): PublicationPrincipalSnapshot {
        if (!principalUuid.matches(canonicalUserId)) principalFail(FailureReason.INVALID_DATA)
        if (binding.lease.scope.actorKind != ActorKind.ACCOUNT) principalFail(FailureReason.UNAUTHENTICATED)
        if (!binding.boundary.isCurrent(binding.lease)) principalFail(FailureReason.STALE_SESSION)
        return PublicationPrincipalSnapshot(this, binding, canonicalUserId, retainedOwner, refreshGeneration)
    }

    /** Compare the exact access already retained by the integration, not a caller scope string. */
    protected fun matchesSession(binding: PublicationSessionBinding, actual: AuthenticatedMealPlanningAccess,
        actualBoundary: SessionBoundary): Boolean = binding.matches(actual, actualBoundary) &&
        actualBoundary.isCurrent(binding.lease) && binding.lease.scope.actorKind == ActorKind.ACCOUNT

    /** Identity comparison against the integration's own CURRENT owner/generation; no value-based
     * generation equality or constructor-argument echo may replace the actual retained state. */
    protected fun matchesCurrentPrincipal(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot,
        retainedOwner: Any, refreshGeneration: Any): Boolean =
        principal.issuer === this && principal.binding === binding && principal.retainedOwner === retainedOwner &&
            principal.refreshGeneration === refreshGeneration && binding.boundary.isCurrent(binding.lease)

    final override fun toString() = "PostPublicationPrincipalIntegration(<redacted>)"
}

/** Actual composer-operation admission over the explicitly supplied trusted mapping integration.
 * Construction performs no I/O or mapping lookup. This does not install a provider, change the
 * native verifier, create a controller/factory/review ticket, or grant dispatch/receipt authority.
 */
internal class PublicationPrincipalAdmission(
    composition: MealKitchenComposition,
    borrower: MealKitchenComposition.Borrower,
    integration: PostPublicationPrincipalIntegration,
) {
    private val admission = ComposerPrincipalAdmission(composition, borrower, integration, MealKitchenFeature.POST_PUBLICATIONS)
    suspend fun resolve(permit: ComposerOperationPermit): PublicationPrincipalSnapshot = admission.resolve(permit)
    suspend fun requireCurrent(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot) = admission.requireCurrent(permit, principal)
    /** Same serialized identity dispatcher only; not an arbitrary caller-delivery proof. */
    fun requireCurrentNow(principal: PublicationPrincipalSnapshot) = admission.requireCurrentNow(principal)
    override fun toString() = "PublicationPrincipalAdmission(<redacted>)"
}

/** The reviewed private-draft PATCH prerequisite, deliberately fixed to POST_DRAFTS.
 * A publication borrower, permit or principal binding cannot be substituted. Sharing private
 * mechanics does not widen either facade's purpose or install a mapping/consent/ACK provider.
 */
internal class ReviewedDraftPrincipalAdmission(
    composition: MealKitchenComposition,
    borrower: MealKitchenComposition.Borrower,
    integration: PostPublicationPrincipalIntegration,
) {
    private val admission = ComposerPrincipalAdmission(composition, borrower, integration, MealKitchenFeature.POST_DRAFTS)
    suspend fun resolve(permit: ComposerOperationPermit): PublicationPrincipalSnapshot = admission.resolve(permit)
    suspend fun requireCurrent(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot) = admission.requireCurrent(permit, principal)
    /** Same serialized identity dispatcher only; not an arbitrary caller-delivery proof. */
    fun requireCurrentNow(principal: PublicationPrincipalSnapshot) = admission.requireCurrentNow(principal)
    override fun toString() = "ReviewedDraftPrincipalAdmission(<redacted>)"
}

/** Private implementation, never a caller-selectable admission purpose. */
private class ComposerPrincipalAdmission(
    private val composition: MealKitchenComposition,
    private val borrower: MealKitchenComposition.Borrower,
    private val integration: PostPublicationPrincipalIntegration,
    private val requiredFeature: MealKitchenFeature,
) {
    private val binding = PublicationSessionBinding(composition.access, composition.boundary)

    init {
        if (borrower.owner !== composition) principalFail(FailureReason.STALE_SESSION)
        if (borrower.feature != requiredFeature) principalFail(FailureReason.NOT_CONFIGURED)
    }

    suspend fun resolve(permit: ComposerOperationPermit): PublicationPrincipalSnapshot {
        fence(permit)
        val result = safely { integration.resolve(binding) }
        fence(permit)
        val principal = principalValue(result)
        structural(principal)
        currentNow(principal)
        // A returned mapping alone is not currentness: require the real retained-owner check,
        // then recheck its synchronous generation after every awaited tail below.
        requireCurrent(permit, principal)
        currentCoroutineContext().ensureActive()
        currentNow(principal)
        currentCoroutineContext().ensureActive()
        return principal
    }

    suspend fun requireCurrent(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot) {
        structural(principal)
        fence(permit, principal)
        val result = safely { integration.requireCurrent(binding, principal) }
        fence(permit, principal)
        principalValue(result)
        currentCoroutineContext().ensureActive()
        currentNow(principal)
        currentCoroutineContext().ensureActive()
    }

    /** No-I/O currentness only, for future final tails on the SAME serialized identity dispatcher.
     * This may be called after operate has ended, but never revives its permit or grants an
     * effect/ACK. The controller still owns cancellation, review/delivery generation and every
     * subsequent domain/dispatcher handoff fence. Not safe from arbitrary caller threads. */
    fun requireCurrentNow(principal: PublicationPrincipalSnapshot) { currentNow(principal) }

    private suspend fun fence(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot? = null) {
        currentCoroutineContext().ensureActive()
        localNow()
        principal?.let(::currentNow)
        currentCoroutineContext().ensureActive()
        // This actual checker can itself suspend in the domain hook. It rechecks its exact
        // inherited Operation on resumption; a detached old child cannot use a later operation.
        composition.requireComposerPermit(permit, borrower)
        currentCoroutineContext().ensureActive()
        localNow()
        principal?.let(::currentNow)
        currentCoroutineContext().ensureActive()
    }

    private fun localNow() {
        if (!binding.matches(composition.access, composition.boundary) ||
            !binding.boundary.isCurrent(binding.lease) || borrower.hooks == null) principalFail(FailureReason.STALE_SESSION)
        if (binding.lease.scope.actorKind != ActorKind.ACCOUNT) principalFail(FailureReason.UNAUTHENTICATED)
    }

    private fun structural(principal: PublicationPrincipalSnapshot) {
        if (principal.issuer !== integration || principal.binding !== binding) principalFail(FailureReason.STALE_SESSION)
        if (!principalUuid.matches(principal.canonicalUserId)) principalFail(FailureReason.INVALID_DATA)
    }

    private fun currentNow(principal: PublicationPrincipalSnapshot) {
        localNow(); structural(principal)
        val current = try { integration.isCurrent(binding, principal) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { principalFail(FailureReason.UNAVAILABLE) }
        // isCurrent is a no-I/O observation. Recheck local lifecycle even if a broken integration
        // synchronously invalidates its binding; never deliver a stale private value.
        localNow()
        if (!current) principalFail(FailureReason.STALE_SESSION)
    }

    private suspend fun <T> safely(action: suspend () -> PortResult<T>): PortResult<T> = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: MealFailure) { PortResult.Failure(failure.reason) }
    catch (_: Exception) { PortResult.Failure(FailureReason.UNAVAILABLE) }

    override fun toString() = "ComposerPrincipalAdmission(<redacted>)"
}

private val principalUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private fun principalFail(reason: FailureReason): Nothing = mealFail(reason)
private fun <T> principalValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> principalFail(result.reason)
}
