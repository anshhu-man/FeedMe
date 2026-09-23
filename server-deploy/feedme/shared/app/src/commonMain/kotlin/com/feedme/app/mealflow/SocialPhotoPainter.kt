package com.feedme.app.mealflow

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.feedme.mealflow.social.SocialPhoto

/** Decodes only already-authorized, RAM-retained bytes. Never opens a URL or persists media. */
@Composable
internal expect fun rememberSocialPhotoPainter(photo: SocialPhoto?, displayCurrent: () -> Boolean): Painter?
