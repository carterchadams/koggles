package dev.koggles

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityLeakTest {
    @Test
    fun recreatedActivity_isGarbageCollected() {
        var first: WeakReference<MainActivity>? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { first = WeakReference(it) }
            repeat(3) { scenario.recreate() }
        }
        repeat(10) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            if (first!!.get() == null) return
            Thread.sleep(200)
        }
        assertNull("destroyed MainActivity is still reachable", first!!.get())
    }
}
