package com.feedme.app.mealflow

/** Observations supplied by the actual private owners, never inferred from a callback.
 * Each action forwards this exact presentation's raw continuation through queued admission. */
class MealOfflineRecovery(
    val savedMealsAvailable: Boolean? = null,
    val cookingSessionAvailable: Boolean? = null,
    val busy: Boolean = false,
    val status: String? = null,
    val openSaved: ((() -> Boolean) -> Unit)? = null,
    val resumeCooking: ((() -> Boolean) -> Unit)? = null,
    val retryConnection: ((() -> Boolean) -> Unit)? = null,
) {
    override fun toString() = "MealOfflineRecovery(<redacted>)"
}
