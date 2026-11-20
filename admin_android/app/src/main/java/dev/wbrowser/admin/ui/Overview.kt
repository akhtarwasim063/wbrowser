package dev.wbrowser.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.data.DateRange
import dev.wbrowser.admin.data.Load
import dev.wbrowser.admin.data.Overview
import dev.wbrowser.admin.data.Presence
import dev.wbrowser.admin.data.UserRow

/** Overview tab content, emitted into the shared LazyColumn of the screen scaffold. */
fun LazyListScope.overviewContent(
    state: Load<Overview>, range: DateRange, onRange: (DateRange) -> Unit, presence: Map<String, Presence>,
) {
    item("range") { Reveal(0) { RangeChips(range, onRange) } }

    when (state) {
        is Load.Loading -> {
            items(5) { i -> Box(Modifier.fillMaxWidth().height(if (i == 0) 118.dp else 190.dp).shimmer()) }
        }
        is Load.Failed -> item("err") {
            AdminCard { Column { CardTitle("Could not load"); Text(state.message, color = palette().bad) } }
        }
        is Load.Ready -> {
            val d = state.data
            val kpis = listOf(
                Kpi("users", Tone.Pri, d.totalUsers.toLong(), ::fmtL, "Registered users"),
                Kpi("activity", Tone.Ok, d.activeUsers.toLong(), ::fmtL, "Active users, ${rangeLabel(range)}"),
                Kpi("globe", Tone.Cyan, d.visits.toLong(), ::fmtL, "Page visits"),
                Kpi("layers", Tone.Violet, d.domains.toLong(), ::fmtL, "Websites visited"),
                Kpi("clock", Tone.Warn, d.totalMs, ::dur, "Total browsing time"),
                Kpi("clock", Tone.Ok, d.sessions.avgMs, ::dur, "Avg session duration"),
                Kpi("list", Tone.Cyan, d.sessions.n.toLong(), ::fmtL, "Sessions"),
                Kpi("userplus", Tone.Violet, d.newUsers.toLong(), ::fmtL, "New users"),
            )
            kpis.chunked(2).forEachIndexed { row, pair ->
                item("kpi$row") {
                    Reveal(row + 1) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { k -> KpiCard(k.icon, k.tone, k.value, k.format, k.label, Modifier.weight(1f)) }
                        }
                    }
                }
            }
            item("visits") {
                Reveal { AdminCard { Column { CardTitle("Visits per day", "Pages opened across all users"); AreaChart(d.daily.map { it.day }, d.daily.map { it.visits }, palette().pri) } } }
            }
            item("active") {
                Reveal { AdminCard { Column { CardTitle("Active users per day", "Distinct people browsing"); AreaChart(d.daily.map { it.day }, d.daily.map { it.users }, palette().cyan) } } }
            }
            item("sites") {
                Reveal { AdminCard { Column { CardTitle("Top websites", "Most visited in this period"); SiteBars(d.topSites) } } }
            }
            item("hours") {
                Reveal { AdminCard { Column { CardTitle("Activity by hour", "When people browse (your local time)"); ColumnChart(d.hourly.map { it.visits }, { "${it}h" }, palette().violet) } } }
            }
            item("recent") {
                Reveal { AdminCard { Column { CardTitle("Recently active"); RecentUsers(d.recentUsers, presence) } } }
            }
        }
    }
}

private data class Kpi(val icon: String, val tone: Tone, val value: Long, val format: (Long) -> String, val label: String)

private fun fmtL(n: Long) = fmt(n)

private fun rangeLabel(r: DateRange) = when (r) {
    DateRange.Today -> "today"
    DateRange.D7 -> "last 7 days"
    DateRange.D30 -> "last 30 days"
    DateRange.D90 -> "last 90 days"
}

@Composable
private fun RecentUsers(users: List<UserRow>, presence: Map<String, Presence>) {
    val p = palette()
    if (users.isEmpty()) { Empty("No users yet."); return }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        users.forEach { u ->
            val live = presence[u.uid]
            val online = live?.online ?: u.online
            val lastSeen = live?.lastSeen ?: u.lastSeen
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(u.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(u.name, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                    Text(u.code, color = p.mut, fontSize = 12.sp, maxLines = 1)
                }
                if (online) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(p.ok))
                        Spacer(Modifier.width(6.dp))
                        Pill("online", p.ok, p.okSoft)
                    }
                } else Pill(ago(lastSeen), p.mut, p.soft)
            }
        }
    }
}
