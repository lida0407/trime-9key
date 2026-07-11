-- SPDX-License-Identifier: GPL-3.0-or-later
--
-- t9_preedit.lua — show pinyin in the composing area instead of the raw T9
-- digits (拼音九宫格 / t9_pinyin schema).
--
-- The 9-key sends digits (e.g. 6,4), so Rime's composition preedit is "64".
-- We don't want the user to see numbers. Each candidate already carries its
-- full pinyin in `comment` (from translator/spelling_hints, e.g. 你 → "nǐ"),
-- and Trime renders the *highlighted candidate's* preedit as the composing
-- text — so overwriting each candidate's preedit with its pinyin makes the
-- composing area read like normal pinyin input, the way Gboard's 9-key does.

local function filter(input, env)
    for cand in input:iter() do
        local py = cand.comment
        if py ~= nil and py ~= "" then
            -- strip any spaces between multi-syllable readings for a tighter look
            cand:get_genuine().preedit = py
        end
        yield(cand)
    end
end

return filter
