package io.github.kinteshi.quirecode.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonVersionTest {
    @Test
    fun theGuestsDaemonIsComparedWithTheAppsRelease() {
        assertEquals(DaemonVersionStatus.Current, daemonVersionStatus("0.1.0", "0.1.0"))
        // A dev build is the release it leads to.
        assertEquals(DaemonVersionStatus.Current, daemonVersionStatus("0.1.0", "0.1.0-dev"))
        assertEquals(DaemonVersionStatus.Older, daemonVersionStatus("0.1.0", "0.2.0"))
        assertEquals(DaemonVersionStatus.Older, daemonVersionStatus("0.9.3", "0.10.0"))
        assertEquals(DaemonVersionStatus.Newer, daemonVersionStatus("1.0.1", "1.0"))
    }

    @Test
    fun anUnreadableVersionIsUnknown() {
        assertEquals(DaemonVersionStatus.Unknown, daemonVersionStatus(null, "0.1.0"))
        assertEquals(DaemonVersionStatus.Unknown, daemonVersionStatus("abc", "0.1.0"))
        assertEquals(DaemonVersionStatus.Unknown, daemonVersionStatus("0.1.0", ""))
    }
}
