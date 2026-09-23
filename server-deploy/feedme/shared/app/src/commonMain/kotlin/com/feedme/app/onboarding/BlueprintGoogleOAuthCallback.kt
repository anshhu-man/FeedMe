package com.feedme.app.onboarding

import androidx.compose.runtime.Composable
import com.feedme.app.blueprint.*

/** Original AUTH_CALLBACK during the actual, explicitly started coordinator operation.
 * In-flight is not callback arrival, provider verification, bootstrap or private readiness.
 * There is no second exchange, callback parser, retry or success transition in this adapter. */
@Composable
internal fun BlueprintGoogleOAuthCallback(canCancel: Boolean, current: () -> Boolean, onCancel: () -> Unit) {
    val page = BlueprintAccountSetupState(
        page = BlueprintAccountSetupPage.AUTH_CALLBACK,
        enabledActions = if (canCancel) setOf(BlueprintAccountSetupAction.BACK, BlueprintAccountSetupAction.CANCEL_SIGN_IN) else emptySet(),
        busy = true,
        callbackFacts = BlueprintCallbackFacts(providerSelected = true, currentTransaction = false),
        callbackStatusCopy = "Your Google sign-in is in progress. FeedMe still needs to confirm the response before opening your account.",
        notice = "Follow Google’s sign-in steps if the browser is open. No additional request is sent by this screen. Not now closes this device’s sign-in flow; it does not undo a request already sent.",
    )
    BlueprintAccountSetupScreen(page, onFieldsChange = {}, onAction = { action ->
        if (canCancel && current() && action in setOf(BlueprintAccountSetupAction.BACK, BlueprintAccountSetupAction.CANCEL_SIGN_IN)) onCancel()
    })
}
