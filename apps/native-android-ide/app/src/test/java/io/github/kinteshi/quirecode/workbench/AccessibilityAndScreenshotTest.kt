package io.github.kinteshi.quirecode.workbench

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.kinteshi.quirecode.notebook.CellType
import io.github.kinteshi.quirecode.notebook.NotebookCell
import io.github.kinteshi.quirecode.notebook.OutputView
import io.github.kinteshi.quirecode.transport.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

class AccessibilityAndScreenshotTest {
    @Test
    fun explorerRowsCarryKindExpansionOfflineAndDecorationSemantics() {
        val expandedDir = explorerRowDescription(
            entry = FileEntry("src", "/w/src", isDirectory = true, size = 0L, modifiedTs = 0L),
            expanded = true,
            tree = true,
            selected = false,
            active = false,
            keptOffline = true,
            mark = FileDecoration(DecorationTone.Error, gitLetter = null, problems = 1, folder = false),
        )
        assertEquals("Folder src, expanded, kept offline, 1 problem", expandedDir)

        val dirtyFile = explorerRowDescription(
            entry = FileEntry("main.rs", "/w/src/main.rs", isDirectory = false, size = 10L, modifiedTs = 0L),
            expanded = false,
            tree = true,
            selected = true,
            active = true,
            keptOffline = false,
            mark = FileDecoration(DecorationTone.Modified, gitLetter = "M", problems = 2),
        )
        assertEquals("File main.rs, active, selected, 2 problems, Modified", dirtyFile)

        val notebook = explorerRowDescription(
            entry = FileEntry("demo.ipynb", "/w/demo.ipynb", isDirectory = false, size = 10L, modifiedTs = 0L),
            expanded = false,
            tree = true,
            selected = false,
            active = true,
            keptOffline = false,
            mark = null,
        )
        assertEquals("Notebook demo.ipynb, active", notebook)
    }

    @Test
    fun tabsOpenEditorsKeyBarAndStatusBarDescribeAllStates() {
        assertEquals(
            "main.rs, active tab, preview, unsaved changes",
            editorTabDescription("main.rs", selected = true, dirty = true, preview = true, pinned = false, deleted = false),
        )
        assertEquals("Unpin Cargo.toml", editorTabActionDescription("Cargo.toml", dirty = false, pinned = true))
        assertEquals("Close main.rs (unsaved changes)", editorTabActionDescription("main.rs", dirty = true, pinned = false))
        assertEquals(
            "main.rs, in src, active, unsaved changes",
            openEditorRowDescription("main.rs", "src", active = true, dirty = true, deleted = false),
        )
        assertEquals("Control" to "Latched for next key", keyBarKeyDescription("Ctrl", Latch.Once))
        assertEquals("Shift" to "Locked on", keyBarKeyDescription("Shift", Latch.Locked))
        assertEquals("Page Up" to null, keyBarKeyDescription("PgUp", Latch.Off))
        assertEquals("Problems: 1 error, 2 warnings", statusBarProblemsDescription(1, 2))
        assertEquals(
            "Code editor, main.rs, rust, line 3 column 5 of 20 lines, 1 problem",
            codeEditorDescription("/w/src/main.rs", "rust", lineCount = 20, cursorLine = 3, cursorCol = 5, problemCount = 1),
        )
    }

    @Test
    fun notebookCellsAndOutputsSummarizeExecutionAndKind() {
        val streamOut = JsonObject().apply {
            addProperty("output_type", "stream")
            addProperty("name", "stdout")
        }
        val errOut = JsonObject().apply {
            addProperty("output_type", "error")
            addProperty("ename", "ValueError")
        }
        val dataOut = JsonObject().apply {
            addProperty("output_type", "execute_result")
            add("data", JsonObject().apply { add("image/png", JsonArray().apply { add("abc") }) })
        }
        val cell = NotebookCell(
            id = "c1",
            type = CellType.Code,
            source = "print(1)",
            executionCount = 3,
            outputs = listOf(streamOut, errOut),
        )
        assertEquals("Code cell 1 of 2, selected, executed [3], 2 outputs", notebookCellDescription(0, 2, cell, selected = true))
        assertEquals("Cell output 1 of 2, stdout stream", cellOutputDescription(0, 2, streamOut, OutputView.Text))
        assertEquals("Cell output 2 of 2, error: ValueError", cellOutputDescription(1, 2, errOut, OutputView.Text))
        assertEquals("Cell output 1 of 1, plot image", cellOutputDescription(0, 1, dataOut, OutputView.Image))
    }

