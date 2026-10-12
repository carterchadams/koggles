package dev.koggles

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.coroutines.runBlocking

/**
 * Marks the one-time "Choose folder" prompt as shown before any test runs: otherwise the first
 * test to launch MainActivity on a fresh install finds the menu open (forcing the single-view
 * preview). Tests of the prompt itself reset the flag explicitly.
 */
class KogglesTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app) // AppGraph exists from here on
        runBlocking { AppGraph.settings.update { it.copy(folderPrompted = true) } }
    }
}
