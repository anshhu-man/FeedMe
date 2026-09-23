package com.feedme.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedme.app.generated.resources.Res
import com.feedme.app.generated.resources.bowl
import com.feedme.app.generated.resources.noodles
import com.feedme.app.generated.resources.wrap
import org.jetbrains.compose.resources.painterResource

/** Presentation-only models: authorization, matching, persistence and navigation live elsewhere. */
data class MealUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val minutes: Int,
    val effort: String,
    val tags: List<String> = emptyList(),
    val imageKey: String = "wrap",
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
)

data class PlateUi(
    val id: String,
    val author: String,
    val handle: String,
    val caption: String,
    val meal: MealUi,
    val ageLabel: String = "Sample post",
)

@Composable
fun WelcomeScreen(onExplore: () -> Unit, onUnavailableAuth: () -> Unit) {
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Wordmark()
            Badge("FIRST BITE", FeedMeColors.Lime)
        }
        Surface(color = FeedMeColors.Blue, shape = RoundedCornerShape(30.dp)) {
            Column(Modifier.fillMaxWidth().padding(25.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
                Eyebrow("YOUR KITCHEN. YOUR RULES.", Color.White)
                Text("GOOD FOOD.\nZERO\nDRAMA.", style = MaterialTheme.typography.displayLarge, color = Color.White, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Make something good.\nMake it yours.", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        Badge("LOW EFFORT.\nBIG YES.", FeedMeColors.Lime)
                    }
                    FoodPhoto("wrap", "Illustrative crisp vegetable wrap", Modifier.size(134.dp).clip(CircleShape))
                }
            }
        }
        PrimaryAction("Explore the demo kitchen", onExplore)
        SecondaryAction("Join the club / Log in", onUnavailableAuth)
        Note("A little preview of your next kitchen era.", "Explore locally without an account. Sign-in, real sharing and purchases are not connected in this build.", FeedMeColors.Lilac)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    meals: List<MealUi>,
    onToday: () -> Unit,
    onMakeMine: () -> Unit,
    onCookbook: () -> Unit,
    onOpenRecipe: (String) -> Unit,
) {
    Page {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Wordmark()
            Badge("DINNER MODE", FeedMeColors.Lime)
        }
        Heading("HEY, HUNGRY HUMAN.", "Good food.\nLess effort.")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
            Box {
                FoodPhoto("wrap", "Illustrative vegetable wrap on a plate", Modifier.fillMaxWidth().height(236.dp))
                Box(Modifier.align(Alignment.TopStart).padding(16.dp)) { Badge("LOW EFFORT · HIGH REWARD", FeedMeColors.Lime) }
            }
            Column(Modifier.padding(23.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Eyebrow("YOUR LOW-EFFORT ERA", FeedMeColors.Blue)
                Text("Dinner doesn’t need\na plot twist.", style = MaterialTheme.typography.headlineMedium)
                Text("Borrow an idea. Find a version that fits your evening.", style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
                PrimaryAction("Make Mine  ↗", onMakeMine)
            }
        }
        Surface(onClick = onToday, color = FeedMeColors.SoftLime, shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar("FM", FeedMeColors.Paper)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Eyebrow("THE PLATE CHECK")
                    Text("A little dinner\ninspiration.", style = MaterialTheme.typography.titleLarge)
                    Text("Explore sample Today posts", style = MaterialTheme.typography.bodySmall)
                }
                Text("↗", fontSize = 28.sp)
            }
        }
        SectionTitle("Your next good meal")
        meals.take(3).forEach { meal -> CompactMeal(meal, onClick = { onOpenRecipe(meal.id) }) }
        SecondaryAction("Open my cookbook", onCookbook)
        FinePrint("No perfect kitchens. No food scores. Just a little less dinner stress.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(plates: List<PlateUi>, onOpenPlate: (String) -> Unit, onMakeMine: (String) -> Unit, onBack: () -> Unit, title: String = "What’s cooking?", showingKeepers: Boolean = false) {
    Page {
        BackAction(onBack)
        Heading("THE PLATE CHECK · DEMO", title, "Everyday meals. Tiny wins. Ideas to make your own.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(if (showingKeepers) "YOUR KEEPERS" else "TODAY MOMENTS", FeedMeColors.Lime)
            Badge("${plates.size} SAMPLE ${if (plates.size == 1) "PLATE" else "PLATES"}", Color.White)
        }
        if (showingKeepers) {
            Note("Worth keeping around.", "These are demo plates you chose to keep. Keeping a post on My Plate is separate from saving its recipe to your cookbook.", FeedMeColors.SoftBlue)
        } else {
            Note("Today is a moment. My Plate is a keeper.", "Today is for passing moments; My Plate keeps the ones you choose. These are local sample posts, not a live feed or verified server expiry.", FeedMeColors.SoftBlue)
        }
        if (plates.isEmpty()) {
            EmptyState("A quiet kitchen.", "No sample plates to show right now.")
        } else {
            plates.forEach { plate ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(26.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(plate.author.take(1), FeedMeColors.SoftBlue)
                        Column(Modifier.weight(1f)) {
                            Text(plate.author, style = MaterialTheme.typography.titleMedium)
                            Text(plate.ageLabel, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                        }
                        Badge(if (showingKeepers) "MY PLATE" else "TODAY", FeedMeColors.Lime)
                    }
                    Surface(onClick = { onOpenPlate(plate.id) }, color = Color.Transparent) {
                        FoodPhoto(plate.meal.imageKey, "Illustrative ${plate.meal.title}", Modifier.fillMaxWidth().aspectRatio(1.35f))
                    }
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(plate.caption, style = MaterialTheme.typography.titleLarge)
                        Text(plate.meal.title, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
                        PrimaryAction("Make Mine  ↗", { onMakeMine(plate.id) })
                        TextButton(onClick = { onOpenPlate(plate.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("See the plate & recipe") }
                    }
                }
            }
        }
    }
}

@Composable
fun PlateScreen(plate: PlateUi, onMakeMine: (String) -> Unit, onBack: () -> Unit) {
    Page {
        BackAction(onBack)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(plate.author.take(1), FeedMeColors.Lime)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plate.author, style = MaterialTheme.typography.titleLarge)
                Text(plate.handle, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
                Text(plate.ageLabel, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            }
        }
        Box {
            FoodPhoto(plate.meal.imageKey, "Illustrative ${plate.meal.title}", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)))
            Box(Modifier.align(Alignment.TopStart).padding(17.dp)) { Badge("SAMPLE PLATE", FeedMeColors.Lime) }
        }
        Text(plate.caption, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text("Their inspiration. Your version.", style = MaterialTheme.typography.bodyLarge, color = FeedMeColors.Muted)
        CompactMeal(plate.meal, onClick = { onMakeMine(plate.id) })
        PrimaryAction("Make Mine  ↗", { onMakeMine(plate.id) })
        FinePrint("Sample person and post. No message, reaction, share, or permission request is sent from this build.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MakeMineScreen(
    selectedMinutes: Int,
    selectedEffort: String,
    onMinutesChange: (Int) -> Unit,
    onEffortChange: (String) -> Unit,
    onFindMatches: () -> Unit,
    onBack: () -> Unit,
    sourceTitle: String? = null,
    availableIngredients: List<String> = emptyList(),
    selectedIngredients: Set<String> = emptySet(),
    onIngredientToggle: (String) -> Unit = {},
) {
    Page {
        BackAction(onBack)
        Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(25.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("THE SAME IDEA. MORE YOU.")
                Text("Make", style = MaterialTheme.typography.displayMedium)
                Text("Mine.", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 65.sp, lineHeight = 68.sp, color = FeedMeColors.Blue)
                Text("Your time. Your energy. Your kitchen.", style = MaterialTheme.typography.bodyLarge)
                if (sourceTitle != null) Badge("INSPIRED BY $sourceTitle", Color.White)
            }
        }
        FeedMeSectionHeading("01  /  Your time", "How much time have you got?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(10, 15, 20, 30).forEach { minutes -> Choice("$minutes min", minutes == selectedMinutes) { onMinutesChange(minutes) } }
        }
        FeedMeSectionHeading("02  /  Your energy", "Match the meal to the evening you’re having.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("assemble" to "Assemble only", "light-prep" to "Light prep", "cooking" to "Up for cooking").forEach { (value, label) ->
                Choice(label, selectedEffort == value) { onEffortChange(value) }
            }
        }
        if (availableIngredients.isNotEmpty()) {
            FeedMeSectionHeading("03  /  Your kitchen", "What’s in your kitchen?")
            Text("Select ingredients you have. An empty selection leaves the pantry filter off.", style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                availableIngredients.forEach { ingredient -> Choice(ingredient, ingredient in selectedIngredients) { onIngredientToggle(ingredient) } }
            }
        }
        PrimaryAction("Find my version  ↗", onFindMatches)
        Note("Your choices. A small sample catalog.", "This build filters local sample options. It does not generate recipes, infer ingredients from photos, or verify allergy safety.", FeedMeColors.SoftLime)
    }
}

