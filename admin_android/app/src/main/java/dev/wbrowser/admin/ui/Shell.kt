package dev.wbrowser.admin.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.AdminViewModel
import dev.wbrowser.admin.data.Me

private data class Tab(val label: String, val icon: String, val title: String, val subtitle: String)

private val TABS = listOf(
    Tab("Overview", "grid", "Overview", "Browsing activity across all users"),
    Tab("Websites", "globe", "Websites", "Tap a website to see every page opened on it"),
    Tab("Users", "users", "Users", "Everyone who installed the app"),
    Tab("Settings", "gear", "Settings", "Account and app information"),
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainShell(vm: AdminViewModel, me: Me) {
    val p = palette()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 28 }) togetherWith fadeOut(tween(140)) },
            label = "tab",
        ) { t ->
            when (t) {
                0 -> {
                    val overview by vm.overview.collectAsState()
                    val range by vm.range.collectAsState()
                    val presence by vm.presence.collectAsState()
                    val refreshing by vm.refreshing.collectAsState()
                    ScreenScaffold(TABS[0].title, TABS[0].subtitle, refreshing, onRefresh = vm::loadOverview) {
                        overviewContent(overview, range, vm::selectRange, presence)
                    }
                }
                1, 2 -> ScreenScaffold(TABS[t].title, TABS[t].subtitle, false, onRefresh = null) { comingSoon(TABS[t]) }
                else -> ScreenScaffold(TABS[3].title, TABS[3].subtitle, false, onRefresh = null) { settingsContent(me, vm::signOut) }
            }
        }
        FloatingTabBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

/** Collapsing large title, pull-to-refresh and a staggered list: the frame shared by every tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenScaffold(title: String, subtitle: String, refreshing: Boolean, onRefresh: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    val p = palette()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = p.bg,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold, color = p.ink) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg, scrolledContainerColor = p.card, titleContentColor = p.ink),
                scrollBehavior = scroll,
            )
        },
    ) { inner ->
        val list: @Composable () -> Unit = {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item("sub") { Text(subtitle, color = p.mut, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) }
                content()
            }
        }
        Box(Modifier.padding(inner).fillMaxSize()) {
            if (onRefresh != null) PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { list() } else list()
        }
    }
}

// ---------- floating bottom bar with a sliding indicator ----------
@Composable
private fun FloatingTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = palette()
    Surface(
        modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(26.dp), clip = false, ambientColor = Color0, spotColor = Color0),
        shape = RoundedCornerShape(26.dp), color = p.card, border = BorderStroke(1.dp, p.line),
    ) {
        BoxWithConstraints(Modifier.padding(6.dp)) {
            val itemW: Dp = maxWidth / TABS.size
            val x by animateDpAsState(itemW * selected, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), label = "indicator")
            Box(Modifier.offset(x = x).width(itemW).height(58.dp).clip(RoundedCornerShape(20.dp)).background(p.priSoft))
            Row(Modifier.fillMaxWidth()) {
                TABS.forEachIndexed { i, t ->
                    val on = i == selected
                    val tint by animateColorAsState(if (on) p.pri else p.mut, tween(250), label = "tint")
                    val src = remember { MutableInteractionSource() }
                    Column(
                        Modifier.weight(1f).height(58.dp).pressScale(src, 0.9f).clip(RoundedCornerShape(20.dp)).clickable(interactionSource = src, indication = null) { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Ico(t.icon, tint, 22.dp)
                        Spacer(Modifier.height(3.dp))
                        Text(t.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color(0x33000000)

// ---------- placeholder + settings tabs ----------
private fun LazyListScope.comingSoon(tab: Tab) {
    item("soon") {
        Reveal {
            AdminCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(palette().priSoft), contentAlignment = Alignment.Center) { Ico(tab.icon, palette().pri, 28.dp) }
                    Spacer(Modifier.height(14.dp))
                    Text("${tab.title} is coming next", color = palette().ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("This screen is being rebuilt natively. Login and Overview are ready.", color = palette().mut, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun LazyListScope.settingsContent(me: Me, onSignOut: () -> Unit) {
    item("account") {
        Reveal {
            val p = palette()
            AdminCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me.name, 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(me.name, color = p.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(me.email, color = p.mut, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Pill(me.role, if (me.role == "superadmin") p.ok else p.mut, if (me.role == "superadmin") p.okSoft else p.soft)
                        }
                    }
                }
            }
        }
    }
    item("signout") {
        Reveal(1) {
            var ask by remember { mutableStateOf(false) }
            val p = palette()
            val src = remember { MutableInteractionSource() }
            Surface(
                Modifier.fillMaxWidth().pressScale(src, 0.98f).clip(RoundedCornerShape(20.dp)).clickable(interactionSource = src, indication = null) { ask = true },
                shape = RoundedCornerShape(20.dp), color = p.card, border = BorderStroke(1.dp, p.line),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(p.badSoft), contentAlignment = Alignment.Center) { Ico("out", p.bad, 19.dp) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Sign out", color = p.bad, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("You will need your password to sign in again.", color = p.mut, fontSize = 12.sp)
                    }
                }
            }
            if (ask) AlertDialog(
                onDismissRequest = { ask = false },
                title = { Text("Sign out?") },
                text = { Text("You will need your email and password to sign in again.") },
                confirmButton = { TextButton(onClick = { ask = false; onSignOut() }) { Text("Sign out", color = p.bad) } },
                dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } },
            )
        }
    }
    item("about") {
        Reveal(2) { Text("WBrowser Admin 2.0 · native", color = palette().mut, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}
