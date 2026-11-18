package dev.wbrowser.nativeapp

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlin.concurrent.thread

/**
 * Optional "Continue with Google" using Google's official Credential Manager flow, linked to the user through
 * Firebase Auth. We never see the Google password; the ID token is handed straight to Firebase and not stored.
 * Needs Google sign-in enabled in the Firebase console (this creates the web client ID used here).
 */
object GoogleLink {
    /** Web client ID: from google-services.json (default_web_client_id) or an explicit -PGOOGLE_WEB_CLIENT_ID. */
    private fun clientId(): String {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) return BuildConfig.GOOGLE_WEB_CLIENT_ID
        val ctx = App.instance
        val res = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
        return if (res != 0) ctx.getString(res) else ""
    }

    val available: Boolean get() = Cloud.ready && clientId().isNotBlank()

    class Identity(val idToken: String, val name: String?, val email: String?)

    fun signIn(activity: Activity, cb: (Result<Identity>) -> Unit) {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId()).build()).build()
        CredentialManager.create(activity).getCredentialAsync(
            activity, request, null, ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val c = result.credential
                    if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val g = GoogleIdTokenCredential.createFrom(c.data)
                        cb(Result.success(Identity(g.idToken, g.displayName, g.id)))
                    } else cb(Result.failure(IllegalStateException("Unexpected credential type")))
                }
                override fun onError(e: GetCredentialException) = cb(Result.failure(e))
            })
    }

    private fun onMain(r: Result<Unit>, done: (Result<Unit>) -> Unit) = Handler(Looper.getMainLooper()).post { done(r) }

    /** Links the Google identity to this user (creates the profile first if the first sync has not happened yet). */
    fun link(identity: Identity, done: (Result<Unit>) -> Unit) {
        thread {
            val r = runCatching {
                val uid = Cloud.uid()
                if (!Account.registered) { Cloud.register(uid); Account.markRegistered() }
                val r = Cloud.linkGoogle(identity.idToken)
                Account.setGoogle(r.email ?: identity.email)
                Account.adoptGoogleName(r.name ?: identity.name)
            }
            onMain(r, done)
        }
    }

    fun unlink(done: (Result<Unit>) -> Unit) {
        thread {
            val r = runCatching { Cloud.unlinkGoogle(); Account.setGoogle(null) }
            onMain(r, done)
        }
    }
}
