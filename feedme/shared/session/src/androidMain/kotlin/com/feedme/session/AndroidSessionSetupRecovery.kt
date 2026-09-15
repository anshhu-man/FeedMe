@file:OptIn(com.feedme.storage.SessionControlRecoveryCompositionApi::class)

package com.feedme.session

import android.content.Context
import com.feedme.core.ports.StorageScope
import com.feedme.storage.*

/** One retained, existing-only application startup owner. No Context storage access here. */
object AndroidSessionSetupRecovery {
    /** Reserve before calling this factory; retain the returned owner before awaiting open. */
    fun createOwner(context: Context, reservation: SessionCompositionReservation): SessionSetupRecoveryOwner =
        RetainedSessionSetupRecoveryOwner(reservation, object : SessionSetupRecoveryFactories {
            override fun control() = AndroidSessionControlStore.createRecoveryStore(context)
            override fun credentials(scope: StorageScope, plan: CredentialCreatePlan) =
                AndroidCredentialStore.createRecoveryOwner(context, scope, plan)
            override fun data(scope: StorageScope, plan: StateActivationPlan) =
                AndroidStateDatabase.createActivationRecoveryOwner(context, scope, plan)
            override fun work(scope: StorageScope, plan: SessionWorkOriginPlan) =
                AndroidSessionWorkRecovery.createOwner(context, scope, plan, reservation.root.boundary, reservation.root.dispatcher)
        })
}
