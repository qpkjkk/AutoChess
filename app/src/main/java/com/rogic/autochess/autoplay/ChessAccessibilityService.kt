package com.rogic.autochess.autoplay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlin.concurrent.thread

/**
 * 无障碍服务：仅用于模拟点击（dispatchGesture），不读取任何屏幕内容。
 */
class ChessAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * 模拟单击屏幕坐标。
     * @param x,y 屏幕绝对坐标
     * @param dur 按压时长 ms
     */
    fun tap(x: Float, y: Float, dur: Long = 1, onDone: ((Boolean) -> Unit)? = null) {
        try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, dur)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone?.invoke(false)
                }
            }, null)
        } catch (t: Throwable) {
            // 服务已断开等情况：不能让异常杀掉主进程
            onDone?.invoke(false)
        }
    }

    /**
     * 模拟拖拽走子（部分 App 支持滑动落子）。
     */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, dur: Long = 220,
             onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke(false)
            }
        }, null)
    }

    /** 顺序执行多次点击（间隔 ms） */
    fun taps(points: List<Pair<Float, Float>>, intervalMs: Long = 160,
             onAllDone: ((Boolean) -> Unit)? = null) {
        thread(name = "taps") {
            var ok = true
            for ((i, p) in points.withIndex()) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var res = false
                mainHandler.post {
                    tap(p.first, p.second) { r ->
                        res = r
                        latch.countDown()
                    }
                }
                latch.await()
                if (!res) ok = false
                if (i < points.size - 1) Thread.sleep(intervalMs)
            }
            onAllDone?.invoke(ok)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        @Volatile
        var instance: ChessAccessibilityService? = null
            private set

        /** 便捷点击（若服务未开启返回 false） */
        fun tapScreen(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null): Boolean {
            val s = instance ?: return false
            s.mainHandler.post {
                s.tap(x, y, onDone = onDone)
            }
            return true
        }
    }
}
