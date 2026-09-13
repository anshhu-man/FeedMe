package com.feedme.core

/** Pure, clock-injected, in-memory preview reducer. This is not a backend or security boundary. */
fun reduce(state: AppState, action: AppAction, nowMillis: Long): AppState {
    require(nowMillis >= 0) { "Clock must be non-negative." }
    return reduceSnapshot(state.snapshot(), action, nowMillis).snapshot()
}

private fun reduceSnapshot(state: AppState, action: AppAction, now: Long): AppState {
    if (action == AppAction.Logout || action == AppAction.Reset) return AppState(mode = state.mode)
    if (action == AppAction.DismissError) return state.copy(error = null)
    if (action == AppAction.ContinueAsGuest) {
        if (state.session != null) return state
        return if (state.mode == OperatingMode.DEMO) DemoFixtures.activeState(now)
        else AppState(route = Route.HOME, session = Session(null, "Guest", isGuest = true),
            recipes = state.recipes)
    }
    if (state.session == null) return state.fail(ErrorCode.SIGN_IN_REQUIRED, "Start a session first.")

    return when (action) {
        AppAction.OpenHome -> state.clearSelection(Route.HOME)
        AppAction.OpenToday -> state.clearSelection(Route.TODAY)
        AppAction.OpenCookbook -> state.clearSelection(Route.COOKBOOK)
        AppAction.OpenMyPlate -> state.clearSelection(Route.PROFILE_PLATE)
        is AppAction.OpenPlate -> {
            val plate = state.plates.firstOrNull { it.id == action.plateId }
            if (plate == null || !canViewPlate(plate, state.session, now, state.mode)) state.unavailable()
            else state.clearSelection(Route.POST).copy(selectedPlateId = plate.id,
                postOrigin = if (state.route == Route.PROFILE_PLATE) Route.PROFILE_PLATE else Route.TODAY)
        }
        is AppAction.OpenRecipe -> {
            val recipe = state.recipes.firstOrNull { it.id == action.recipeId }
            when {
                recipe == null -> state.unavailable()
                !usable(recipe.reviewStatus, state.mode) -> state.unreviewed()
                else -> state.clearSelection(Route.RECIPE).copy(selectedRecipeId = recipe.id)
            }
        }
        is AppAction.OpenSavedRecipe -> {
            val saved = state.savedRecipes.firstOrNull { it.key == action.key }
            when {
                saved == null -> state.unavailable()
                !usable(saved.recipe.reviewStatus, state.mode) -> state.unreviewed()
                else -> state.clearSelection(Route.RECIPE).copy(
                    selectedRecipeId = saved.recipe.id,
                    recipeOrigin = Route.COOKBOOK,
                    adaptation = saved.source?.let {
                        Adaptation(saved.recipe.id, saved.variantId ?: "original", saved.recipe, it)
                    },
                )
            }
        }
        is AppAction.OpenMakeMine -> {
            val plate = state.plates.firstOrNull { it.id == action.plateId }
            val recipe = state.recipes.firstOrNull { it.id == plate?.recipeId }
            when {
                plate == null || !canViewPlate(plate, state.session, now, state.mode) || !plate.allowMakeMine -> state.unavailable()
                recipe == null -> state.unavailable()
                !usable(recipe.reviewStatus, state.mode) -> state.unreviewed()
                else -> state.clearSelection(Route.ADAPT).copy(selectedPlateId = plate.id, selectedRecipeId = recipe.id,
                    postOrigin = when (state.route) {
                        Route.PROFILE_PLATE -> Route.PROFILE_PLATE
                        Route.POST -> state.postOrigin
                        else -> Route.TODAY
                    })
            }
        }
        is AppAction.Adapt -> adapt(state, action.request, now)
        AppAction.StartCooking -> {
            val recipe = state.currentRecipe
            when {
                state.route == Route.COOK && state.cooking != null -> state
                state.route != Route.RECIPE || recipe == null -> state.invalid()
                !usable(recipe.reviewStatus, state.mode) -> state.unreviewed()
                recipe.steps.isEmpty() -> state.invalid()
                else -> state.copy(route = Route.COOK, error = null, cooking = state.cooking?.takeUnless { it.isDone } ?: CookingSession(
                    id = "local:${recipe.id}:${state.adaptation?.variantId ?: "original"}",
                    recipeId = recipe.id, variantId = state.adaptation?.variantId,
                ))
            }
        }
        is AppAction.CompleteStep -> {
            val cooking = state.cooking
            val recipe = state.currentRecipe
            when {
                cooking == null || recipe == null -> state.invalid()
                action.stepIndex in cooking.completedStepIndexes -> state
                state.route != Route.COOK || cooking.isDone || action.stepIndex != cooking.stepIndex -> state.invalid()
                action.stepIndex !in recipe.steps.indices -> state.invalid()
                else -> {
                    val done = action.stepIndex == recipe.steps.lastIndex
                    state.copy(route = if (done) Route.MEAL_DONE else Route.COOK, error = null,
                        cooking = cooking.copy(
                            completedStepIndexes = cooking.completedStepIndexes + action.stepIndex,
                            stepIndex = if (done) action.stepIndex else action.stepIndex + 1,
                            isDone = done,
                        ))
                }
            }
        }
        AppAction.SaveRecipe -> {
            val recipe = state.currentRecipe
            val variantId = state.adaptation?.variantId
            val source = state.adaptation?.source
            val key = "${recipe?.id}:${variantId ?: "original"}:${source?.plateId ?: "catalog"}"
            when {
                recipe == null -> state.invalid()
                !usable(recipe.reviewStatus, state.mode) -> state.unreviewed()
                state.savedRecipes.any { it.key == key } -> state.copy(error = null)
                else -> state.copy(savedRecipes = state.savedRecipes + SavedRecipe(key, recipe, variantId, source), error = null)
            }
        }
        AppAction.OpenShare -> when {
            state.mode != OperatingMode.DEMO -> state.backendRequired()
            state.route != Route.MEAL_DONE || state.cooking?.isDone != true -> state.invalid()
            else -> state.copy(route = Route.CAPTURE, error = null)
        }
        is AppAction.Share -> share(state, action, now)
        is AppAction.DeletePlate -> {
            val plate = state.plates.firstOrNull { it.id == action.plateId }
            when {
                state.mode != OperatingMode.DEMO -> state.backendRequired()
                plate == null || plate.authorId != state.session.userId -> state.unavailable()
                else -> state.clearSelection(Route.PROFILE_PLATE).copy(
                    plates = state.plates.map { if (it.id == plate.id) it.copy(deleted = true) else it },
                )
            }
        }
        is AppAction.BlockUser -> when {
            state.mode != OperatingMode.DEMO -> state.backendRequired()
            action.userId.isBlank() || action.userId == state.session.userId -> state.invalid()
            else -> state.clearSelection(Route.TODAY).copy(
                session = state.session.copy(blockedUserIds = state.session.blockedUserIds + action.userId),
                plates = state.plates.filterNot { it.authorId == action.userId },
                savedRecipes = state.savedRecipes.filterNot { it.source?.authorId == action.userId },
            )
        }
        AppAction.Back -> when (state.route) {
            Route.AUTH_WELCOME -> state
            Route.POST -> state.clearSelection(if (state.postOrigin == Route.PROFILE_PLATE) Route.PROFILE_PLATE else Route.TODAY)
            Route.ADAPT -> state.copy(route = Route.POST, selectedRecipeId = null, adaptation = null, cooking = null, error = null)
            Route.RECIPE -> if (state.selectedPlateId != null) state.copy(route = Route.ADAPT, adaptation = null, cooking = null, error = null)
                else state.clearSelection(if (state.recipeOrigin == Route.COOKBOOK) Route.COOKBOOK else Route.HOME)
            Route.COOK, Route.MEAL_DONE -> state.copy(route = Route.RECIPE, error = null)
            Route.CAPTURE -> state.copy(route = Route.MEAL_DONE, error = null)
            else -> state.clearSelection(Route.HOME)
        }
        AppAction.ContinueAsGuest, AppAction.Logout, AppAction.Reset, AppAction.DismissError -> state
    }
}

