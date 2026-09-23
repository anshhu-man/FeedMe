package com.feedme.android

import com.feedme.core.ports.*
import com.feedme.mealflow.AuthenticatedMealPlanningAccess
import com.feedme.mealflow.social.*
import com.feedme.session.EmailAccountPrivateConnection

/** Actual native account lifetime plus explicit API prerequisites. Neither this client
 * integration nor a local composer save substitutes for server publication authorization. */
internal class NativeReviewedKitchenIntegration(connection: EmailAccountPrivateConnection,
    access: AuthenticatedMealPlanningAccess, clock: EpochClock, connectivity: ConnectivityPort,
    policy: ReviewedAttachmentPolicy) : ReviewedKitchenIntegration {
    private val identity = NativeReviewedPostIdentity(connection, access)
    override val principals: PostPublicationPrincipalIntegration get() = identity.principals
    override val delivery: PostPublicationDeliveryIntegration get() = identity.delivery
    private val checks = AccountReviewedPostPrerequisites(access, connection.boundary, clock, connectivity,
        principals, PublicationDisclosure("feedme-private-recipe-saves-v1",
            "Your people can save the recipe text—not your photo, replies or post.\n" +
                "Saving does not grant permission to repost your content.\n" +
                "Turning this off stops future saves. Earlier authorized copies may remain, except for safety or legal removal."), policy)

    override suspend fun disclosure(context: ReviewedPostPrerequisiteContext) = checks.disclosure(context)
    override suspend fun requireNewPrivateSave(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveCheck) =
        checks.requireNewPrivateSave(context, check)
    override suspend fun requirePrivateSaveReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveReplayCheck) =
        checks.requirePrivateSaveReplay(context, check)
    override suspend fun requireNewPublication(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationCheck) =
        checks.requireNewPublication(context, check)
    override suspend fun requirePublicationReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationReplayCheck) =
        checks.requirePublicationReplay(context, check)
    fun close() = identity.close()
}
