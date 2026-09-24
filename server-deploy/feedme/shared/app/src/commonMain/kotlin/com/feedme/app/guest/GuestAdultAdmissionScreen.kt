package com.feedme.app.guest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeTheme
import com.feedme.app.FeedMeWordmark
import com.feedme.core.FeedMeAdultPolicy

/** Presentation admission only: this is not verified age, account consent, Terms acceptance,
 * provider identity or a durable guest credential. The host must not attach private guest
 * storage until the exact current confirmation is returned. */
internal fun guestAdultAdmissionAllowed(declaration: String, confirmed: Boolean): Boolean =
    confirmed && declaration == FeedMeAdultPolicy.AGE_DECLARATION

/** A small release-policy interstitial, not a new blueprint destination. It preserves the
 * original AUTH_WELCOME design and appears only after the user intentionally chooses guest
 * cooking. No birth date, name, account identifier or remote request is collected here. */
@Composable
fun GuestAdultAdmissionScreen(onConfirm: () -> Unit, onBack: () -> Unit) {
    var confirmed by remember { mutableStateOf(false) }
    val declaration = FeedMeAdultPolicy.AGE_DECLARATION
    FeedMeTheme {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding()
            .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FeedMeWordmark(compact = true)
            Text("Before you cook.", style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() })
            Text("FeedMe’s connected release is for adults aged ${FeedMeAdultPolicy.MINIMUM_AGE_YEARS} or older.",
                style = MaterialTheme.typography.bodyLarge)
            Surface(color = FeedMeColors.Surface, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().toggleable(value = confirmed, role = Role.Checkbox,
                    onValueChange = { confirmed = it }).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(checked = confirmed, onCheckedChange = null)
                    Text(declaration, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
            Text("This confirmation opens local guest cooking only. FeedMe does not ask for or store your date of birth here, and no account is created.",
                style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
            Button(onClick = onConfirm,
                enabled = guestAdultAdmissionAllowed(declaration, confirmed),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Enter guest kitchen")
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Back")
            }
        }
    }
}