private fun adapt(state: AppState, request: AdaptRequest, now: Long): AppState {
    if (state.route != Route.ADAPT) return state.invalid()
    val plate = state.currentPlate ?: return state.unavailable()
    if (!canViewPlate(plate, state.session, now, state.mode) || !plate.allowMakeMine) return state.unavailable()
    val base = state.recipes.firstOrNull { it.id == plate.recipeId } ?: return state.unavailable()
    if (!usable(base.reviewStatus, state.mode)) return state.unreviewed()
    if (request.allergyIngredients.isNotEmpty()) return state.fail(ErrorCode.ALLERGY_REQUEST_UNSUPPORTED,
        "Allergy-aware adaptation is not supported. This preview cannot determine whether a meal is safe for an allergy.")
    if ((request.maxMinutes != null && request.maxMinutes <= 0) ||
        (request.effort != null && request.effort !in setOf("assemble", "light-prep", "cooking"))) {
        return state.fail(ErrorCode.UNSUPPORTED_INPUT, "Choose a supported time and effort.")
    }
    val variant = if (request.variantId == "original") RecipeVariant("original", base.title, base.minutes,
        base.effort, base.ingredients, base.steps, base.reviewStatus)
        else base.variants.firstOrNull { it.id == request.variantId }
            ?: return state.fail(ErrorCode.UNSUPPORTED_VARIANT, "This plate has no supported version for that request.")
    if (!usable(variant.reviewStatus, state.mode)) return state.unreviewed()
    val available = request.availableIngredients.map(::normalized).toSet()
    val excluded = request.excludedIngredients.map(::normalized).toSet()
    val known = (base.ingredients + base.variants.flatMap { it.ingredients }).map(::normalized).toSet()
    if ((available + excluded).any { it !in known }) return state.fail(ErrorCode.UNSUPPORTED_INPUT,
        "An ingredient is not in this recipe's supported ingredient list. No substitution was invented.")
    val needed = variant.ingredients.map(::normalized).toSet()
    if ((request.maxMinutes != null && variant.minutes > request.maxMinutes) ||
        (request.effort != null && variant.effort != request.effort) ||
        (available.isNotEmpty() && !available.containsAll(needed)) || needed.any { it in excluded }) {
        return state.fail(ErrorCode.CONSTRAINT_NOT_MET, "No supported version meets those choices. Adjust a choice or pick another plate.")
    }
    val adapted = base.copy(title = variant.title, minutes = variant.minutes, effort = variant.effort,
        ingredients = variant.ingredients, steps = variant.steps, reviewStatus = variant.reviewStatus)
    return state.copy(route = Route.RECIPE, selectedRecipeId = base.id, cooking = null, error = null,
        adaptation = Adaptation(base.id, variant.id, adapted,
            SourceAttribution(plate.id, plate.authorId, plate.authorName, plate.recipeId, plate.circleId)))
}

