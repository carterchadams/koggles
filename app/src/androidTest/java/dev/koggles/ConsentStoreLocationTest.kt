package dev.koggles

import androidx.test.platform.app.InstrumentationRegistry
import dev.koggles.telemetry.ConsentStore
import org.junit.Assert.assertTrue
import org.junit.Test

/** The consent file is excluded from Android's automatic backup: a restored phone starts with the switch off. */
class ConsentStoreLocationTest {
    @Test fun consentFileIsUnderTheNoBackupDirectory() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = ConsentStore.file(ctx)
        assertTrue("$file is not under ${ctx.noBackupFilesDir}", file.startsWith(ctx.noBackupFilesDir))
    }
}
