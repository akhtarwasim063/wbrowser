package dev.wbrowser.nativeapp

import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * Every confirmation, message, prompt and chooser in the browser is a bottom sheet (never a centred dialog).
 * The API mirrors AlertDialog.Builder, so existing call sites only had to change the class name.
 *
 *   SheetBuilder(ctx).setTitle("Delete?").setMessage("...").danger()
 *       .setPositiveButton("Delete") { _, _ -> ... }.setNegativeButton("Cancel", null).show()
 *
 * Buttons dismiss the sheet by default; call [keepOpen] when the click handler decides (it then calls dialog.dismiss() itself).
 */
class SheetBuilder(private val ctx: Context) {
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var customView: View? = null
    private var items: Array<out CharSequence>? = null
    private var itemListener: DialogInterface.OnClickListener? = null
    private var multiLabels: Array<out CharSequence>? = null
    private var multiChecked: BooleanArray? = null
    private var multiListener: DialogInterface.OnMultiChoiceClickListener? = null
    private var positive: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
    private var negative: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
    private var neutral: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
    private var cancelable = true
    private var cancelListener: DialogInterface.OnCancelListener? = null
    private var danger = false
    private var keepOpen = false

    fun setTitle(t: CharSequence?) = apply { title = t }
    fun setMessage(m: CharSequence?) = apply { message = m }
    fun setView(v: View) = apply { customView = v }
    fun setItems(labels: Array<out CharSequence>, l: DialogInterface.OnClickListener) = apply { items = labels; itemListener = l }
    fun setMultiChoiceItems(labels: Array<out CharSequence>, checked: BooleanArray, l: DialogInterface.OnMultiChoiceClickListener) =
        apply { multiLabels = labels; multiChecked = checked; multiListener = l }
    fun setPositiveButton(text: CharSequence, l: DialogInterface.OnClickListener?) = apply { positive = text to l }
    fun setNegativeButton(text: CharSequence, l: DialogInterface.OnClickListener?) = apply { negative = text to l }
    fun setNeutralButton(text: CharSequence, l: DialogInterface.OnClickListener?) = apply { neutral = text to l }
    fun setCancelable(b: Boolean) = apply { cancelable = b }
    fun setOnCancelListener(l: DialogInterface.OnCancelListener) = apply { cancelListener = l }
    /** The positive button is a destructive action: it is drawn in the warning colour. */
    fun danger() = apply { danger = true }
    fun keepOpen() = apply { keepOpen = true }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(ctx, attr, fallback)

    fun show(): BottomSheetDialog {
        val dlg = BottomSheetDialog(ctx)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface, 0xFF111426.toInt())
        val onVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF5E6781.toInt())

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(6), dp(22), dp(20)) }
        title?.let {
            col.addView(TextView(ctx).apply {
                text = it; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTypeface(typeface, Typeface.BOLD); setTextColor(onSurface)
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        message?.let {
            col.addView(TextView(ctx).apply {
                text = it; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f); setTextColor(onVariant); setLineSpacing(0f, 1.15f)
                setPadding(0, dp(2), 0, dp(10))
            })
        }
        customView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(8) })
        }
        fun row(): TextView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(onSurface); gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52); setPadding(dp(4), 0, dp(4), 0)
            val out = TypedValue(); ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true); setBackgroundResource(out.resourceId)
        }
        items?.let { labels ->
            labels.forEachIndexed { i, label ->
                col.addView(row().apply { text = label; setOnClickListener { dlg.dismiss(); itemListener?.onClick(dlg, i) } })
            }
        }
        multiLabels?.let { labels ->
            val checked = multiChecked ?: BooleanArray(labels.size)
            labels.forEachIndexed { i, label ->
                col.addView(CheckBox(ctx).apply {
                    text = label; isChecked = checked[i]; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); minimumHeight = dp(52)
                    setOnCheckedChangeListener { _, c -> checked[i] = c; multiListener?.onClick(dlg, i, c) }
                })
            }
        }

        fun click(p: Pair<CharSequence, DialogInterface.OnClickListener?>, which: Int) = View.OnClickListener {
            if (!keepOpen) dlg.dismiss()
            p.second?.onClick(dlg, which)
        }
        neutral?.let { n ->
            col.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = n.first; isAllCaps = false; setOnClickListener(click(n, DialogInterface.BUTTON_NEUTRAL))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(6) })
        }
        if (positive != null || negative != null) {
            val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            negative?.let { n ->
                btns.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = n.first; isAllCaps = false; setOnClickListener(click(n, DialogInterface.BUTTON_NEGATIVE))
                }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { if (positive != null) marginEnd = dp(10) })
            }
            positive?.let { p ->
                btns.addView(MaterialButton(ctx).apply {
                    text = p.first; isAllCaps = false; setOnClickListener(click(p, DialogInterface.BUTTON_POSITIVE))
                    if (danger) { setBackgroundColor(ctx.getColor(R.color.wb_danger)); setTextColor(0xFFFFFFFF.toInt()) }
                }, LinearLayout.LayoutParams(0, dp(50), 1f))
            }
            col.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        dlg.setContentView(ScrollView(ctx).apply { isFillViewport = false; addView(col) })
        dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(R.drawable.bg_sheet)
        dlg.behavior.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true; isHideable = cancelable }
        dlg.setCancelable(cancelable)
        dlg.setCanceledOnTouchOutside(cancelable)
        cancelListener?.let { dlg.setOnCancelListener(it) }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
        return dlg
    }
}
