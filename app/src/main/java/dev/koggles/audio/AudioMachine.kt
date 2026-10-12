package dev.koggles.audio

// Pure audio/permission state machine. No Android imports: the
// app-scoped AudioSession runs `reduce` and performs the returned effects.

enum class Source { Standard, HighQuality }

enum class FailReason { VisualizerUnavailable, VisualizerBusy, CaptureStartFailed, ConsentDeclined, CaptureLost }

sealed interface AudioState {
    data object Idle : AudioState
    data object ShowingExplainer : AudioState
    data object AwaitingPermission : AudioState
    data class PermissionDenied(val permanent: Boolean) : AudioState
    data object NeedsConsent : AudioState
    data class AwaitingConsent(val requestId: Long) : AudioState
    data object Starting : AudioState
    data object Active : AudioState
    data object StoppedByUser : AudioState
    data class Failed(val reason: FailReason) : AudioState
}

sealed interface AudioEvent {
    data class ActivityStarted(val hasPermission: Boolean) : AudioEvent
    data object ActivityStopped : AudioEvent
    data object MenuOpened : AudioEvent
    data object MenuClosed : AudioEvent
    data object ExplainerAccepted : AudioEvent
    data class PermissionResult(val granted: Boolean, val permanent: Boolean) : AudioEvent
    data class ConsentResult(val requestId: Long, val granted: Boolean) : AudioEvent
    data object SourceStarted : AudioEvent
    data class SourceFailed(val reason: FailReason) : AudioEvent
    data object ProjectionStopped : AudioEvent
    data object UserStop : AudioEvent
    data object UserResume : AudioEvent
    data object Retry : AudioEvent
    data class SelectSource(val source: Source) : AudioEvent
}

sealed interface AudioEffect {
    data object ShowExplainer : AudioEffect
    data object RequestPermission : AudioEffect
    data object OpenAppSettings : AudioEffect
    data class LaunchConsent(val requestId: Long) : AudioEffect
    data class StartSource(val source: Source) : AudioEffect
    data object StopSource : AudioEffect
    data object FlushAudio : AudioEffect
    data object MarkExplainerSeen : AudioEffect
    /** Dispatch [AudioEvent.Retry] after [delayMs] (busy Visualizer, or its one constructor retry). */
    data class ScheduleRetry(val delayMs: Long) : AudioEffect
}

data class AudioSnapshot(
    val state: AudioState = AudioState.Idle,
    val source: Source = Source.Standard,
    val hasPermission: Boolean = false,
    val explainerSeen: Boolean = false,
    val activityStarted: Boolean = false,
    val menuOpen: Boolean = false,
    val nextRequestId: Long = 1,
    /** The latest prompt deferred because the activity is stopped or the menu is open. */
    val pendingPrompt: AudioEffect? = null,
    /** The Visualizer's one constructor retry has been used (reset on start and source change). */
    val visualizerRetried: Boolean = false,
)

private typealias Step = Pair<AudioSnapshot, List<AudioEffect>>

private val BUSY = AudioState.Failed(FailReason.VisualizerBusy)
private val UNAVAILABLE = AudioState.Failed(FailReason.VisualizerUnavailable)
const val BUSY_RETRY_MS = 2_000L
const val CONSTRUCTOR_RETRY_MS = 1_000L

private val STOP_AND_FLUSH = listOf(AudioEffect.StopSource, AudioEffect.FlushAudio)

