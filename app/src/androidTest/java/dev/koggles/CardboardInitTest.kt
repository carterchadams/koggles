package dev.koggles

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardboardInitTest {
    @Test
    fun lensDistortion_createsAndDestroys_withProcessContext() {
        // KogglesApp has initialized Cardboard; lens creation reads screen density via the
        // stored context, which must be display-associated on API 30+.
        assertTrue(NativeBridge.cardboardLensProbe(2400, 1080))
    }
}
