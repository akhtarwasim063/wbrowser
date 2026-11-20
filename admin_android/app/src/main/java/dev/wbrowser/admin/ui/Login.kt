package dev.wbrowser.admin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.AuthState
import androidx.compose.foundation.clickable

@Composable
fun LoginScreen(state: AuthState.SignedOut, onSignIn: (String, String) -> Unit, onForgot: (String) -> Unit) {
    val p = palette()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.pri, unfocusedBorderColor = p.line, focusedContainerColor = p.card, unfocusedContainerColor = p.card,
        focusedTextColor = p.ink, unfocusedTextColor = p.ink, cursorColor = p.pri,
        focusedLabelColor = p.pri, unfocusedLabelColor = p.mut,
    )

    Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).imePadding()) {
        // gradient hero
        Box(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF5B5BF0), Color(0xFF8B5CF6), Color(0xFFD946EF))), RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .statusBarsPadding().padding(horizontal = 28.dp, vertical = 36.dp),
        ) {
            Column {
                Reveal(0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Ico("shield", Color.White, 24.dp, strokeWidth = 2.1f) }
                        Spacer(Modifier.size(12.dp))
                        Text("WBrowser Admin", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.height(36.dp))
                Reveal(1) { Text("Understand how your browser is used.", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp) }
                Spacer(Modifier.height(10.dp))
                Reveal(2) { Text("Live activity, website analytics and per-user history in one secure place.", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 22.sp) }
            }
        }

        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Reveal(3) {
                Column {
                    Text("Welcome back", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.4).sp)
                    Text("Sign in to the admin console.", color = p.mut, fontSize = 14.sp)
                }
            }
            Reveal(4) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    leadingIcon = { Ico("mail", p.mut, 20.dp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                )
            }
            Reveal(5) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    leadingIcon = { Ico("lock", p.mut, 20.dp) },
                    trailingIcon = { Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { show = !show }.padding(8.dp)) { Ico("eye", if (show) p.pri else p.mut, 20.dp) } },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); onSignIn(email, password) }),
                )
            }

            AnimatedVisibility(state.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.badSoft).padding(12.dp)) { Text(state.error.orEmpty(), color = p.bad, fontSize = 14.sp) }
            }
            AnimatedVisibility(state.info != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.okSoft).padding(12.dp)) { Text(state.info.orEmpty(), color = p.ok, fontSize = 14.sp) }
            }

            Reveal(6) {
                val src = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxWidth().height(52.dp).pressScale(src, 0.97f).clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF5B5BF0), Color(0xFF8B5CF6), Color(0xFFD946EF))))
                        .clickable(interactionSource = src, indication = null, enabled = !state.busy) { focus.clearFocus(); onSignIn(email, password) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
                    else Text("Sign in", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Reveal(7) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { focus.clearFocus(); onForgot(email) }, enabled = !state.busy) { Text("Forgot password?", color = p.pri, fontWeight = FontWeight.SemiBold) }
                }
            }
            Text("Access is limited to authorised administrators. Activity is logged.", color = p.mut, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