@Composable
fun MatchResultsScreen(meals: List<MealUi>, onOpenRecipe: (String) -> Unit, onBack: () -> Unit) {
    Page {
        BackAction(onBack, "Change my choices")
        Heading("YOUR MEAL MATCH", "Your kind\nof doable.", "Options from the local sample catalog that fit your current choices.")
        if (meals.isEmpty()) {
            EmptyState("No match.\nNo pressure.", "Nothing in this small sample catalog fits every choice. Try a different time, energy level, or ingredient selection.")
            PrimaryAction("Adjust my choices", onBack)
        } else {
            meals.forEach { meal -> MealCard(meal, { onOpenRecipe(meal.id) }) }
        }
    }
}

@Composable
fun RecipeScreen(meal: MealUi, isSaved: Boolean, onSave: () -> Unit, onCook: () -> Unit, onBack: () -> Unit) {
    Page {
        BackAction(onBack)
        Box {
            FoodPhoto(meal.imageKey, "Illustrative ${meal.title}", Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(28.dp)))
            Box(Modifier.align(Alignment.TopStart).padding(17.dp)) { Badge("YOUR NEXT GOOD MEAL", FeedMeColors.Lime) }
        }
        Heading("FEEDME SAMPLE KITCHEN", meal.title, meal.subtitle)
        MealMetadata(meal)
        PrimaryAction("Let’s make it  ↗", onCook)
        SecondaryAction(if (isSaved) "Saved in my cookbook" else "Save to my cookbook", onSave)
        Note("Know what you’re making.", "Illustrative recipe content, not a professionally reviewed recipe. Check ingredients, product labels and preparation guidance before real cooking.", FeedMeColors.Lilac)
        SectionTitle("The lineup")
        Surface(color = Color.White, shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                meal.ingredients.forEachIndexed { index, ingredient ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("+", color = FeedMeColors.Blue, fontWeight = FontWeight.Bold)
                        Text(ingredient, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                    if (index < meal.ingredients.lastIndex) HorizontalDivider(color = FeedMeColors.Line)
                }
            }
        }
        SectionTitle("The game plan")
        meal.steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(15.dp), verticalAlignment = Alignment.Top) {
                Surface(color = FeedMeColors.Lime, shape = CircleShape) { Text("${index + 1}", modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp), fontWeight = FontWeight.Bold) }
                Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 7.dp))
            }
        }
        FinePrint("The generated food photo is illustrative and does not establish the recipe’s ingredients or nutrition.")
    }
}

