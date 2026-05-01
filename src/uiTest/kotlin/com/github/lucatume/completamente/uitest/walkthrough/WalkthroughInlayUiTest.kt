package com.github.lucatume.completamente.uitest.walkthrough

import com.github.lucatume.completamente.uitest.BaseCompletamenteUiTest
import org.junit.Ignore
import org.junit.Test

/**
 * UI test scaffold for the Walkthrough feature. The fake-agent.sh stub serves canned
 * <Walkthrough> output from `src/uiTest/resources/fake-agent/fixtures/walkthrough-*.txt`,
 * and these tests drive the action via reflective JS through the Remote Robot agent.
 *
 * **Currently @Ignore'd**: invoking AnAction-based commands via reflective JS in the
 * existing Order 89 UI test wedged the EDT (see the note in Order89PopupUiTest.kt). The
 * Walkthrough action takes the same shape — `actionPerformed` opens a modal Swing dialog
 * that blocks the EDT until the user submits, which the JS bridge cannot type into. The
 * known-good path is to either (a) drive the action via a real ESC/Enter keystroke through
 * the Robot's keyboard API instead of `actionPerformed`, or (b) factor out a non-action
 * entry point (e.g. `WalkthroughCoordinator.start(prompt, editor, project)`) that bypasses
 * the modal dialog for tests.
 *
 * Re-enable once one of those paths is implemented and verified, mirroring whichever fix
 * is applied to Order 89.
 *
 * Each test below documents the user behavior the spec promises; the fixture files live
 * alongside this scaffold so reviewers can read the canned agent output without running
 * the test.
 */
@Ignore("WIP — see EDT wedge note above; mirrors Order89PopupUiTest's @Ignore.")
class WalkthroughInlayUiTest : BaseCompletamenteUiTest() {

    @Test
    fun testHappyPath_threeStepsAdvanceAndClose() {
        // Fixture: walkthrough-three-steps.txt (3 linear steps in src/sample.kt).
        // Expected behavior:
        //   1. Walkthrough action triggered → dialog opens.
        //   2. Enter on the default prompt submits.
        //   3. Progress widget shows "Walkthrough: thinking…" briefly.
        //   4. Popup appears anchored to step 1's range; «,< disabled, >,» enabled.
        //   5. Click > → popup re-anchors to step 2; all four buttons enabled.
        //   6. Click > → popup re-anchors to step 3; <,« enabled, >,» disabled.
        //   7. Click × → popup disposes, highlighter is removed, session ends.
        useFakeWalkthroughFixture("walkthrough-three-steps.txt")
        // TODO: reflective JS to invoke the action; assertions on popup component fixtures.
    }

    @Test
    fun testParseFailure_surfacesNotification() {
        // Fixture: walkthrough-garbage.txt (no <Walkthrough> block at all).
        // Expected: error notification "Could not parse the walkthrough: …" with the raw
        // output truncated to 2000 chars; no popup; no highlighter.
        useFakeWalkthroughFixture("walkthrough-garbage.txt")
        // TODO: assertion on Notifications tool window content.
    }

    @Test
    fun testZeroSteps_surfacesNotification() {
        // Fixture: walkthrough-no-steps.txt (legal-empty <Walkthrough></Walkthrough>).
        // Expected: error notification "Walkthrough produced no steps."; no popup.
        useFakeWalkthroughFixture("walkthrough-no-steps.txt")
        // TODO.
    }

    @Test
    fun testMissingFile_skipsStep() {
        // Fixture: walkthrough-missing-file.txt (step 1 references a file that doesn't
        // exist; step 2 is resolvable). Expected: session skips step 1 with a one-shot
        // warning notification; popup opens on step 2 as the effective root.
        useFakeWalkthroughFixture("walkthrough-missing-file.txt")
        // TODO.
    }

    @Test
    fun testFirstStepUnresolvable_neverOpens() {
        // Edge case: every step is unresolvable. Spec: error notification, no session opens.
        // When re-enabling, add a fixture (e.g. `walkthrough-all-missing.txt`) where every
        // <Step> file= references a non-existent path, then call
        // `useFakeWalkthroughFixture("walkthrough-all-missing.txt")` here.
        // TODO.
    }

    @Test
    fun testTabSwitchHidesAndRestoresPopup() {
        // Open a second tab while the popup is showing → popup is disposed and the
        // highlighter removed, but the session stays alive. Switching back to the active
        // step's tab rebuilds both. Spec: "Window/focus interactions" §selectionChanged.
        useFakeWalkthroughFixture("walkthrough-three-steps.txt")
        // TODO.
    }

    @Test
    fun testCancelDuringAgentRun() {
        // FAKE_AGENT_DELAY_MS=2000 → the agent pauses for 2s; pressing the progress
        // widget's cancel button calls AgentProcessSession.cancel() and the walkthrough
        // never opens. Verify no popup, no highlighter, no notification (cancel is silent).
        useFakeWalkthroughFixture("walkthrough-three-steps.txt")
        // TODO: re-enabling this test needs an env-injection extension on FakeAgentCli
        // (e.g., `buildWalkthroughCommand(fixture, env = mapOf("FAKE_AGENT_DELAY_MS" to "2000"))`)
        // and a small change to BaseCompletamenteUiTest.configureSettings to prepend the env
        // assignments to the CLI command. The `extractProgramName` helper in AgentProcess.kt
        // already skips leading `KEY=value` tokens, so the rendering is plumbing-only.
    }
}
