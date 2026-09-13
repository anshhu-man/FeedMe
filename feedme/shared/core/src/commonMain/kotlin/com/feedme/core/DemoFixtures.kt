package com.feedme.core

/** Fictional preview content. None of these recipes or substitutions has food-safety review. */
object DemoFixtures {
    const val USER_ID = "demo-you"
    const val CIRCLE_ID = "demo-midnight-kitchen"
    const val PRIMARY_RECIPE_ID = "demo-crunch-bowl"
    const val PRIMARY_PLATE_ID = "demo-maya-plate"

    fun initialState(nowMillis: Long = 0L): AppState {
        require(nowMillis >= 0) { "Demo clock must be non-negative." }
        return AppState(mode = OperatingMode.DEMO)
    }

    internal fun activeState(nowMillis: Long): AppState = AppState(
        mode = OperatingMode.DEMO,
        route = Route.HOME,
        session = Session(USER_ID, "You", isGuest = true, isSimulated = true,
            invitedCircleIds = setOf(CIRCLE_ID)),
        recipes = recipes(),
        plates = listOf(
            Plate(PRIMARY_PLATE_ID, "demo-maya", "Maya", PRIMARY_RECIPE_ID,
                "Big crunch. Tiny effort. Who's making this?", nowMillis,
                keepOnPlate = false, circleId = CIRCLE_ID),
            Plate("demo-jay-plate", "demo-jay", "Jay", "demo-wrap",
                "The between-meetings wrap.", nowMillis,
                keepOnPlate = true, circleId = CIRCLE_ID),
        ),
    )

    fun recipes(): List<Recipe> = listOf(
        Recipe(
            id = PRIMARY_RECIPE_ID,
            title = "The crunch club bowl",
            subtitle = "Color, crunch, and a little main-character energy.",
            minutes = 25,
            effort = "cooking",
            ingredients = listOf("cooked rice", "cooked chickpeas", "cucumber", "carrot", "lemon"),
            steps = listOf(
                "Preview step 1: set out the listed ingredients. This is unreviewed demo content, not validated cooking guidance.",
                "Preview step 2: arrange the bowl ingredients.",
                "Preview step 3: finish the presentation and try the completion interaction.",
            ),
            reviewStatus = ReviewStatus.DEMO_UNREVIEWED,
            imageKey = "bowl",
            tags = listOf("DEMO_UNREVIEWED", "Big crunch", "One bowl"),
            variants = listOf(
                RecipeVariant("quick", "The quick crunch bowl", 15, "light-prep",
                    listOf("cooked rice", "cooked chickpeas", "cucumber", "lemon"),
                    listOf("Demo step 1: preview the pre-prepared ingredient list.",
                        "Demo step 2: arrange the quick bowl.", "Demo step 3: finish the preview."),
                    ReviewStatus.DEMO_UNREVIEWED),
            ),
        ),
        Recipe(
            id = "demo-wrap", title = "Wrap star", subtitle = "A small wrap with big lunch energy.",
            minutes = 10, ingredients = listOf("wrap", "cooked chickpeas", "cucumber", "lemon"),
            steps = listOf("Unreviewed demo: preview the wrap ingredients.",
                "Preview the filling arrangement.", "Preview the fold and finish."),
            reviewStatus = ReviewStatus.DEMO_UNREVIEWED, imageKey = "wrap", effort = "assemble",
            tags = listOf("DEMO_UNREVIEWED", "Lunch energy"),
            variants = listOf(RecipeVariant("quick", "Wrap star, your way", 10, "assemble",
                listOf("wrap", "cooked chickpeas", "cucumber", "lemon"),
                listOf("Unreviewed demo: preview the ingredients.", "Preview the fold."),
                ReviewStatus.DEMO_UNREVIEWED)),
        ),
        Recipe(
            id = "demo-noodles", title = "Noodle mood", subtitle = "Your cozy-night plate.",
            minutes = 20, ingredients = listOf("cooked noodles", "carrot", "cucumber", "lemon"),
            steps = listOf("Unreviewed demo: preview the prepared ingredients.",
                "Preview the noodle bowl arrangement.", "Finish the interaction preview."),
            reviewStatus = ReviewStatus.DEMO_UNREVIEWED, imageKey = "noodles", effort = "light-prep",
            tags = listOf("DEMO_UNREVIEWED", "Cozy energy"),
            variants = listOf(RecipeVariant("quick", "Quick noodle mood", 15, "light-prep",
                listOf("cooked noodles", "cucumber", "lemon"),
                listOf("Unreviewed demo: preview the prepared ingredients.", "Preview the quick bowl."),
                ReviewStatus.DEMO_UNREVIEWED)),
        ),
    )
}
