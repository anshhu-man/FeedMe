package com.feedme.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedme.app.FeedMeColors
import com.feedme.app.blueprint.*

enum class BlueprintAuthPage { SIGNUP, LOGIN, VERIFY }

/** Attachment-only form values. This visual component never persists or dispatches them. */
data class BlueprintAuthInput(
    val email: String = "", val password: String = "", val confirmation: String = "", val code: String = "",
    val termsAccepted: Boolean = false, val ageConfirmed: Boolean = false,
) { override fun toString() = "BlueprintAuthInput(<redacted>)" }

data class BlueprintAuthErrors(
    val email: String? = null, val password: String? = null, val confirmation: String? = null,
    val code: String? = null, val declarations: String? = null,
)

/** Native rendering of AUTH_SIGNUP, AUTH_LOGIN and AUTH_VERIFY from views-access.js.
 * Callbacks are presentation intents only: the real host retains validation, exact-state admission,
 * provider transport and one-use handoffs. A disabled empty rendering is also safe without config.
 * Prototype credentials/code and delivery/success claims are deliberately not carried into the app.
 */
@Composable
fun BlueprintAuthForm(
    page: BlueprintAuthPage,
    input: BlueprintAuthInput = BlueprintAuthInput(),
    onInputChange: (BlueprintAuthInput) -> Unit = {},
    enabled: Boolean = false,
    ageDeclaration: String,
    passwordGuidance: String,
    errors: BlueprintAuthErrors = BlueprintAuthErrors(),
    maskedEmail: String? = null,
    verificationEnabled: Boolean = enabled,
    resendLabel: String = "Send a new code",
    resendEnabled: Boolean = false,
    onSubmit: () -> Unit = {},
    onSwitch: () -> Unit = {},
    onResend: () -> Unit = {},
    onChangeEmail: () -> Unit = {},
    onUnavailable: (String) -> Unit = {},
    onGoogle: (() -> Unit)? = null,
    onPasswordRecovery: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().testTag("auth.${page.name.lowercase()}")) {
        when (page) {
            BlueprintAuthPage.SIGNUP -> {
                Spacer(Modifier.height(5.dp))
                BlueprintHero("A seat at the table", "You belong\nin this club.",
                    "A little inspiration. A lot less “what’s for dinner?”")
                Spacer(Modifier.height(23.dp))
                BlueprintAuthField("Email", "you@example.com", input.email, { onInputChange(input.copy(email = it)) },
                    enabled, errors.email, KeyboardType.Email, tag = "auth.email")
                BlueprintAuthField("Password", "Your password", input.password, { onInputChange(input.copy(password = it)) },
                    enabled, errors.password, KeyboardType.Password, secret = true, tag = "auth.password")
                BlueprintAuthField("Confirm password", "Re-enter your password", input.confirmation,
                    { onInputChange(input.copy(confirmation = it)) }, enabled, errors.confirmation,
                    KeyboardType.Password, secret = true, last = true, tag = "auth.confirmation")
                BlueprintAuthDeclaration("Accept Terms and Privacy", input.termsAccepted, enabled, "auth.terms") {
                    onInputChange(input.copy(termsAccepted = it))
                }
                BlueprintAuthDeclaration(ageDeclaration, input.ageConfirmed, enabled, "auth.age") {
                    onInputChange(input.copy(ageConfirmed = it))
                }
                errors.declarations?.let { BlueprintAuthError(it) }
                Spacer(Modifier.height(16.dp))
                BlueprintButton("Make me a member", onSubmit, Modifier.fillMaxWidth().testTag("auth.submit"),
                    kind = "primary", icon = "arrow", enabled = enabled)
                Spacer(Modifier.height(12.dp))
                BlueprintButton("Already in? Log in", onSwitch, Modifier.fillMaxWidth().testTag("auth.switch"),
                    kind = "ghost", enabled = enabled)
                // Configured provider guidance stays visible, but is not replaced with prototype policy.
                if (passwordGuidance.isNotBlank()) {
                    Spacer(Modifier.height(13.dp))
                    Text(passwordGuidance, fontSize = 11.sp, lineHeight = 17.6.sp, color = FeedMeColors.Muted)
                }
            }
            BlueprintAuthPage.LOGIN -> {
                Spacer(Modifier.height(5.dp))
                BlueprintSticker("saved you a seat.")
                Spacer(Modifier.height(23.dp))
                BlueprintHero("Welcome back", "Hey, hungry\nhuman.", "Your saves, your people, your next good meal.")
                Spacer(Modifier.height(24.dp))
                BlueprintAuthField("Email", "you@example.com", input.email, { onInputChange(input.copy(email = it)) },
                    enabled, errors.email, KeyboardType.Email, tag = "auth.email")
                BlueprintAuthField("Password", "Your password", input.password, { onInputChange(input.copy(password = it)) },
                    enabled, errors.password, KeyboardType.Password, secret = true, last = true, tag = "auth.password",
                    bottomSpace = 0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    BlueprintButton("Forgot your password?", { onPasswordRecovery?.invoke() ?: onUnavailable("Password recovery is not connected in this build yet.") },
                        kind = "ghost", enabled = enabled)
                }
                BlueprintButton("Let’s get cooking", onSubmit, Modifier.fillMaxWidth().testTag("auth.submit"),
                    kind = "primary", icon = "arrow", enabled = enabled)
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(Modifier.weight(1f), color = FeedMeColors.Line)
                    Text("or drop in with", style = BlueprintType.Small, color = Color(0xFF747D6C))
                    HorizontalDivider(Modifier.weight(1f), color = FeedMeColors.Line)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    BlueprintButton("Apple", { onUnavailable("Apple sign-in is not connected in this build yet.") },
                        Modifier.weight(1f).testTag("auth.apple"), icon = "lock", enabled = enabled)
                    BlueprintButton("Google", { onGoogle?.invoke() ?: onUnavailable("Google sign-in is not connected in this build yet.") },
                        Modifier.weight(1f).testTag("auth.google"), icon = "user", enabled = enabled)
                }
                Spacer(Modifier.height(10.dp))
                BlueprintButton("New here? Join FeedMe", onSwitch, Modifier.testTag("auth.switch"), kind = "ghost", enabled = enabled)
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    BlueprintIcon("shield", Modifier.size(13.dp), Color(0xFF67725C))
                    Text("Private by default. Always your choice.", style = BlueprintType.Small, color = Color(0xFF67725C))
                }
            }
            BlueprintAuthPage.VERIFY -> {
                Row(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 25.dp)
                    .semantics { contentDescription = "Setup step 1 of 3" }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { index -> Box(Modifier.weight(1f).height(4.dp)
                        .background(if (index == 0) FeedMeColors.Blue else Color(0xFFE0E5D8), RoundedCornerShape(5.dp))) }
                }
                Box(Modifier.padding(top = 0.dp, bottom = 25.dp).size(91.dp).rotate(-5f)
                    .background(FeedMeColors.Lime, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                    BlueprintIcon("send", Modifier.size(41.dp))
                }
                BlueprintHero("One quick check", "Check your\ninbox.",
                    "Enter the code from your verification email. Then continue account setup.")
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth().border(1.dp, FeedMeColors.Line, RoundedCornerShape(22.dp))
                    .background(Color.White, RoundedCornerShape(22.dp)).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        // A pending address is not proof that an email was delivered.
                        Text("EMAIL TO CHECK", style = BlueprintType.Eyebrow)
                        Text(maskedEmail ?: "Your signup email", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    BlueprintIcon("send")
                }
                Spacer(Modifier.height(23.dp))
                BlueprintAuthField("Your six-digit code", "— — — — — —", input.code, { onInputChange(input.copy(code = it)) },
                    verificationEnabled, errors.code, KeyboardType.NumberPassword, secret = true, last = true,
                    code = true, tag = "auth.code")
                BlueprintButton("Yep, that’s me", onSubmit, Modifier.fillMaxWidth().testTag("auth.submit"),
                    kind = "primary", icon = "arrow", enabled = verificationEnabled)
                Spacer(Modifier.height(12.dp))
                BlueprintButton(resendLabel, onResend, Modifier.fillMaxWidth().testTag("auth.resend"), kind = "ghost", enabled = resendEnabled)
                BlueprintButton("Wrong email? Change it", onChangeEmail, Modifier.fillMaxWidth().testTag("auth.changeEmail"),
                    kind = "ghost", enabled = enabled)
            }
        }
    }
}

