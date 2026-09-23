package com.feedme.app.mealflow

import androidx.compose.runtime.Composable

/** Borrowed, fixed-page preference editor. This host carries no preference values or
 * permission: the retained kitchen owner still admits each actual read and mutation.
 * [open] is called only by an explicit navigation action; [content] performs no entry I/O.
 * [current] pins the parent account/form/navigation, excluding the kitchen observations
 * which this editor intentionally changes. Local departure must not require this grant. */
class MealPreferenceEditorHost(
    val current: () -> Boolean,
    val open: (isCurrent: () -> Boolean) -> Unit,
    val content: @Composable (isCurrent: () -> Boolean, onClose: () -> Unit) -> Unit,
)
