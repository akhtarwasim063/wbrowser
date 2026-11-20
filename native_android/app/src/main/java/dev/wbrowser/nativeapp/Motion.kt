package dev.wbrowser.nativeapp

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Springy press feedback: shrinks while touched and bounces back on release. The click itself still fires. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressFx(scale: Float = 0.86f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.6f)).start()
        }
        false
    }
}

/** Fades and scales a full-screen layer in. */
fun View.popIn() {
    tag = null; animate().cancel()
    alpha = 0f; scaleX = 0.96f; scaleY = 0.96f; visibility = View.VISIBLE
    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
}

/** Fades and scales a full-screen layer out, then hides it. */
fun View.popOut() {
    if (visibility != View.VISIBLE) return
    tag = "hiding"
    animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(170).withEndAction {
        if (tag == "hiding") { visibility = View.GONE; tag = null }
        alpha = 1f; scaleX = 1f; scaleY = 1f
    }.start()
}
