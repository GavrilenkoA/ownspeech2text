package app.murmur.platform.overlay

import app.murmur.core.dictation.BubbleError
import app.murmur.core.dictation.DictationBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubblePolicyTest {
    private val base = VisibilityInput(editableFocused = true, bubbleAllowedForField = true, appHidden = false, hiddenUntilMs = null,
        nowMs = 10_000, showAlways = false, idleSinceMs = 9_000, tuckIn = true, talkBackOn = false)

    private fun idle(v: VisibilityInput) = BubblePolicy.merge(DictationBubble.Idle, v, dragging = false, overHideTarget = false)

    @Test
    fun designVisibilityRules() {
        assertEquals(BubbleVisual.Ready, BubblePolicy.merge(DictationBubble.Idle, base, false, false))
        assertEquals(BubbleVisual.Rest, BubblePolicy.merge(DictationBubble.Idle, base.copy(idleSinceMs = 5_000), false, false))
        assertEquals(BubbleVisual.Ready, BubblePolicy.merge(DictationBubble.Idle, base.copy(idleSinceMs = 5_000, talkBackOn = true), false, false))
        assertEquals(BubbleVisual.Hidden, BubblePolicy.merge(DictationBubble.Idle, base.copy(bubbleAllowedForField = false), false, false))
        assertEquals(BubbleVisual.Hidden, BubblePolicy.merge(DictationBubble.Idle, base.copy(hiddenUntilMs = 20_000), false, false))
        assertEquals(BubbleVisual.Active(DictationBubble.Polishing), BubblePolicy.merge(DictationBubble.Polishing, base.copy(appHidden = true), false, false))
    }

    @Test
    fun passwordFieldHidesTheBubbleEvenWhenShowAlwaysIsOn() {
        // review 2026-11-19: returned Ready for a focused password field with "Always".
        assertEquals(BubbleVisual.Hidden, BubblePolicy.merge(DictationBubble.Idle, base.copy(bubbleAllowedForField = false, showAlways = true), false, false))
        assertEquals(BubbleVisual.Rest, BubblePolicy.merge(DictationBubble.Idle, base.copy(editableFocused = false, showAlways = true), false, false))
    }

    // --- DESIGN 5.2, one test per row of the table

    @Test
    fun row1_noEditableFieldIsHiddenByDefault_andAlwaysKeepsARestTab() {
        val blurred = base.copy(editableFocused = false, idleSinceMs = null)
        assertEquals(BubbleVisual.Hidden, idle(blurred))
        assertEquals(BubbleVisual.Rest, idle(blurred.copy(showAlways = true)))
        // "Always" keeps the tab even under TalkBack and with tuck-in off: it is the no-field state, not a tuck.
        assertEquals(BubbleVisual.Rest, idle(blurred.copy(showAlways = true, talkBackOn = true, tuckIn = false)))
    }

    @Test
    fun row1_theAlwaysTabTappedOpenIsReadyForFourSeconds_andUnderTalkBackUntilFocusMoves() {
        // DESIGN 5.3 Rest: "tap -> Ready" also for the "Always" tab with no field focused (review F1).
        val now = base.nowMs
        val tab = base.copy(editableFocused = false, showAlways = true, idleSinceMs = null)
        fun open(v: VisibilityInput, at: Long?) = BubblePolicy.merge(DictationBubble.Idle, v, dragging = false, overHideTarget = false, restOpenedAtMs = at)
        assertEquals(BubbleVisual.Ready, open(tab, now))
        assertEquals(BubbleVisual.Ready, open(tab, now - 3_999))
        assertEquals(BubbleVisual.Rest, open(tab, now - 4_000))
        assertEquals(now + 4_000, BubblePolicy.nextDeadline(DictationBubble.Idle, tab, restOpenedAtMs = now))
        // The opened tab folds back whatever the tuck-in setting: it is the no-field resting form, not a tuck.
        assertEquals(BubbleVisual.Rest, open(tab.copy(tuckIn = false), now - 4_000))
        // DESIGN 8: no tuck-in under TalkBack, so the opened tab stays a stable Ready target.
        assertEquals(BubbleVisual.Ready, open(tab.copy(talkBackOn = true), now - 60_000))
        assertNull(BubblePolicy.nextDeadline(DictationBubble.Idle, tab.copy(talkBackOn = true), restOpenedAtMs = now))
        // Opening the tab never overrides a rule that hides the bubble.
        assertEquals(BubbleVisual.Hidden, open(tab.copy(appHidden = true), now))
        assertEquals(BubbleVisual.Hidden, open(tab.copy(hiddenUntilMs = now + 1_000), now))
        assertEquals(BubbleVisual.Hidden, open(tab.copy(showAlways = false), now))
    }

    @Test
    fun row2_editableFieldFocusedShowsReady() {
        assertEquals(BubbleVisual.Ready, idle(base))
        assertEquals(BubbleVisual.Ready, idle(base.copy(showAlways = true)))
        // Focus just arrived: no idle time yet.
        assertEquals(BubbleVisual.Ready, idle(base.copy(idleSinceMs = base.nowMs)))
    }

    @Test
    fun row3_passwordNumberAndPhoneFieldsHideAlways() {
        // bubbleAllowedForField = false is FieldKinds.bubbleAllowed for isPassword, *PASSWORD variations, CLASS_NUMBER, CLASS_PHONE.
        val secret = base.copy(bubbleAllowedForField = false)
        assertEquals(BubbleVisual.Hidden, idle(secret))
        assertEquals(BubbleVisual.Hidden, idle(secret.copy(showAlways = true)))
        assertEquals(BubbleVisual.Hidden, idle(secret.copy(talkBackOn = true)))
        assertEquals(BubbleVisual.Hidden, idle(secret.copy(idleSinceMs = 0)))
    }

    @Test
    fun row4_appInHiddenInListIsHidden() {
        assertEquals(BubbleVisual.Hidden, idle(base.copy(appHidden = true)))
        assertEquals(BubbleVisual.Hidden, idle(base.copy(appHidden = true, showAlways = true)))
    }

    @Test
    fun row5_murmursOwnUiIsHidden() {
        // :platform:a11y folds "Murmur's own UI" into appHidden (VisibilityRules.appHidden); same outcome, with a blurred field too.
        assertEquals(BubbleVisual.Hidden, idle(base.copy(appHidden = true)))
        assertEquals(BubbleVisual.Hidden, idle(base.copy(appHidden = true, editableFocused = false, showAlways = true)))
    }

    @Test
    fun row6_readyForFourSecondsTucksIntoRest_notUnderTalkBack_notWithTuckInOff() {
        val now = base.nowMs
        assertEquals(BubbleVisual.Ready, idle(base.copy(idleSinceMs = now - 3_999)))
        assertEquals(BubbleVisual.Rest, idle(base.copy(idleSinceMs = now - 4_000)))
        assertEquals(BubbleVisual.Ready, idle(base.copy(idleSinceMs = now - 60_000, talkBackOn = true)))
        assertEquals(BubbleVisual.Ready, idle(base.copy(idleSinceMs = now - 60_000, tuckIn = false)))
        assertEquals(BubbleVisual.Ready, idle(base.copy(idleSinceMs = null)))
    }

    @Test
    fun row7_userHidItUntilTheTimeShown_undoOrShowNowClearsIt() {
        val now = base.nowMs
        assertEquals(BubbleVisual.Hidden, idle(base.copy(hiddenUntilMs = now + 1)))
        assertEquals(BubbleVisual.Hidden, idle(base.copy(hiddenUntilMs = now + 3_600_000, showAlways = true, editableFocused = false)))
        // The hour is over.
        assertEquals(BubbleVisual.Ready, idle(base.copy(hiddenUntilMs = now)))
        // Undo / "Show now" write null.
        assertEquals(BubbleVisual.Ready, idle(base.copy(hiddenUntilMs = null)))
    }

    @Test
    fun row8_keyboardClosedWhileReadyOrRestHides() {
        // The 300 ms debounce lives in :platform:a11y (VisibilityRules.delayFor); what reaches the policy is "no field".
        assertEquals(BubbleVisual.Hidden, idle(base.copy(editableFocused = false)))
        assertEquals(BubbleVisual.Hidden, idle(base.copy(editableFocused = false, idleSinceMs = 0)))
    }

    @Test
    fun aRunningDictationIsShownWhateverTheVisibility_andDraggingWinsEverywhere() {
        val hiddenEverywhere = base.copy(editableFocused = false, appHidden = true, hiddenUntilMs = base.nowMs + 10_000, bubbleAllowedForField = false)
        val listening = DictationBubble.Listening(startedAtMs = 1, hold = false, warn = false)
        for (s in listOf(listening, DictationBubble.Transcribing, DictationBubble.Polishing, DictationBubble.Inserted(false),
            DictationBubble.Copied(null), DictationBubble.Error(BubbleError.MIC_BUSY, null))) {
            assertEquals(BubbleVisual.Active(s), BubblePolicy.merge(s, hiddenEverywhere, dragging = false, overHideTarget = false))
            // DESIGN 5.3: a working or result pill can only be moved; the hide target is never armed for it (review F2).
            assertEquals(BubbleVisual.Dragging(false), BubblePolicy.merge(s, hiddenEverywhere, dragging = true, overHideTarget = true))
        }
        assertEquals(BubbleVisual.Dragging(true), BubblePolicy.merge(DictationBubble.Idle, base, dragging = true, overHideTarget = true))
        assertEquals(BubbleVisual.Dragging(false), BubblePolicy.merge(DictationBubble.Idle, base, dragging = true, overHideTarget = false))
    }

    @Test
    fun nextDeadlineIsTheTuckOrTheEndOfTheHiddenHour() {
        val now = base.nowMs
        assertEquals(9_000L + 4_000L, BubblePolicy.nextDeadline(DictationBubble.Idle, base))
        assertEquals(now + 500, BubblePolicy.nextDeadline(DictationBubble.Idle, base.copy(hiddenUntilMs = now + 500, idleSinceMs = null)))
        assertEquals(now + 500, BubblePolicy.nextDeadline(DictationBubble.Idle, base.copy(hiddenUntilMs = now + 500)))
        assertNull(BubblePolicy.nextDeadline(DictationBubble.Idle, base.copy(talkBackOn = true)))
        assertNull(BubblePolicy.nextDeadline(DictationBubble.Idle, base.copy(idleSinceMs = now - 5_000)))
        assertNull(BubblePolicy.nextDeadline(DictationBubble.Polishing, base))
    }
}
