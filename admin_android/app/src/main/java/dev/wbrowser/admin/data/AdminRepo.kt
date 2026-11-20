package dev.wbrowser.admin.data

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.AggregateSource
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
 * the same per-user queries, aggregation and actions, so numbers match the web console.
 * What an admin may read or delete is enforced by firestore.rules, not here.
 */
class AdminRepo {
    class Forbidden : Exception("forbidden")
    class Protected : Exception("Admin accounts can never be erased from here.")
    class Weak : Exception("The new password needs at least 12 characters.")

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private var adminEmail = ""

    private val perms = mapOf(
        "superadmin" to setOf("aggregate", "user_list", "user_detail", "export", "manage_users", "manage_admins", "audit", "delete_data", "server_info", "manage_config"),
        "analyst" to setOf("aggregate", "user_list", "user_detail", "export", "server_info"),
    )

    private suspend fun loadMe(u: FirebaseUser, force: Boolean): Me {
        val role = u.getIdToken(force).await().claims["role"] as? String
        val p = perms[role]
        if (role == null || p == null) { auth.signOut(); throw Forbidden() }
        adminEmail = u.email.orEmpty()
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

    suspend fun changePassword(current: String, next: String) {
        val u = auth.currentUser ?: error("Not signed in")
        u.reauthenticate(EmailAuthProvider.getCredential(adminEmail, current)).await()
        if (next.length < 12) throw Weak()
        u.updatePassword(next).await()
    }

    private suspend fun audit(action: String, target: String, detail: String = "") {
        runCatching {
            db.collection("audit").add(mapOf("at" to System.currentTimeMillis(), "by" to adminEmail, "action" to action, "target" to target, "detail" to detail.take(300))).await()
        }
    }

    // ---------- helpers ----------
    private var usersCache: Pair<Long, List<UserRow>>? = null
    private fun Any?.l(): Long = (this as? Number)?.toLong() ?: 0L

    private fun userRow(d: DocumentSnapshot) = UserRow(
        uid = d.id, name = d.getString("name") ?: "Unknown", code = d.getString("userId") ?: d.id,
        email = d.getString("email"), lastSeen = d.get("lastSeen").l(), online = d.getBoolean("online") == true,
        createdAt = d.get("createdAt").l(),
    )

    private fun userFull(d: DocumentSnapshot): UserFull {
        @Suppress("UNCHECKED_CAST") val dev = d.get("device") as? Map<String, Any?>
        return UserFull(
            uid = d.id, name = d.getString("name") ?: "Unknown", code = d.getString("userId") ?: d.id, email = d.getString("email"),
            googleName = d.getString("googleName"), createdAt = d.get("createdAt").l(), lastSeen = d.get("lastSeen").l(),
            online = d.getBoolean("online") == true, status = d.getString("status") ?: "active",
            searchConsent = d.getBoolean("searchConsent") == true, activityConsent = d.getBoolean("activityConsent") == true,
            deviceModel = dev?.get("model") as? String, manufacturer = dev?.get("manufacturer") as? String, os = dev?.get("os") as? String,
            sdk = (dev?.get("sdk") as? Number)?.toLong(), appVersion = dev?.get("appVersion") as? String,
            locale = d.getString("locale"), timezone = d.getString("timezone"), nameSource = d.getString("nameSource"),
        )
    }

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

    private fun visitOf(uid: String, d: DocumentSnapshot) =
        Visit(uid, d.getString("domain").orEmpty(), d.getString("url").orEmpty(), d.getString("title").orEmpty(), d.get("visitedAt").l(), d.get("durationMs").l())

    private suspend fun allVisits(from: Long, to: Long, us: List<UserRow>) = rangeDocs("visits", "visitedAt", from, to, us, ::visitOf)
    private suspend fun allSessions(from: Long, to: Long, us: List<UserRow>) =
        rangeDocs("sessions", "startedAt", from, to, us) { uid, d -> Sess(uid, d.get("startedAt").l(), d.get("durationMs").l()) }

    private fun day(ms: Long, tz: Int) = Instant.ofEpochMilli(ms + tz * 60000L).atZone(ZoneOffset.UTC).toLocalDate().toString()
    private fun tzMin(at: Long) = TimeZone.getDefault().getOffset(at) / 60000

    private fun domainMap(visits: List<Visit>): List<SiteStat> =
        visits.groupBy { it.domain }.map { (domain, vs) ->
            val total = vs.sumOf { it.durationMs }
            SiteStat(domain, vs.size, vs.map { it.uid }.toSet().size, total, total / vs.size, vs.maxOf { it.visitedAt })
        }

    private fun sessionStats(durations: List<Long>): SessionStat {
        val total = durations.sum()
        return SessionStat(durations.size, total, if (durations.isEmpty()) 0 else total / durations.size)
    }

    private fun rangeBounds(p: Period): Pair<Long, Long> {
        if (p.preset == DateRange.Custom) return p.from to p.to
        val to = System.currentTimeMillis()
        val from = when (p.preset) {
            DateRange.Today -> Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            DateRange.D7 -> to - 7 * 86400000L
            DateRange.D30 -> to - 30 * 86400000L
            DateRange.D90 -> to - 90 * 86400000L
            DateRange.Custom -> p.from
        }
        return from to to
    }

    // ---------- aggregates ----------
    suspend fun overview(r: Period): Overview {
        val (from, to) = rangeBounds(r)
        val tz = tzMin(to)
        val us = users(force = true)
        val (visits, sess) = coroutineScope {
            val v = async { allVisits(from, to, us) }
            val s = async { allSessions(from, to, us) }
            v.await() to s.await()
        }
        val sites = domainMap(visits).sortedByDescending { it.visits }
        val daily = visits.groupBy { day(it.visitedAt, tz) }.map { (k, vs) -> DayStat(k, vs.size, vs.map { it.uid }.toSet().size) }.sortedBy { it.day }
        val byHour = visits.groupingBy { Instant.ofEpochMilli(it.visitedAt + tz * 60000L).atZone(ZoneOffset.UTC).hour }.eachCount()
        return Overview(
            visits = visits.size, activeUsers = visits.map { it.uid }.toSet().size, domains = sites.size, totalMs = visits.sumOf { it.durationMs },
            sessions = sessionStats(sess.map { it.durationMs }), totalUsers = us.size, newUsers = us.count { it.createdAt in from..to },
            daily = daily, topSites = sites.take(10), hourly = (0..23).map { HourStat(it, byHour[it] ?: 0) },
            recentUsers = us.sortedByDescending { it.lastSeen }.take(5),
        )
    }

    suspend fun websites(r: Period): List<SiteStat> {
        val (from, to) = rangeBounds(r)
        return domainMap(allVisits(from, to, users(force = true))).sortedByDescending { it.visits }.take(200)
    }

    suspend fun websiteDetail(domain: String, r: Period): SiteDetail {
        val (from, to) = rangeBounds(r)
        val us = users(force = true)
        val names = us.associate { it.uid to it.name }
        val mine = allVisits(from, to, us).filter { it.domain == domain.lowercase() }
        val pages = mine.groupBy { it.url }.map { (url, vs) ->
            val newest = vs.maxByOrNull { it.visitedAt }
            PageStat(url, newest?.title?.ifEmpty { null } ?: vs.firstOrNull { it.title.isNotEmpty() }?.title.orEmpty(), vs.size,
                vs.map { it.uid }.toSet().size, vs.sumOf { it.durationMs }, vs.maxOf { it.visitedAt })
        }.sortedWith(compareByDescending<PageStat> { it.visits }.thenByDescending { it.lastVisit })
        val byUser = mine.groupBy { it.uid }.map { (uid, vs) ->
            SiteUser(uid, names[uid] ?: "Unknown", vs.size, vs.sumOf { it.durationMs }, vs.maxOf { it.visitedAt })
        }.sortedByDescending { it.visits }
        return SiteDetail(
            domain, mine.size, pages.size, byUser.size, mine.sumOf { it.durationMs }, pages,
            mine.sortedByDescending { it.visitedAt }.take(300).map { TimelineRow(it.uid, names[it.uid] ?: "Unknown", it.url, it.title, it.visitedAt, it.durationMs) },
            byUser,
        )
    }

    suspend fun sessions(r: Period): List<SessionDay> {
        val (from, to) = rangeBounds(r)
        val tz = tzMin(to)
        return allSessions(from, to, users(force = true)).groupBy { day(it.startedAt, tz) }.map { (k, ss) ->
            val total = ss.sumOf { it.durationMs }
            SessionDay(k, ss.size, total, total / ss.size)
        }.sortedBy { it.day }
    }

    // ---------- users ----------
    suspend fun usersList(r: Period): List<UserSummary> {
        val (from, to) = rangeBounds(r)
        val docs = db.collection("users").get().await().documents
        val rows = docs.map(::userRow)
        val (visits, sess) = coroutineScope {
            val v = async { allVisits(from, to, rows) }
            val s = async { allSessions(from, to, rows) }
            v.await() to s.await()
        }
        val vBy = visits.groupBy { it.uid }
        val sBy = sess.groupBy { it.uid }
        return docs.map { d ->
            val u = userFull(d)
            UserSummary(u, vBy[u.uid]?.size ?: 0, vBy[u.uid]?.sumOf { it.durationMs } ?: 0, sBy[u.uid]?.sumOf { it.durationMs } ?: 0)
        }.sortedByDescending { it.user.lastSeen }
    }

    suspend fun userDetail(uid: String, r: Period): UserDetail {
        val (from, to) = rangeBounds(r)
        val tz = tzMin(to)
        val snap = db.collection("users").document(uid).get().await()
        if (!snap.exists()) error("User not found")
        suspend fun inRange(col: String, field: String) = db.collection("users").document(uid).collection(col)
            .whereGreaterThanOrEqualTo(field, from).whereLessThanOrEqualTo(field, to)
            .orderBy(field, Query.Direction.DESCENDING).limit(5000).get().await().documents
        val (vs, ss, se) = coroutineScope {
            val a = async { inRange("visits", "visitedAt") }
            val b = async { inRange("sessions", "startedAt") }
            val c = async { runCatching { inRange("searches", "at") }.getOrDefault(emptyList()) }
            Triple(a.await(), b.await(), c.await())
        }
        val visits = vs.map { visitOf(uid, it) }
        val sites = domainMap(visits)
        audit("view_user", uid)
        return UserDetail(
            user = userFull(snap),
            visits = vs.map { VisitRow(it.id, it.getString("url").orEmpty(), it.getString("domain").orEmpty(), it.getString("title").orEmpty(), it.get("visitedAt").l(), it.get("durationMs").l()) },
            sessions = ss.map { SessRow(it.id, it.get("startedAt").l(), it.get("endedAt").l(), it.get("durationMs").l()) },
            searches = se.map { SearchRow(it.id, it.getString("query").orEmpty(), it.getString("engine").orEmpty(), it.get("at").l()) },
            domains = sites.size, totalMs = visits.sumOf { it.durationMs }, sessionStat = sessionStats(ss.map { it.get("durationMs").l() }),
            topSites = sites.sortedByDescending { it.totalMs }.take(10),
            daily = visits.groupBy { day(it.visitedAt, tz) }.map { (k, v) -> DayStat(k, v.size, 1) }.sortedBy { it.day },
        )
    }

    private suspend fun wipeSub(uid: String, name: String): Int {
        var n = 0
        while (true) {
            val snap = db.collection("users").document(uid).collection(name).limit(400).get().await()
            if (snap.isEmpty) break
            val b = db.batch(); snap.documents.forEach { b.delete(it.reference) }; b.commit().await(); n += snap.size()
            if (snap.size() < 400) break
        }
        return n
    }

    suspend fun setStatus(uid: String, status: String) {
        db.collection("users").document(uid).update("status", status).await(); audit("set_user_status", uid, status)
    }

    suspend fun eraseUser(uid: String) {
        if (db.collection("admins").document(uid).get().await().exists()) throw Protected()
        listOf("visits", "searches", "sessions").forEach { wipeSub(uid, it) }
        db.collection("users").document(uid).delete().await(); audit("erase_user", uid); usersCache = null
    }

    suspend fun clearHistory(uid: String): Int { val n = wipeSub(uid, "visits"); wipeSub(uid, "sessions"); audit("clear_user_history", uid, "$n visits"); return n }
    suspend fun clearSearches(uid: String): Int { val n = wipeSub(uid, "searches"); audit("clear_user_searches", uid, "$n searches"); return n }
    suspend fun deleteVisit(uid: String, id: String) { db.collection("users").document(uid).collection("visits").document(id).delete().await(); audit("delete_visit", uid, id) }
    /** Deletes many entries in batches of 400 (multi-select delete). */
    private suspend fun deleteMany(uid: String, col: String, ids: List<String>) {
        ids.chunked(400).forEach { chunk ->
            val b = db.batch(); chunk.forEach { b.delete(db.collection("users").document(uid).collection(col).document(it)) }; b.commit().await()
        }
    }
    suspend fun deleteVisits(uid: String, ids: List<String>) { deleteMany(uid, "visits", ids); audit("delete_visits", uid, "${ids.size} entries") }
    suspend fun deleteSearches(uid: String, ids: List<String>) { deleteMany(uid, "searches", ids); audit("delete_searches", uid, "${ids.size} entries") }
    suspend fun deleteSearch(uid: String, id: String) { db.collection("users").document(uid).collection("searches").document(id).delete().await(); audit("delete_search", uid, id) }

    private fun csvCell(v: Any?): String {
        var s = v?.toString() ?: ""
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it in "\",\n\r" }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    suspend fun exportCsv(uid: String, r: Period): String {
        val (from, to) = rangeBounds(r)
        val lines = mutableListOf("visited_at_iso,url,domain,title,duration_seconds")
        var last: DocumentSnapshot? = null
        var page = 0
        while (page < 20) {   // up to 100,000 rows, 5,000 per request
            var q = db.collection("users").document(uid).collection("visits")
                .whereGreaterThanOrEqualTo("visitedAt", from).whereLessThanOrEqualTo("visitedAt", to).orderBy("visitedAt", Query.Direction.DESCENDING)
            last?.let { q = q.startAfter(it) }
            val snap = q.limit(5000).get().await()
            snap.documents.forEach { d ->
                lines += listOf(Instant.ofEpochMilli(d.get("visitedAt").l()).toString(), d.getString("url"), d.getString("domain"), d.getString("title"), Math.round(d.get("durationMs").l() / 1000.0))
                    .joinToString(",") { csvCell(it) }
            }
            if (snap.size() < 5000) break
            last = snap.documents.last(); page++
        }
        audit("export_history", uid)
        return lines.joinToString("\n")
    }

    // ---------- management ----------
    suspend fun admins(): List<AdminRow> =
        db.collection("admins").get().await().documents.map { AdminRow(it.getString("name").orEmpty(), it.getString("email").orEmpty(), it.getString("role").orEmpty(), it.get("updatedAt").l()) }

    suspend fun auditLog(): List<AuditRow> =
        db.collection("audit").orderBy("at", Query.Direction.DESCENDING).limit(200).get().await().documents
            .map { AuditRow(it.get("at").l(), it.getString("by").orEmpty(), it.getString("action").orEmpty(), it.getString("target").orEmpty(), it.getString("detail").orEmpty()) }

    private suspend fun count(q: Query): Long = q.count().get(AggregateSource.SERVER).await().count

    suspend fun serverInfo(): ServerInfo {
        val us = users(force = true)
        val (v, s, se) = coroutineScope {
            fun total(name: String) = async { us.map { u -> async { count(db.collection("users").document(u.uid).collection(name)) } }.awaitAll().sum() }
            val a = total("visits"); val b = total("searches"); val c = total("sessions")
            Triple(a.await(), b.await(), c.await())
        }
        return ServerInfo(count(db.collection("users")), v, s, se, count(db.collection("admins")), db.app.options.projectId.orEmpty())
    }

    suspend fun config(): AppConfig {
        val d = db.collection("config").document("app").get().await()
        return AppConfig(
            trackingEnabled = d.getBoolean("trackingEnabled") != false, searchAllowed = d.getBoolean("searchAllowed") != false,
            uploadIntervalSeconds = (d.get("uploadIntervalSeconds") as? Number)?.toInt() ?: 15, retentionDays = (d.get("retentionDays") as? Number)?.toInt() ?: 180,
            announcement = d.getString("announcement").orEmpty(), updatedAt = d.get("updatedAt").l(), updatedBy = d.getString("updatedBy").orEmpty(),
        )
    }

    suspend fun saveConfig(c: AppConfig) {
        val data = mapOf(
            "trackingEnabled" to c.trackingEnabled, "searchAllowed" to c.searchAllowed,
            "uploadIntervalSeconds" to c.uploadIntervalSeconds.coerceIn(5, 600), "retentionDays" to c.retentionDays.coerceIn(1, 3650),
            "announcement" to c.announcement.take(200), "updatedAt" to System.currentTimeMillis(), "updatedBy" to adminEmail,
        )
        db.collection("config").document("app").set(data).await(); audit("update_config", "config/app", data.toString().take(280))
    }

    /** Deletes everything older than [days] for every user. Returns visits/sessions/searches deleted. */
    suspend fun purge(days: Int): Triple<Int, Int, Int> {
        val cutoff = System.currentTimeMillis() - days * 86400000L
        val out = intArrayOf(0, 0, 0)
        for (u in users(force = true)) {
            listOf("visits" to "visitedAt", "sessions" to "startedAt", "searches" to "at").forEachIndexed { i, (col, field) ->
                while (true) {
                    val snap = db.collection("users").document(u.uid).collection(col).whereLessThan(field, cutoff).limit(400).get().await()
                    if (snap.isEmpty) break
                    val b = db.batch(); snap.documents.forEach { b.delete(it.reference) }; b.commit().await(); out[i] += snap.size()
                    if (snap.size() < 400) break
                }
            }
        }
        audit("purge_older_than", "$days days", out.joinToString()); return Triple(out[0], out[1], out[2])
    }

    /** scope: "history", "searches" or "all". */
    suspend fun clearAll(scope: String): String {
        var v = 0; var s = 0; var q = 0
        for (u in users(force = true)) {
            if (scope == "history" || scope == "all") { v += wipeSub(u.uid, "visits"); s += wipeSub(u.uid, "sessions") }
            if (scope == "searches" || scope == "all") q += wipeSub(u.uid, "searches")
        }
        val msg = "visits $v, sessions $s, searches $q"
        audit("clear_all_data", scope, msg); return msg
    }
}
