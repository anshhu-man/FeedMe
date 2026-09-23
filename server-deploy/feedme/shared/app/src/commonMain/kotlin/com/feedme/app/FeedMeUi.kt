package com.feedme.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedme.app.generated.resources.Res
import com.feedme.app.generated.resources.bowl
import org.jetbrains.compose.resources.painterResource

/** Shared presentation only. These components never create navigation or account authority. */
@Composable
fun FeedMeWordmark(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = FeedMeColors.Blue, shape = RoundedCornerShape(12.dp)) {
            Box(Modifier.size(if (compact) 34.dp else 42.dp), contentAlignment = Alignment.Center) {
                Text("f.", color = FeedMeColors.Lime, fontWeight = FontWeight.Black, fontSize = if (compact) 27.sp else 32.sp)
            }
        }
        Text("feedme", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
    }
}

@Composable
fun FeedMeStatusLabel(text: String, color: Color = FeedMeColors.SoftBlue) {
    Surface(color = color, contentColor = FeedMeColors.Ink, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** Brand illustration only; never substituted for an actual selected recipe or user photo. */
@Composable
fun FeedMeWelcomePhoto(modifier: Modifier = Modifier) {
    Image(painterResource(Res.drawable.bowl), "Illustrative bowl, not a recommended meal",
        modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(28.dp)), contentScale = ContentScale.Crop)
}

/** Optional background detail, never used to conceal errors, safety steps or pending actions. */
@Composable
fun FeedMeDetails(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(if (expanded) "−" else "+", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
            if (expanded) Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun FeedMeSectionHeading(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted) }
    }
}
