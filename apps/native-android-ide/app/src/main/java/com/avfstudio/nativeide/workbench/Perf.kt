package com.avfstudio.nativeide.workbench

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import java.util.concurrent.atomic.AtomicLong

/**
 * `adb shell setprop log.tag.AVFPERF DEBUG`: timings for the spec's performance budgets (its
 * section 11) in logcat, read when the app starts. Off, nothing is measured.
 */
internal object Perf {
    private const val TAG = "AVFPERF"
    val enabled: Boolean by lazy { Log.isLoggable(TAG, Log.DEBUG) }

    /** The newest key press not drawn yet (System.nanoTime clock, as frame metrics are). */
    private val pendingKey = AtomicLong(0)
    private var firstEditor = true

    fun log(message: () -> String) {
        if (enabled) Log.d(TAG, message())
    }

    /** Logs [what] with the time from [startUptime] (SystemClock.uptimeMillis) to the end of the next frame. */
    fun afterNextFrame(what: String, startUptime: Long) {
        if (!enabled) return
        // The frame after the one already being built is the first that shows what changed now.
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback {
                log { "$what ${SystemClock.uptimeMillis() - startUptime} ms" }
            }
        }
    }

    /** The first editor in this process: the cold start to an editable file. */
    fun editorShown() {
        if (!enabled || !firstEditor) return
        firstEditor = false
        // Only a start that opened a file: a process that showed the welcome page or a notebook first isn't one.
        val started = android.os.Process.getStartUptimeMillis()
        if (SystemClock.uptimeMillis() - started < 30_000) afterNextFrame("cold start to editor", started)
    }

    fun keyDown(event: KeyEvent) {
        if (!enabled || event.action != KeyEvent.ACTION_DOWN) return
        // eventTime is SystemClock.uptimeMillis, the same monotonic clock as the frame timestamps.
        pendingKey.set(event.eventTime * 1_000_000)
    }

    /** Key press to the end of the frame that drew it (rendering done; the display's scan-out is not counted). */
    fun watchFrames(window: Window) {
        if (!enabled) return
        val thread = android.os.HandlerThread("perf-frames").apply { start() }
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val key = pendingKey.get()
            if (key != 0L && vsync >= key && pendingKey.compareAndSet(key, 0)) {
                val done = vsync + metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                log { "key to frame ${"%.1f".format(java.util.Locale.ROOT, (done - key) / 1e6)} ms" }
            }
        }, android.os.Handler(thread.looper))
    }
}
