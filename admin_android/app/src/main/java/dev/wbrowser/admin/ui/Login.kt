package dev.wbrowser.admin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.AuthState

@Composable
fun LoginScreen(state: AuthState.SignedOut, onSignIn: (String, String) -> Unit, onForgot: (String) -> Unit) {
    val p = palette()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val openLink = rememberOpenLink()
    val webUrl = "https://" + dev.wbrowser.admin.BuildConfig.FIREBASE_PROJECT_ID + ".web.app/admin/"
    val field = if (p.dark) Color(0xFF1B2140) else Color.White
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.pri, unfocusedBorderColor = if (p.dark) Color(0x66FFFFFF) else Color(0x665456E8),
        focusedContainerColor = field, unfocusedContainerColor = field,
        focusedTextColor = p.ink, unfocusedTextColor = p.ink, cursorColor = p.pri,
        focusedLabelColor = p.pri, unfocusedLabelColor = p.mut,
    )
    val brand = Brush.horizontalGradient(listOf(p.pri, p.pri2))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().imePadding().navigationBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(24.dp))
        Reveal(0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(brand), contentAlignment = Alignment.Center) { Ico("shield", Color.White, 22.dp, strokeWidth = 2.1f) }
                Spacer(Modifier.size(10.dp))
                Text("WBrowser Admin", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Reveal(1) {
            Column {
                Text("Welcome back", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.6).sp)
                Text("Sign in to see how your browser is used.", color = p.mut, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Reveal(2) {
            LoginCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                        leadingIcon = { Ico("mail", p.mut, 19.dp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                        leadingIcon = { Ico("lock", p.mut, 19.dp) },
                        trailingIcon = { Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { show = !show }.padding(8.dp)) { Ico("eye", if (show) p.pri else p.mut, 19.dp) } },
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); onSignIn(email, password) }),
                    )
                    AnimatedVisibility(state.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.badSoft).padding(10.dp)) { Text(state.error.orEmpty(), color = p.bad, fontSize = 14.sp, lineHeight = 19.sp) }
                    }
                    AnimatedVisibility(state.info != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.okSoft).padding(10.dp)) { Text(state.info.orEmpty(), color = p.ok, fontSize = 14.sp, lineHeight = 19.sp) }
                    }
                    val src = remember { MutableInteractionSource() }
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).pressScale(src, 0.97f).clip(RoundedCornerShape(14.dp)).background(brand)
                            .clickable(interactionSource = src, indication = null, enabled = !state.busy) { focus.clearFocus(); onSignIn(email, password) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.5.dp)
                        else Text("Sign in", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { focus.clearFocus(); onForgot(email) }, enabled = !state.busy) { Text("Forgot password?", color = p.pri, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { openLink(webUrl) }) { Text("Locked out? Recover on the web console", color = p.mut, fontSize = 13.sp, textAlign = TextAlign.Center) }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Access is limited to authorised administrators. Activity is logged.", color = p.mut, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
    }
    }
}

/** The login card: an almost opaque surface, so text stays sharp on top of the moving glass background. */
@Composable
private fun LoginCard(content: @Composable () -> Unit) {
    val p = palette()
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(p.card.copy(alpha = if (p.dark) 0.94f else 0.96f))
            .border(1.dp, if (p.dark) Color(0x33FFFFFF) else Color(0x335456E8), shape).padding(18.dp),
    ) { content() }
}
