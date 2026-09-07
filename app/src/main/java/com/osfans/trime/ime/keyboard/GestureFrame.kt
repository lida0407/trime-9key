/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

open class GestureFrame(context: Context) : FrameLayout(context) {

    private var touchId = 0
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var startTime = 0L

    private var isLongPressed = false
    private var slideActivated = false
    private var swipeTriggered = false

    private var longPressJob: Job? = null
    private var repeatJob: Job? = null
    private var doubleTapJob: Job? = null

    private var lastTapTime = 0L
    private var lastSwipeBehavior: KeyBehavior = KeyBehavior.CLICK

    private val lifecycleScope by lazy {
        findViewTreeLifecycleOwner()?.lifecycleScope!!
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * trime-9key: [swipeTravel] and [swipeVelocity] are configured in dp and
     * dp/s (that's what the settings screen shows), but were compared against
     * raw pixel distances -- so the real threshold shrank as screen density
     * grew (~23dp instead of 60 on a 420dpi phone) and differed on every
     * device. Convert once here.
     */
    private val density = context.resources.displayMetrics.density
    private val swipeTravelPx get() = swipeTravel * density
    private val swipeVelocityPx get() = swipeVelocity * density

    var onClick: (() -> Unit)? = null
    var onDoubleClick: (() -> Unit)? = null
    var onLazyDoubleClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    var onSlide: ((delta: Int, x: Float, y: Float) -> Unit)? = null

    var onPress: (() -> Unit)? = null
    var onRelease: ((behavior: KeyBehavior, longPress: Boolean) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onMove: ((x: Float, y: Float, longPress: Boolean) -> Unit)? = null
    var onSwipe: ((behavior: KeyBehavior) -> Unit)? = null

    /** trime-9key: fires when a plain long press engages (at the timeout,
     * alongside the haptic) -- the commit itself is deferred to release, so
     * this is the listener's chance to preview what release will produce. */
    var onLongPressEngaged: (() -> Unit)? = null

    var isRepeatable = false
    var isSlideCursor = false
    var isSlideDelete = false

    var hasLongPress = false
    var hasDouble = false
    var hasLazyDouble = false
    var hasPopup = false

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
        // avoid gaining focus unexpectedly
        isFocusable = false
        isFocusableInTouchMode = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                touchId = (touchId + 1) and 0xFFFF
                val currentTouchId = touchId
                startX = x
                startY = y
                lastX = startX
                startTime = SystemClock.elapsedRealtime()

                isLongPressed = false
                slideActivated = false
                swipeTriggered = false
                lastSwipeBehavior = KeyBehavior.CLICK

                drawableHotspotChanged(x, y)
                isPressed = true
                if (vibrateOnKeyPress) InputFeedbackManager.keyPressVibrate(this)
                onPress?.invoke()

                if (hasLongPress || isRepeatable || hasPopup) {
                    startLongPressJob(currentTouchId)
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)

                val dx = x - startX
                val dy = y - startY

                onMove?.invoke(x, y, isLongPressed)

                // trime-9key: on popup keys, a finger that starts moving is
                // swiping, not asking for the popup -- cancel the pending
                // long press once movement passes touch slop, else any swipe
                // slower than longPressTimeout opens the popup mid-gesture
                // and swipe detection dies with it. Plain long_click keys
                // don't need this: their commit is deferred to ACTION_UP,
                // where a developed swipe already outranks the long click,
                // and cancelling on mere slop would turn a wobbly-fingered
                // long press into a plain click.
                if (!isLongPressed && hasPopup && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    longPressJob?.cancel()
                }

                if ((isSlideCursor || isSlideDelete) && onSlide != null && !isLongPressed && swipeTravel > 0) {
                    if (!slideActivated) {
                        if (abs(dx) >= swipeTravelPx) {
                            slideActivated = true
                            lastX = startX
                        }
                    }

                    if (slideActivated) {
                        val step = getNStep(lastX, x, slideStepSize.toFloat())
                        if (step != 0) {
                            onSlide?.invoke(step, x, y)
                            lastX = x
                        }
                    }
                }

                // trime-9key: many users' natural "swipe" is press, hold a
                // beat, THEN move -- which lands after the long-press fires.
                // Keep detecting swipe past that point so the movement still
                // wins on release. Popup keys are excluded (movement there
                // means "choose within the popup") and so are repeatable keys
                // (finger drift while holding e.g. BackSpace must not morph
                // into a swipe).
                if (!isLongPressed || (!hasPopup && !isRepeatable)) {
                    val behavior = detectSwipe(dx, dy)
                    if (behavior != lastSwipeBehavior) {
                        lastSwipeBehavior = behavior
                        // also fired when the direction falls back to CLICK,
                        // so the listener can clear its gesture preview.
                        onSwipe?.invoke(behavior)
                    }
                }

                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = x - startX
                val dy = y - startY

                isPressed = false
                if (vibrateOnKeyRelease) InputFeedbackManager.keyPressVibrate(this)
                cancelJobs()

                if (slideActivated) {
                    onSlide?.invoke(0, x, y)
                    onCancel?.invoke()
                    return true
                }

                // trime-9key: movement outranks the hold -- a hold-then-drag
                // is a swipe, not a long click (see the ACTION_MOVE comment).
                if (swipeTriggered && lastSwipeBehavior != KeyBehavior.CLICK) {
                    dispatchBehavior(lastSwipeBehavior, false)
                    return true
                }

                if (isLongPressed) {
                    dispatchBehavior(KeyBehavior.LONG_CLICK, true)
                    return true
                }

                if (!hasDouble && !hasLazyDouble) {
                    dispatchBehavior(KeyBehavior.CLICK, false)
                    return true
                }

                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTapTime
                if (delta <= doubleTapTimeout) {
                    lastTapTime = 0
                    doubleTapJob?.cancel()
                    if (hasDouble) {
                        dispatchBehavior(KeyBehavior.DOUBLE_CLICK, false)
                    } else {
                        dispatchBehavior(KeyBehavior.LAZY_DOUBLE_CLICK, false)
                    }
                } else {
                    lastTapTime = now
                    if (hasLazyDouble && !hasDouble) {
                        doubleTapJob = lifecycleScope.launch {
                            delay(doubleTapTimeout.toLong())
                            if (lastTapTime == now) {
                                lastTapTime = 0
                                dispatchBehavior(KeyBehavior.CLICK, false)
                            }
                        }
                    } else {
                        dispatchBehavior(KeyBehavior.CLICK, false)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                cancelJobs()

                isLongPressed = false
                slideActivated = false
                swipeTriggered = false

                onCancel?.invoke()
                return true
            }
        }

        return true
    }

    private fun startLongPressJob(currentTouchId: Int) {
        longPressJob = lifecycleScope.launch {
            delay(longPressTimeout.toLong())
            if (touchId != currentTouchId) return@launch
            if (swipeTriggered || slideActivated) return@launch
            isLongPressed = true

            if (vibrateOnKeyPress) InputFeedbackManager.keyPressVibrate(this@GestureFrame, true)

            if (isRepeatable) {
                startRepeatJob()
            } else if (hasPopup) {
                // popup must appear while the finger is still down, so the
                // user can slide onto a choice.
                performLongClick()
            }
            // trime-9key: plain long_click keys used to commit right here, at
            // the timeout -- so anyone whose swipe style is "hold, then move"
            // had the long_click text committed before their movement even
            // began. The commit now happens on ACTION_UP (dispatchBehavior
            // LONG_CLICK -> KeyView.onRelease), where a swipe, if one
            // developed, takes precedence. The vibration above still marks
            // the moment the hold engages.
            else if (hasLongPress) {
                onLongPressEngaged?.invoke()
            }
        }
    }

    private fun startRepeatJob() {
        repeatJob = lifecycleScope.launch {
            try {
                while (true) {
                    if (vibrateOnKeyRepeat) InputFeedbackManager.keyPressVibrate(this@GestureFrame)
                    dispatchBehavior(KeyBehavior.CLICK, true)
                    delay(repeatInterval.toLong())
                }
            } finally {
                onCancel?.invoke()
            }
        }
    }

    private fun detectSwipe(dx: Float, dy: Float): KeyBehavior {
        val absDx = abs(dx)
        val absDy = abs(dy)

        val distance = if (absDx > absDy) absDx else absDy
        val elapsed = SystemClock.elapsedRealtime() - startTime

        val velocity = if (elapsed > 0) {
            (distance / elapsed) * 1000f
        } else {
            0f
        }

        val isSwipe =
            (swipeTravel > 0 && distance >= swipeTravelPx) ||
                (swipeVelocity > 0 && velocity >= swipeVelocityPx)
        swipeTriggered = isSwipe

        if (!isSwipe) return KeyBehavior.CLICK
        return if (absDx > absDy) {
            if (dx > 0) KeyBehavior.SWIPE_RIGHT else KeyBehavior.SWIPE_LEFT
        } else {
            if (dy > 0) KeyBehavior.SWIPE_DOWN else KeyBehavior.SWIPE_UP
        }
    }

    private fun dispatchBehavior(
        behavior: KeyBehavior,
        longPress: Boolean,
    ) {
        onRelease?.invoke(behavior, longPress)
        when (behavior) {
            KeyBehavior.CLICK -> performClick()
            KeyBehavior.DOUBLE_CLICK -> onDoubleClick?.invoke()
            KeyBehavior.LAZY_DOUBLE_CLICK -> onLazyDoubleClick?.invoke()
            KeyBehavior.SWIPE_LEFT -> onSwipeLeft?.invoke()
            KeyBehavior.SWIPE_RIGHT -> onSwipeRight?.invoke()
            KeyBehavior.SWIPE_UP -> onSwipeUp?.invoke()
            KeyBehavior.SWIPE_DOWN -> onSwipeDown?.invoke()
            else -> {}
        }
    }

    private fun cancelJobs() {
        longPressJob?.cancel()
        repeatJob?.cancel()
        doubleTapJob?.cancel()
    }

    fun getNStep(start: Float, end: Float, step: Float): Int = (if (start < end) 1 else -1) *
        floor(abs(end - start) / step).toInt()

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        hasLongPress = l != null
        super.setOnLongClickListener(l)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onClick?.invoke()
        return true
    }

    override fun performLongClick(): Boolean {
        val handled = super.performLongClick()
        onLongClick?.invoke()
        return handled || onLongClick != null
    }

    companion object {
        private val swipeTravel by AppPrefs.defaultInstance().keyboard.swipeTravel
        private val swipeVelocity by AppPrefs.defaultInstance().keyboard.swipeVelocity
        private val longPressTimeout by AppPrefs.defaultInstance().keyboard.longPressTimeout
        private val repeatInterval by AppPrefs.defaultInstance().keyboard.repeatInterval
        private val doubleTapTimeout by AppPrefs.defaultInstance().keyboard.doubleTapTimeout
        private val slideStepSize by AppPrefs.defaultInstance().keyboard.slideStepSize
        private val vibrateOnKeyPress by AppPrefs.defaultInstance().keyboard.vibrateOnKeyPress
        private val vibrateOnKeyRelease by AppPrefs.defaultInstance().keyboard.vibrateOnKeyRelease
        private val vibrateOnKeyRepeat by AppPrefs.defaultInstance().keyboard.vibrateOnKeyRepeat
    }
}
