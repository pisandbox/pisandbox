package app.pisandbox

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupErrorsTest {
    @Test fun messagesAreFriendly() {
        assertEquals("This copy of PiSandbox is incomplete", StartupErrors.describe(IllegalStateException("libproot.so missing from nativeLibraryDir")).title)
        assertEquals("This copy of PiSandbox is incomplete", StartupErrors.describe(FileNotFoundException("rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz")).title)
        val other = StartupErrors.describe(RuntimeException("x"))
        assertEquals("Linux couldn't start", other.title)
        assertFalse("no raw shell text", other.message.contains("sh -c"))
        assertTrue(StartupErrors.earlyExit(127).message.isNotBlank())
    }

    @Test fun crashDetailsHaveWhatABugReportNeedsAndNothingPrivate() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = CrashReport.describe(ctx, IllegalStateException("kaboom"), "test crash")
        for (k in listOf("PiSandbox ${BuildConfig.VERSION_NAME}", "Android", "kaboom", "test crash", "IllegalStateException")) assertTrue("has $k:\n$d", k in d)
    }
}
