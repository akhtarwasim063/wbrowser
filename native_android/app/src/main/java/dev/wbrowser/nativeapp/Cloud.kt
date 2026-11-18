package dev.wbrowser.nativeapp

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * All talking to Firebase (Auth + Firestore). Every function blocks, so call them from the SyncWorker thread.
 * Identity is a Firebase Auth user (anonymous, optionally linked to Google); access control lives in firestore.rules.
 * Not configured (no google-services.json) => ready=false and the app simply keeps its local outbox.
 */
object Cloud {
    var ready = false
        private set

    fun init(ctx: Context) {
        try {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                if (BuildConfig.FIREBASE_EMULATOR_HOST.isNotEmpty()) {
                    // local testing against the Firebase emulators: no real project needed
                    FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder().setApplicationId("1:1:android:wbrowser").setApiKey("fake-key").setProjectId("demo-wbrowser").build())
                } else FirebaseApp.initializeApp(ctx)
            }
            ready = FirebaseApp.getApps(ctx).isNotEmpty()
            if (ready && BuildConfig.FIREBASE_EMULATOR_HOST.isNotEmpty()) {
                val h = BuildConfig.FIREBASE_EMULATOR_HOST
                FirebaseAuth.getInstance().useEmulator(h, 9099)
                FirebaseFirestore.getInstance().useEmulator(h, 8080)
            }
        } catch (e: Exception) { ready = false }
    }

    /** Public site (Firebase Hosting) that serves the privacy policy / terms, or null when not configured. */
    fun siteUrl(): String? = if (!ready) null else runCatching {
        if (BuildConfig.FIREBASE_EMULATOR_HOST.isNotEmpty()) null else "https://${FirebaseApp.getInstance().options.projectId}.web.app"
    }.getOrNull()

    private fun <T> await(t: Task<T>): T = Tasks.await(t, 40, TimeUnit.SECONDS)
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private fun userDoc(uid: String): DocumentReference = db.collection("users").document(uid)

    fun uid(): String = auth.currentUser?.uid ?: await(auth.signInAnonymously()).user!!.uid

    private fun device(): Map<String, Any> = Account.deviceJson().let {
        mapOf("model" to it.optString("model"), "manufacturer" to android.os.Build.MANUFACTURER.orEmpty().take(40), "os" to it.optString("os"),
            "sdk" to android.os.Build.VERSION.SDK_INT, "appVersion" to it.optString("appVersion"))
    }
    private fun locale(): Map<String, Any> = mapOf("locale" to java.util.Locale.getDefault().toLanguageTag(), "timezone" to java.util.TimeZone.getDefault().id)

    /** The signed-in Firebase user, without ever creating one. */
    fun currentUid(): String? = if (ready) auth.currentUser?.uid else null

    /** Reads the superadmin's remote settings (config/app); a missing doc means defaults. */
    fun fetchConfig() {
        val d = await(db.collection("config").document("app").get()).data
        Remote.apply(
            tracking = d?.get("trackingEnabled") != false, search = d?.get("searchAllowed") != false,
            interval = (d?.get("uploadIntervalSeconds") as? Number)?.toInt() ?: 15, announcement = (d?.get("announcement") as? String).orEmpty())
    }

    /** After an admin erased this user: remove the Firebase sign-in too (never for an admin account). */
    fun deleteOwnAuth() {
        val u = auth.currentUser ?: return
        val role = runCatching { await(u.getIdToken(false)).claims["role"] }.getOrNull()
        if (role != null) { auth.signOut(); return }
        runCatching { await(u.delete()) }.onFailure { auth.signOut() }
    }

    enum class State { MISSING, ACTIVE, SUSPENDED }
    fun state(uid: String): State {
        val s = await(userDoc(uid).get())
        return if (!s.exists()) State.MISSING else if (s.getString("status") == "suspended") State.SUSPENDED else State.ACTIVE
    }

    /** Creates the profile on first run (idempotent); otherwise just refreshes the mutable fields. */
    fun register(uid: String) {
        val ref = userDoc(uid)
        if (await(ref.get()).exists()) { pushProfile(uid); return }
        await(ref.set(mapOf(
            "name" to Account.name, "userId" to Account.userId, "status" to "active", "activityConsent" to true,
            "searchConsent" to Account.searchConsent, "createdAt" to System.currentTimeMillis(), "lastSeen" to System.currentTimeMillis(),
            "device" to device(), "nameSource" to Account.nameSource) + locale()))
    }

    /** Mutable fields only: owners can never change their own status (rules enforce it). */
    fun pushProfile(uid: String) {
        await(userDoc(uid).update(mapOf(
            "name" to Account.name.ifEmpty { "Unnamed" }, "activityConsent" to Account.sharing, "searchConsent" to Account.searchConsent,
            "lastSeen" to System.currentTimeMillis(), "device" to device(), "nameSource" to Account.nameSource) + locale()))
    }

    /** Writes outbox rows; returns the rows the server accepted (or that are permanently invalid). Throws on network errors. */
    fun pushEvents(uid: String, rows: List<ActivityDb.Row>): List<Long> {
        val done = ArrayList<Long>()
        rows.chunked(100).forEach { chunk ->
            try {
                val b = db.batch()
                chunk.forEach { r -> docFor(uid, r.json)?.let { (ref, data) -> b.set(ref, data, SetOptions.merge()) } }
                await(b.commit())
                done += chunk.map { it.seq }
            } catch (e: Exception) {
                if (!isDenied(e)) throw e
                // one bad document rejects the whole batch: retry one by one, dropping only the invalid ones
                chunk.forEach { r ->
                    try { docFor(uid, r.json)?.let { (ref, data) -> await(ref.set(data, SetOptions.merge())) }; done += r.seq }
                    catch (e2: Exception) { if (isDenied(e2)) { if (state(uid) != State.ACTIVE) throw e2 else done += r.seq } else throw e2 }
                }
            }
        }
        return done
    }

    private fun docFor(uid: String, j: JSONObject): Pair<DocumentReference, Map<String, Any>>? {
        val col = { n: String -> db.collection("users").document(uid).collection(n) }
        return when (j.optString("type")) {
            "visit" -> {
                val url = j.getString("url")
                col("visits").document(j.getString("id")) to mapOf(
                    "url" to url, "domain" to (Sanitizer.clean(url)?.domain ?: return null), "title" to j.optString("title").take(300),
                    "visitedAt" to j.getLong("startedAt"), "durationMs" to j.getLong("durationMs").coerceIn(0, 86_400_000), "sessionId" to j.optString("sessionId"))
            }
            "search" -> col("searches").document(j.getString("id")) to mapOf(
                "query" to j.getString("query").take(500), "engine" to j.optString("engine"), "at" to j.getLong("at"))
            "session" -> col("sessions").document(j.getString("id")) to mapOf(
                "startedAt" to j.getLong("startedAt"), "endedAt" to j.getLong("endedAt"),
                "durationMs" to (j.getLong("endedAt") - j.getLong("startedAt")).coerceIn(0, 86_400_000))
            else -> null
        }
    }

    fun isDenied(e: Throwable): Boolean = (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
        (e.cause as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED

    /** Right to erasure: deletes everything under the user and the Firebase Auth account itself. */
    fun erase(uid: String) {
        for (c in listOf("visits", "searches", "sessions")) {
            while (true) {
                val snap = await(userDoc(uid).collection(c).limit(400).get())
                if (snap.isEmpty) break
                val b = db.batch(); snap.documents.forEach { b.delete(it.reference) }; await(b.commit())
                if (snap.size() < 400) break
            }
        }
        await(userDoc(uid).delete())
        runCatching { await(auth.currentUser!!.delete()) }.onFailure { auth.signOut() }
    }

    // ---- optional Google sign-in (Firebase Auth links the Google identity to this user) ----
    class LinkResult(val email: String?, val name: String?)

    fun linkGoogle(idToken: String): LinkResult {
        val uid = uid()
        val user = auth.currentUser!!
        val cred = GoogleAuthProvider.getCredential(idToken, null)
        try { await(user.linkWithCredential(cred)) }
        catch (e: Exception) {
            if (e.cause is com.google.firebase.auth.FirebaseAuthUserCollisionException) throw IllegalStateException("That Google account is already linked to another user")
            throw e
        }
        val g = auth.currentUser!!.providerData.firstOrNull { it.providerId == "google.com" }
        val r = LinkResult(g?.email, g?.displayName)
        runCatching { await(userDoc(uid).update(mapOf("email" to r.email, "googleName" to r.name))) }
        return r
    }

    fun unlinkGoogle() {
        val uid = uid()
        await(auth.currentUser!!.unlink("google.com"))
        await(userDoc(uid).update(mapOf("email" to FieldValue.delete(), "googleName" to FieldValue.delete())))
    }
}
