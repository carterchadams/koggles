package dev.koggles.telemetry

import dev.koggles.audio.Source
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The complete wire schema of our events. Every name and property key is listed here on purpose: adding a
 * field means changing this test and the README's disclosure together. Values are Int only (the Aptabase
 * SDK documents String and Int custom properties; we never send free text).
 */
class EventTest {
    private val all: List<Event> = listOf(
        Event.AppStarted(source = Source.HighQuality, goggles = true, wideView = 2),
        Event.SessionEnded(minutes = 7),
        Event.GogglesToggled(on = false),
        Event.WideViewSet(mode = 1),
        Event.ViewerQrScanned,
        Event.CalibrationFinished,
    )

    @Test fun namesAndPropertyKeysAreExactlyTheDisclosedOnes() {
        val schema = all.associate { it.name to it.props.keys }
        assertEquals(
            mapOf(
                "app_started" to setOf("source", "goggles", "wide_view"),
                "session_ended" to setOf("minutes"),
                "goggles_toggled" to setOf("on"),
                "wide_view_set" to setOf("mode"),
                "viewer_qr_scanned" to emptySet(),
                "calibration_finished" to emptySet(),
            ),
            schema,
        )
    }

    @Test fun everySubclassIsCovered() {
        val declared = Event::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(declared, all.map { it::class.simpleName }.toSet())
    }

    @Test fun valuesAreIntsWithBooleansAsZeroOrOne() {
        assertEquals(
            mapOf("source" to 1, "goggles" to 1, "wide_view" to 2),
            Event.AppStarted(source = Source.HighQuality, goggles = true, wideView = 2).props,
        )
        assertEquals(mapOf("source" to 0, "goggles" to 0, "wide_view" to 0),
            Event.AppStarted(source = Source.Standard, goggles = false, wideView = 0).props)
        assertEquals(mapOf("on" to 0), Event.GogglesToggled(on = false).props)
        assertEquals(mapOf("minutes" to 7), Event.SessionEnded(minutes = 7).props)
        all.forEach { e -> e.props.values.forEach { v -> assertEquals(e.name, Int::class.javaObjectType, v.javaClass) } }
    }
}
