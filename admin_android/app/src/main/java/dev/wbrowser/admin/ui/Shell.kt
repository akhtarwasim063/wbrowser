package dev.wbrowser.admin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.AdminViewModel
import dev.wbrowser.admin.Route
import dev.wbrowser.admin.data.DateRange
import dev.wbrowser.admin.data.Me

private data class Tab(val label: String, val icon: String)

private val TABS = listOf(Tab("Overview", "grid"), Tab("Websites", "globe"), Tab("Users", "users"), Tab("Settings", "gear"))

/** What is on screen: a tab, or a route opened on top of it. `depth` decides the slide direction. */
private data class Screen(val route: Route?, val tab: Int, val depth: Int)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainShell(vm: AdminViewModel, me: Me) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack by vm.stack.collectAsState()
    val top = stack.lastOrNull()
    BackHandler(enabled = top != null) { vm.pop() }
    BackHandler(enabled = top == null && tab != 0) { tab = 0 }

    // which bottom tab stays lit while a detail screen is open
    val lit = when (top) {
        null -> tab
        is Route.Website -> 1
        is Route.User -> 2
        Route.Sessions -> 0
        else -> 3
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = Screen(top, tab, stack.size),
            contentKey = { it.route ?: it.tab },
            transitionSpec = {
                if (targetState.depth > initialState.depth)
                    (slideInHorizontally(tween(300)) { it / 8 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(260)) { -it / 10 } + fadeOut(tween(160)))
                else if (targetState.depth < initialState.depth)
                    (slideInHorizontally(tween(300)) { -it / 8 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(260)) { it / 10 } + fadeOut(tween(160)))
                else fadeIn(tween(240)) togetherWith fadeOut(tween(140))
            },
            label = "screen",
        ) { s ->
            val r = s.route
            when {
                r != null -> RouteScreen(vm, me, r)
                s.tab == 0 -> OverviewScreen(vm)
                s.tab == 1 -> WebsitesScreen(vm)
                s.tab == 2 -> UsersScreen(vm)
                else -> SettingsScreen(vm, me)
            }
        }
        FloatingTabBar(lit, { vm.popToRoot(); tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
private fun RouteScreen(vm: AdminViewModel, me: Me, r: Route) {
    when (r) {
        Route.Sessions -> SessionsScreen(vm)
        is Route.Website -> WebsiteDetailScreen(vm, r.domain)
        is Route.User -> UserDetailScreen(vm, me, r.uid)
        Route.Admins -> AdminsScreen(vm)
        Route.Audit -> AuditScreen(vm)
        Route.Server -> ServerScreen(vm, me)
        Route.Config -> ConfigScreen(vm)
        Route.Account -> AccountScreen(vm, me)
    }
}

// ---------- floating glass bar: the selected tab grows into a brand pill with its label ----------
@Composable
private fun FloatingTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = palette()
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), lift = 14.dp).background(p.card.copy(alpha = if (p.dark) 0.94f else 0.93f)).padding(5.dp).height(50.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TABS.forEachIndexed { i, t ->
            val on = i == selected
            val w by animateFloatAsState(if (on) 2.4f else 1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), label = "w")
            val a by animateFloatAsState(if (on) 1f else 0f, tween(260), label = "pill")
            val tint by animateColorAsState(if (on) Color.White else p.mut, tween(250), label = "tint")
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(w).fillMaxSize().pressScale(src, 0.92f).clip(RoundedCornerShape(22.dp))
                    .background(Brush.horizontalGradient(listOf(p.pri, p.pri2)), alpha = a)
                    .clickable(interactionSource = src, indication = null) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ico(t.icon, tint, 20.dp)
                    AnimatedVisibility(on, enter = fadeIn(tween(200)) + expandHorizontally(), exit = fadeOut(tween(100)) + shrinkHorizontally()) {
                        Text(t.label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

// ---------- Settings ----------
@Composable
private fun SettingsScreen(vm: AdminViewModel, me: Me) {
    val mode by vm.themeMode.collectAsState()
    val defRange by vm.defaultRange.collectAsState()
    var askOut by remember { mutableStateOf(false) }
    val p = palette()
    ScreenScaffold("Settings", "Appearance, access and account", refreshing = false, onRefresh = null) {
        item("account") {
            Reveal {
                AdminCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me.name, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(me.name, color = p.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(me.email, color = p.mut, fontSize = 12.sp)
                            Spacer(Modifier.height(5.dp))
                            Pill(me.role, if (me.role == "superadmin") p.ok else p.mut, if (me.role == "superadmin") p.okSoft else p.soft)
                        }
                    }
                }
            }
        }
        item("look") {
            Reveal(1) {
                AdminCard {
                    Column {
                        CardTitle("Appearance and behaviour")
                        SettingRow("Theme", "Match your device, or force light or dark") { Segmented(ThemeMode.entries, mode, { it.label }, vm::setTheme) }
                        SettingRow("Default date range", "Used when you open the console") {
                            Segmented(listOf(DateRange.D7, DateRange.D30, DateRange.D90), defRange, { it.label }, vm::setDefaultRange)
                        }
                    }
                }
            }
        }
        item("analytics") {
            Reveal(2) { AdminCard { Column { CardTitle("Analytics"); NavRow("clock", "Sessions", "How often and how long the browser is used") { vm.push(Route.Sessions) } } } }
        }
        if (me.can("manage_config") || me.can("manage_admins") || me.can("audit") || me.can("server_info")) item("manage") {
            Reveal(3) {
                AdminCard {
                    Column {
                        CardTitle("Manage", "Control the phone app and see who has access.")
                        if (me.can("manage_config")) NavRow("gear", "Firebase settings", "Remote app settings and data retention") { vm.push(Route.Config) }
                        if (me.can("manage_admins")) NavRow("shield", "Admins", "Who can open this console") { vm.push(Route.Admins) }
                        if (me.can("audit")) NavRow("list", "Audit log", "Every sensitive action by an admin") { vm.push(Route.Audit) }
                        if (me.can("server_info")) NavRow("db", "Data and storage", "Records stored, clear data") { vm.push(Route.Server) }
                    }
                }
            }
        }
        item("myaccount") {
            Reveal(4) { AdminCard { Column { CardTitle("Account"); NavRow("user", "My account", "Change your password") { vm.push(Route.Account) } } } }
        }
        item("signout") {
            Reveal(5) {
                val src = remember { MutableInteractionSource() }
                Box(Modifier.fillMaxWidth().pressScale(src, 0.98f).glass(RoundedCornerShape(20.dp), lift = 6.dp).clickable(interactionSource = src, indication = null) { askOut = true }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(p.badSoft), contentAlignment = Alignment.Center) { Ico("out", p.bad, 17.dp) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Sign out", color = p.bad, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("You will need your password to sign in again.", color = p.mut, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item("about") {
            Text("WBrowser Admin 2.0 · native", color = p.mut, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center)
        }
    }
    if (askOut) ConfirmDialog("Sign out?", "You will need your email and password to sign in again.", "Sign out", onConfirm = vm::signOut, onDismiss = { askOut = false })
}