fun reduce(s: AudioSnapshot, e: AudioEvent): Pair<AudioSnapshot, List<AudioEffect>> {
    val st = s.state
    val hq = s.source == Source.HighQuality
    return when (e) {
        is AudioEvent.ActivityStarted -> {
            val f = s.copy(activityStarted = true, hasPermission = e.hasPermission)
            when {
                st == AudioState.Idle || st == AudioState.NeedsConsent -> begin(f)
                st == AudioState.Active && hq && !e.hasPermission -> STOP_AND_FLUSH then begin(f)
                st is AudioState.PermissionDenied && st.permanent && e.hasPermission -> begin(f)
                // A new foreground retries a busy or unavailable Visualizer: the other app may have let go meanwhile.
                st == BUSY || st == UNAVAILABLE -> begin(f.copy(visualizerRetried = false))
                else -> releasePending(f)
            }
        }

        AudioEvent.ActivityStopped -> {
            val f = s.copy(activityStarted = false)
            if (!hq && (st == AudioState.Active || st == AudioState.Starting)) {
                f.enter(AudioState.Idle) to STOP_AND_FLUSH
            } else {
                f to emptyList()
            }
        }

        AudioEvent.MenuOpened -> releasePending(s.copy(menuOpen = true))
        AudioEvent.MenuClosed -> releasePending(s.copy(menuOpen = false))

        AudioEvent.ExplainerAccepted ->
            if (st == AudioState.ShowingExplainer) {
                listOf(AudioEffect.MarkExplainerSeen) then prompt(
                    s.copy(explainerSeen = true).enter(AudioState.AwaitingPermission),
                    AudioEffect.RequestPermission,
                )
            } else {
                unchanged(s)
            }

        is AudioEvent.PermissionResult -> when {
            st != AudioState.AwaitingPermission -> unchanged(s)
            e.granted -> begin(s.copy(hasPermission = true))
            else -> s.copy(hasPermission = false)
                .enter(AudioState.PermissionDenied(e.permanent)) to emptyList()
        }

        is AudioEvent.ConsentResult ->
            if (st is AudioState.AwaitingConsent && st.requestId == e.requestId) {
                if (e.granted) {
                    s.enter(AudioState.Starting) to listOf(AudioEffect.StartSource(Source.HighQuality))
                } else {
                    s.enter(AudioState.Failed(FailReason.ConsentDeclined)) to emptyList()
                }
            } else {
                unchanged(s)
            }

        AudioEvent.SourceStarted ->
            if (st == AudioState.Starting) {
                s.copy(visualizerRetried = false).enter(AudioState.Active) to emptyList()
            } else {
                unchanged(s)
            }

        is AudioEvent.SourceFailed ->
            when (st) {
                // Another client holds the Visualizer: say so and keep retrying while visible.
                AudioState.Starting if e.reason == FailReason.VisualizerBusy ->
                    s.enter(BUSY) to listOf(AudioEffect.StopSource, AudioEffect.ScheduleRetry(BUSY_RETRY_MS))
                // One delayed constructor retry before calling the Visualizer unavailable.
                AudioState.Starting if e.reason == FailReason.VisualizerUnavailable && !s.visualizerRetried ->
                    s.copy(visualizerRetried = true) to
                        listOf(AudioEffect.StopSource, AudioEffect.ScheduleRetry(CONSTRUCTOR_RETRY_MS))
                AudioState.Starting -> s.enter(AudioState.Failed(e.reason)) to listOf(AudioEffect.StopSource)
                // A running source died (e.g. AudioRecord read error): surface it, don't stay Active.
                AudioState.Active -> s.enter(AudioState.Failed(e.reason)) to
                    listOf(AudioEffect.StopSource, AudioEffect.FlushAudio)
                else -> unchanged(s)
            }

        AudioEvent.ProjectionStopped ->
            if (st == AudioState.Active && hq) {
                // D10: a stop while visible came from the user (system chip);
                // while backgrounded it came from the lock screen.
                val to = if (s.activityStarted) AudioState.StoppedByUser else AudioState.NeedsConsent
                s.enter(to) to STOP_AND_FLUSH
            } else {
                unchanged(s)
            }

        AudioEvent.UserStop ->
            if (st == AudioState.Active && hq) {
                s.enter(AudioState.StoppedByUser) to STOP_AND_FLUSH
            } else {
                unchanged(s)
            }

        AudioEvent.UserResume ->
            if (st == AudioState.StoppedByUser || st is AudioState.Failed) begin(s) else unchanged(s)

        AudioEvent.Retry -> when {
            // Scheduled retries (ScheduleRetry) never restart audio in the background.
            st == BUSY -> if (s.activityStarted) begin(s) else unchanged(s)
            st == AudioState.Starting -> if (s.activityStarted && s.visualizerRetried) begin(s) else unchanged(s)
            st == AudioState.StoppedByUser || st is AudioState.Failed -> begin(s)
            st is AudioState.PermissionDenied && st.permanent ->
                s to listOf(AudioEffect.OpenAppSettings)
            st is AudioState.PermissionDenied ->
                prompt(s.enter(AudioState.AwaitingPermission), AudioEffect.RequestPermission)
            else -> unchanged(s)
        }

        is AudioEvent.SelectSource ->
            if (e.source == s.source) {
                unchanged(s)
            } else {
                val f = s.copy(source = e.source, visualizerRetried = false)
                STOP_AND_FLUSH then
                    (if (f.activityStarted) begin(f) else (f.enter(AudioState.Idle) to emptyList()))
            }
    }
}

/**
 * Start (or restart) audio for the current source: the permission flow when
 * the grant is missing, else the Visualizer or the consent dialog.
 */
private fun begin(s: AudioSnapshot): Step = when {
    !s.hasPermission && !s.explainerSeen ->
        prompt(s.enter(AudioState.ShowingExplainer), AudioEffect.ShowExplainer)
    !s.hasPermission ->
        prompt(s.enter(AudioState.AwaitingPermission), AudioEffect.RequestPermission)
    s.source == Source.Standard ->
        s.enter(AudioState.Starting) to listOf(AudioEffect.StartSource(Source.Standard))
    else -> {
        val id = s.nextRequestId
        prompt(
            s.copy(nextRequestId = id + 1).enter(AudioState.AwaitingConsent(id)),
            AudioEffect.LaunchConsent(id),
        )
    }
}

private fun promptsAllowed(s: AudioSnapshot) = s.activityStarted && !s.menuOpen

/** Emit [p] now if prompts are allowed, else keep it as the (only) pending prompt. */
private fun prompt(s: AudioSnapshot, p: AudioEffect): Step =
    if (promptsAllowed(s)) {
        s.copy(pendingPrompt = null) to listOf(p)
    } else {
        s.copy(pendingPrompt = p) to emptyList()
    }

/** Emit the pending prompt if the flags now allow it. */
private fun releasePending(s: AudioSnapshot): Step {
    val p = s.pendingPrompt
    return if (p != null && promptsAllowed(s)) {
        s.copy(pendingPrompt = null) to listOf(p)
    } else {
        s to emptyList()
    }
}

/** A state transition supersedes any prompt deferred for the previous state. */
private fun AudioSnapshot.enter(state: AudioState) = copy(state = state, pendingPrompt = null)

private fun unchanged(s: AudioSnapshot): Step = s to emptyList()

private infix fun List<AudioEffect>.then(step: Step): Step = step.first to (this + step.second)
