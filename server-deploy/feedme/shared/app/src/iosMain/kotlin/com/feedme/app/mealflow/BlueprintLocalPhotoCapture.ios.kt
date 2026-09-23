package com.feedme.app.mealflow

import androidx.compose.runtime.Composable
import com.feedme.app.blueprint.BlueprintAuthoringIdentity
import com.feedme.app.blueprint.BlueprintAuthoringPreview
import com.feedme.mealflow.social.LocalPostDraft
import com.feedme.mealflow.social.PostDraftController

@Composable
internal actual fun rememberBlueprintPhotoCapture(controller: PostDraftController) =
    BlueprintPhotoCaptureActions(false, false, "Photo selection is not connected on this platform. Your draft stays on this device.") { }

@Composable
internal actual fun rememberBlueprintRetainedPhoto(controller: PostDraftController, selected: LocalPostDraft,
    identity: BlueprintAuthoringIdentity): BlueprintAuthoringPreview.Actual? = null