@Composable
fun CookingScreen(meal: MealUi, stepIndex: Int, onPrevious: () -> Unit, onNext: () -> Unit, onFinish: () -> Unit, onBack: () -> Unit) {
    Page {
        BackAction(onBack, "Leave cooking")
        val safeStep = stepIndex.coerceIn(0, (meal.steps.size - 1).coerceAtLeast(0))
        Eyebrow("COOKING MODE · SAMPLE RECIPE")
        Text(meal.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (meal.steps.isEmpty()) {
            EmptyState("No steps available.", "Return to the recipe and choose another sample.")
            PrimaryAction("Back to recipe", onBack)
        } else {
            LinearProgressIndicator(progress = { (safeStep + 1).toFloat() / meal.steps.size }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape), color = FeedMeColors.Blue, trackColor = FeedMeColors.Line)
            Surface(color = FeedMeColors.Lime, shape = RoundedCornerShape(30.dp)) {
                Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(23.dp)) {
                    Eyebrow("STEP ${safeStep + 1} OF ${meal.steps.size}")
                    Text("${safeStep + 1}".padStart(2, '0'), fontSize = 74.sp, lineHeight = 80.sp, fontWeight = FontWeight.Black, color = FeedMeColors.Blue)
                    Text(meal.steps[safeStep], style = MaterialTheme.typography.headlineMedium)
                }
            }
            Text("One thing at a time. You’ve got this.", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 23.sp, lineHeight = 30.sp)
            if (safeStep == meal.steps.lastIndex) PrimaryAction("Done. That’s my dinner.  ✓", onFinish) else PrimaryAction("Next step  →", onNext)
            if (safeStep > 0) SecondaryAction("Previous step", onPrevious)
            FinePrint("Sample instructions. Leaving this screen does not publish or share anything.")
        }
    }
}

