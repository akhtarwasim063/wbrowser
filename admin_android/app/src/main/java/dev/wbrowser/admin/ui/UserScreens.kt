package dev.wbrowser.admin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.AdminViewModel
import dev.wbrowser.admin.Route
import dev.wbrowser.admin.data.Me
import dev.wbrowser.admin.data.Presence
import dev.wbrowser.admin.data.UserFull
import dev.wbrowser.admin.data.UserSummary
import kotlinx.coroutines.launch

private const val PAGE = 25

// ---------- Users list ----------
@Composable
fun UsersScreen(vm: AdminViewModel) {
    val range by vm.range.collectAsState()
    val presence by vm.presence.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    DataScreen(
        title = "Users", subtitle = "Everyone who installed the app", keys = listOf(range), fetch = { vm.repo.usersList(range) },
        header = {
            rangeBar(vm)
            item("search") { SearchField(q, { q = it; page = 1 }, "Search name, ID or email…") }
        },
    ) { all, _ ->
        val f = all.filter { s -> q.isEmpty() || listOf(s.user.name, s.user.code, s.user.email).any { it?.contains(q, ignoreCase = true) == true } }
        if (f.isEmpty()) item("none") { Empty("Nothing to show.") }
        itemsIndexed(f.drop((page - 1) * PAGE).take(PAGE), key = { _, s -> s.user.uid }) { i, s ->
            Reveal(i) { TapCard({ vm.push(Route.User(s.user.uid)) }) { UserRowCard(s, presence[s.user.uid]) } }
        }
        item("pager") { Pager(page, f.size, PAGE) { page = it } }
    }
}

@Composable
private fun UserRowCard(s: UserSummary, live: Presence?) {
    val p = palette()
    val u = s.user
    val online = live?.online ?: u.online
    val seen = live?.lastSeen ?: u.lastSeen
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.name)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(u.name, color = p.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(u.email ?: u.googleName ?: u.code, color = p.mut, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (u.status == "active") Pill("Active", p.ok, p.okSoft) else Pill("Suspended", p.warn, p.warnSoft)
        }
        StatGrid(listOf(
            "User ID" to u.code, "Last seen" to (if (online) "● Online" else ago(seen)), "Visits" to fmt(s.visits),
            "Browsing" to dur(s.totalMs), "Sessions" to dur(s.sessionMs),
        ))
    }
}

// ---------- one user ----------
private sealed interface UserAction {
    data object Suspend : UserAction
    data object Erase : UserAction
    data object ClearHistory : UserAction
    data object ClearSearches : UserAction
    data class DeleteVisit(val id: String) : UserAction
    data class DeleteSearch(val id: String) : UserAction
    data class DeleteMany(val ids: List<String>, val searches: Boolean) : UserAction
}

private fun toggled(set: Set<String>, id: String) = if (id in set) set - id else set + id

