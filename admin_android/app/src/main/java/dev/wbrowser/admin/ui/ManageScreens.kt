package dev.wbrowser.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wbrowser.admin.AdminViewModel
import dev.wbrowser.admin.data.AppConfig
import dev.wbrowser.admin.data.Me
import kotlinx.coroutines.launch

@Composable
private fun fieldColors() = palette().let { p ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.pri, unfocusedBorderColor = p.glassRimBottom.copy(alpha = 0.5f),
        focusedContainerColor = p.glassFill, unfocusedContainerColor = p.glassFill,
        focusedTextColor = p.ink, unfocusedTextColor = p.ink, cursorColor = p.pri, focusedLabelColor = p.pri, unfocusedLabelColor = p.mut,
    )
}

// ---------- Admins ----------
@Composable
fun AdminsScreen(vm: AdminViewModel) {
    val p = palette()
    DataScreen("Admins", "Who can open this console", listOf(Unit), { vm.repo.admins() }, onBack = vm::pop) { rows, _ ->
        if (rows.isEmpty()) item("none") { Empty("Nothing to show.") }
        itemsIndexed(rows, key = { i, r -> "a$i${r.email}" }) { i, r ->
            Reveal(i) {
                AdminCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(r.name.ifEmpty { r.email })
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifEmpty { r.email }, color = p.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.email, color = p.mut, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Pill(r.role, if (r.role == "superadmin") p.ok else p.mut, if (r.role == "superadmin") p.okSoft else p.soft)
                        }
                        Chips { Pill("Cannot be deleted here", p.ok, p.okSoft); Pill("Updated ${dt(r.updatedAt)}", p.mut, p.soft) }
                    }
                }
            }
        }
        item("info") {
            Reveal { AdminCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardTitle("Add or change admins", "Roles are Firebase sign-in claims, so they are managed from your PC with the Admin SDK, never from a phone:")
                Text("node scripts/set-role.js person@example.com analyst \"Name\"", color = p.ink, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("Roles: superadmin (everything, can delete) and analyst (read-only). Use \"none\" to remove access. The person must sign in again.", color = p.mut, fontSize = 12.sp)
            } } }
        }
    }
}

// ---------- Audit log ----------
@Composable
fun AuditScreen(vm: AdminViewModel) {
    val p = palette()
    DataScreen("Audit log", "Every sensitive action taken by an admin", listOf(Unit), { vm.repo.auditLog() }, onBack = vm::pop) { rows, _ ->
        if (rows.isEmpty()) item("none") { Empty("Nothing to show.") }
        itemsIndexed(rows, key = { i, r -> "l$i${r.at}" }) { i, r ->
            Reveal(i) {
                AdminCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            val bad = Regex("delete|erase|clear").containsMatchIn(r.action)
                            Pill(r.action, if (bad) p.bad else p.mut, if (bad) p.badSoft else p.soft)
                            Text(dt(r.at), color = p.mut, fontSize = 11.sp)
                        }
                        StatGrid(listOf("Admin" to r.admin.ifEmpty { "—" }, "Target" to r.target.ifEmpty { "—" }), columns = 2)
                        if (r.detail.isNotEmpty()) Text(r.detail, color = p.mut, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ---------- Data and storage ----------
@Composable
fun ServerScreen(vm: AdminViewModel, me: Me) {
    val p = palette()
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }   // scope to label
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DataScreen("Data and storage", "What is stored and where", listOf(Unit, tick), { vm.repo.serverInfo() }, onBack = vm::pop) { d, _ ->
        item("k1") { Reveal { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("users", Tone.Pri, d.users, { fmt(it) }, "Users", Modifier.weight(1f))
            KpiCard("globe", Tone.Cyan, d.visits, { fmt(it) }, "Visits", Modifier.weight(1f)) } } }
        item("k2") { Reveal(1) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("search", Tone.Violet, d.searches, { fmt(it) }, "Searches", Modifier.weight(1f))
            KpiCard("list", Tone.Warn, d.sessions, { fmt(it) }, "Sessions", Modifier.weight(1f)) } } }
        item("k3") { Reveal(2) { KpiCard("shield", Tone.Ok, d.admins, { fmt(it) }, "Admins", Modifier.fillMaxWidth()) } }
        item("where") {
            Reveal { AdminCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Where data is stored")
                StatGrid(listOf("Database" to "Google Firebase: Cloud Firestore", "Project" to d.projectId), columns = 1)
                StatGrid(listOf("Access control" to "Firestore security rules + Firebase Authentication roles"), columns = 1)
            } } }
        }
        if (me.can("delete_data")) item("danger") {
            Reveal { AdminCard { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Danger zone: all users", "User accounts stay registered; only collected data is removed. Actions are audit-logged.")
                Chips {
                    GlassButton("Clear history", "trash", danger = true) { confirm = "history" to "browsing history + sessions" }
                    GlassButton("Clear searches", "trash", danger = true) { confirm = "searches" to "search queries" }
                    GlassButton("Clear ALL data", "trash", danger = true) { confirm = "all" to "ALL collected data" }
                }
            } } }
        }
    }
    confirm?.let { (sc, label) ->
        TypeDeleteDialog("This permanently deletes $label for ALL users.", onDismiss = { confirm = null }, onConfirm = {
            scope.launch {
                try { message = "Deleted: " + vm.repo.clearAll(sc); tick++ } catch (e: Exception) { message = vm.friendly(e) }
            }
        })
    }
    message?.let { MessageDialog("Result", it) { message = null } }
}