@Composable
fun CookbookScreen(meals: List<MealUi>, onOpenRecipe: (String) -> Unit, onExplore: () -> Unit, onBack: () -> Unit) {
    Page {
        BackAction(onBack)
        Heading("YOUR PRIVATE LITTLE COLLECTION", "The keepers.", "Meals you’d happily meet again.")
        Badge("${meals.size} SAVED ${if (meals.size == 1) "MEAL" else "MEALS"}", FeedMeColors.Lime)
        if (meals.isEmpty()) {
            EmptyState("Nothing saved.\nYet.", "Find a meal, save it, and make future-you’s dinner a little easier.")
            PrimaryAction("Find my first keeper", onExplore)
        } else {
            meals.forEach { meal -> MealCard(meal, { onOpenRecipe(meal.id) }) }
        }
        FinePrint("Your local demo cookbook. No public profile or cloud account is created.")
    }
}

@Composable
fun MealDoneScreen(meal: MealUi, onShare: () -> Unit, onSave: () -> Unit, onHome: () -> Unit) {
    Page {
        Surface(color = FeedMeColors.Blue, shape = RoundedCornerShape(30.dp)) {
            Column(Modifier.fillMaxWidth().padding(27.dp), verticalArrangement = Arrangement.spacedBy(21.dp)) {
                Eyebrow("SESSION COMPLETE", Color.White)
                Text("THAT’S\nYOUR\nVERSION.", style = MaterialTheme.typography.displayLarge, color = Color.White)
                FoodPhoto(meal.imageKey, "Illustrative ${meal.title}", Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(22.dp)))
                Badge("GOOD FOOD. YOUR CALL.", FeedMeColors.Lime)
            }
        }
        Text(meal.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text("Keep it for next time, share a demo plate, or call it a night.", style = MaterialTheme.typography.bodyLarge, color = FeedMeColors.Muted)
        PrimaryAction("Create a local demo plate", onShare)
        SecondaryAction("Save this version", onSave)
        SecondaryAction("Back to my kitchen", onHome)
        FinePrint("Completing the sample steps does not upload a photo, publish a post, or verify a real cooked meal.")
    }
}

@Composable
fun ShareScreen(meal: MealUi, keepOnPlate: Boolean, onKeepChange: (Boolean) -> Unit, onShare: () -> Unit, onBack: () -> Unit) {
    Page {
        BackAction(onBack)
        Heading("A LITTLE DINNER CHECK-IN", "Made it.\nMade it mine.", "Try the sharing flow with a local sample plate.")
        Box {
            FoodPhoto(meal.imageKey, "Generated sample photo for ${meal.title}", Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(28.dp)))
            Box(Modifier.align(Alignment.TopStart).padding(17.dp)) { Badge("GENERATED DEMO PHOTO", FeedMeColors.Lime) }
        }
        Text(meal.title, style = MaterialTheme.typography.headlineMedium)
        Note("Audience: this demo only", "Nothing leaves this device. No real circle, person, or public profile can see this sample post.", FeedMeColors.Lilac)
        Surface(color = Color.White, shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
            Row(Modifier.fillMaxWidth().toggleable(value = keepOnPlate, role = Role.Switch, onValueChange = onKeepChange).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Keep on My Plate", style = MaterialTheme.typography.titleMedium)
                    Text("A keeper as well as a Today moment. You choose.", style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
                }
                Switch(checked = keepOnPlate, onCheckedChange = null)
            }
        }
        PrimaryAction("Publish to local demo only", onShare)
        FinePrint("This sample uses the existing generated food image. Camera, photo upload, server expiry and live social delivery are not connected.")
    }
}

@Composable
fun SettingsScreen(onReset: () -> Unit, onBack: () -> Unit) {
    Page {
        BackAction(onBack)
        Heading("CLEAR IS KIND", "Your kitchen.\nYour controls.")
        Badge("LOCAL DEMO WORKSPACE", FeedMeColors.Lime)
        Note("You’re in the demo kitchen.", "Sample recipes. Sample people. No real account, cloud sync, live circles, photo publishing, payments or notifications are connected on this surface.", FeedMeColors.SoftBlue)
        Note("Make Mine is yours.", "Change time, effort and pantry selections to filter the sample catalog. No generative AI call is made, and no preference is sent to a server.", FeedMeColors.SoftLime)
        Surface(color = Color.White, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionTitle("A fresh start")
                Text("Reset this demo’s saved meals, selections and cooking progress. No real account or cloud data is deleted.",
                    style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
                SecondaryAction("Reset local demo", onReset)
            }
        }
        FinePrint("FeedMe · Your kitchen. Your rules.")
    }
}

