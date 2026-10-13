package dev.koggles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The installed app's launcher icon (brand-images M3): declared, adaptive on black, and contained in the safe zone. */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val info get() = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)

    @Test fun manifestDeclaresTheKogglesIcon() {
        assertNotEquals("no android:icon in the manifest", 0, info.icon)
        assertEquals("ic_launcher", ctx.resources.getResourceEntryName(info.icon))
        // roundIcon has no public ApplicationInfo field: assert the resource exists; the manifest line is
        // read at review and checked by the release gate (verify-release-apk.sh check 9).
        assertNotEquals(0, ctx.resources.getIdentifier("ic_launcher_round", "mipmap", ctx.packageName))
    }

    @Test fun iconIsAdaptiveWithBlackBackground() {
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        assertTrue("not adaptive: ${icon.javaClass.name}", icon is AdaptiveIconDrawable)
        val bg = (icon as AdaptiveIconDrawable).background
        assertTrue("background is not a black ColorDrawable: $bg", bg is ColorDrawable && bg.color == Color.BLACK)
    }

    /** The swirl (glow included) sits inside the 66/108 safe circle, so no launcher mask clips it. */
    @Test fun swirlFitsSafeZone() {
        val fg = (ctx.packageManager.getApplicationIcon(ctx.packageName) as AdaptiveIconDrawable).foreground
        val px = 432
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        fg.setBounds(0, 0, px, px); fg.draw(Canvas(bmp))
        val c = px / 2; val safe = 66f / 108f * px / 2f
        // The swirl's core is bright somewhere in the central 24 px. Outside the safe circle (no slack) no pixel
        // has any channel at or above 24/255: per channel, not luma, so saturated blue or magenta glow counts;
        // 24/255 is just above the 8% trim level, i.e. "no visible glow on black".
        assertTrue("no bright pixel at the centre", (c - 12 until c + 12).any { x -> (c - 12 until c + 12).any { y -> maxChannel(bmp.getPixel(x, y)) > 128 } })
        val lit = mutableListOf<String>()
        for (y in 0 until px) for (x in 0 until px) {
            val dx = x - c + 0.5; val dy = y - c + 0.5
            if (dx * dx + dy * dy > safe * safe && maxChannel(bmp.getPixel(x, y)) >= 24) lit += "($x,$y)"
        }
        assertTrue("${lit.size} lit pixels outside the safe zone, first ${lit.take(5)}", lit.isEmpty())
    }

    private fun maxChannel(argb: Int) = maxOf(Color.red(argb), Color.green(argb), Color.blue(argb))
}
