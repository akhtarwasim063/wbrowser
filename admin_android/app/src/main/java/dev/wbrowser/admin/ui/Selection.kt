package dev.wbrowser.admin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A glass card that opens/toggles on tap and shows the row actions on long-press.
 * While [selecting], a round check appears on the left and the card gets a brand outline when selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableCard(selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, content: @Composable () -> Unit) {
    val p = palette()
    val src = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val outline by animateColorAsState(if (selected) p.pri else Color.Transparent, tween(180), label = "outline")
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().pressScale(src, 0.985f).glass(shape, lift = 6.dp).border(2.dp, outline, shape)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(selecting, enter = fadeIn(tween(160)) + expandHorizontally(), exit = fadeOut(tween(100)) + shrinkHorizontally()) {
                Box(
                    Modifier.padding(end = 12.dp).size(24.dp).clip(CircleShape)
                        .then(if (selected) Modifier.background(Brush.horizontalGradient(listOf(p.pri, p.pri2))) else Modifier.border(2.dp, p.mut.copy(alpha = 0.6f), CircleShape)),
                    contentAlignment = Alignment.Center,
                ) { if (selected) Ico("check", Color.White, 14.dp, strokeWidth = 2.6f) }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** What long-press offers for one row: select several, or delete just this one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowActionSheet(title: String, canDelete: Boolean, onSelect: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val p = palette()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.card, contentColor = p.ink) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = p.mut, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            SheetItem("check", "Select multiple", p.ink) { onDismiss(); onSelect() }
            if (canDelete) SheetItem("trash", "Delete this one", p.bad) { onDismiss(); onDelete() }
        }
    }
}

@Composable
private fun SheetItem(icon: String, label: String, tint: Color, onClick: () -> Unit) {
    val p = palette()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.soft).clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon, tint, 20.dp); Spacer(Modifier.width(12.dp))
        Text(label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Floating bar shown above the tab bar while selecting. */
@Composable
fun SelectionBar(count: Int, total: Int, canDelete: Boolean, onAll: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val p = palette()
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), lift = 10.dp).background(p.card.copy(alpha = if (p.dark) 0.94f else 0.93f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$count selected", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        GlassButton(if (count == total) "None" else "All", null, onClick = onAll)
        if (canDelete) GlassButton("Delete", "trash", danger = true, enabled = count > 0, onClick = onDelete)
        GlassButton("Done", null, onClick = onCancel)
    }
}
