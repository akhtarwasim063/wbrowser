package dev.wbrowser.admin.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * All Firestore access for the admin app. This is a Kotlin port of firebase/public/admin/fb.js:
 * the same per-user queries and the same aggregation, so the numbers match the web console.
 * What an admin may read or delete is enforced by firestore.rules, not here.
 */
class AdminRepo {
    class Forbidden : Exception("forbidden")

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    private val perms = mapOf(
        "superadmin" to setOf("aggregate", "user_list", "user_detail", "export", "manage_users", "manage_admins", "audit", "delete_data", "server_info", "manage_config"),
        "analyst" to setOf("aggregate", "user_list", "user_detail", "export", "server_info"),
    )

    private suspend fun loadMe(u: FirebaseUser, force: Boolean): Me {
        val role = u.getIdToken(force).await().claims["role"] as? String
        val p = perms[role]
        if (role == null || p == null) { auth.signOut(); throw Forbidden() }
        return Me(u.uid, u.email.orEmpty(), u.displayName ?: u.email.orEmpty(), role, p)
    }

    /** The already signed-in admin (if any), without asking for the password again. */
    suspend fun restore(): Me? {
        val u = auth.currentUser ?: return null
        return try { loadMe(u, false) } catch (e: Exception) { null }
    }

    suspend fun login(email: String, password: String): Me {
        val u = auth.signInWithEmailAndPassword(email.trim(), password).await().user ?: error("no user")
        return loadMe(u, true)
    }

    suspend fun sendReset(email: String) { auth.sendPasswordResetEmail(email.trim()).await() }
    fun logout() { auth.signOut(); usersCache = null }

    // ---------- users + live presence ----------
    private var usersCache: Pair<Long, List<UserRow>>? = null
    private fun Any?.l(): Long = (this as? Number)?.toLong() ?: 0L

    private fun userRow(d: DocumentSnapshot) = UserRow(
        uid = d.id, name = d.getString("name") ?: "Unknown", code = d.getString("userId") ?: d.id,
        email = d.getString("email"), lastSeen = d.get("lastSeen").l(), online = d.getBoolean("online") == true,
        createdAt = d.get("createdAt").l(),
    )

    private suspend fun users(force: Boolean = false): List<UserRow> {
        usersCache?.let { (t, v) -> if (!force && System.currentTimeMillis() - t < 6000) return v }
        val v = db.collection("users").get().await().documents.map(::userRow)
        usersCache = System.currentTimeMillis() to v
        return v
    }

    /** Realtime online/offline: the phone app writes `online` the moment it is foregrounded/backgrounded. */
    fun presence(): Flow<Map<String, Presence>> = callbackFlow {
        val reg = db.collection("users").addSnapshotListener { snap, _ ->
            if (snap != null) trySend(snap.documents.associate { it.id to Presence(it.getBoolean("online") == true, it.get("lastSeen").l()) })
        }
        awaitClose { reg.remove() }
    }

    // ---------- per-user range queries (default single-field indexes; no collection-group index needed) ----------
    private suspend fun <T> rangeDocs(group: String, field: String, from: Long, to: Long, us: List<UserRow>, map: (String, DocumentSnapshot) -> T): List<T> = coroutineScope {
        val limit = min(10000, ceil(30000.0 / max(us.size, 1)).toInt()).toLong()
        us.map { u ->
            async {
                db.collection("users").document(u.uid).collection(group)
                    .whereGreaterThanOrEqualTo(field, from).whereLessThanOrEqualTo(field, to)
                    .orderBy(field, Query.Direction.DESCENDING).limit(limit).get().await().documents.map { map(u.uid, it) }
            }
        }.awaitAll().flatten()
    }

    private fun day(ms: Long, tz: Int) = Instant.ofEpochMilli(ms + tz * 60000L).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun domainMap(visits: List<Visit>): List<SiteStat> =
        visits.groupBy { it.domain }.map { (domain, vs) ->
            val total = vs.sumOf { it.durationMs }
            SiteStat(domain, vs.size, vs.map { it.uid }.toSet().size, total, total / vs.size, vs.maxOf { it.visitedAt })
        }

    private fun sessionStats(rows: List<Sess>): SessionStat {
        val total = rows.sumOf { it.durationMs }
        return SessionStat(rows.size, total, if (rows.isEmpty()) 0 else total / rows.size)
    }

    private fun rangeBounds(r: DateRange): Pair<Long, Long> {
        val to = System.currentTimeMillis()
        val from = when (r) {
            DateRange.Today -> Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            DateRange.D7 -> to - 7 * 86400000L
            DateRange.D30 -> to - 30 * 86400000L
            DateRange.D90 -> to - 90 * 86400000L
        }
        return from to to
    }

    suspend fun overview(r: DateRange): Overview {
        val (from, to) = rangeBounds(r)
        val tz = TimeZone.getDefault().getOffset(to) / 60000
        val us = users(force = true)
        val (visits, sess) = coroutineScope {
            val v = async { rangeDocs("visits", "visitedAt", from, to, us) { uid, d -> Visit(uid, d.getString("domain").orEmpty(), d.getString("url").orEmpty(), d.getString("title").orEmpty(), d.get("visitedAt").l(), d.get("durationMs").l()) } }
            val s = async { rangeDocs("sessions", "startedAt", from, to, us) { uid, d -> Sess(uid, d.get("startedAt").l(), d.get("durationMs").l()) } }
            v.await() to s.await()
        }
        val sites = domainMap(visits).sortedByDescending { it.visits }
        val daily = visits.groupBy { day(it.visitedAt, tz) }.map { (k, vs) -> DayStat(k, vs.size, vs.map { it.uid }.toSet().size) }.sortedBy { it.day }
        val byHour = visits.groupingBy { Instant.ofEpochMilli(it.visitedAt + tz * 60000L).atZone(ZoneOffset.UTC).hour }.eachCount()
        return Overview(
            visits = visits.size, activeUsers = visits.map { it.uid }.toSet().size, domains = sites.size, totalMs = visits.sumOf { it.durationMs },
            sessions = sessionStats(sess), totalUsers = us.size, newUsers = us.count { it.createdAt in from..to },
            daily = daily, topSites = sites.take(10), hourly = (0..23).map { HourStat(it, byHour[it] ?: 0) },
            recentUsers = us.sortedByDescending { it.lastSeen }.take(5),
        )
    }
}
