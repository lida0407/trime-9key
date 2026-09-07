/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady

/**
 * trime-9key: coordinates the T9 letter-disambiguation features.
 *
 * Two entry points share the same mechanism:
 *  - [correctLetter]: drag a letter in the preedit up/down to cycle it
 *    through its T9 group ("hao" -> "gao");
 *  - [pickLetter]: pick an exact letter on the digit key itself (hold GHI,
 *    drag left/release/right = g/h/i), which replaces the first letter of
 *    that key's group in the current preedit.
 *
 * Correcting is done by switching the active Rime schema to a plain-letter
 * pinyin schema, retyping the corrected word, then switching back to
 * `t9_pinyin` once it's committed. The visible keyboard deliberately
 * follows the schema both ways (`KeyboardWindow.smartMatchKeyboard`): while
 * correcting, the letter keyboard is shown -- the composition is now exact
 * pinyin and its keys do the right thing -- and committing the word brings
 * the 9-key grid back. (An earlier "suppress the keyboard switch to keep
 * the 9-key grid" flag left the T9 grid visible over the letter schema,
 * where digit taps got interpreted as candidate selection.)
 *
 * Shared (rather than owned by one view) because Trime renders the preedit
 * in two places -- the inline `CandidatesView` and the floating
 * `PreeditDelegate` window that sits above it and can intercept the same
 * touch -- and both need to trigger the same correction.
 */
object T9CorrectionState {
    /** A correction is composing under the letter schema. */
    @Volatile
    private var active: Boolean = false

    /** A correction was requested but its text hasn't shown up in a
     * composition yet. clearComposition()/selectSchema() each emit an empty
     * CompositionMessage whose delivery races the correction request across
     * threads -- flipping [active] on the first NON-empty composition (and
     * ignoring empties until then) is the only ordering-safe signal that
     * the correction is really underway. */
    @Volatile
    private var pending: Boolean = false

    /** True from the moment a pick/correction is requested until the word
     * commits (or the user backs out). While this holds, the 9-key grid
     * stays visible over the letter schema so the user can keep building
     * the word with drag-picks (h, then a, then o -> "hao"), and bare digit
     * taps are swallowed (the letter schema would read them as candidate
     * selection). */
    val isCorrecting: Boolean
        get() = active || pending

    /** Latest composition seen by either preedit view; lets key-side
     * gestures ([pickLetter]) act on the current word without having their
     * own subscription to the Rime message stream. */
    @Volatile
    var lastComposition: CompositionProto = CompositionProto()

    /** The schema the user was actually typing in when the correction
     * started, restored when it ends. Hardcoding `t9_pinyin` here dumped
     * qwerty users onto the 9-key grid after any preedit correction. */
    @Volatile
    private var originSchema: String = T9_SCHEMA

    /** Schema this feature's key-side gestures belong to. */
    const val T9_SCHEMA = "t9_pinyin"

    /** The plain-letter schema corrections compose in. */
    private const val LETTER_SCHEMA = "luna_pinyin"

    /** Exposed so the keyboard can tell an internal correction swap apart
     * from a schema change the user actually asked for. */
    val letterSchemaId: String get() = LETTER_SCHEMA

    /**
     * Forget any in-flight correction. Called when the IME (re)starts on a
     * field: the process may have been killed mid-session, leaving these
     * flags disagreeing with the engine's actual schema and the visible
     * keyboard.
     */
    fun reset() {
        active = false
        pending = false
        lastComposition = CompositionProto()
    }

    /** Both preedit views funnel every composition update here. */
    fun onComposition(
        rime: RimeSession,
        data: CompositionProto,
    ) {
        lastComposition = data
        if (pending) {
            if (data.length > 0) {
                pending = false
                active = true
            }
            return
        }
        // user backed all the way out of the word they were correcting: go
        // back to fast T9 typing instead of staying on the letter schema.
        if (active && data.length == 0) {
            restoreOriginSchema(rime)
        }
    }

    /** Committing anything while correcting ends the correction. */
    fun onCommit(rime: RimeSession) {
        if (active) {
            restoreOriginSchema(rime)
        }
    }

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
        // Cycling a letter through its T9 group only means something for
        // 9-key input; on a letter keyboard the user just types the letter
        // they want, and this would have dumped them onto the 9-key grid.
        if (!isCorrecting && rime.run { statusCached }.schemaId != T9_SCHEMA) return
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
        applyCorrection(rime, corrected)
    }

    /**
     * The user picked an exact [letter] on its T9 key. The letter always
     * becomes part of the pinyin composition, never bare committed text:
     *  - empty composition: the letter STARTS a composition ("g" composing
     *    with candidates), ready for more letters;
     *  - the current word has a letter of the same T9 group: that letter is
     *    corrected to the picked one (hao -> gao) and re-composed;
     *  - otherwise: the letter is appended as the next exact pinyin letter.
     *
     * Returns false only for non-letter input the caller should handle.
     */
    fun pickLetter(
        rime: RimeSession,
        letter: Char,
    ): Boolean {
        val group = letterGroups.find { letter in it } ?: return false
        val raw = lastComposition.preedit?.replace(CURSOR_MARK.toString(), "").orEmpty()
        if (raw.isBlank()) {
            applyCorrection(rime, letter.toString())
            return true
        }
        val idx = raw.indexOfFirst { it.lowercaseChar() in group }
        val corrected = when {
            idx < 0 -> raw + letter
            raw[idx].lowercaseChar() == letter -> return true
            else -> raw.substring(0, idx) + letter + raw.substring(idx + 1)
        }
        applyCorrection(rime, corrected)
        return true
    }

    private fun applyCorrection(
        rime: RimeSession,
        corrected: String,
    ) {
        // Selecting a schema reloads its config and reopens dictionaries
        // (hundreds of ms with the big dict). Picks after the first in a
        // session are already on the letter schema -- skip the reload and
        // just retype. Read before setting `pending`, which would mask it.
        val alreadyOnLetterSchema = isCorrecting
        if (!alreadyOnLetterSchema) {
            // Remember where to return. Reading it here (before any schema
            // swap) is the only point where it's still the user's own schema.
            originSchema = rime
                .run { statusCached }
                .schemaId
                .takeIf { it.isNotBlank() && it != LETTER_SCHEMA }
                ?: T9_SCHEMA
        }
        pending = true
        active = false
        rime.launchOnReady { api ->
            api.clearComposition()
            if (!alreadyOnLetterSchema) {
                api.selectSchema(LETTER_SCHEMA)
            }
            // multi-syllable preedits carry Rime's display separator (a
            // space); retype it as the apostrophe, the typeable syllable
            // divider, so the boundary survives the round trip.
            api.simulateKeySequence(corrected.replace(' ', '\''))
        }
    }

    private fun restoreOriginSchema(rime: RimeSession) {
        active = false
        pending = false
        val target = originSchema
        rime.launchOnReady { api ->
            api.selectSchema(target)
        }
    }
}
