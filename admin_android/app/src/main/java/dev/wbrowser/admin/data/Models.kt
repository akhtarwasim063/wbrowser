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

enum class DateRange(val label: String) { Today("Today"), D7("7 days"), D30("30 days"), D90("90 days") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}
