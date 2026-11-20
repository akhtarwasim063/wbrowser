package dev.wbrowser.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.AdminViewModel
import dev.wbrowser.admin.Route
import dev.wbrowser.admin.data.DateRange
import dev.wbrowser.admin.data.Presence
import dev.wbrowser.admin.data.UserRow

/** The date filter shared by every analytics screen. */
fun LazyListScope.rangeBar(range: DateRange, onRange: (DateRange) -> Unit) {
    item("range") { Reveal(0) { Segmented(DateRange.entries, range, { it.label }, onRange) } }
}

private data class Kpi(val icon: String, val tone: Tone, val value: Long, val format: (Long) -> String, val label: String)

private fun fmtL(n: Long) = fmt(n)

fun rangeLabel(r: DateRange) = when (r) {
    DateRange.Today -> "today"
    DateRange.D7 -> "last 7 days"
    DateRange.D30 -> "last 30 days"
    DateRange.D90 -> "last 90 days"
}

@Composable
fun OverviewScreen(vm: AdminViewModel) {
    val range by vm.range.collectAsState()
    val presence by vm.presence.collectAsState()
    DataScreen(
        title = "Overview", subtitle = "Browsing activity across all users", keys = listOf(range), fetch = { vm.repo.overview(range) },
        header = { rangeBar(range, vm::selectRange) },
    ) { d, _ ->
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { k -> KpiCard(k.icon, k.tone, k.value, k.format, k.label, Modifier.weight(1f)) }
                    }
                }
            }
        }
        item("sessions") {
            Reveal { TapCard({ vm.push(Route.Sessions) }) { NavRowStatic("clock", "Sessions", "${fmt(d.sessions.n)} sessions, ${dur(d.sessions.avgMs)} on average") } }
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

/** NavRow look without its own click handling (the whole card is the tap target). */
@Composable
fun NavRowStatic(icon: String, title: String, hint: String) {
    val p = palette()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(p.priSoft), contentAlignment = Alignment.Center) { Ico(icon, p.pri, 17.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(hint, color = p.mut, fontSize = 11.sp, maxLines = 1)
        }
        Ico("chev", p.mut, 18.dp)
    }
}

@Composable
private fun RecentUsers(users: List<UserRow>, presence: Map<String, Presence>) {
    val p = palette()
    if (users.isEmpty()) { Empty("No users yet."); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
