package com.feedme.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.core.AdaptRequest
import com.feedme.core.AppAction
import com.feedme.core.DemoKitchenRuntime
import com.feedme.core.Plate
import com.feedme.core.Recipe
import com.feedme.core.Route
import com.feedme.core.visibleMyPlatePosts
import com.feedme.core.visibleTodayPlates

/** Explicit development composition. No service implementation is substituted by these fixtures. */
@Composable
fun FeedMeApp(
    runtime: DemoKitchenRuntime,
    platformBackHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    fun now() = runtime.nowMillis()
    var state by remember(runtime) { mutableStateOf(runtime.initialState()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var resetRequested by remember { mutableStateOf(false) }
    var viewedStep by remember(state.cooking?.id) { mutableIntStateOf(state.cooking?.stepIndex ?: 0) }
    var keepOnPlate by remember { mutableStateOf(false) }
    var shareCommandId by remember { mutableStateOf("") }
    val preferredVariant = state.currentRecipe?.variants?.firstOrNull()
    var selectedMinutes by remember(state.selectedPlateId) { mutableIntStateOf(preferredVariant?.minutes ?: 15) }
    var selectedEffort by remember(state.selectedPlateId) { mutableStateOf(preferredVariant?.effort ?: "light-prep") }
    var ingredients by remember(state.selectedPlateId) { mutableStateOf(emptySet<String>()) }
    fun dispatch(action: AppAction) { state = runtime.dispatch(state, action) }
    fun back() {
        if (settingsOpen) settingsOpen = false else dispatch(AppAction.Back)
    }

    platformBackHandler(settingsOpen || state.route !in setOf(Route.AUTH_WELCOME, Route.HOME), ::back)

    FeedMeTheme {
        Scaffold(
            modifier = Modifier.safeDrawingPadding(),
            topBar = {
                Column {
                    FeedMeDemoLabel()
                    if (state.session != null) {
                        TextButton(onClick = { settingsOpen = true }) { Text("Demo settings") }
                    }
                }
            },
            bottomBar = {
                if (state.session != null && state.route !in setOf(Route.COOK, Route.CAPTURE)) {
                    FeedMeBottomBar(
                        selected = when (state.route) {
                            Route.TODAY, Route.POST -> "today"
                            Route.COOKBOOK -> "cookbook"
                            Route.PROFILE_PLATE -> "settings"
                            else -> "home"
                        },
                        onHome = { settingsOpen = false; dispatch(AppAction.OpenHome) },
                        onToday = { settingsOpen = false; dispatch(AppAction.OpenToday) },
                        onCookbook = { settingsOpen = false; dispatch(AppAction.OpenCookbook) },
                        onSettings = { settingsOpen = false; dispatch(AppAction.OpenMyPlate) },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                key(state.route, settingsOpen) {
                    if (settingsOpen) {
                        SettingsScreen(onReset = { resetRequested = true }, onBack = { settingsOpen = false })
                    } else {
                        val current = state.currentRecipe
                        when (state.route) {
                            Route.AUTH_WELCOME -> WelcomeScreen(
                                onExplore = { dispatch(AppAction.ContinueAsGuest) },
                                onUnavailableAuth = { info = "Sign-up and login are not connected yet. Explore the local demo without entering credentials; no account will be created." },
                            )
                            Route.HOME -> HomeScreen(
                                meals = state.recipes.map { it.toUi() },
                                onToday = { dispatch(AppAction.OpenToday) },
                                onMakeMine = {
                                    val source = runtime.featuredPlateId(state)
                                    if (source != null) dispatch(AppAction.OpenMakeMine(source))
                                    else info = "No available plate can be adapted right now. Choose another meal from your kitchen."
                                },
                                onCookbook = { dispatch(AppAction.OpenCookbook) },
                                onOpenRecipe = { dispatch(AppAction.OpenRecipe(it)) },
                            )
                            Route.TODAY, Route.PROFILE_PLATE -> {
                                val plates = if (state.route == Route.TODAY) visibleTodayPlates(state, now()) else visibleMyPlatePosts(state, now())
                                TodayScreen(
                                    plates = plates.mapNotNull { it.toUi(state.recipes) },
                                    onOpenPlate = { dispatch(AppAction.OpenPlate(it)) },
                                    onMakeMine = { dispatch(AppAction.OpenMakeMine(it)) },
                                    onBack = ::back,
                                    title = if (state.route == Route.TODAY) "What’s cooking?" else "My Plate.",
                                    showingKeepers = state.route == Route.PROFILE_PLATE,
                                )
                            }
                            Route.POST -> state.currentPlate?.toUi(state.recipes)?.let { plate ->
                                PlateScreen(plate, onMakeMine = { dispatch(AppAction.OpenMakeMine(it)) }, onBack = ::back)
                            } ?: MissingSelection { dispatch(AppAction.OpenHome) }
                            Route.ADAPT -> {
                                MakeMineScreen(
                                    selectedMinutes = selectedMinutes,
                                    selectedEffort = selectedEffort,
                                    onMinutesChange = { selectedMinutes = it },
                                    onEffortChange = { selectedEffort = it },
                                    onFindMatches = {
                                        val variant = current?.variants?.firstOrNull {
                                            it.minutes <= selectedMinutes && it.effort == selectedEffort &&
                                                (ingredients.isEmpty() || ingredients.containsAll(it.ingredients))
                                        }
                                        val variantId = variant?.id ?: if (current?.effort == selectedEffort) "original" else "quick"
                                        dispatch(AppAction.Adapt(AdaptRequest(variantId, selectedMinutes, selectedEffort, ingredients)))
                                    },
                                    onBack = ::back,
                                    sourceTitle = state.currentPlate?.authorName,
                                    availableIngredients = current?.ingredients.orEmpty(),
                                    selectedIngredients = ingredients,
                                    onIngredientToggle = { ingredients = if (it in ingredients) ingredients - it else ingredients + it },
                                )
                            }
                            Route.RECIPE -> current?.let { recipe ->
                                Column {
                                    state.adaptation?.source?.let {
                                        Text("Inspired by ${it.authorName} · your version", modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                                    }
                                    RecipeScreen(
                                        meal = recipe.toUi(),
                                        isSaved = state.savedRecipes.any { it.recipe == recipe && it.source == state.adaptation?.source },
                                        onSave = { dispatch(AppAction.SaveRecipe); if (state.error == null) info = "Saved in this local demo session. Cloud sync and durable storage are not connected yet." },
                                        onCook = { viewedStep = state.cooking?.takeUnless { it.isDone }?.stepIndex ?: 0; dispatch(AppAction.StartCooking) },
                                        onBack = ::back,
                                    )
                                }
                            } ?: MissingSelection { dispatch(AppAction.OpenHome) }
                            Route.COOK -> current?.let { recipe ->
                                fun completeViewedStep() {
                                    dispatch(AppAction.CompleteStep(viewedStep))
                                    if (state.error == null) viewedStep = (viewedStep + 1).coerceAtMost(recipe.steps.lastIndex)
                                }
                                CookingScreen(recipe.toUi(), viewedStep,
                                    onPrevious = { viewedStep = (viewedStep - 1).coerceAtLeast(0) },
                                    onNext = ::completeViewedStep, onFinish = ::completeViewedStep, onBack = ::back)
                            } ?: MissingSelection { dispatch(AppAction.OpenHome) }
                            Route.MEAL_DONE -> current?.let { recipe ->
                                MealDoneScreen(recipe.toUi(),
                                    onShare = { keepOnPlate = false; shareCommandId = "local-${now()}-${state.completedCommandIds.size}"; dispatch(AppAction.OpenShare) },
                                    onSave = { dispatch(AppAction.SaveRecipe); if (state.error == null) info = "Your version is saved for this demo session." },
                                    onHome = { dispatch(AppAction.OpenHome) })
                            } ?: MissingSelection { dispatch(AppAction.OpenHome) }
                            Route.CAPTURE -> current?.let { recipe ->
                                ShareScreen(recipe.toUi(), keepOnPlate, onKeepChange = { keepOnPlate = it },
                                    onShare = { dispatch(AppAction.Share(keepOnPlate, shareCommandId)) }, onBack = ::back)
                            } ?: MissingSelection { dispatch(AppAction.OpenHome) }
                            Route.COOKBOOK -> CookbookScreen(
                                meals = state.savedRecipes.map { it.recipe.toUi().copy(id = it.key) },
                                onOpenRecipe = { dispatch(AppAction.OpenSavedRecipe(it)) },
                                onExplore = { dispatch(AppAction.OpenHome) }, onBack = ::back)
                            Route.UNAVAILABLE -> MissingSelection { dispatch(AppAction.OpenHome) }
                        }
                    }
                }
            }
        }
        val message = state.error?.message ?: info
        if (message != null) {
            AlertDialog(onDismissRequest = { dispatch(AppAction.DismissError); info = null },
                title = { Text(if (state.error != null) "A quick heads-up" else "Local demo") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { dispatch(AppAction.DismissError); info = null }) { Text("Got it") } })
        }
        if (resetRequested) {
            AlertDialog(onDismissRequest = { resetRequested = false }, title = { Text("Reset this local demo?") },
                text = { Text("This clears only this session’s sample saves, posts and progress. No real account or cloud data is involved.") },
                confirmButton = { TextButton(onClick = { dispatch(AppAction.Reset); settingsOpen = false; resetRequested = false; info = null }) { Text("Reset demo") } },
                dismissButton = { TextButton(onClick = { resetRequested = false }) { Text("Keep exploring") } })
        }
    }
}

private fun Recipe.toUi() = MealUi(id, title, subtitle, minutes, effort,
    tags.filterNot { it == "DEMO_UNREVIEWED" }, imageKey, ingredients, steps)

private fun Plate.toUi(recipes: List<Recipe>): PlateUi? {
    val base = recipes.firstOrNull { it.id == recipeId } ?: return null
    val variant = base.variants.firstOrNull { it.id == variantId }
    val meal = if (variant == null) base else base.copy(title = variant.title, minutes = variant.minutes,
        effort = variant.effort, ingredients = variant.ingredients, steps = variant.steps)
    return PlateUi(id, authorName, "@${authorName.lowercase()}", caption, meal.toUi(),
        if (keepOnPlate) "Sample · kept on My Plate" else "Sample · Today only")
}

@Composable
private fun MissingSelection(onHome: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("This plate isn’t available.", style = MaterialTheme.typography.headlineMedium)
        Text("Return to your kitchen to choose another sample.")
        TextButton(onClick = onHome) { Text("Back to my kitchen") }
    }
}
