package com.avfstudio.nativeide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleDocumentTest {
    @Test
    fun generatesExactlyTheRequestedNumberOfLines() {
        val document = buildSampleDocument(10_000)

        assertEquals(10_000, document.lineSequence().count())
        assertTrue(document.startsWith("value_0 = normalize("))
        assertTrue(document.contains("value_9999 = normalize("))
    }
}
