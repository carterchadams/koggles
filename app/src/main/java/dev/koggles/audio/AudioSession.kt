package dev.koggles.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * App-scoped audio controller: runs [reduce], executes source effects itself, and hands prompt
 * effects to the activity through [uiEffects]. [dispatch] is called on the main thread.
 *
 * Every source start gets a fresh generation; callbacks from a superseded source are dropped
 * before they reach the reducer (F3).
 */
class AudioSession(
    private val factory: SourceFactory,
    private val flush: () -> Unit,
    private val scope: CoroutineScope,
    private val explainerStore: ExplainerStore,
    initial: AudioSnapshot = AudioSnapshot(),
) {
    private val _snapshot = MutableStateFlow(initial)
    val snapshot: StateFlow<AudioSnapshot> = _snapshot.asStateFlow()

    private val uiChannel = Channel<AudioEffect>(Channel.BUFFERED)

    /** ShowExplainer, RequestPermission, OpenAppSettings, LaunchConsent — for the activity. */
    val uiEffects: Flow<AudioEffect> = uiChannel.receiveAsFlow()

    private var current: AudioSource? = null
    private var generation = 0L
    private var pendingConsent: ConsentData? = null

    fun dispatch(e: AudioEvent) {
        val (next, effects) = reduce(_snapshot.value, e)
        _snapshot.value = next
        effects.forEach(::execute)
    }

    /** Result of the consent request [requestId]; `null` means declined or cancelled. */
    fun provideConsent(requestId: Long, consent: ConsentData?) {
        pendingConsent = consent
        dispatch(AudioEvent.ConsentResult(requestId, granted = consent != null))
    }

    private fun execute(effect: AudioEffect) {
        when (effect) {
            is AudioEffect.StartSource -> startSource(effect.source)
            AudioEffect.StopSource -> stopSource()
            AudioEffect.FlushAudio -> flush()
            AudioEffect.MarkExplainerSeen -> scope.launch { explainerStore.markSeen() }
            AudioEffect.ShowExplainer,
            AudioEffect.RequestPermission,
            AudioEffect.OpenAppSettings,
            is AudioEffect.LaunchConsent -> uiChannel.trySend(effect)
        }
    }

    private fun startSource(source: Source) {
        stopSource()
        val gen = ++generation
        val consent = if (source == Source.HighQuality) pendingConsent else null
        val created = factory.create(source, consent, gen)
        current = created
        created.start(boundListener(gen))
    }

    private fun stopSource() {
        current?.stop()
        current = null
        generation++ // every callback from the stopped source is now stale
    }

    private fun boundListener(gen: Long) = object : AudioSourceListener {
        override fun onStarted() = post(gen, AudioEvent.SourceStarted)
        override fun onFailed(reason: FailReason) = post(gen, AudioEvent.SourceFailed(reason))
        override fun onProjectionStopped() = post(gen, AudioEvent.ProjectionStopped)
    }

    private fun post(gen: Long, event: AudioEvent) {
        scope.launch { if (gen == generation) dispatch(event) }
    }
}