private fun share(state: AppState, action: AppAction.Share, now: Long): AppState {
    if (state.mode != OperatingMode.DEMO || state.session?.isSimulated != true) return state.backendRequired()
    if (action.commandId in state.completedCommandIds) return state
    val recipe = state.currentRecipe ?: return state.invalid()
    val owner = state.session.userId ?: return state.invalid()
    if (state.route != Route.CAPTURE || state.cooking?.isDone != true || action.commandId.isBlank()) return state.invalid()
    val circle = state.session.invitedCircleIds.firstOrNull() ?: return state.unavailable()
    val plate = Plate("demo-share:${action.commandId}", owner, state.session.displayName, recipe.id,
        "Made it mine. Local demo post — not uploaded.", now, action.keepOnPlate, circle,
        variantId = state.adaptation?.variantId, source = state.adaptation?.source)
    return state.clearSelection(if (action.keepOnPlate) Route.PROFILE_PLATE else Route.TODAY).copy(plates = state.plates + plate,
        completedCommandIds = state.completedCommandIds + action.commandId)
}

private fun usable(status: ReviewStatus, mode: OperatingMode): Boolean =
    status == ReviewStatus.REVIEWED || (mode == OperatingMode.DEMO && status == ReviewStatus.DEMO_UNREVIEWED)

private fun normalized(value: String): String = value.trim().lowercase()
private fun AppState.fail(code: ErrorCode, message: String) = copy(error = AppError(code, message))
private fun AppState.invalid() = fail(ErrorCode.INVALID_ACTION, "That action is not available on this screen.")
private fun AppState.unavailable() = clearSelection(Route.UNAVAILABLE).fail(ErrorCode.UNAVAILABLE,
    "This plate or recipe is unavailable. It may have expired, been removed, or require access.")
private fun AppState.unreviewed() = fail(ErrorCode.UNREVIEWED_CONTENT,
    "This content is not approved for production. Unreviewed recipes are available only in explicit demo mode.")
private fun AppState.backendRequired() = fail(ErrorCode.BACKEND_REQUIRED,
    "A real authenticated backend is required. Nothing was published or changed remotely.")
private fun AppState.clearSelection(route: Route) = copy(route = route, selectedRecipeId = null,
    selectedPlateId = null, adaptation = null, cooking = null, error = null,
    postOrigin = Route.TODAY, recipeOrigin = Route.HOME)

/** Defensively detach externally supplied mutable collection implementations at the reducer boundary. */
private fun Recipe.snapshot() = copy(ingredients = ingredients.toList(), steps = steps.toList(), tags = tags.toList(),
    variants = variants.map { it.copy(ingredients = it.ingredients.toList(), steps = it.steps.toList()) })
private fun AppState.snapshot() = copy(
    session = session?.copy(invitedCircleIds = session.invitedCircleIds.toSet(), blockedUserIds = session.blockedUserIds.toSet()),
    recipes = recipes.map { it.snapshot() },
    plates = plates.map { it.copy(blockedViewerIds = it.blockedViewerIds.toSet()) },
    adaptation = adaptation?.copy(recipe = adaptation.recipe.snapshot()),
    cooking = cooking?.copy(completedStepIndexes = cooking.completedStepIndexes.toSet()),
    savedRecipes = savedRecipes.map { it.copy(recipe = it.recipe.snapshot()) },
    completedCommandIds = completedCommandIds.toSet(),
)
