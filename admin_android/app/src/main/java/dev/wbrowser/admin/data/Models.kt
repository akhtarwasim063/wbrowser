package dev.wbrowser.admin.data

data class Me(val uid: String, val email: String, val name: String, val role: String, val permissions: Set<String>) {
    fun can(p: String) = p in permissions
}

data class UserRow(
    val uid: String, val name: String, val code: String, val email: String?,
    val lastSeen: Long, val online: Boolean, val createdAt: Long,
)

data class Presence(val online: Boolean, val lastSeen: Long)

data class Visit(val uid: String, val domain: String, val url: String, val title: String, val visitedAt: Long, val durationMs: Long)
data class Sess(val uid: String, val startedAt: Long, val durationMs: Long)

data class SiteStat(val domain: String, val visits: Int, val users: Int, val totalMs: Long, val avgMs: Long, val lastVisit: Long)
data class DayStat(val day: String, val visits: Int, val users: Int)
data class HourStat(val hour: Int, val visits: Int)
data class SessionStat(val n: Int, val totalMs: Long, val avgMs: Long)

data class Overview(
    val visits: Int, val activeUsers: Int, val domains: Int, val totalMs: Long,
    val sessions: SessionStat, val totalUsers: Int, val newUsers: Int,
    val daily: List<DayStat>, val topSites: List<SiteStat>, val hourly: List<HourStat>,
    val recentUsers: List<UserRow>,
)

// ---------- websites / sessions ----------
data class PageStat(val url: String, val title: String, val visits: Int, val users: Int, val totalMs: Long, val lastVisit: Long)
data class TimelineRow(val uid: String, val user: String, val url: String, val title: String, val visitedAt: Long, val durationMs: Long)
data class SiteUser(val uid: String, val name: String, val visits: Int, val totalMs: Long, val lastVisit: Long)
data class SiteDetail(
    val domain: String, val visits: Int, val pages: Int, val users: Int, val totalMs: Long,
    val pageStats: List<PageStat>, val timeline: List<TimelineRow>, val byUser: List<SiteUser>,
)
data class SessionDay(val day: String, val sessions: Int, val totalMs: Long, val avgMs: Long)

// ---------- users ----------
data class UserFull(
    val uid: String, val name: String, val code: String, val email: String?, val googleName: String?,
    val createdAt: Long, val lastSeen: Long, val online: Boolean, val status: String,
    val searchConsent: Boolean, val activityConsent: Boolean,
    val deviceModel: String?, val manufacturer: String?, val os: String?, val sdk: Long?, val appVersion: String?,
    val locale: String?, val timezone: String?, val nameSource: String?,
)
data class UserSummary(val user: UserFull, val visits: Int, val totalMs: Long, val sessionMs: Long)
data class VisitRow(val id: String, val url: String, val domain: String, val title: String, val visitedAt: Long, val durationMs: Long)
data class SessRow(val id: String, val startedAt: Long, val endedAt: Long, val durationMs: Long)
data class SearchRow(val id: String, val query: String, val engine: String, val at: Long)
data class UserDetail(
    val user: UserFull, val visits: List<VisitRow>, val sessions: List<SessRow>, val searches: List<SearchRow>,
    val domains: Int, val totalMs: Long, val sessionStat: SessionStat, val topSites: List<SiteStat>, val daily: List<DayStat>,
)

// ---------- management ----------
data class AdminRow(val name: String, val email: String, val role: String, val updatedAt: Long)
data class AuditRow(val at: Long, val admin: String, val action: String, val target: String, val detail: String)
data class ServerInfo(val users: Long, val visits: Long, val searches: Long, val sessions: Long, val admins: Long, val projectId: String)
data class AppConfig(
    val trackingEnabled: Boolean, val searchAllowed: Boolean, val uploadIntervalSeconds: Int, val retentionDays: Int,
    val announcement: String, val updatedAt: Long, val updatedBy: String,
)

enum class DateRange(val label: String) { Today("Today"), D7("7 days"), D30("30 days"), D90("90 days"), Custom("Custom") }

/** A preset, or (for Custom) an explicit [from, to] in epoch millis. */
data class Period(val preset: DateRange = DateRange.D7, val from: Long = 0L, val to: Long = 0L)

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}
