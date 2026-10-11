package dev.koggles.audio

import dev.koggles.audio.AudioEffect.FlushAudio
import dev.koggles.audio.AudioEffect.LaunchConsent
import dev.koggles.audio.AudioEffect.MarkExplainerSeen
import dev.koggles.audio.AudioEffect.OpenAppSettings
import dev.koggles.audio.AudioEffect.RequestPermission
import dev.koggles.audio.AudioEffect.ShowExplainer
import dev.koggles.audio.AudioEffect.StartSource
import dev.koggles.audio.AudioEffect.StopSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMachineTest {

    /** A visible, menu-closed snapshot: prompts are allowed. */
    private fun visible(
        state: AudioState,
        source: Source = Source.Standard,
        hasPermission: Boolean = true,
        explainerSeen: Boolean = true,
        nextRequestId: Long = 1,
    ) = AudioSnapshot(
        state = state,
        source = source,
        hasPermission = hasPermission,
        explainerSeen = explainerSeen,
        activityStarted = true,
        menuOpen = false,
        nextRequestId = nextRequestId,
    )

    private fun check(
        result: Pair<AudioSnapshot, List<AudioEffect>>,
        state: AudioState,
        vararg effects: AudioEffect,
    ): AudioSnapshot {
        assertEquals(state, result.first.state)
        assertEquals(effects.toList(), result.second)
        return result.first
    }

    // Row: Idle + ActivityStarted(false), explainer not seen -> ShowingExplainer.
    @Test
    fun start_noPermission_firstTime_showsExplainer() {
        val s = check(
            reduce(AudioSnapshot(), AudioEvent.ActivityStarted(hasPermission = false)),
            AudioState.ShowingExplainer,
            ShowExplainer,
        )
        assertTrue(s.activityStarted)
        assertFalse(s.hasPermission)
        assertNull(s.pendingPrompt)
    }

    // Row: Idle + ActivityStarted(false), explainer already seen -> AwaitingPermission.
    @Test
    fun start_noPermission_explainerSeen_requestsDirectly() {
        check(
            reduce(AudioSnapshot(explainerSeen = true), AudioEvent.ActivityStarted(false)),
            AudioState.AwaitingPermission,
            RequestPermission,
        )
    }

    // Row: ShowingExplainer + ExplainerAccepted.
    @Test
    fun explainerAccepted_marksSeenAndRequests() {
        val start = visible(AudioState.ShowingExplainer, hasPermission = false, explainerSeen = false)
        val s = check(
            reduce(start, AudioEvent.ExplainerAccepted),
            AudioState.AwaitingPermission,
            MarkExplainerSeen,
            RequestPermission,
        )
        assertTrue(s.explainerSeen)
    }

    // Row: any + MenuOpened / MenuClosed / ActivityStarted (prompt deferral).
    @Test
    fun start_whileMenuOpen_defersPromptUntilMenuClosed() {
        val (opened, e0) = reduce(AudioSnapshot(), AudioEvent.MenuOpened)
        assertTrue(opened.menuOpen)
        assertEquals(emptyList<AudioEffect>(), e0)

        val started = check(
            reduce(opened, AudioEvent.ActivityStarted(false)),
            AudioState.ShowingExplainer,
        )
        assertEquals(ShowExplainer, started.pendingPrompt)

        // Re-opening the menu keeps it deferred.
        val reopened = check(reduce(started, AudioEvent.MenuOpened), AudioState.ShowingExplainer)
        assertEquals(ShowExplainer, reopened.pendingPrompt)

        val closed = check(
            reduce(reopened, AudioEvent.MenuClosed),
            AudioState.ShowingExplainer,
            ShowExplainer,
        )
        assertFalse(closed.menuOpen)
        assertNull(closed.pendingPrompt)

        // Nothing left to emit.
        check(reduce(closed, AudioEvent.MenuClosed), AudioState.ShowingExplainer)
    }

    // Row: AwaitingPermission + PermissionResult(true, _), Standard.
    @Test
    fun permissionGranted_standard_startsSource() {
        val start = visible(AudioState.AwaitingPermission, hasPermission = false)
        val s = check(
            reduce(start, AudioEvent.PermissionResult(granted = true, permanent = false)),
            AudioState.Starting,
            StartSource(Source.Standard),
        )
        assertTrue(s.hasPermission)
        check(reduce(s, AudioEvent.SourceStarted), AudioState.Active)
    }

    // Rows: AwaitingPermission + PermissionResult(false, false); PermissionDenied(false) + Retry.
    @Test
    fun permissionDenied_temporary_retryRequestsWithoutExplainer() {
        val start = visible(AudioState.AwaitingPermission, hasPermission = false)
        val denied = check(
            reduce(start, AudioEvent.PermissionResult(granted = false, permanent = false)),
            AudioState.PermissionDenied(permanent = false),
        )
        check(
            reduce(denied, AudioEvent.Retry),
            AudioState.AwaitingPermission,
            RequestPermission,
        )
        // Even if the explainer was never marked seen, Retry does not show it.
        check(
            reduce(denied.copy(explainerSeen = false), AudioEvent.Retry),
            AudioState.AwaitingPermission,
            RequestPermission,
        )
    }

    // Rows: PermissionResult(false, true); PermissionDenied(true) + Retry; next ActivityStarted(true) begins.
    @Test
    fun permissionDenied_permanent_retryOpensSettings() {
        val start = visible(AudioState.AwaitingPermission, hasPermission = false)
        val denied = check(
            reduce(start, AudioEvent.PermissionResult(granted = false, permanent = true)),
            AudioState.PermissionDenied(permanent = true),
        )
        val afterRetry = check(
            reduce(denied, AudioEvent.Retry),
            AudioState.PermissionDenied(permanent = true),
            OpenAppSettings,
        )
        val stopped = check(
            reduce(afterRetry, AudioEvent.ActivityStopped),
            AudioState.PermissionDenied(permanent = true),
        )
        // Coming back without the grant: still denied, nothing emitted.
        check(
            reduce(stopped, AudioEvent.ActivityStarted(false)),
            AudioState.PermissionDenied(permanent = true),
        )
        val s = check(
            reduce(stopped, AudioEvent.ActivityStarted(true)),
            AudioState.Starting,
            StartSource(Source.Standard),
        )
        assertTrue(s.hasPermission)
    }

    // Spec §7: no High-quality path while RECORD_AUDIO is denied.
    @Test
    fun permissionDenied_neverStartsHighQuality() {
        val denied = visible(AudioState.PermissionDenied(false), hasPermission = false)
        val (s, effects) = reduce(denied, AudioEvent.SelectSource(Source.HighQuality))
        assertEquals(AudioState.AwaitingPermission, s.state)
        assertEquals(listOf(StopSource, FlushAudio, RequestPermission), effects)
        assertEquals(Source.HighQuality, s.source)
        assertTrue(effects.none { it is LaunchConsent || it is StartSource })

        val hqDenied = denied.copy(source = Source.HighQuality)
        val (r, retryEffects) = reduce(hqDenied, AudioEvent.Retry)
        assertEquals(AudioState.AwaitingPermission, r.state)
        assertEquals(listOf(RequestPermission), retryEffects)
    }

    // Row: Idle + ActivityStarted(true) with HQ -> AwaitingConsent; ids increase.
    @Test
    fun highQuality_start_launchesConsentWithIncrementingId() {
        val idle = AudioSnapshot(source = Source.HighQuality)
        val first = check(
            reduce(idle, AudioEvent.ActivityStarted(true)),
            AudioState.AwaitingConsent(1),
            LaunchConsent(1),
        )
        assertEquals(2L, first.nextRequestId)

        val declined = check(
            reduce(first, AudioEvent.ConsentResult(1, granted = false)),
            AudioState.Failed(FailReason.ConsentDeclined),
        )
        val second = check(
            reduce(declined, AudioEvent.Retry),
            AudioState.AwaitingConsent(2),
            LaunchConsent(2),
        )
        assertEquals(3L, second.nextRequestId)

        check(
            reduce(second, AudioEvent.ConsentResult(2, granted = true)),
            AudioState.Starting,
            StartSource(Source.HighQuality),
        )
    }

    // Row: any + ConsentResult(other id, _).
    @Test
    fun staleConsentResult_isDiscarded() {
        val awaiting = visible(AudioState.AwaitingConsent(5), source = Source.HighQuality, nextRequestId = 6)
        assertEquals(awaiting to emptyList<AudioEffect>(), reduce(awaiting, AudioEvent.ConsentResult(4, true)))
        assertEquals(awaiting to emptyList<AudioEffect>(), reduce(awaiting, AudioEvent.ConsentResult(4, false)))

        val active = visible(AudioState.Active, source = Source.HighQuality)
        assertEquals(active to emptyList<AudioEffect>(), reduce(active, AudioEvent.ConsentResult(1, true)))
    }

    // Row: AwaitingConsent(id) + ConsentResult(id, false).
    @Test
    fun consentDeclined_fails() {
        val awaiting = visible(AudioState.AwaitingConsent(3), source = Source.HighQuality, nextRequestId = 4)
        check(
            reduce(awaiting, AudioEvent.ConsentResult(3, granted = false)),
            AudioState.Failed(FailReason.ConsentDeclined),
        )
    }

    // Rows: Starting + SourceFailed(r); Failed(VisualizerUnavailable) + SelectSource(HQ).
    @Test
    fun sourceFailed_visualizerUnavailable_thenSelectHighQuality_launchesConsent() {
        val starting = visible(AudioState.Starting)
        val failed = check(
            reduce(starting, AudioEvent.SourceFailed(FailReason.VisualizerUnavailable)),
            AudioState.Failed(FailReason.VisualizerUnavailable),
            StopSource,
        )
        val s = check(
            reduce(failed, AudioEvent.SelectSource(Source.HighQuality)),
            AudioState.AwaitingConsent(1),
            StopSource,
            FlushAudio,
            LaunchConsent(1),
        )
        assertEquals(Source.HighQuality, s.source)
    }

    // Row: Active(Standard) or Starting(Standard) + ActivityStopped.
    @Test
    fun activityStopped_standard_stopsAndFlushes() {
        for (st in listOf(AudioState.Active, AudioState.Starting)) {
            val s = check(
                reduce(visible(st), AudioEvent.ActivityStopped),
                AudioState.Idle,
                StopSource,
                FlushAudio,
            )
            assertFalse(s.activityStarted)
            // Returning restarts the Visualizer with no prompt.
            check(
                reduce(s, AudioEvent.ActivityStarted(true)),
                AudioState.Starting,
                StartSource(Source.Standard),
            )
        }
    }

    // Row: Active(HQ) + ActivityStopped; Active(HQ) + ActivityStarted(true).
    @Test
    fun activityStopped_highQuality_keepsCapture() {
        val active = visible(AudioState.Active, source = Source.HighQuality)
        val s = check(reduce(active, AudioEvent.ActivityStopped), AudioState.Active)
        assertFalse(s.activityStarted)
        val back = check(reduce(s, AudioEvent.ActivityStarted(true)), AudioState.Active)
        assertTrue(back.activityStarted)
    }

    // Row: Active(HQ) + ProjectionStopped while started (D10).
    @Test
    fun projectionStopped_whileVisible_isStoppedByUser() {
        val active = visible(AudioState.Active, source = Source.HighQuality)
        val stopped = check(
            reduce(active, AudioEvent.ProjectionStopped),
            AudioState.StoppedByUser,
            StopSource,
            FlushAudio,
        )
        // Resume needs an explicit tap.
        check(
            reduce(stopped, AudioEvent.UserResume),
            AudioState.AwaitingConsent(1),
            LaunchConsent(1),
        )
    }

    // Rows: Active(HQ) + ProjectionStopped while backgrounded; NeedsConsent + ActivityStarted.
    @Test
    fun projectionStopped_whileBackgrounded_relaunchesConsentOnStart() {
        val active = visible(AudioState.Active, source = Source.HighQuality, nextRequestId = 7)
        val (bg, _) = reduce(active, AudioEvent.ActivityStopped)
        val needs = check(
            reduce(bg, AudioEvent.ProjectionStopped),
            AudioState.NeedsConsent,
            StopSource,
            FlushAudio,
        )
        check(
            reduce(needs, AudioEvent.ActivityStarted(true)),
            AudioState.AwaitingConsent(7),
            LaunchConsent(7),
        )
    }

    // Rows: StoppedByUser + ActivityStarted; Active(HQ) + UserStop.
    @Test
    fun stoppedByUser_activityStart_emitsNothing() {
        val active = visible(AudioState.Active, source = Source.HighQuality)
        val stopped = check(
            reduce(active, AudioEvent.UserStop),
            AudioState.StoppedByUser,
            StopSource,
            FlushAudio,
        )
        val (bg, _) = reduce(stopped, AudioEvent.ActivityStopped)
        check(reduce(bg, AudioEvent.ActivityStarted(true)), AudioState.StoppedByUser)
    }

    // Row: any + SelectSource(x != source).
    @Test
    fun selectSource_ordersStopFlushStart() {
        val active = visible(AudioState.Active, source = Source.HighQuality)
        val s = check(
            reduce(active, AudioEvent.SelectSource(Source.Standard)),
            AudioState.Starting,
            StopSource,
            FlushAudio,
            StartSource(Source.Standard),
        )
        assertEquals(Source.Standard, s.source)

        // Same source: no change.
        assertEquals(active to emptyList<AudioEffect>(), reduce(active, AudioEvent.SelectSource(Source.HighQuality)))

        // Not started: stop, flush, then Idle.
        val bg = active.copy(activityStarted = false)
        val idle = check(
            reduce(bg, AudioEvent.SelectSource(Source.Standard)),
            AudioState.Idle,
            StopSource,
            FlushAudio,
        )
        assertEquals(Source.Standard, idle.source)
    }

    // Row: Active(HQ) + ActivityStarted(false).
    @Test
    fun permissionRevokedWhileActiveHq_restartsPermissionFlow() {
        val active = visible(AudioState.Active, source = Source.HighQuality)
        val (bg, _) = reduce(active, AudioEvent.ActivityStopped)
        val s = check(
            reduce(bg, AudioEvent.ActivityStarted(false)),
            AudioState.AwaitingPermission,
            StopSource,
            FlushAudio,
            RequestPermission,
        )
        assertFalse(s.hasPermission)
    }

    // Unlisted pair: state unchanged, no effects.
    @Test
    fun awaitingPermission_activityStopped_noChange() {
        val start = visible(AudioState.AwaitingPermission, hasPermission = false)
        val s = check(reduce(start, AudioEvent.ActivityStopped), AudioState.AwaitingPermission)
        // The result still arrives and is honoured.
        check(
            reduce(s, AudioEvent.PermissionResult(granted = false, permanent = false)),
            AudioState.PermissionDenied(false),
        )
    }

    // Unlisted pair: state unchanged, no effects.
    @Test
    fun awaitingConsent_activityStopped_noChange() {
        val start = visible(AudioState.AwaitingConsent(2), source = Source.HighQuality, nextRequestId = 3)
        val s = check(reduce(start, AudioEvent.ActivityStopped), AudioState.AwaitingConsent(2))
        assertEquals(3L, s.nextRequestId)
        check(
            reduce(s, AudioEvent.ConsentResult(2, granted = true)),
            AudioState.Starting,
            StartSource(Source.HighQuality),
        )
    }
}
