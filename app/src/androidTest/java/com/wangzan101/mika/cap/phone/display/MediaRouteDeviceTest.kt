package com.wangzan101.mika.cap.phone.display

import android.hardware.display.DisplayManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.task.AppMuter
import com.wangzan101.mika.task.AudioMute
import com.wangzan101.mika.task.MarkerStore
import com.wangzan101.mika.task.ShellAudio
import com.wangzan101.mika.task.ShellMediaRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: `-e mediaMutePackage com.ss.android.ugc.aweme`; choose an app that auto-plays media. */
@RunWith(AndroidJUnit4::class)
class MediaRouteDeviceTest {
    @Test fun appOpsDeniedStillMutesTheVirtualScreenAndRestoresSound() {
        val target = InstrumentationRegistry.getArguments().getString("mediaMutePackage").orEmpty()
        assumeTrue("pass mediaMutePackage to launch a chosen app on the test display", target.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = context.packageManager.getApplicationInfo(target, 0).uid
        val user = AppCopyReader.myUser
        val shell = DeviceSupport.shell()
        assumeTrue("requires a phone that denies AppOps writes", shell.playAudioAppOpsDenied())
        assumeFalse("the user is currently using the target on the main display", shell.visibleOnMainScreen(target, user))
        assumeTrue("the target already has a virtual task", shell.displayOfPackage(target) < 0)
        val prior = shell.getAudioMode(target)
        assertNotNull(prior)
        val display = AgentDisplay(shell, 720, 1600, 320, context.getSystemService(DisplayManager::class.java))
        val marker = object : MarkerStore {
            override fun load(): Map<String, String?>? = null
            override fun save(values: Map<String, String?>): Unit = error("the fallback must not write AppOps records")
            override fun clear(): Unit = error("unexpected persisted state")
        }
        val audio = AudioMute(ShellAudio(shell), marker, ShellMediaRoute(shell, user) { display.displayId })
        val muter = AppMuter(audio, enabled = { true }, mutable = { it == target },
            onMainScreen = { pkg, copy -> shell.visibleOnMainScreen(pkg, copy ?: user) },
            failed = { error("could not mute $it") })
        try {
            muter.beforeOpen(target, null)
            assertEquals(ShellService.START_OK, shell.startApp(display.displayId, target))
            assertNotNull("no target playback on remote submix", DeviceSupport.pollUntil(12_000) {
                audibleOutputs(uid).takeIf { it.isNotEmpty() && it.all { dev -> SUBMIX in dev } }
            })
            assertEquals(target, shell.packageOfTask(shell.topTaskId(display.displayId)))
            muter.opened(target, null)
            assertTrue("explicit restore failed", muter.unmute(target, null))
            assertNotNull("output did not return to a physical device", DeviceSupport.pollUntil(8_000) {
                audibleOutputs(uid).takeIf { it.isNotEmpty() && it.none { dev -> SUBMIX in dev } }
            })
            assertEquals(prior, shell.getAudioMode(target))
        } finally {
            try { display.release() } finally { audio.restoreAll() }
        }
    }

    /** AudioFlinger mixer state, not the `STARTED` bit (which stays set while routed). No audio data is read. */
    private fun audibleOutputs(uid: Int): Set<String> = buildSet {
        var output: String? = null
        DeviceSupport.sh("dumpsys media.audio_flinger").lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("Output thread ")) output = null
            if (trimmed.startsWith("Output devices:")) output = trimmed.substringAfter(':').trim()
            val match = ACTIVE_TRACK.matchEntire(line) ?: return@forEach
            if (match.groupValues[1].toIntOrNull() != uid) return@forEach
            val gain = match.groupValues[2].split(SPACE).getOrNull(10)?.toFloatOrNull() ?: return@forEach
            if (gain.isFinite()) output?.let(::add)
        }
    }

    companion object {
        private const val SUBMIX = "AUDIO_DEVICE_OUT_REMOTE_SUBMIX"
        private val ACTIVE_TRACK = Regex("""\s*(?:(?:F\d+|[A-Z])\s+)*\d+\s+yes\s+\d+/\s*(\d+)\s+(.+)""")
        private val SPACE = Regex("""\s+""")
    }
}