@Composable
fun FeedMeDemoLabel(onSettings: (() -> Unit)? = null) {
    Surface(color = FeedMeColors.Ink) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LOCAL DEMO · SAMPLE DATA · NO LIVE SHARING", color = Color.White,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(vertical = 7.dp))
            if (onSettings != null) TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = FeedMeColors.Lime)) { Text("Demo settings") }
        }
    }
}

@Composable
fun FeedMeBottomBar(selected: String, onHome: () -> Unit, onToday: () -> Unit, onCookbook: () -> Unit, onSettings: () -> Unit) {
    Surface(color = FeedMeColors.Paper, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Triple("home", "Kitchen", onHome), Triple("today", "Today", onToday), Triple("cookbook", "Saves", onCookbook), Triple("settings", "My Plate", onSettings)).forEach { (key, label, action) ->
                TextButton(onClick = action, modifier = Modifier.weight(1f).heightIn(min = 54.dp).semantics { this.selected = selected == key; role = Role.Tab }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (selected == key) FeedMeColors.Lime else Color.Transparent, contentColor = if (selected == key) FeedMeColors.Ink else FeedMeColors.Muted), contentPadding = PaddingValues(horizontal = 3.dp, vertical = 10.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(FeedMeColors.Paper), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
    }
}

@Composable
private fun Wordmark() {
    FeedMeWordmark()
}

@Composable
private fun BackAction(onBack: () -> Unit, label: String = "Back") {
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 10.dp)) { Text("←  $label", color = FeedMeColors.Ink) }
}

@Composable
private fun Heading(eyebrow: String, title: String, copy: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(eyebrow)
        Text(title, style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { heading() })
        if (copy != null) Text(copy, style = MaterialTheme.typography.bodyLarge, color = FeedMeColors.Muted)
    }
}

@Composable
private fun Eyebrow(text: String, color: Color = FeedMeColors.Muted) { Text(text, style = MaterialTheme.typography.labelMedium, color = color) }

@Composable
private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(11.dp)) { Text(text, color = FeedMeColors.Ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
}

@Composable
private fun Avatar(initial: String, color: Color) {
    Surface(color = color, shape = CircleShape) { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text(initial, fontWeight = FontWeight.Black, fontSize = if (initial.length > 1) 14.sp else 19.sp, color = FeedMeColors.Ink) } }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), border = BorderStroke(1.dp, FeedMeColors.Line), colors = ButtonDefaults.outlinedButtonColors(contentColor = FeedMeColors.Ink, containerColor = Color.White)) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(if (selected) "✓ $label" else label, style = MaterialTheme.typography.labelLarge) }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FeedMeColors.Lime, selectedLabelColor = FeedMeColors.Ink, containerColor = Color.White, labelColor = FeedMeColors.Ink))
}

@Composable
private fun Note(title: String, copy: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FeedMeColors.Ink)
            Text(copy, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Ink)
        }
    }
}

@Composable
private fun FinePrint(copy: String) { Text(copy, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted) }

@Composable
private fun EmptyState(title: String, copy: String) {
    Surface(color = FeedMeColors.SoftLime, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(27.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
            Text("✳", fontSize = 57.sp, color = FeedMeColors.Blue)
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(copy, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FoodPhoto(key: String, description: String, modifier: Modifier = Modifier) {
    val resource = when (key) { "bowl" -> Res.drawable.bowl; "noodles" -> Res.drawable.noodles; else -> Res.drawable.wrap }
    Image(painter = painterResource(resource), contentDescription = description, modifier = modifier, contentScale = ContentScale.Crop)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealMetadata(meal: MealUi) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge("${meal.minutes} MIN", FeedMeColors.Lime)
        Badge(meal.effort, FeedMeColors.Lilac)
        meal.tags.take(2).forEach { Badge(it, FeedMeColors.Line) }
    }
}

@Composable
private fun CompactMeal(meal: MealUi, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            FoodPhoto(meal.imageKey, "Illustrative ${meal.title}", Modifier.size(79.dp).clip(RoundedCornerShape(15.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(meal.title, style = MaterialTheme.typography.titleMedium)
                Text("${meal.minutes} min · ${meal.effort}", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            }
            Text("↗", fontSize = 22.sp, color = FeedMeColors.Blue)
        }
    }
}

@Composable
private fun MealCard(meal: MealUi, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(27.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        FoodPhoto(meal.imageKey, "Illustrative ${meal.title}", Modifier.fillMaxWidth().height(235.dp))
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(meal.title, style = MaterialTheme.typography.headlineMedium)
            Text(meal.subtitle, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
            MealMetadata(meal)
            Text("See the recipe  ↗", color = FeedMeColors.Blue, style = MaterialTheme.typography.labelLarge)
        }
    }
}
