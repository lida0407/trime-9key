/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady

/**
 * trime-9key: coordinates the "drag a preedit letter to correct it" feature.
 *
 * Correcting a letter (e.g. "hao" -> "gao") is done by briefly switching the
 * active Rime schema to a plain-letter pinyin schema, retyping the corrected
 * word, then switching back to `t9_pinyin` once it's committed. Switching
 * schema normally also re-picks the keyboard layout (see
 * `KeyboardWindow.smartMatchKeyboard`), which would flip the visible grid
 * away from the 9-key layout mid-correction -- that's the one thing this
 * flag exists to suppress for these two internal, momentary schema swaps.
 *
 * Shared (rather than owned by one view) because Trime renders the preedit
 * in two places -- the inline `CandidatesView` and the floating
 * `PreeditDelegate` window that sits above it and can intercept the same
 * touch -- and both need to trigger the same correction.
 */
object T9CorrectionState {
    @Volatile
    var suppressNextKeyboardSwitch: Boolean = false

    @Volatile
    var active: Boolean = false

    private val letterGroups = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")

    // Rime embeds its cursor caret as a literal U+2038 character inside
    // `composition.preedit` (e.g. "ni‸"). It's not part of the typed
    // spelling, so it must be stripped before the string is used to build a
    // key sequence -- otherwise it gets fed to `simulateKeySequence` and
    // committed verbatim as garbage text.
    private const val CURSOR_MARK = '‸'

    fun correctLetter(
        rime: RimeSession,
        composition: CompositionProto,
        offset: Int,
        forward: Boolean,
    ) {
        val raw = composition.preedit ?: return
        if (offset !in raw.indices) return
        val markIdx = raw.indexOf(CURSOR_MARK)
        if (offset == markIdx) return
        val current = if (markIdx >= 0) raw.removeRange(markIdx, markIdx + 1) else raw
        val adjustedOffset = if (markIdx in 0 until offset) offset - 1 else offset
        if (adjustedOffset !in current.indices) return
        val ch = current[adjustedOffset].lowercaseChar()
        val group = letterGroups.find { ch in it } ?: return
        val idx = group.indexOf(ch)
        val nextIdx = ((if (forward) idx + 1 else idx - 1) + group.length) % group.length
        val corrected = current.substring(0, adjustedOffset) + group[nextIdx] + current.substring(adjustedOffset + 1)
        rime.launchOnReady { api ->
            // Both clearComposition() and selectSchema() reset the engine's
            // composition state and each emits their own empty
            // CompositionMessage. `active` must stay false until both have
            // happened, otherwise the empty-composition listener (see
            // CandidatesView/PreeditDelegate) reads one of those resets as
            // "user backed out of the correction" and races
            // restoreT9Schema's selectSchema("t9_pinyin") against
            // simulateKeySequence below.
            suppressNextKeyboardSwitch = true
            api.clearComposition()
            api.selectSchema("luna_pinyin")
            active = true
            api.simulateKeySequence(corrected)
        }
    }

    fun restoreT9Schema(rime: RimeSession) {
        active = false
        rime.launchOnReady { api ->
            suppressNextKeyboardSwitch = true
            api.selectSchema("t9_pinyin")
        }
    }
}
