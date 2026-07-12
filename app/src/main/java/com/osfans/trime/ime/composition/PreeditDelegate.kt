/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.T9CorrectionState
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.horizontalPadding
import splitties.views.verticalPadding

class PreeditDelegate : InputBroadcastReceiver {

    private val context: Context by InputDependencyManager.getInstance().di.instance()
    private val theme: Theme by InputDependencyManager.getInstance().di.instance()
    private val rime: RimeSession by InputDependencyManager.getInstance().di.instance()

    // trime-9key: this floating window sits above CandidatesView's own preedit
    // and can intercept the same drag gesture, so it needs the same wiring
    // (see T9CorrectionState) to correct a T9 letter -- kept in sync with the
    // composition it last rendered.
    private var composition = CompositionProto()

    val ui =
        PreeditUi(
            context,
            theme,
            setupPreeditView = {
                val radiusSize = dp(theme.preedit.topEndRadius)
                val radii = if (layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                    floatArrayOf(0f, 0f, radiusSize, radiusSize, 0f, 0f, 0f, 0f)
                } else {
                    floatArrayOf(radiusSize, radiusSize, 0f, 0f, 0f, 0f, 0f, 0f)
                }
                background = GradientDrawable().apply {
                    setColor(ColorManager.getColor("text_back_color"))
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = radii
                }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                horizontalPadding = dp(theme.preedit.horizontalPadding)
                // trime-9key: the bare text strip is only ~4mm tall -- far
                // smaller than a fingertip, which made the drag-to-correct
                // gesture nearly impossible to land by hand (it only worked
                // via pixel-exact adb taps). Pad it out to a comfortable
                // touch target; the drag callbacks map any y within the view
                // onto the nearest character, so the extra height all counts.
                verticalPadding = dp(10)
                minimumWidth = dp(48)
            },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
            onDragLetter = { offset, forward -> T9CorrectionState.correctLetter(rime, composition, offset, forward) },
        ).apply {
            root.alpha = theme.preedit.alpha
            root.visibility = View.INVISIBLE
        }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(ui.root)

    override fun onCompositionUpdate(data: CompositionProto) {
        composition = data
        ui.update(data)
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
        if (data.length > 0) {
            touchEventReceiverWindow.show()
        } else {
            touchEventReceiverWindow.dismiss()
        }
        // every commit clears composition, so this alone catches both
        // "committed a corrected word" and "backed all the way out of it".
        if (T9CorrectionState.active && data.length == 0) {
            T9CorrectionState.restoreT9Schema(rime)
        }
    }
}
