package io.github.kinteshi.nookraide.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTabsTest {
    @Test
    fun tabsWaitingForTheGuestKeepTheirPlace() {
        // a.py had a copy and opened offline; b.ipynb and c.py wait for the guest.
        val order = mapOf("/w/a.py" to 1, "/w/b.ipynb" to 0, "/w/c.py" to 2)
        assertEquals(listOf("/w/b.ipynb", "/w/a.py", "/w/c.py"), sessionTabs(listOf("/w/a.py"), listOf("/w/b.ipynb", "/w/c.py"), order))
    }

    @Test
    fun newTabsGoAfterRestoredOnesAndNothingWaitingKeepsTheOpenOrder() {
        val order = mapOf("/w/a.py" to 0, "/w/b.py" to 1)
        assertEquals(listOf("/w/b.py", "/w/new.py"), sessionTabs(listOf("/w/new.py"), listOf("/w/b.py"), order).let { listOf(it[0], it[1]) })
        assertEquals(listOf("/w/z.py", "/w/a.py"), sessionTabs(listOf("/w/z.py", "/w/a.py"), emptyList(), order))
    }
}