// ---------- Firebase settings ----------
@Composable
fun ConfigScreen(vm: AdminViewModel) {
    var tick by remember { mutableIntStateOf(0) }
    DataScreen("Firebase settings", "Control the phone app remotely and manage retention", listOf(Unit, tick), { vm.repo.config() }, onBack = vm::pop) { c, _ ->
        item("form") { Reveal { ConfigForm(vm, c) { tick++ } } }
        item("info") {
            Reveal(1) { AdminCard { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardTitle("Accounts and deletion")
                Text("Admin (superadmin) accounts can never be erased from this console; the security rules refuse it.", color = palette().mut, fontSize = 12.sp)
                Text("Erasing a user, or clearing history or searches, deletes the records from Firebase immediately. When an erased user next opens the app, it also removes that person’s Firebase sign-in, so nothing is left behind.", color = palette().mut, fontSize = 12.sp)
            } } }
        }
    }
}

@Composable
private fun ConfigForm(vm: AdminViewModel, c: AppConfig, onChanged: () -> Unit) {
    val p = palette()
    var tracking by remember(c) { mutableStateOf(c.trackingEnabled) }
    var search by remember(c) { mutableStateOf(c.searchAllowed) }
    var interval by remember(c) { mutableStateOf(c.uploadIntervalSeconds.toString()) }
    var retention by remember(c) { mutableStateOf(c.retentionDays.toString()) }
    var note by remember(c) { mutableStateOf(c.announcement) }
    var saving by remember { mutableStateOf(false) }
    var purge by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fc = fieldColors()
    val numKb = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AdminCard {
            Column {
                CardTitle("Remote app settings", "Saved in Firebase. Every phone reads these at its next sync, and the security rules enforce the first two switches.")
                SettingRow("Collect browsing activity", "Off = every phone stops uploading immediately (kill switch)") { GlassSwitch(tracking) { tracking = it } }
                SettingRow("Allow search-query collection", "Off = search queries are blocked for everyone") { GlassSwitch(search) { search = it } }
                SettingRow("Upload interval (seconds)", "How quickly phones push new activity (5 to 600)") {
                    OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(3) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = fc, keyboardOptions = numKb)
                }
                SettingRow("Retention (days)", "Used by \"Delete old data\" below (1 to 3650)") {
                    OutlinedTextField(retention, { retention = it.filter(Char::isDigit).take(4) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = fc, keyboardOptions = numKb)
                }
                SettingRow("Announcement", "A short message shown inside the phone app") {
                    OutlinedTextField(note, { note = it.take(200) }, shape = RoundedCornerShape(12.dp), colors = fc, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Shown inside the phone app (optional)") })
                }
                Spacer(Modifier.width(1.dp))
                GlassButton(if (saving) "Saving…" else "Save settings", "check", primary = true, enabled = !saving) {
                    saving = true
                    scope.launch {
                        try {
                            vm.repo.saveConfig(AppConfig(tracking, search, interval.toIntOrNull() ?: 15, retention.toIntOrNull() ?: 180, note, 0, ""))
                            message = "Saved. Phones pick this up at their next sync."; onChanged()
                        } catch (e: Exception) { message = vm.friendly(e) }
                        saving = false
                    }
                }
                if (c.updatedAt > 0) Text("Last changed ${dt(c.updatedAt)} by ${c.updatedBy}", color = p.mut, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        AdminCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Delete old data", "Permanently deletes visits, sessions and searches older than the retention period for every user.")
                GlassButton("Delete data older than the retention period", "trash", danger = true) { purge = true }
            }
        }
    }
    if (purge) {
        val days = retention.toIntOrNull() ?: 180
        TypeDeleteDialog("Delete everything older than $days days for ALL users?", onDismiss = { purge = false }, onConfirm = {
            scope.launch {
                try { val r = vm.repo.purge(days); message = "Deleted: visits ${r.first}, sessions ${r.second}, searches ${r.third}" } catch (e: Exception) { message = vm.friendly(e) }
            }
        })
    }
    message?.let { MessageDialog("Result", it) { message = null } }
}

// ---------- Change password ----------
@Composable
fun AccountScreen(vm: AdminViewModel, me: Me) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fc = fieldColors()
    ScreenScaffold("My account", "Your sign-in details", refreshing = false, onRefresh = null, onBack = vm::pop) {
        item("form") {
            Reveal {
                AdminCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CardTitle("Change password", "Signed in as ${me.email} (${me.role}).")
                        OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fc, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        OutlinedTextField(next, { next = it }, label = { Text("New password (12+ chars)") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fc, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        GlassButton(if (busy) "Updating…" else "Update password", "lock", primary = true, enabled = !busy && current.isNotEmpty() && next.isNotEmpty()) {
                            busy = true
                            scope.launch {
                                try { vm.repo.changePassword(current, next); current = ""; next = ""; message = "Password changed." }
                                catch (e: Exception) { message = "Failed: " + (if (e is dev.wbrowser.admin.data.AdminRepo.Weak) e.message else "check your current password.") }
                                busy = false
                            }
                        }
                    }
                }
            }
        }
    }
    message?.let { MessageDialog("Account", it) { message = null } }
}
