package com.feedme.app.mealflow

import androidx.compose.runtime.Composable
import com.feedme.mealflow.social.LocalPostDraft
import com.feedme.mealflow.social.PostDraftController

@Composable
internal actual fun DraftPhotoControls(controller: PostDraftController, selected: LocalPostDraft, enabled: Boolean) =
    UnavailableDraftPhotoControls(controller, selected, enabled)