    @Test
    fun goldenComparatorDetectsVisualDriftAndMissingAccessibilityLabels() {
        val baseline = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color(28, 25, 23)
                fillRect(0, 0, 320, 200)
                color = Color(180, 120, 90)
                fillRect(20, 20, 120, 40)
                dispose()
            }
        }
        val identical = GoldenVisualComparator.rgbGrid(baseline, 0, 0, 320, 200, 16, 10)
        val tinyShift = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color(30, 27, 25)
                fillRect(0, 0, 320, 200)
                color = Color(182, 122, 92)
                fillRect(20, 20, 120, 40)
                dispose()
            }
        }
        val shifted = GoldenVisualComparator.rgbGrid(tinyShift, 0, 0, 320, 200, 16, 10)
        val broken = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color(240, 240, 240)
                fillRect(0, 0, 320, 200)
                dispose()
            }
        }
        val brokenGrid = GoldenVisualComparator.rgbGrid(broken, 0, 0, 320, 200, 16, 10)

        assertTrue(GoldenVisualComparator.matchesGrid(identical, shifted, meanTolerance = 12.0, maxCellTolerance = 42))
        assertFalse(GoldenVisualComparator.matchesGrid(identical, brokenGrid, meanTolerance = 12.0, maxCellTolerance = 42))

        val xml = """
            <hierarchy rotation="1">
              <node resource-id="android:id/content" bounds="[0,20][320,200]">
                <node clickable="true" content-desc="Open workspace" bounds="[10,25][90,55]" />
                <node clickable="true" bounds="[100,25][140,55]" />
              </node>
            </hierarchy>
        """.trimIndent()
        val parsed = GoldenVisualComparator.parseHierarchy(xml)
        assertTrue(parsed.hasLabel("Open workspace"))
        assertFalse(parsed.hasLabel("Missing label"))
        assertEquals(listOf("node@[100, 25, 140, 55]"), parsed.unlabeledClickables())
    }

    @Test
    fun shippedGoldenManifestIsWellFormed() {
        val stream = javaClass.classLoader!!.getResourceAsStream("goldens/workbench-goldens.json")
        assertNotNull("goldens/workbench-goldens.json must exist in test resources", stream)
        val root = JsonParser.parseString(stream!!.bufferedReader().readText()).asJsonObject
        val cols = root.get("gridCols").asInt
        val rows = root.get("gridRows").asInt
        assertEquals(16, cols)
        assertEquals(10, rows)
        val scenarios = root.getAsJsonObject("scenarios")
        assertTrue(scenarios.size() >= 4)
        scenarios.entrySet().forEach { (name, value) ->
            val obj = value.asJsonObject
            assertEquals("Scenario $name RGB grid cell count", cols * rows, obj.getAsJsonArray("rgbGrid").size())
            assertTrue("Scenario $name requiredLabels", obj.getAsJsonArray("requiredLabels").size() > 0)
            assertTrue("Scenario $name structuralBounds", obj.getAsJsonObject("structuralBounds").size() > 0)
        }
    }

    @Test
    fun runsOnDeviceAccessibilityAndGoldenChecksWhenDeviceConfigured() {
        val serial = System.getenv("AVF_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
        assumeTrue("set AVF_DEVICE_SERIAL to run live device accessibility & golden checks", serial != null)
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var script: File? = null
        repeat(5) {
            val candidate = File(dir, "scripts/on-device-tests.py")
            if (candidate.isFile) {
                script = candidate
                return@repeat
            }
            dir = dir.parentFile ?: return@repeat
        }
        val runner = requireNotNull(script) { "Could not locate scripts/on-device-tests.py from ${System.getProperty("user.dir")}" }
        val process = ProcessBuilder("python3", runner.absolutePath, "--verify", "--serial", serial!!)
            .directory(runner.parentFile?.parentFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("on-device runner timed out", process.waitFor(180, TimeUnit.SECONDS))
        assertEquals("on-device accessibility & golden checks failed:\n$output", 0, process.exitValue())
    }
}

internal object GoldenVisualComparator {
    data class ParsedHierarchy(val nodes: List<UiNode>, val contentBox: IntArray) {
        fun hasLabel(substring: String): Boolean =
            nodes.any { substring in it.text || substring in it.contentDesc }

        fun unlabeledClickables(): List<String> {
            val cw = (contentBox[2] - contentBox[0]).coerceAtLeast(1)
            val ch = (contentBox[3] - contentBox[1]).coerceAtLeast(1)
            return nodes.filter { node ->
                node.clickable &&
                    "EditText" !in node.className &&
                    (node.bounds[2] - node.bounds[0]) < cw * 0.9 &&
                    !node.subtreeHasLabel
            }.map { "${it.tag}@${it.bounds.toList()}" }
        }
    }

    data class UiNode(
        val tag: String,
        val className: String,
        val text: String,
        val contentDesc: String,
        val clickable: Boolean,
        val bounds: IntArray,
        val subtreeHasLabel: Boolean,
    )

    fun rgbGrid(image: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int, cols: Int, rows: Int): List<IntArray> {
        val cw = (x1 - x0).coerceAtLeast(1)
        val ch = (y1 - y0).coerceAtLeast(1)
        val out = ArrayList<IntArray>(cols * rows)
        for (r in 0 until rows) {
            val py0 = y0 + (r * ch) / rows
            val py1 = (y0 + ((r + 1) * ch) / rows).coerceAtLeast(py0 + 1)
            for (c in 0 until cols) {
                val px0 = x0 + (c * cw) / cols
                val px1 = (x0 + ((c + 1) * cw) / cols).coerceAtLeast(px0 + 1)
                var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
                for (y in py0 until py1) {
                    for (x in px0 until px1) {
                        val rgb = image.getRGB(x, y)
                        rs += (rgb shr 16) and 0xFF
                        gs += (rgb shr 8) and 0xFF
                        bs += rgb and 0xFF
                        n++
                    }
                }
                out.add(intArrayOf((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt()))
            }
        }
        return out
    }

    fun matchesGrid(
        expected: List<IntArray>,
        actual: List<IntArray>,
        meanTolerance: Double,
        maxCellTolerance: Int,
    ): Boolean {
        if (expected.size != actual.size || expected.isEmpty()) return false
        var total = 0L
        var maxDelta = 0
        for (i in expected.indices) {
            for (c in 0..2) {
                val d = abs(expected[i][c] - actual[i][c])
                total += d
                if (d > maxDelta) maxDelta = d
            }
        }
        val mean = total.toDouble() / (expected.size * 3)
        return mean <= meanTolerance && maxDelta <= maxCellTolerance
    }

    fun parseHierarchy(xml: String): ParsedHierarchy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val collected = mutableListOf<UiNode>()
        var content = intArrayOf(0, 0, 1, 1)

        fun walk(el: Element): Boolean {
            val tag = el.tagName
            val text = el.getAttribute("text").orEmpty()
            val desc = el.getAttribute("content-desc").orEmpty()
            val cls = el.getAttribute("class").orEmpty()
            val clickable = el.getAttribute("clickable") == "true"
            val bounds = parseBounds(el.getAttribute("bounds"))
            if (el.getAttribute("resource-id") == "android:id/content" && bounds[2] > bounds[0]) {
                content = bounds
            }
            var hasLabel = text.isNotBlank() || desc.isNotBlank()
            val children = el.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element && walk(child)) hasLabel = true
            }
            if (tag == "node") {
                collected.add(UiNode(tag, cls, text, desc, clickable, bounds, hasLabel))
            }
            return hasLabel
        }

        walk(doc.documentElement)
        return ParsedHierarchy(collected, content)
    }

    private fun parseBounds(raw: String): IntArray {
        val match = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").matchEntire(raw) ?: return intArrayOf(0, 0, 0, 0)
        return intArrayOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
        )
    }
}
