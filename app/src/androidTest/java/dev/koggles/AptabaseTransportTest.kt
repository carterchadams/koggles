package dev.koggles

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.telemetry.AptabaseTelemetry
import dev.koggles.telemetry.ConsentGate
import dev.koggles.telemetry.Event
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * What the Aptabase SDK really puts on the wire, against a local server (a self-hosted "A-SH-" key is the SDK's
 * only way to point at another host). This is the disclosure's evidence: the README's Privacy section lists
 * exactly these fields. The SDK is a process singleton, so this class is the only place the real adapter is built.
 */
@RunWith(AndroidJUnit4::class)
class AptabaseTransportTest {
    private lateinit var server: ServerSocket
    private val accepted = LinkedBlockingQueue<Socket>()
    private lateinit var acceptor: Thread

    @Before fun start() {
        server = ServerSocket(0)
        acceptor = thread { runCatching { while (true) accepted.put(server.accept()) } }
    }

    @After fun stop() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
    }

    private fun telemetry() = AptabaseTelemetry(
        InstrumentationRegistry.getInstrumentation().targetContext,
        key = "A-SH-test", // the SDK requires exactly three dash-separated parts
        host = "http://127.0.0.1:${server.localPort}",
    )

    /** Reads one HTTP request from [s]; answers 200 only after [respond] is true. Returns the parsed JSON body. */
    private fun readRequest(s: Socket): JSONObject {
        val input = BufferedReader(InputStreamReader(s.getInputStream()))
        var length = 0
        var line = input.readLine()
        val requestLine = line
        while (!line.isNullOrEmpty()) {
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
            line = input.readLine()
        }
        val body = CharArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
        assertTrue(requestLine, requestLine!!.startsWith("POST /api/v0/event "))
        return JSONObject(String(body))
    }

    private fun respond(s: Socket) {
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        s.getOutputStream().flush()
        s.close()
    }

    @Test fun oneEventIsOnePostWithExactlyTheDisclosedFields() {
        telemetry().track(Event.AppStarted(source = dev.koggles.audio.Source.Standard, goggles = true, wideView = 2))
        val s = accepted.poll(10, TimeUnit.SECONDS) ?: error("no request within 10 s")
        val body = readRequest(s)
        respond(s)

        assertEquals(setOf("timestamp", "sessionId", "eventName", "systemProps", "props"), body.keys().asSequence().toSet())
        assertEquals("app_started", body.getString("eventName"))
        val sys = body.getJSONObject("systemProps")
        assertEquals(
            setOf("isDebug", "osName", "osVersion", "locale", "appVersion", "appBuildNumber", "sdkVersion", "deviceModel"),
            sys.keys().asSequence().toSet(),
        )
        assertEquals(Build.MODEL, sys.getString("deviceModel"))
        assertEquals("Android", sys.getString("osName"))
        assertEquals(Build.VERSION.RELEASE, sys.getString("osVersion"))
        val props = body.getJSONObject("props")
        assertEquals(setOf("source", "goggles", "wide_view"), props.keys().asSequence().toSet())
        assertEquals(0, props.getInt("source"))
        assertEquals(1, props.getInt("goggles"))
        assertEquals(2, props.getInt("wide_view"))
        assertTrue(body.getString("sessionId"), Regex("[0-9a-f-]{36}").matches(body.getString("sessionId")))
    }

    /**
     * The retained SDK's limit, characterised (Codex spec review round 2, finding 1): with the network stalled its
     * five-thread executor queues further POSTs without bound and without timeouts, so events accepted before the
     * switch is turned off may still be delivered later. Nothing accepted after the switch is.
     */
    @Test fun eventsAcceptedBeforeRevocationMayStillBeSentLater_nothingAfterIs() {
        val gate = ConsentGate(persisted = true) { telemetry() }
        repeat(7) { gate.track(Event.SessionEnded(minutes = it)) }   // 5 in flight, 2 queued behind them
        Thread.sleep(500)
        gate.setEnabled(false)
        gate.track(Event.SessionEnded(minutes = 99))                 // after revocation: must never arrive

        val minutes = mutableListOf<Int>()
        while (minutes.size < 7) {
            val s = accepted.poll(10, TimeUnit.SECONDS) ?: error("stalled queue did not drain: got $minutes")
            minutes += readRequest(s).getJSONObject("props").getInt("minutes")
            respond(s)                                                // releasing one lets a queued POST begin
        }
        assertEquals((0..6).toSet(), minutes.toSet())
        assertNull("an event after revocation reached the server", accepted.poll(2, TimeUnit.SECONDS))
    }
}
