package app.pisandbox

import android.system.ErrnoException
import android.system.OsConstants
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** First-run install space checks and the 0.4 -> 0.5 runtime upgrade (runtime overlay re-applied once, user files kept). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallerTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun lowStorageFailsFastWithFriendlyError() {
        val inst = RootfsInstaller(ctx) { 200L * 1024 * 1024 }
        try { inst.install {}; fail("should refuse with 200 MB free") } catch (e: NotEnoughStorageException) {
            assertEquals(RootfsInstaller.NEEDED_BYTES, e.neededBytes)
            assertTrue(e.message!!.contains("600 MB") && e.message!!.contains("200 MB free"))
            assertEquals("Not enough storage", StartupErrors.describe(e).title)
            assertTrue(StartupErrors.describe(e).message.contains("200 MB"))
        }
        assertFalse("no half-unpacked rootfs left behind", File(ctx.filesDir, "rootfs.partial").exists())
        assertFalse(inst.isInstalled())
    }

    @Test fun outOfSpaceDetection() {
        assertTrue(RootfsInstaller.isOutOfSpace(ErrnoException("write", OsConstants.ENOSPC)))
        assertTrue(RootfsInstaller.isOutOfSpace(IOException("wrap", ErrnoException("write", OsConstants.EDQUOT))))
        assertTrue(RootfsInstaller.isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertFalse(RootfsInstaller.isOutOfSpace(IOException("Permission denied")))
        assertFalse(RootfsInstaller.isOutOfSpace(null))
    }

    @Test fun upgradeFrom040ReappliesRuntimeOnceAndKeepsUserFiles() {
        val root = File(ctx.filesDir, "rootfs")
        File(root, ".pisandbox-installed").apply { parentFile!!.mkdirs(); writeText("0.4.0 1234\n") }
        File(root, "etc/pisandbox/runtime-stamp").apply { parentFile!!.mkdirs(); writeText("0.4.0 1700000000000\n") }
        File(root, "etc/pisandbox/version").writeText("0.4.0\n")
        val mine = File(root, "home/pi/examples/blink.py").apply { parentFile!!.mkdirs(); writeText("# my edited blink\n") }
        val inst = RootfsInstaller(ctx)
        assertTrue(inst.isInstalled())
        val msgs = mutableListOf<String>()
        assertTrue("runtime applied after update", inst.applyRuntime { msgs += it })
        assertEquals("0.5.2", File(root, "etc/pisandbox/version").readText().trim())
        assertTrue(File(root, "usr/share/pisandbox/examples/.shipped-hashes").length() > 0)
        assertTrue(File(root, "etc/profile.d/pisandbox.sh").readText().contains("Kept your edited"))
        assertTrue(File(root, "usr/lib/python3/dist-packages/RPi/GPIO.py").exists())
        assertEquals("home is never touched by the overlay", "# my edited blink\n", mine.readText())
        assertTrue(File(root, "etc/pisandbox/runtime-stamp").readText().startsWith("0.5.2 "))
        assertTrue(msgs.single().contains("0.5.2"))
        assertFalse("second start: nothing to do", inst.applyRuntime {})
    }
}
