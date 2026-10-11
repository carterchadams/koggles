package dev.koggles.audio

import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSessionTest {

    private class FakeSource(val source: Source, val consent: ConsentData?, val generation: Long) : AudioSource {
        var listener: AudioSourceListener? = null
        var started = 0
        var stopped = 0
        override fun start(listener: AudioSourceListener) { this.listener = listener; started++ }
        override fun stop() { stopped++ }
    }

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val created = mutableListOf<FakeSource>()
    private var flushes = 0
    private var marked = 0
    private val session = AudioSession(
        factory = { source, consent, generation -> FakeSource(source, consent, generation).also { created += it } },
        flush = { flushes++ },
        scope = scope,
        explainerStore = { marked++ },
    )

    private fun startStandardActive() {
        session.dispatch(AudioEvent.ActivityStarted(hasPermission = true))
        created.last().listener!!.onStarted()
        scope.advanceUntilIdle()
    }

    /** Standard Active → select HighQuality → consent → HQ Active. Returns the HQ source. */
    private fun switchToHighQualityActive(): FakeSource {
        session.dispatch(AudioEvent.SelectSource(Source.HighQuality))
        val consentState = session.snapshot.value.state as AudioState.AwaitingConsent
        session.provideConsent(consentState.requestId, ConsentData(-1, Intent()))
        val hq = created.last()
        hq.listener!!.onStarted()
        scope.advanceUntilIdle()
        return hq
    }

    @Test
    fun startSourceEffect_createsAndStartsSource() {
        session.dispatch(AudioEvent.ActivityStarted(hasPermission = true))
        assertEquals(1, created.size)
        assertEquals(Source.Standard, created[0].source)
        assertEquals(1, created[0].started)
    }

    @Test
    fun sourceCallbacks_dispatchEvents() {
        startStandardActive()
        assertEquals(AudioState.Active, session.snapshot.value.state)
    }

    @Test
    fun stopSourceEffect_stopsCurrentSource() {
        startStandardActive()
        session.dispatch(AudioEvent.ActivityStopped)
        assertEquals(1, created[0].stopped)
    }

    @Test
    fun flushEffect_callsFlush() {
        startStandardActive()
        session.dispatch(AudioEvent.ActivityStopped)
        assertEquals(1, flushes)
    }

    @Test
    fun markExplainerSeen_writesStore() {
        session.dispatch(AudioEvent.ActivityStarted(hasPermission = false))
        session.dispatch(AudioEvent.ExplainerAccepted)
        scope.advanceUntilIdle()
        assertEquals(1, marked)
    }

    @Test
    fun uiEffects_bufferedUntilCollected() = runTest(dispatcher) {
        session.dispatch(AudioEvent.ActivityStarted(hasPermission = false)) // emits ShowExplainer
        val effect = session.uiEffects.first()
        assertEquals(AudioEffect.ShowExplainer, effect)
    }

    @Test
    fun provideConsent_passesConsentToFactory() {
        startStandardActive()
        session.dispatch(AudioEvent.SelectSource(Source.HighQuality))
        val id = (session.snapshot.value.state as AudioState.AwaitingConsent).requestId
        val consent = ConsentData(-1, Intent())
        session.provideConsent(id, consent)
        assertEquals(Source.HighQuality, created.last().source)
        assertSame(consent, created.last().consent)
    }

    @Test
    fun provideConsent_nullDeclines() {
        startStandardActive()
        session.dispatch(AudioEvent.SelectSource(Source.HighQuality))
        val id = (session.snapshot.value.state as AudioState.AwaitingConsent).requestId
        session.provideConsent(id, null)
        assertEquals(AudioState.Failed(FailReason.ConsentDeclined), session.snapshot.value.state)
    }

    @Test
    fun staleProjectionStopped_afterRestart_isDiscarded() {
        startStandardActive()
        val gen1 = switchToHighQualityActive()
        session.dispatch(AudioEvent.SelectSource(Source.Standard))
        created.last().listener!!.onStarted()
        scope.advanceUntilIdle()
        val gen2 = switchToHighQualityActive()
        assertTrue(gen2.generation > gen1.generation)
        val stopsBefore = gen2.stopped
        gen1.listener!!.onProjectionStopped()
        scope.advanceUntilIdle()
        assertEquals(AudioState.Active, session.snapshot.value.state)
        assertEquals(stopsBefore, gen2.stopped)
    }

    @Test
    fun staleOnStarted_afterRestart_isDiscarded() {
        session.dispatch(AudioEvent.ActivityStarted(hasPermission = true))
        val first = created.last()
        session.dispatch(AudioEvent.SelectSource(Source.HighQuality)) // stops first, awaits consent
        first.listener!!.onStarted()
        scope.advanceUntilIdle()
        assertTrue(session.snapshot.value.state is AudioState.AwaitingConsent)
    }

    @Test
    fun staleOnFailed_afterRestart_isDiscarded() {
        startStandardActive()
        val old = created.last()
        val hq = switchToHighQualityActive()
        old.listener!!.onFailed(FailReason.VisualizerUnavailable)
        scope.advanceUntilIdle()
        assertEquals(AudioState.Active, session.snapshot.value.state)
        assertEquals(0, hq.stopped)
    }

    @Test
    fun currentGenerationCallbacks_stillDispatch() {
        startStandardActive()
        val hq = switchToHighQualityActive()
        session.dispatch(AudioEvent.ActivityStopped) // HQ keeps capturing in the background
        hq.listener!!.onProjectionStopped()
        scope.advanceUntilIdle()
        assertEquals(AudioState.NeedsConsent, session.snapshot.value.state)
    }
}