@Composable
fun UserDetailScreen(vm: AdminViewModel, me: Me, uid: String) {
    val p = palette()
    val range by vm.range.collectAsState()
    val presence by vm.presence.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var action by remember { mutableStateOf<UserAction?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val open = rememberOpenLink()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val text = pendingCsv
        if (uri != null && text != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
        pendingCsv = null
    }
    var suspended by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var sheet by remember { mutableStateOf<Pair<String, String>?>(null) }
    val visible = remember { object { var ids: List<String> = emptyList() } }
    BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }

    DataScreen(
        title = "User activity", subtitle = "Profile, history, sessions and searches", keys = listOf(range, uid, tick),
        fetch = { vm.repo.userDetail(uid, range) }, onBack = vm::pop, header = { rangeBar(vm) },
        overlay = {
            AnimatedVisibility(selecting, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(180)) + slideInVertically { it }, exit = fadeOut(tween(120)) + slideOutVertically { it }) {
                SelectionBar(
                    selected.size, visible.ids.size, me.can("delete_data"),
                    onAll = { selected = if (selected.size == visible.ids.size) emptySet() else visible.ids.toSet() },
                    onDelete = { action = UserAction.DeleteMany(selected.toList(), tab == 2) },
                    onCancel = { selecting = false; selected = emptySet() },
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 78.dp),
                )
            }
        },
    ) { d, _ ->
        val u = d.user
        suspended = u.status != "active"
        val live = presence[uid]
        val online = live?.online ?: u.online
        val seen = live?.lastSeen ?: u.lastSeen

        item("hero") {
            Reveal {
                HeroCard({ Avatar(u.name, 52.dp) }, u.name, "${u.code}${u.email?.let { " · $it" } ?: ""}") {
                    Chips {
                        if (online) Pill("Online now", p.ok, p.okSoft) else Pill("Seen ${ago(seen)}", p.mut, p.soft)
                        Pill("Registered ${dt(u.createdAt)}", p.mut, p.soft)
                        Pill(deviceLine(u), p.mut, p.soft)
                        u.locale?.let { Pill(it + (u.timezone?.let { z -> " · $z" } ?: ""), p.mut, p.soft) }
                        u.nameSource?.let { Pill("Name from: " + (mapOf("typed" to "typed by user", "device-name" to "phone name", "google" to "Google account", "guest" to "auto (guest)")[it] ?: it), p.mut, p.soft) }
                        if (u.activityConsent) Pill("Activity sharing on", p.ok, p.okSoft) else Pill("Activity sharing off", p.bad, p.badSoft)
                        if (u.searchConsent) Pill("Search sharing on", p.ok, p.okSoft) else Pill("Search sharing off", p.mut, p.soft)
                        if (u.status != "active") Pill("Suspended", p.warn, p.warnSoft)
                    }
                }
            }
        }
        item("actions") {
            Reveal(1) {
                Chips {
                    if (me.can("manage_users")) {
                        GlassButton(if (u.status == "active") "Suspend uploads" else "Reactivate", "pause") { action = UserAction.Suspend }
                        GlassButton("Erase user", "trash", danger = true) { action = UserAction.Erase }
                    }
                    if (me.can("delete_data")) {
                        GlassButton("Clear history", "trash", danger = true) { action = UserAction.ClearHistory }
                        GlassButton("Clear searches", "trash", danger = true) { action = UserAction.ClearSearches }
                    }
                    if (me.can("export")) GlassButton("Export CSV", "download") {
                        scope.launch {
                            try { pendingCsv = vm.repo.exportCsv(uid, range); saver.launch("history-${u.code}.csv") }
                            catch (e: Exception) { message = "Export failed" to vm.friendly(e) }
                        }
                    }
                }
            }
        }
        item("kpi1") { Reveal(2) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("globe", Tone.Cyan, d.visits.size.toLong(), { fmt(it) }, "Visits", Modifier.weight(1f))
            KpiCard("layers", Tone.Violet, d.domains.toLong(), { fmt(it) }, "Websites", Modifier.weight(1f)) } } }
        item("kpi2") { Reveal(3) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("clock", Tone.Warn, d.totalMs, ::dur, "Browsing time", Modifier.weight(1f))
            KpiCard("list", Tone.Pri, d.sessionStat.n.toLong(), { fmt(it) }, "Sessions", Modifier.weight(1f)) } } }
        item("kpi3") { Reveal(4) { KpiCard("clock", Tone.Ok, d.sessionStat.avgMs, ::dur, "Avg session", Modifier.fillMaxWidth()) } }
        item("chart") { Reveal { AdminCard { Column { CardTitle("Visits per day"); AreaChart(d.daily.map { it.day }, d.daily.map { it.visits }, p.pri) } } } }
        item("sites") { Reveal { AdminCard { Column { CardTitle("Top sites", "By time spent"); SiteBars(d.topSites) } } } }

        item("tabs") { Segmented(listOf(0, 1, 2), tab, { listOf("History", "Sessions", "Searches")[it] }, { tab = it; page = 1; selecting = false; selected = emptySet() }) }
        if (me.can("delete_data") && tab != 1) item("hint") { Text("Tip: press and hold an entry to select or delete.", color = p.mut, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp)) }
        when (tab) {
            0 -> {
                item("hq") { SearchField(q, { q = it; page = 1 }, "Filter history by URL or title…") }
                val rows = d.visits.filter { q.isEmpty() || it.url.contains(q, true) || it.title.contains(q, true) }
                visible.ids = rows.map { it.id }
                if (rows.isEmpty()) item("h0") { Empty("Nothing to show.") }
                itemsIndexed(rows.drop((page - 1) * 50).take(50), key = { _, v -> "v${v.id}" }) { i, v ->
                    Reveal(i) {
                        SelectableCard(
                            selected = v.id in selected, selecting = selecting,
                            onClick = { if (selecting) selected = toggled(selected, v.id) else open(v.url) },
                            onLongClick = { if (selecting) selected = toggled(selected, v.id) else if (me.can("delete_data")) sheet = v.id to v.title.ifEmpty { v.domain } },
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(v.domain, 28.dp, square = true, text = v.domain.take(1).uppercase().ifEmpty { "?" })
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(v.title.ifEmpty { v.domain }, color = p.pri, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(v.url, color = p.mut, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                StatGrid(listOf("When" to dt(v.visitedAt), "Time spent" to dur(v.durationMs)), columns = 2)
                            }
                        }
                    }
                }
                item("hp") { Pager(page, rows.size, 50) { page = it } }
            }
            1 -> {
                visible.ids = emptyList()
                if (d.sessions.isEmpty()) item("s0") { Empty("Nothing to show.") }
                itemsIndexed(d.sessions.take(100), key = { _, s -> "s${s.id}" }) { i, s ->
                    Reveal(i) { AdminCard { StatGrid(listOf("Started" to dt(s.startedAt), "Ended" to dt(s.endedAt), "Duration" to dur(s.durationMs)), columns = 3) } }
                }
            }
            else -> {
                if (!u.searchConsent) item("sc") { Reveal { AdminCard { Text("This user has not enabled search-query sharing.", color = p.mut, fontSize = 13.sp) } } }
                val rows = d.searches.take(100)
                visible.ids = rows.map { it.id }
                if (rows.isEmpty()) item("q0") { Empty("Nothing to show.") }
                itemsIndexed(rows, key = { _, s -> "q${s.id}" }) { i, s ->
                    Reveal(i) {
                        SelectableCard(
                            selected = s.id in selected, selecting = selecting,
                            onClick = { if (selecting) selected = toggled(selected, s.id) else open("https://www.google.com/search?q=" + android.net.Uri.encode(s.query)) },
                            onLongClick = { if (selecting) selected = toggled(selected, s.id) else if (me.can("delete_data")) sheet = s.id to s.query },
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(s.query, color = p.pri, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                StatGrid(listOf("When" to dt(s.at), "Engine" to s.engine.ifEmpty { "—" }), columns = 2)
                            }
                        }
                    }
                }
            }
        }
    }

    val a = action
    if (a != null) {
        val (title, text, label) = when (a) {
            UserAction.Suspend -> Triple(if (suspended) "Reactivate uploads?" else "Suspend uploads?", "This changes whether the phone may upload activity.", if (suspended) "Reactivate" else "Suspend")
            UserAction.Erase -> Triple("Erase user?", "Permanently erase this person and all their data. This cannot be undone.", "Erase")
            UserAction.ClearHistory -> Triple("Clear history?", "Delete ALL browsing history and sessions of this user.", "Delete")
            UserAction.ClearSearches -> Triple("Clear searches?", "Delete ALL search queries of this user.", "Delete")
            is UserAction.DeleteVisit -> Triple("Delete this entry?", "Delete this history entry.", "Delete")
            is UserAction.DeleteSearch -> Triple("Delete this search?", "Delete this search query.", "Delete")
            is UserAction.DeleteMany -> Triple("Delete ${a.ids.size} entries?", "The selected entries are deleted permanently.", "Delete")
        }
        ConfirmDialog(title, text, label, danger = a != UserAction.Suspend, onDismiss = { action = null }, onConfirm = {
            scope.launch {
                try {
                    when (a) {
                        UserAction.Suspend -> vm.repo.setStatus(uid, if (suspended) "active" else "suspended")
                        UserAction.Erase -> { vm.repo.eraseUser(uid); vm.pop() }
                        UserAction.ClearHistory -> message = "Done" to "Deleted ${vm.repo.clearHistory(uid)} history entries."
                        UserAction.ClearSearches -> message = "Done" to "Deleted ${vm.repo.clearSearches(uid)} searches."
                        is UserAction.DeleteVisit -> vm.repo.deleteVisit(uid, a.id)
                        is UserAction.DeleteSearch -> vm.repo.deleteSearch(uid, a.id)
                        is UserAction.DeleteMany -> { if (a.searches) vm.repo.deleteSearches(uid, a.ids) else vm.repo.deleteVisits(uid, a.ids); selecting = false; selected = emptySet() }
                    }
                    tick++
                } catch (e: Exception) { message = "Failed" to vm.friendly(e) }
            }
        })
    }
    sheet?.let { (id, title) ->
        RowActionSheet(title, me.can("delete_data"), onSelect = { selecting = true; selected = setOf(id) },
            onDelete = { action = if (tab == 2) UserAction.DeleteSearch(id) else UserAction.DeleteVisit(id) }, onDismiss = { sheet = null })
    }
    message?.let { (t, m) -> MessageDialog(t, m) { message = null } }
}

private fun deviceLine(u: UserFull): String =
    "${u.manufacturer?.let { "$it " } ?: ""}${u.deviceModel ?: "Unknown device"} · Android ${u.os ?: "?"}${u.sdk?.let { " (API $it)" } ?: ""} · app ${u.appVersion ?: "?"}"
