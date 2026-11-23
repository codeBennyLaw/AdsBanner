package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashResumeTrackerTest {
    @Test fun openingAndBackingOutOfDetailsCannotRearmAFinishedScan() {
        val tracker = tracker()
        tracker.finishScan("app")
        repeat(10) { index ->
            val opened = tracker.enter("app", "Detail", 11, 100L + index * 100)
            assertEquals(SplashResumeTracker.Entry.ACTIVITY, opened)
            assertFalse(tracker.allowsScan(opened))
            val returned = tracker.enter("app", "Main", 10, 150L + index * 100)
            assertEquals(SplashResumeTracker.Entry.ACTIVITY, returned)
            assertFalse(tracker.allowsScan(returned))
        }
        tracker.onTransientWindow()
        val returned = tracker.enter("app", "Main", 10, 2_000)
        assertEquals(SplashResumeTracker.Entry.RETURN, returned)
        assertFalse(tracker.allowsScan(returned))
    }

    @Test fun anActivityChangeNeverStartsANewLaunchBudget() {
        val tracker = tracker()
        assertFalse(tracker.allowsScan(tracker.enter("app", "Main", 10, 100)))
        assertFalse(tracker.allowsScan(tracker.enter("app", "Detail", 11, 200)))
    }

    @Test fun genuineAppLaunchAndWakeCanDetectANewSplashAfterCompletion() {
        val tracker = tracker()
        tracker.finishScan("app")
        assertTrue(tracker.allowsScan(tracker.enter("other", "Main", 20, 100)))
        // Finishing an old app's session must not suppress the new app's return context.
        tracker.finishScan("app")
        tracker.onTransientWindow()
        assertTrue(tracker.allowsScan(tracker.enter("other", "Main", 20, 200)))
        tracker.finishScan("other")
        tracker.onScreenHidden()
        tracker.onScreenReady(300)
        assertTrue(tracker.allowsScan(tracker.enter("other", "Main", 20, 301)))
    }

    private fun tracker() = SplashResumeTracker().apply {
        assertEquals(SplashResumeTracker.Entry.LAUNCH, enter("app", "Main", 10, 0))
    }

    @Test fun wakeRearmsTheSameWindowOnceWithoutRelyingOnAnotherPackage() {
        val tracker = tracker()
        tracker.onScreenHidden()
        assertFalse(tracker.takeWindowCheck(10_000))
        tracker.onScreenReady(50_000)
        assertTrue(tracker.takeWindowCheck(50_000))
        assertTrue(tracker.canReconcile("app", 10, 50_000))
        assertEquals(SplashResumeTracker.Entry.WAKE, tracker.enter("app", "Main", 10, 50_000))
        tracker.onScreenReady(50_001)
        assertFalse(tracker.takeWindowCheck(50_001))
        assertEquals(SplashResumeTracker.Entry.NONE, tracker.enter("app", "Main", 10, 50_002))
    }

    @Test fun aSameAppShareWindowCanReturnToTheUnchangedActivity() {
        val tracker = tracker()
        tracker.onTransientWindow("app", 20)
        assertFalse(tracker.canReconcile("app", 20, 100))
        assertFalse(tracker.canReconcile("other", 10, 100))
        assertTrue(tracker.canReconcile("app", 10, 100))
        assertEquals(SplashResumeTracker.Entry.RETURN, tracker.enter("app", "Main", 10, 200))
        assertEquals(SplashResumeTracker.Entry.NONE, tracker.enter("app", "Main", 10, 201))
    }

    @Test fun chooserReturnAndDeclaredActivityTransitionsAreBoundaries() {
        val tracker = tracker()
        tracker.onTransientWindow()
        assertEquals(SplashResumeTracker.Entry.RETURN, tracker.enter("app", "Main", 10, 100))
        assertEquals(SplashResumeTracker.Entry.ACTIVITY, tracker.enter("app", "Share", 11, 200))
        assertEquals(SplashResumeTracker.Entry.ACTIVITY, tracker.enter("app", "Main", 10, 300))
        assertEquals(SplashResumeTracker.Entry.NONE, tracker.enter("app", "Main", 10, 400))
    }

    @Test fun repeatedContentAndSameWindowEventsDoNotRearm() {
        val tracker = tracker()
        for (now in 1L..100L) {
            tracker.onTransientWindow("app", 10)
            assertFalse(tracker.takeWindowCheck(now))
            assertEquals(SplashResumeTracker.Entry.NONE, tracker.enter("app", "Main", 10, now))
        }
    }

    @Test fun windowChecksAreEventDrivenLimitedAndWakeRequestExpires() {
        val tracker = tracker()
        tracker.onScreenHidden()
        tracker.onScreenReady(1_000)
        assertTrue(tracker.takeWindowCheck(1_000))
        assertFalse(tracker.takeWindowCheck(1_099))
        assertTrue(tracker.takeWindowCheck(1_100))
        tracker.onScreenReady(30_000)
        assertFalse(tracker.takeWindowCheck(30_000))
        assertEquals(SplashResumeTracker.Entry.NONE, tracker.enter("app", "Main", 10, 30_001))
    }

    @Test fun appSwitchAndResetDiscardThePreviousReturnContext() {
        val tracker = tracker()
        tracker.onTransientWindow()
        assertEquals(SplashResumeTracker.Entry.LAUNCH, tracker.enter("other", "Other", 20, 100))
        assertFalse(tracker.takeWindowCheck(101))
        tracker.onScreenHidden()
        tracker.clear()
        tracker.onScreenReady(200)
        assertFalse(tracker.takeWindowCheck(201))
        assertEquals(SplashResumeTracker.Entry.LAUNCH, tracker.enter("app", "Main", 10, 300))
    }
}
