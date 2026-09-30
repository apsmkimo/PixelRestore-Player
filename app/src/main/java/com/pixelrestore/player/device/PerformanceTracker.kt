package com.pixelrestore.player.device

data class FrameTiming(
    val frameTimeMs: Float,
    val averageFrameTimeMs: Float,
    val actualFps: Float,
    val droppedFrames: Int,
    val overBudgetSustained: Boolean,
    val actualWidth: Int,
    val actualHeight: Int,
)

/**
 * Tracks frame cost against the processing budget (33.3 ms at 30 FPS, 16.7 ms at 60 FPS).
 * A sustained overrun is two continuous seconds over budget.
 */
class PerformanceTracker {
    private var lastFrameMs = 0f
    private var averageMs = 0f
    private var dropped = 0
    private var framesInWindow = 0
    private var windowStartNs = 0L
    private var actualFps = 0f
    private var overrunStartNs = 0L
    private var healthyStartNs = 0L
    private var sustained = false
    private var width = 0
    private var height = 0

    fun reset() {
        lastFrameMs = 0f
        averageMs = 0f
        dropped = 0
        framesInWindow = 0
        windowStartNs = 0L
        actualFps = 0f
        overrunStartNs = 0L
        healthyStartNs = 0L
        sustained = false
    }

    fun record(
        frameTimeMs: Float,
        droppedDelta: Int,
        nowNs: Long,
        budgetMs: Float,
        width: Int,
        height: Int,
    ): FrameTiming {
        lastFrameMs = frameTimeMs
        averageMs = if (averageMs == 0f) frameTimeMs else averageMs * 0.9f + frameTimeMs * 0.1f
        dropped += droppedDelta.coerceAtLeast(0)
        this.width = width
        this.height = height
        if (windowStartNs == 0L) windowStartNs = nowNs
        framesInWindow += 1
        val windowNs = nowNs - windowStartNs
        if (windowNs >= 1_000_000_000L) {
            actualFps = framesInWindow * 1_000_000_000f / windowNs.toFloat()
            framesInWindow = 0
            windowStartNs = nowNs
        }
        if (frameTimeMs > budgetMs) {
            if (overrunStartNs == 0L) overrunStartNs = nowNs
            healthyStartNs = 0L
            if (nowNs - overrunStartNs >= 2_000_000_000L) sustained = true
        } else {
            overrunStartNs = 0L
            if (healthyStartNs == 0L) healthyStartNs = nowNs
            if (sustained && nowNs - healthyStartNs >= 2_000_000_000L) sustained = false
        }
        return snapshot()
    }

    fun snapshot(): FrameTiming = FrameTiming(
        frameTimeMs = lastFrameMs,
        averageFrameTimeMs = averageMs,
        actualFps = actualFps,
        droppedFrames = dropped,
        overBudgetSustained = sustained,
        actualWidth = width,
        actualHeight = height,
    )
}
