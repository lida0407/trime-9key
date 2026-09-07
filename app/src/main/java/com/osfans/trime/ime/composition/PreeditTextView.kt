/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.core.math.MathUtils
import kotlin.math.abs

@SuppressLint("AppCompatCustomView")
class PreeditTextView
@JvmOverloads
constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
) : TextView(context, attributeSet) {
    var onMoveCursor: ((Int) -> Unit)? = null

    /** trime-9key: vertical drag on a preedit character to cycle it through its
     * T9 letter group (e.g. drag on 'h' -> 'g' -> 'i'), the way Sogou's 9-key
     * lets you correct a wrongly-guessed letter without retyping the whole word. */
    var onDragLetter: ((offset: Int, forward: Boolean) -> Unit)? = null

    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop * 2

    private var lastTapOffset = -1
    private var newCursorPos = -1
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var draggedPastSlop = false

    /**
     * trime-9key: moving the caret into the middle of the word re-segments
     * the candidates ("hao" -> 哈 for "h|ao") with no visible way back, and
     * the strip is now a deliberately finger-sized target sitting right
     * above the keys -- so a stray tap while reaching for a candidate used
     * to silently derail the word. Only a deliberate hold moves the caret.
     */
    private val cursorHoldTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val textString = text.toString()
                lastTapOffset = MathUtils.clamp(
                    getOffsetForPosition(x, y),
                    0,
                    textString.length,
                )
                val bytes = textString.substring(0, lastTapOffset).toByteArray()
                newCursorPos = bytes.size
                downX = x
                downY = y
                downTime = event.eventTime
                draggedPastSlop = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                if (!draggedPastSlop && abs(dy) > dragSlop && abs(dy) > abs(dx)) {
                    draggedPastSlop = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (draggedPastSlop && lastTapOffset in text.indices) {
                    onDragLetter?.invoke(lastTapOffset, /* forward = */ y < downY)
                } else if (event.eventTime - downTime >= cursorHoldTimeout) {
                    // deliberate hold: the user really is aiming at the caret
                    onMoveCursor?.invoke(newCursorPos)
                }
                lastTapOffset = -1
                newCursorPos = -1
                draggedPastSlop = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapOffset = -1
                newCursorPos = -1
                draggedPastSlop = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
