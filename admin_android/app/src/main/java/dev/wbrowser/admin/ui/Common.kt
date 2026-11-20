package dev.wbrowser.admin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.data.Load
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Seconds between automatic reloads, set in Settings (0 = off). */
val LocalAutoRefresh = androidx.compose.runtime.compositionLocalOf { 0 }

/**
 * Compact glass app bar, pull-to-refresh and a staggered list: the frame shared by every screen.
 * The pull indicator only shows while the user is pulling ([refreshing]); background reloads
 * (changing the date range, a filter, an action) only show a thin progress line ([busy]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String, subtitle: String, refreshing: Boolean, onRefresh: (() -> Unit)?, busy: Boolean = false,
    onBack: (() -> Unit)? = null, overlay: @Composable BoxScope.() -> Unit = {}, content: LazyListScope.() -> Unit,
) {
    val p = palette()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        val src = remember { MutableInteractionSource() }
                        Box(
                            Modifier.padding(start = 10.dp).size(38.dp).pressScale(src, 0.9f).glass(CircleShape, lift = 4.dp).clickable(interactionSource = src, indication = null) { onBack() },
                            contentAlignment = Alignment.Center,
                        ) { Ico("back", p.ink, 18.dp) }
                    }
                },
                title = {
                    Column(Modifier.padding(start = if (onBack != null) 4.dp else 0.dp)) {
                        Text(title, fontWeight = FontWeight.Bold, color = p.ink, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, color = p.mut, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = p.glassFill, titleContentColor = p.ink),
                scrollBehavior = scroll,
            )
        },
    ) { inner ->
        val list: @Composable () -> Unit = {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
        Box(Modifier.padding(inner).fillMaxSize()) {
            if (onRefresh != null) PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { list() } else list()
            AnimatedVisibility(busy && !refreshing, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.pri, trackColor = Color.Transparent)
            }
            overlay()
        }
    }
}

/**
 * Loads [fetch] whenever [keys] change or the user pulls to refresh, and keeps showing the previous
 * data while it reloads. [content] gets the data and a `reload` callback for after an action.
 */
@Composable
fun <T> DataScreen(
    title: String, subtitle: String, keys: List<Any?>, fetch: suspend () -> T, onBack: (() -> Unit)? = null,
    header: (LazyListScope.() -> Unit)? = null, overlay: @Composable BoxScope.() -> Unit = {}, content: LazyListScope.(T, () -> Unit) -> Unit,
) {
    var tick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<T>>(Load.Loading) }
    var pulling by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(keys, tick) {
        busy = true
        try { state = Load.Ready(fetch()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (state !is Load.Ready) state = Load.Failed(e.message ?: "Something went wrong") }
        busy = false; pulling = false
    }
    // optional timer from Settings: reloads quietly (thin progress line only) while the app is on screen
    val autoSec = LocalAutoRefresh.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(autoSec) {
        if (autoSec > 0) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(autoSec * 1000L); tick++ } }
    }
    val reload: () -> Unit = { tick++ }
    ScreenScaffold(title, subtitle, refreshing = pulling, onRefresh = { pulling = true; tick++; Unit }, busy = busy, onBack = onBack, overlay = overlay) {
        header?.invoke(this)
        when (val s = state) {
            is Load.Loading -> items(3) { Box(Modifier.fillMaxWidth().height(86.dp).shimmer()) }
            is Load.Failed -> item("err") { ErrorCard(s.message, reload) }
            is Load.Ready -> content(s.data, reload)
        }
    }
}

// ---------- building blocks ----------
@Composable
fun ErrorCard(message: String, onRetry: () -> Unit) {
    val p = palette()
    AdminCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Could not load", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(message, color = p.bad, fontSize = 13.sp)
            GlassButton("Try again", "refresh", onClick = onRetry)
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = palette()
    Row(modifier.fillMaxWidth().glass(RoundedCornerShape(15.dp), lift = 4.dp).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico("search", p.mut, 18.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = p.mut, fontSize = 14.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = p.ink, fontSize = 14.sp), cursorBrush = SolidColor(p.pri), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Box(Modifier.clip(CircleShape).clickable { onChange("") }.padding(2.dp)) { Text("✕", color = p.mut, fontSize = 13.sp) }
    }
}

/** A tappable glass card (list rows that open another screen). */
@Composable
fun TapCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(modifier.fillMaxWidth().pressScale(src, 0.98f).glass(RoundedCornerShape(20.dp), lift = 6.dp).clickable(interactionSource = src, indication = null) { onClick() }) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

/** Label-over-value cells laid out in equal columns. */
@Composable
fun StatGrid(items: List<Pair<String, String>>, columns: Int = 3) {
    val p = palette()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k.uppercase(), color = p.mut, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, maxLines = 1)
                        Text(v, color = p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Pager(page: Int, total: Int, limit: Int, onPage: (Int) -> Unit) {
    val p = palette()
    if (total <= limit) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${fmt(total)} total", color = p.mut, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Prev", null, enabled = page > 1) { onPage(page - 1) }
            Text("Page $page", color = p.ink, fontSize = 13.sp)
            GlassButton("Next", null, enabled = page * limit < total) { onPage(page + 1) }
        }
    }
}

@Composable
fun GlassButton(text: String, icon: String?, modifier: Modifier = Modifier, danger: Boolean = false, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = palette()
    val src = remember { MutableInteractionSource() }
    val fg = when { primary -> Color.White; danger -> p.bad; else -> p.ink }
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier.pressScale(src, 0.95f).clip(shape)
            .then(if (primary) Modifier.background(Brush.horizontalGradient(listOf(p.pri, p.pri2))) else Modifier.background(if (danger) p.badSoft else p.glassFill).border(1.dp, if (danger) p.bad.copy(alpha = 0.35f) else p.glassRimBottom.copy(alpha = 0.5f), shape))
            .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) fg else fg.copy(alpha = 0.4f)
        if (icon != null) { Ico(icon, c, 16.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun NavRow(icon: String, title: String, hint: String, onClick: () -> Unit) {
    val p = palette()
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().pressScale(src, 0.98f).clip(RoundedCornerShape(14.dp)).clickable(interactionSource = src, indication = null) { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(p.priSoft), contentAlignment = Alignment.Center) { Ico(icon, p.pri, 17.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(hint, color = p.mut, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Ico("chev", p.mut, 18.dp)
    }
}

@Composable
fun SettingRow(title: String, hint: String, control: @Composable () -> Unit) {
    val p = palette()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(hint, color = p.mut, fontSize = 11.sp)
        }
        control()
    }
}

@Composable
fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = palette()
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = p.pri, checkedThumbColor = Color.White, uncheckedTrackColor = p.soft, uncheckedThumbColor = p.mut, uncheckedBorderColor = p.line))
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = palette()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = if (danger) p.bad else p.pri) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Destructive, wide-reaching actions need the word DELETE typed first (same as the web console). */
@Composable
fun TypeDeleteDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = palette()
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Are you sure?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text)
                OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("Type DELETE to confirm") })
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }, enabled = typed == "DELETE") { Text("Delete", color = if (typed == "DELETE") p.bad else p.mut) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun MessageDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

@Composable
fun HeroCard(avatar: @Composable () -> Unit, title: String, subtitle: String, chips: @Composable () -> Unit = {}) {
    val p = palette()
    AdminCard {
        Row(verticalAlignment = Alignment.Top) {
            avatar()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column {
                    Text(title, color = p.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = p.mut, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                chips()
            }
        }
    }
}
