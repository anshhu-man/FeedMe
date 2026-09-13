package com.feedme.core

/** IDs deliberately match the approved screen registry. This is only the first vertical slice. */
enum class Route {
    AUTH_WELCOME, HOME, TODAY, POST, ADAPT, RECIPE, COOK, MEAL_DONE,
    COOKBOOK, CAPTURE, PROFILE_PLATE, UNAVAILABLE,
}

enum class OperatingMode { PRODUCTION, DEMO }
enum class ReviewStatus { DEMO_UNREVIEWED, REVIEWED, UNKNOWN, REJECTED }

data class RecipeVariant(
    val id: String,
    val title: String,
    val minutes: Int,
    val effort: String,
    val ingredients: List<String>,
    val steps: List<String>,
    val reviewStatus: ReviewStatus,
)

data class Recipe(
    val id: String,
    val title: String,
    val subtitle: String,
    val minutes: Int,
    val ingredients: List<String>,
    val steps: List<String>,
    val reviewStatus: ReviewStatus,
    val imageKey: String,
    val effort: String = "light-prep",
    val tags: List<String> = emptyList(),
    val variants: List<RecipeVariant> = emptyList(),
)

/** A local fixture identity is never an authenticated production identity. */
data class Session(
    val userId: String?,
    val displayName: String,
    val isGuest: Boolean,
    val isSimulated: Boolean = false,
    val invitedCircleIds: Set<String> = emptySet(),
    val blockedUserIds: Set<String> = emptySet(),
)

data class Plate(
    val id: String,
    val authorId: String,
    val authorName: String,
    val recipeId: String,
    val caption: String,
    val createdAtMillis: Long,
    val keepOnPlate: Boolean,
    val circleId: String,
    val deleted: Boolean = false,
    val allowMakeMine: Boolean = true,
    val blockedViewerIds: Set<String> = emptySet(),
    val variantId: String? = null,
    val source: SourceAttribution? = null,
)

data class SourceAttribution(
    val plateId: String,
    val authorId: String,
    val authorName: String,
    val originalRecipeId: String,
    val circleId: String,
)

data class AdaptRequest(
    val variantId: String = "quick",
    val maxMinutes: Int? = null,
    val effort: String? = null,
    val availableIngredients: Set<String> = emptySet(),
    val excludedIngredients: Set<String> = emptySet(),
    val allergyIngredients: Set<String> = emptySet(),
)

data class Adaptation(
    val baseRecipeId: String,
    val variantId: String,
    val recipe: Recipe,
    val source: SourceAttribution,
)

data class CookingSession(
    val id: String,
    val recipeId: String,
    val variantId: String?,
    val stepIndex: Int = 0,
    val completedStepIndexes: Set<Int> = emptySet(),
    val isDone: Boolean = false,
)

data class SavedRecipe(
    val key: String,
    val recipe: Recipe,
    val variantId: String?,
    val source: SourceAttribution?,
)

enum class ErrorCode {
    SIGN_IN_REQUIRED, BACKEND_REQUIRED, UNAVAILABLE, UNREVIEWED_CONTENT,
    UNSUPPORTED_VARIANT, UNSUPPORTED_INPUT, ALLERGY_REQUEST_UNSUPPORTED,
    CONSTRAINT_NOT_MET, INVALID_ACTION,
}

data class AppError(val code: ErrorCode, val message: String)

data class AppState(
    val route: Route = Route.AUTH_WELCOME,
    val mode: OperatingMode = OperatingMode.PRODUCTION,
    val session: Session? = null,
    val recipes: List<Recipe> = emptyList(),
    val plates: List<Plate> = emptyList(),
    val selectedRecipeId: String? = null,
    val selectedPlateId: String? = null,
    val adaptation: Adaptation? = null,
    val cooking: CookingSession? = null,
    val savedRecipes: List<SavedRecipe> = emptyList(),
    val completedCommandIds: Set<String> = emptySet(),
    val error: AppError? = null,
    /** Detail return destinations, never snapshots of another screen's session data. */
    val postOrigin: Route = Route.TODAY,
    val recipeOrigin: Route = Route.HOME,
) {
    val currentRecipe: Recipe?
        get() = adaptation?.recipe?.takeIf { adaptation.baseRecipeId == selectedRecipeId }
            ?: recipes.firstOrNull { it.id == selectedRecipeId }
    val currentPlate: Plate? get() = plates.firstOrNull { it.id == selectedPlateId }
    val savedRecipeIds: Set<String> get() = savedRecipes.map { it.recipe.id }.toSet()
}

sealed interface AppAction {
    data object ContinueAsGuest : AppAction
    data object OpenHome : AppAction
    data object OpenToday : AppAction
    data class OpenPlate(val plateId: String) : AppAction
    data class OpenRecipe(val recipeId: String) : AppAction
    data class OpenSavedRecipe(val key: String) : AppAction
    data class OpenMakeMine(val plateId: String) : AppAction
    data class Adapt(val request: AdaptRequest) : AppAction
    data object StartCooking : AppAction
    data class CompleteStep(val stepIndex: Int) : AppAction
    data object SaveRecipe : AppAction
    data object OpenCookbook : AppAction
    data object OpenShare : AppAction
    data class Share(val keepOnPlate: Boolean, val commandId: String) : AppAction
    data object OpenMyPlate : AppAction
    data class DeletePlate(val plateId: String) : AppAction
    data class BlockUser(val userId: String) : AppAction
    data object Back : AppAction
    data object Logout : AppAction
    data object Reset : AppAction
    data object DismissError : AppAction
}