@Composable
private fun BlueprintAuthField(label: String, placeholder: String, value: String, onChange: (String) -> Unit,
    enabled: Boolean, error: String?, keyboard: KeyboardType, secret: Boolean = false, last: Boolean = false,
    code: Boolean = false, tag: String, bottomSpace: Int = 16) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(bottom = bottomSpace.dp)) {
        Text("$label *", style = BlueprintType.FieldLabel)
        Spacer(Modifier.height(8.dp))
        val style = if (code) BlueprintType.Field.copy(fontSize = 25.sp, lineHeight = 35.sp,
            letterSpacing = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) else BlueprintType.Field
        BasicTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            textStyle = style.copy(color = FeedMeColors.Ink), cursorBrush = SolidColor(FeedMeColors.Blue),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboard,
                imeAction = if (last) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
                onDone = { focus.clearFocus() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = if (code) 65.dp else 50.dp)
                    .background(Color.White, RoundedCornerShape(13.dp))
                    .border(1.dp, if (error != null) MaterialTheme.colorScheme.error else Color(0xFFDCE0D6), RoundedCornerShape(13.dp))
                    .padding(horizontal = 13.dp, vertical = 14.dp),
                    contentAlignment = if (code) Alignment.Center else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(letterSpacing = if (code) 0.sp else style.letterSpacing),
                        color = Color(0xFF858D7C), modifier = if (code) Modifier.fillMaxWidth() else Modifier)
                    inner()
                }
            })
        error?.let { BlueprintAuthError(it) }
    }
}

@Composable
private fun BlueprintAuthDeclaration(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Text("$label *", Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.size(42.dp, 25.dp).background(if (checked) FeedMeColors.Blue else Color(0xFFDCE1D4), RoundedCornerShape(20.dp))
                .padding(3.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(19.dp).background(Color.White, RoundedCornerShape(50)))
            }
        }
        HorizontalDivider(color = FeedMeColors.Line)
    }
}

@Composable
private fun BlueprintAuthError(message: String) {
    Text(message, Modifier.padding(top = 6.dp), fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.error)
}
