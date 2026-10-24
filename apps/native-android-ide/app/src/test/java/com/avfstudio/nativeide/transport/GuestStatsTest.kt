package com.avfstudio.nativeide.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class GuestStatsTest {
    @Test
    fun theBalloonIsNotCountedAsTheTabletsCost() {
        // Recorded on the AVF guest: 3908 MB total, 2055 MB free, 2202 available, 1024 MB ballooned.
        val stats = GuestStats(0, 3f, usedMb = 3908 - 2202, totalMb = 3908, freeMb = 2055, cachedMb = 147, balloonMb = 1024)
        assertEquals(682, stats.programsMb)
        assertEquals(829, stats.footprintMb)
    }

    @Test
    fun olderDaemonsWithoutFreeMemoryFallBackToProgramsUsed() {
        assertEquals(1100, GuestStats(0, 0f, usedMb = 1100, totalMb = 3908, freeMb = 0, cachedMb = 0).footprintMb)
    }
}
