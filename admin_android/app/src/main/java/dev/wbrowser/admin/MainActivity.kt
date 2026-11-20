package dev.wbrowser.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.wbrowser.admin.ui.AdminTheme
import dev.wbrowser.admin.ui.LoginScreen
import dev.wbrowser.admin.ui.MainShell
import dev.wbrowser.admin.ui.palette

/**
 * WBrowser Admin, native. No WebView: screens are Jetpack Compose, data comes straight from Firebase
 * (Auth + Firestore) through AdminRepo. Screenshots and screen recording are allowed.
 */
class MainActivity : ComponentActivity() {
    private val vm: AdminViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AdminTheme {
                val auth by vm.auth.collectAsState()
                Box(Modifier.fillMaxSize().background(palette().bg)) {
                    AnimatedContent(auth, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) }, contentKey = { it::class }, label = "auth") { s ->
                        when (s) {
                            is AuthState.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette().pri) }
                            is AuthState.SignedOut -> LoginScreen(s, vm::signIn, vm::forgotPassword)
                            is AuthState.SignedIn -> MainShell(vm, s.me)
                        }
                    }
                }
            }
        }
    }
}
