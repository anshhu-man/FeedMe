package com.feedme.app.mealflow

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.feedme.mealflow.social.SocialPhoto

/** Native iOS decoder integration remains unavailable; never substitute a prototype image. */
@Composable
internal actual fun rememberSocialPhotoPainter(photo: SocialPhoto?, displayCurrent: () -> Boolean): Painter? = null
