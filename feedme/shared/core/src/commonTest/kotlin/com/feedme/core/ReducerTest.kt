package com.feedme.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReducerTest {
    private val now = 1_000_000L
    private fun demo() = reduce(DemoFixtures.initialState(now), AppAction.ContinueAsGuest, now)
    private fun makeMine(state: AppState = demo(), request: AdaptRequest = AdaptRequest()): AppState =
        reduce(reduce(state, AppAction.OpenMakeMine(DemoFixtures.PRIMARY_PLATE_ID), now), AppAction.Adapt(request), now)
    private fun done(): AppState {
        var state = reduce(makeMine(), AppAction.StartCooking, now)
        state.currentRecipe!!.steps.indices.forEach { state = reduce(state, AppAction.CompleteStep(it), now) }
        return state
    }

    @Test fun productionIsDefaultAndDoesNotLoadDemoIdentity() {
        val state = reduce(AppState(), AppAction.ContinueAsGuest, now)
        assertEquals(OperatingMode.PRODUCTION, state.mode)
        assertNull(state.session!!.userId)
        assertFalse(state.session!!.isSimulated)
        assertTrue(state.recipes.isEmpty())
        assertTrue(state.plates.isEmpty())
    }

    @Test fun demoRequiresExplicitModeAndGuestAction() {
        val welcome = DemoFixtures.initialState(now)
        assertEquals(Route.AUTH_WELCOME, welcome.route)
        assertNull(welcome.session)
        assertTrue(welcome.plates.isEmpty())
        val state = reduce(welcome, AppAction.ContinueAsGuest, now)
        assertEquals(Route.HOME, state.route)
        assertTrue(state.session!!.isSimulated)
        assertTrue(state.recipes.all { it.reviewStatus == ReviewStatus.DEMO_UNREVIEWED })
        assertTrue(state.recipes.flatMap { it.variants }.all { it.reviewStatus == ReviewStatus.DEMO_UNREVIEWED })
    }

    @Test fun makeMinePreservesSourceAndUsesSupportedVariant() {
        val state = makeMine(request = AdaptRequest(maxMinutes = 15, effort = "light-prep"))
        assertEquals(Route.RECIPE, state.route)
        assertNull(state.error)
        val adaptation = assertNotNull(state.adaptation)
        assertEquals("demo-maya", adaptation.source.authorId)
        assertEquals("Maya", adaptation.source.authorName)
        assertEquals(DemoFixtures.PRIMARY_PLATE_ID, adaptation.source.plateId)
        assertEquals(DemoFixtures.PRIMARY_RECIPE_ID, adaptation.source.originalRecipeId)
        assertEquals("quick", adaptation.variantId)
        assertEquals(15, state.currentRecipe!!.minutes)
        assertEquals(25, state.recipes.first().minutes)
    }

    @Test fun unsupportedVariantDoesNotInventRecipe() {
        val state = makeMine(request = AdaptRequest(variantId = "invent-anything"))
        assertEquals(ErrorCode.UNSUPPORTED_VARIANT, state.error!!.code)
        assertNull(state.adaptation)
        assertEquals(Route.ADAPT, state.route)
    }

    @Test fun unknownIngredientsAndEffortAreRejected() {
        assertEquals(ErrorCode.UNSUPPORTED_INPUT,
            makeMine(request = AdaptRequest(availableIngredients = setOf("unknown powder"))).error!!.code)
        assertEquals(ErrorCode.UNSUPPORTED_INPUT,
            makeMine(request = AdaptRequest(effort = "no effort ever")).error!!.code)
        assertEquals(ErrorCode.UNSUPPORTED_INPUT,
            makeMine(request = AdaptRequest(maxMinutes = 0)).error!!.code)
    }

    @Test fun timeEffortAndIngredientConstraintsAreActuallyEnforced() {
        listOf(
            AdaptRequest(maxMinutes = 5),
            AdaptRequest(effort = "assemble"),
            AdaptRequest(availableIngredients = setOf("cucumber")),
            AdaptRequest(excludedIngredients = setOf("cooked chickpeas")),
        ).forEach { request ->
            val state = makeMine(request = request)
            assertEquals(ErrorCode.CONSTRAINT_NOT_MET, state.error!!.code)
            assertNull(state.adaptation)
        }
        val state = makeMine(request = AdaptRequest(availableIngredients =
            setOf(" cooked rice ", "COOKED CHICKPEAS", "cucumber", "lemon")))
        assertNull(state.error)
    }

    @Test fun allergyRequestsDoNotReceiveSafetyClaimsOrAdaptations() {
        val state = makeMine(request = AdaptRequest(allergyIngredients = setOf("peanut")))
        assertEquals(ErrorCode.ALLERGY_REQUEST_UNSUPPORTED, state.error!!.code)
        assertTrue(state.error!!.message.contains("cannot determine"))
        assertNull(state.adaptation)
    }

    @Test fun unknownAndRejectedRecipesNeverRunEvenInDemo() {
        listOf(ReviewStatus.UNKNOWN, ReviewStatus.REJECTED).forEach { status ->
            val original = demo()
            val state = original.copy(recipes = original.recipes.map { it.copy(reviewStatus = status) })
            val next = reduce(state, AppAction.OpenRecipe(DemoFixtures.PRIMARY_RECIPE_ID), now)
            assertEquals(ErrorCode.UNREVIEWED_CONTENT, next.error!!.code)
            assertNull(next.selectedRecipeId)
        }
    }

    @Test fun unreviewedContentCannotBeUsedInProduction() {
        val seed = demo().copy(mode = OperatingMode.PRODUCTION)
        val state = reduce(seed, AppAction.OpenRecipe(DemoFixtures.PRIMARY_RECIPE_ID), now)
        assertEquals(ErrorCode.UNREVIEWED_CONTENT, state.error!!.code)
        val mutation = reduce(seed, AppAction.OpenMakeMine(DemoFixtures.PRIMARY_PLATE_ID), now)
        assertNull(mutation.adaptation)
        assertNotNull(mutation.error)
    }

    @Test fun unreviewedVariantCannotBypassReviewedParent() {
        val seed = demo()
        val recipe = seed.recipes.first().copy(reviewStatus = ReviewStatus.REVIEWED,
            variants = seed.recipes.first().variants.map { it.copy(reviewStatus = ReviewStatus.UNKNOWN) })
        val next = makeMine(seed.copy(recipes = listOf(recipe)))
        assertEquals(ErrorCode.UNREVIEWED_CONTENT, next.error!!.code)
        assertNull(next.adaptation)
    }

    @Test fun todayExpiresAtExactly24HoursEvenWhenKeptOnPlate() {
        val state = demo()
        assertEquals(2, visibleTodayPlates(state, now + TODAY_DURATION_MILLIS - 1).size)
        assertTrue(visibleTodayPlates(state, now + TODAY_DURATION_MILLIS).isEmpty())
        assertFalse(canViewPlate(state.plates.first(), state.session, now + TODAY_DURATION_MILLIS, state.mode))
        assertTrue(canViewPlate(state.plates.last(), state.session, now + TODAY_DURATION_MILLIS, state.mode))
    }

    @Test fun futureAndNegativeTimestampsNeverLeakIntoTodayOrDirectPosts() {
        val state = demo()
        val future = state.plates.first().copy(createdAtMillis = now + 1, keepOnPlate = true)
        val negative = future.copy(createdAtMillis = -1)
        listOf(future, negative).forEach { plate ->
            assertFalse(canViewPlate(plate, state.session, now, state.mode))
            assertTrue(visibleTodayPlates(state.copy(plates = listOf(plate)), now).isEmpty())
        }
    }

    @Test fun invitationBlockDeletionAndProductionModeGateLocalViews() {
        val state = demo()
        val plate = state.plates.first()
        assertFalse(canViewPlate(plate, state.session!!.copy(invitedCircleIds = emptySet()), now, state.mode))
        assertFalse(canViewPlate(plate, state.session!!.copy(blockedUserIds = setOf(plate.authorId)), now, state.mode))
        assertFalse(canViewPlate(plate.copy(blockedViewerIds = setOf(DemoFixtures.USER_ID)), state.session, now, state.mode))
        assertFalse(canViewPlate(plate.copy(deleted = true), state.session, now, state.mode))
        assertFalse(canViewPlate(plate, state.session, now, OperatingMode.PRODUCTION))
        assertFalse(canViewPlate(plate, null, now, state.mode))
    }

    @Test fun expiryAndPermissionAreRecheckedWhenAdapting() {
        val opened = reduce(demo(), AppAction.OpenMakeMine(DemoFixtures.PRIMARY_PLATE_ID), now)
        val expired = reduce(opened, AppAction.Adapt(AdaptRequest()), now + TODAY_DURATION_MILLIS)
        assertEquals(Route.UNAVAILABLE, expired.route)
        assertNull(expired.currentPlate)
        assertNull(expired.adaptation)
        val revoked = opened.copy(plates = opened.plates.map { it.copy(allowMakeMine = false) })
        assertNull(reduce(revoked, AppAction.Adapt(AdaptRequest()), now).adaptation)
    }

    @Test fun doubleTapsDoNotSkipStepsOrCreateNewCookingSession() {
        val first = reduce(makeMine(), AppAction.StartCooking, now)
        assertEquals(first, reduce(first, AppAction.StartCooking, now))
        val step = reduce(first, AppAction.CompleteStep(0), now)
        assertEquals(1, step.cooking!!.stepIndex)
        assertEquals(step, reduce(step, AppAction.CompleteStep(0), now))
        val skipped = reduce(first, AppAction.CompleteStep(2), now)
        assertEquals(ErrorCode.INVALID_ACTION, skipped.error!!.code)
        assertFalse(skipped.cooking!!.isDone)
    }

    @Test fun doneAndSavingAreIdempotentAndRetainAttribution() {
        val done = done()
        assertEquals(Route.MEAL_DONE, done.route)
        assertTrue(done.cooking!!.isDone)
        assertEquals(done, reduce(done, AppAction.CompleteStep(done.currentRecipe!!.steps.lastIndex), now))
        val saved = reduce(done, AppAction.SaveRecipe, now)
        assertEquals(saved, reduce(saved, AppAction.SaveRecipe, now))
        assertEquals(1, saved.savedRecipes.size)
        assertEquals("demo-maya", saved.savedRecipes.single().source!!.authorId)
        val reopened = reduce(reduce(saved, AppAction.OpenCookbook, now),
            AppAction.OpenSavedRecipe(saved.savedRecipes.single().key), now)
        assertEquals(15, reopened.currentRecipe!!.minutes)
        assertEquals("demo-maya", reopened.adaptation!!.source.authorId)
    }

    @Test fun cookingBackResumesUnfinishedButExplicitRecookResetsCompletedSession() {
        val started = reduce(makeMine(), AppAction.StartCooking, now)
        val progressed = reduce(started, AppAction.CompleteStep(0), now)
        val resumed = reduce(reduce(progressed, AppAction.Back, now), AppAction.StartCooking, now)
        assertEquals(progressed.cooking, resumed.cooking)
        val finished = done()
        val again = reduce(reduce(finished, AppAction.Back, now), AppAction.StartCooking, now)
        assertEquals(Route.COOK, again.route)
        assertFalse(again.cooking!!.isDone)
        assertEquals(0, again.cooking!!.stepIndex)
        assertTrue(again.cooking!!.completedStepIndexes.isEmpty())
    }

    @Test fun shareIsLocalExplicitAndCommandReplaySafe() {
        val ready = reduce(done(), AppAction.OpenShare, now)
        val action = AppAction.Share(keepOnPlate = true, commandId = "one")
        val shared = reduce(ready, action, now)
        assertEquals(Route.PROFILE_PLATE, shared.route)
        assertEquals(3, shared.plates.size)
        assertEquals(shared, reduce(shared, action, now))
        val own = visibleMyPlatePosts(shared, now).single()
        assertEquals(DemoFixtures.USER_ID, own.authorId)
        assertEquals("demo-maya", own.source!!.authorId)
        assertEquals("quick", own.variantId)
    }

    @Test fun todayOnlyShareIsNotKeptAndNavigatesToToday() {
        val ready = reduce(done(), AppAction.OpenShare, now)
        val shared = reduce(ready, AppAction.Share(keepOnPlate = false, commandId = "today"), now)
        assertEquals(Route.TODAY, shared.route)
        assertTrue(visibleMyPlatePosts(shared, now).isEmpty())
        assertTrue(visibleTodayPlates(shared, now + TODAY_DURATION_MILLIS).isEmpty())
    }

    @Test fun productionSocialMutationsAreAlwaysDeniedWithoutBackend() {
        val seed = reduce(done(), AppAction.OpenShare, now).copy(mode = OperatingMode.PRODUCTION)
        listOf(AppAction.Share(true, "not-real"), AppAction.DeletePlate(DemoFixtures.PRIMARY_PLATE_ID),
            AppAction.BlockUser("demo-maya")).forEach { action ->
            val next = reduce(seed, action, now)
            assertEquals(ErrorCode.BACKEND_REQUIRED, next.error!!.code)
            assertEquals(seed.plates, next.plates)
            assertEquals(seed.completedCommandIds, next.completedCommandIds)
        }
    }

    @Test fun deletionRequiresOwnershipAndTombstoneIsHidden() {
        val denied = reduce(demo(), AppAction.DeletePlate(DemoFixtures.PRIMARY_PLATE_ID), now)
        assertFalse(denied.plates.first().deleted)
        val shared = reduce(reduce(done(), AppAction.OpenShare, now), AppAction.Share(true, "mine"), now)
        val own = visibleMyPlatePosts(shared, now).single()
        val deleted = reduce(shared, AppAction.DeletePlate(own.id), now)
        assertTrue(visibleMyPlatePosts(deleted, now).isEmpty())
        assertTrue(deleted.plates.first { it.id == own.id }.deleted)
    }

    @Test fun backAndBlockRemoveTransientSourceContext() {
        val state = makeMine()
        val back = reduce(reduce(state, AppAction.Back, now), AppAction.Back, now)
        assertNull(back.adaptation)
        assertNull(back.selectedRecipeId)
        val today = reduce(back, AppAction.Back, now)
        assertEquals(Route.TODAY, today.route)
        assertNull(today.currentPlate)
        val saved = reduce(state, AppAction.SaveRecipe, now)
        val blocked = reduce(saved, AppAction.BlockUser("demo-maya"), now)
        assertNull(blocked.adaptation)
        assertNull(blocked.currentPlate)
        assertTrue(blocked.savedRecipes.isEmpty())
        assertTrue(blocked.plates.none { it.authorId == "demo-maya" })
    }

    @Test fun logoutAndResetClearAllSessionDataAndCannotBackIntoIt() {
        val seed = reduce(done(), AppAction.SaveRecipe, now)
        listOf(AppAction.Logout, AppAction.Reset).forEach { action ->
            val clean = reduce(seed, action, now)
            assertEquals(AppState(mode = OperatingMode.DEMO), clean)
            val back = reduce(clean, AppAction.Back, now)
            assertEquals(Route.AUTH_WELCOME, back.route)
            assertNull(back.session)
            assertTrue(back.plates.isEmpty())
            assertTrue(back.savedRecipes.isEmpty())
            assertNull(back.adaptation)
        }
    }

    @Test fun reducerDoesNotMutateInputsAndDetachesCollectionAliases() {
        val ingredients = mutableListOf("cucumber", "lemon")
        val recipes = mutableListOf(DemoFixtures.recipes().first().copy(ingredients = ingredients))
        val seed = demo().copy(recipes = recipes)
        val next = reduce(seed, AppAction.OpenHome, now)
        ingredients.add("unexpected external mutation")
        recipes.clear()
        assertEquals(1, next.recipes.size)
        assertEquals(listOf("cucumber", "lemon"), next.recipes.first().ingredients)
        assertTrue(next.plates.isNotEmpty())
    }

    @Test fun myPlatePostReturnsToMyPlateIncludingAfterMakeMine() {
        val shared = reduce(reduce(done(), AppAction.OpenShare, now), AppAction.Share(true, "origin"), now)
        val own = visibleMyPlatePosts(shared, now).single()
        val post = reduce(shared, AppAction.OpenPlate(own.id), now)
        assertEquals(Route.PROFILE_PLATE, reduce(post, AppAction.Back, now).route)
        val adapting = reduce(post, AppAction.OpenMakeMine(own.id), now)
        val recipe = reduce(adapting, AppAction.Adapt(AdaptRequest()), now)
        assertEquals(own.id, recipe.adaptation!!.source.plateId)
        val backToAdapt = reduce(recipe, AppAction.Back, now)
        assertEquals(Route.ADAPT, backToAdapt.route)
        val backToPost = reduce(backToAdapt, AppAction.Back, now)
        assertEquals(Route.POST, backToPost.route)
        assertEquals(own.id, backToPost.selectedPlateId)
        val backToMyPlate = reduce(backToPost, AppAction.Back, now)
        assertEquals(Route.PROFILE_PLATE, backToMyPlate.route)
        assertNull(backToMyPlate.currentPlate)
        assertNull(backToMyPlate.adaptation)
    }

    @Test fun cookbookSavedRecipeReturnsToCookbookWithoutLeakingOriginToCatalog() {
        val saved = reduce(makeMine(), AppAction.SaveRecipe, now)
        val cookbook = reduce(saved, AppAction.OpenCookbook, now)
        val recipe = reduce(cookbook, AppAction.OpenSavedRecipe(saved.savedRecipes.single().key), now)
        val back = reduce(recipe, AppAction.Back, now)
        assertEquals(Route.COOKBOOK, back.route)
        assertNull(back.currentRecipe)
        assertNull(back.adaptation)
        val home = reduce(back, AppAction.OpenHome, now)
        val catalogRecipe = reduce(home, AppAction.OpenRecipe(DemoFixtures.PRIMARY_RECIPE_ID), now)
        assertEquals(Route.HOME, reduce(catalogRecipe, AppAction.Back, now).route)
        assertEquals(AppState(mode = OperatingMode.DEMO), reduce(recipe, AppAction.Logout, now))
    }
}
