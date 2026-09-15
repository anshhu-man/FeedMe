@file:OptIn(com.feedme.storage.WorkRecoveryCompositionApi::class)

package com.feedme.session

import android.content.Context
import com.feedme.core.ports.SessionBoundary
import com.feedme.core.ports.StorageScope
import com.feedme.storage.AndroidSessionWorkStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Existing-only, purpose-fixed recovery; not initialization, user consent or startup completion. */
object AndroidSessionWorkRecovery {
    /** Construct and retain before open. Neither this factory nor the native constructor does I/O. */
    fun createOwner(
        context: Context,
        scope: StorageScope,
        plan: SessionWorkOriginPlan,
        boundary: SessionBoundary,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): SessionWorkOriginRecoveryOwner = RetainedSessionWorkOriginRecoveryOwner(
        scope, plan, AndroidSessionWorkStore.createRecoveryStore(context), boundary, dispatcher,
    )
}
