package dev.wbrowser.admin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.AdminViewModel
import dev.wbrowser.admin.Route
import dev.wbrowser.admin.data.SiteStat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** Opens http(s) links in the phone's browser (URLs are untrusted data, so nothing else is ever opened). */
@Composable
fun rememberOpenLink(): (String) -> Unit {
    val ctx = LocalContext.current
    return { url -> if (url.startsWith("http://") || url.startsWith("https://")) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

// ---------- Websites ----------
@Composable
fun WebsitesScreen(vm: AdminViewModel) {
    val range by vm.range.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    DataScreen(
        title = "Websites", subtitle = "Tap a website to see every page opened on it", keys = listOf(range), fetch = { vm.repo.websites(range) },
        header = {
            rangeBar(range, vm::selectRange)
            item("search") { SearchField(q, { q = it }, "Filter websites…") }
            item("sort") { Segmented(listOf(0, 1), sort, { if (it == 0) "Most visits" else "Most time" }, { sort = it }) }
        },
    ) { sites, _ ->
        val rows = sites.filter { it.domain.contains(q, ignoreCase = true) }.sortedByDescending { if (sort == 0) it.visits.toLong() else it.totalMs }
        if (rows.isEmpty()) item("none") { Empty("Nothing to show.") }
        itemsIndexed(rows, key = { _, s -> s.domain }) { i, s -> Reveal(i) { TapCard({ vm.push(Route.Website(s.domain)) }) { SiteRow(s) } } }
    }
}

@Composable
private fun SiteRow(s: SiteStat) {
    val p = palette()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.domain, 32.dp, square = true, text = s.domain.take(1).uppercase())
            Spacer(Modifier.width(10.dp))
            Text(s.domain, color = p.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Ico("chev", p.mut, 18.dp)
        }
        StatGrid(listOf("Visits" to fmt(s.visits), "Users" to fmt(s.users), "Total time" to dur(s.totalMs), "Avg / visit" to dur(s.avgMs), "Last visit" to ago(s.lastVisit)))
    }
}

// ---------- one website ----------
@Composable
fun WebsiteDetailScreen(vm: AdminViewModel, domain: String) {
    val range by vm.range.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val open = rememberOpenLink()
    DataScreen(
        title = domain, subtitle = "Every page opened on this website", keys = listOf(range, domain), fetch = { vm.repo.websiteDetail(domain, range) },
        onBack = vm::pop, header = { rangeBar(range, vm::selectRange) },
    ) { d, _ ->
        val p = palette()
        item("he ro") {
            Reveal {
                HeroCard({ Avatar(domain, 52.dp, square = true, text = domain.take(1).uppercase()) }, domain, "Opened ${fmt(d.visits)} times in the ${rangeLabel(range)}") {
                    GlassButton("Open $domain", "globe") { open("https://$domain") }
                }
            }
        }
        item("kpi1") { Reveal(1) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("globe", Tone.Cyan, d.visits.toLong(), { fmt(it) }, "Visits", Modifier.weight(1f))
            KpiCard("list", Tone.Violet, d.pages.toLong(), { fmt(it) }, "Different pages", Modifier.weight(1f)) } } }
        item("kpi2") { Reveal(2) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("users", Tone.Pri, d.users.toLong(), { fmt(it) }, "Users", Modifier.weight(1f))
            KpiCard("clock", Tone.Warn, d.totalMs, ::dur, "Time spent", Modifier.weight(1f)) } } }
        item("tabs") { Segmented(listOf(0, 1, 2), tab, { listOf("Pages", "Timeline", "Users")[it] }, { tab = it }) }
        when (tab) {
            0 -> {
                if (d.pageStats.isEmpty()) item("e0") { Empty("Nothing to show.") }
                itemsIndexed(d.pageStats.take(100), key = { i, s -> "p$i${s.url}" }) { i, s ->
                    Reveal(i) { AdminCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column {
                            Text(s.title.ifEmpty { s.url }, color = p.pri, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { open(s.url) })
                            Text(s.url, color = p.mut, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StatGrid(listOf("Visits" to fmt(s.visits), "Users" to fmt(s.users), "Time" to dur(s.totalMs), "Last opened" to ago(s.lastVisit)), columns = 4)
                    } } }
                }
            }
            1 -> {
                if (d.timeline.isEmpty()) item("e1") { Empty("Nothing to show.") }
                itemsIndexed(d.timeline, key = { i, t -> "t$i${t.visitedAt}" }) { i, t ->
                    Reveal(i) { AdminCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t.title.ifEmpty { t.url }, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(t.url, color = p.mut, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatGrid(listOf("When" to dt(t.visitedAt), "User" to t.user, "Time spent" to dur(t.durationMs)))
                    } } }
                }
            }
            else -> {
                if (d.byUser.isEmpty()) item("e2") { Empty("Nothing to show.") }
                itemsIndexed(d.byUser, key = { _, u -> "u${u.uid}" }) { i, u ->
                    Reveal(i) { TapCard({ vm.push(Route.User(u.uid)) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.name); Spacer(Modifier.width(10.dp))
                            Text(u.name, color = p.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Ico("chev", p.mut, 18.dp)
                        }
                        StatGrid(listOf("Visits" to fmt(u.visits), "Time" to dur(u.totalMs), "Last visit" to ago(u.lastVisit)))
                    } } }
                }
            }
        }
    }
}

// ---------- Sessions ----------
@Composable
fun SessionsScreen(vm: AdminViewModel) {
    val range by vm.range.collectAsState()
    DataScreen(
        title = "Sessions", subtitle = "How often and how long the browser is used", keys = listOf(range), fetch = { vm.repo.sessions(range) },
        onBack = vm::pop, header = { rangeBar(range, vm::selectRange) },
    ) { days, _ ->
        val p = palette()
        item("chart") { Reveal { AdminCard { Column { CardTitle("Sessions per day", "Each time someone opens the browser"); AreaChart(days.map { it.day }, days.map { it.sessions }, p.pri) } } } }
        if (days.isEmpty()) item("none") { Empty("Nothing to show.") }
        itemsIndexed(days.reversed(), key = { _, d -> d.day }) { i, d ->
            Reveal(i) { AdminCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.day, color = p.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                StatGrid(listOf("Sessions" to fmt(d.sessions), "Avg duration" to dur(d.avgMs), "Total time" to dur(d.totalMs)))
            } } }
        }
    }
}
