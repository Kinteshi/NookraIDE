package io.github.kinteshi.quirecode.workbench

import com.google.gson.JsonParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotlyOutputTest {
    @Test
    fun figureTextCannotCloseTheScriptTag() {
        val figure = JsonParser.parseString("""{"data":[{"type":"bar","name":"</script><script>alert(1)"}],"layout":{}}""").asJsonObject
        val page = plotlyPage(figure, dark = true)
        assertFalse(page.contains("</script><script>alert"))
        assertTrue(page.contains("<\\/script>"))
        assertTrue(page.contains("plotly-4.1.1.min.js"))
        // Drawn through avfRender, which updates in place use as well.
        assertTrue(page.contains("Plotly.react") && page.contains("avfRender({"))
    }
}
