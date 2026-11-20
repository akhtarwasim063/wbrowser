package dev.wbrowser.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import dev.wbrowser.admin.data.AdminRepo
import dev.wbrowser.admin.data.DateRange
import dev.wbrowser.admin.data.Me
import dev.wbrowser.admin.data.Period
import dev.wbrowser.admin.data.Presence
import dev.wbrowser.admin.ui.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object Checking : AuthState
    data class SignedOut(val busy: Boolean = false, val error: String? = null, val info: String? = null) : AuthState
    data class SignedIn(val me: Me) : AuthState
}

/** Screens that open on top of the four tabs. */
sealed interface Route {
    data object Sessions : Route
    data class Website(val domain: String) : Route
    data class User(val uid: String) : Route
    data object Admins : Route
    data object Audit : Route
    data object Server : Route
    data object Config : Route
    data object Account : Route
}

class AdminViewModel(app: Application) : AndroidViewModel(app) {
    val repo = AdminRepo()
    private val prefs = app.getSharedPreferences("admin", android.content.Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "Auto") }.getOrDefault(ThemeMode.Auto))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    fun setTheme(m: ThemeMode) { _themeMode.value = m; prefs.edit().putString("theme", m.name).apply() }

    private val _defaultRange = MutableStateFlow(runCatching { DateRange.valueOf(prefs.getString("range", null) ?: "D7") }.getOrDefault(DateRange.D7).let { if (it == DateRange.Custom) DateRange.D7 else it })
    val defaultRange: StateFlow<DateRange> = _defaultRange.asStateFlow()

    /** The date range shared by every analytics screen (like the web console's filter). */
    private val _range = MutableStateFlow(Period(_defaultRange.value))
    val range: StateFlow<Period> = _range.asStateFlow()
    fun selectRange(p: Period) { _range.value = p }
    fun setDefaultRange(r: DateRange) { _defaultRange.value = r; _range.value = Period(r); prefs.edit().putString("range", r.name).apply() }

    /** Seconds between automatic reloads of the open screen (0 = off). */
    private val _autoRefresh = MutableStateFlow(prefs.getInt("auto", 0))
    val autoRefresh: StateFlow<Int> = _autoRefresh.asStateFlow()
    fun setAutoRefresh(sec: Int) { _autoRefresh.value = sec; prefs.edit().putInt("auto", sec).apply() }

    private val _auth = MutableStateFlow<AuthState>(AuthState.Checking)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()
    private var presenceJob: Job? = null

    private val _stack = MutableStateFlow<List<Route>>(emptyList())
    val stack: StateFlow<List<Route>> = _stack.asStateFlow()
    fun push(r: Route) { _stack.value = _stack.value + r }
    fun pop() { if (_stack.value.isNotEmpty()) _stack.value = _stack.value.dropLast(1) }
    fun popToRoot() { _stack.value = emptyList() }

    init {
        viewModelScope.launch {
            val me = repo.restore()
            if (me != null) signedIn(me) else _auth.value = AuthState.SignedOut()
        }
    }

    private fun signedIn(me: Me) {
        _auth.value = AuthState.SignedIn(me)
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch { repo.presence().collect { _presence.value = it } }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isEmpty()) { _auth.value = AuthState.SignedOut(error = "Enter your email and password."); return }
        _auth.value = AuthState.SignedOut(busy = true)
        viewModelScope.launch {
            try { signedIn(repo.login(email, password)) }
            catch (e: Exception) { _auth.value = AuthState.SignedOut(error = friendly(e)) }
        }
    }

    fun forgotPassword(email: String) {
        if (email.isBlank()) { _auth.value = AuthState.SignedOut(error = "Type your admin email first, then tap Forgot password."); return }
        _auth.value = AuthState.SignedOut(busy = true)
        viewModelScope.launch {
            // always answers the same way, so it never reveals which emails are admins
            try { repo.sendReset(email) } catch (e: Exception) { if (e is FirebaseTooManyRequestsException) { _auth.value = AuthState.SignedOut(error = friendly(e)); return@launch } }
            _auth.value = AuthState.SignedOut(info = "If that email belongs to an admin, a reset link has been sent. Check your inbox and spam.")
        }
    }

    fun signOut() {
        presenceJob?.cancel()
        repo.logout()
        _presence.value = emptyMap()
        _stack.value = emptyList()
        _auth.value = AuthState.SignedOut()
    }

    fun friendly(e: Throwable): String = when (e) {
        is AdminRepo.Forbidden -> "This account is not an administrator."
        is FirebaseTooManyRequestsException -> "Too many attempts. Try again later."
        is FirebaseNetworkException -> "No internet connection."
        is FirebaseAuthException -> when (e.errorCode) {
            "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_EMAIL" -> "Invalid email or password."
            "ERROR_USER_DISABLED" -> "This account has been disabled."
            else -> e.message ?: "Sign-in failed."
        }
        else -> e.message ?: "Something went wrong."
    }
}
