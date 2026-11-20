package dev.wbrowser.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import dev.wbrowser.admin.data.AdminRepo
import dev.wbrowser.admin.data.DateRange
import dev.wbrowser.admin.data.Load
import dev.wbrowser.admin.data.Me
import dev.wbrowser.admin.data.Overview
import dev.wbrowser.admin.data.Presence
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

class AdminViewModel : ViewModel() {
    private val repo = AdminRepo()

    private val _auth = MutableStateFlow<AuthState>(AuthState.Checking)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _range = MutableStateFlow(DateRange.D7)
    val range: StateFlow<DateRange> = _range.asStateFlow()

    private val _overview = MutableStateFlow<Load<Overview>>(Load.Loading)
    val overview: StateFlow<Load<Overview>> = _overview.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    private var presenceJob: Job? = null

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
        loadOverview()
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
        _overview.value = Load.Loading
        _presence.value = emptyMap()
        _auth.value = AuthState.SignedOut()
    }

    fun selectRange(r: DateRange) {
        if (r == _range.value) return
        _range.value = r
        loadOverview()
    }

    fun loadOverview() {
        viewModelScope.launch {
            _refreshing.value = true
            try { _overview.value = Load.Ready(repo.overview(_range.value)) }
            catch (e: Exception) { if (_overview.value !is Load.Ready) _overview.value = Load.Failed(friendly(e)) }
            finally { _refreshing.value = false }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
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
