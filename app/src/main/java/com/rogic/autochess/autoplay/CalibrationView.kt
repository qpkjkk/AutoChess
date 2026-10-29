package com.rogic.autochess.autoplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 手动棋盘校准覆盖层：8×8 半透明网格。
 * 拖动网格体内移动整体，拖四角缩放；对齐屏幕上的真实棋盘后由外部按钮确认。
 * 坐标系：真实屏幕像素。
 */
class CalibrationView(context: Context) : View(context) {

    /** 校准矩形（真实屏幕像素坐标） */
    var boardRect: RectF = RectF()
        private set

    private val paintDim = Paint().apply { color = 0x66000000 }
    private val paintGridFill = Paint().apply { color = 0x14FFFFFF }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val paintHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5FA8F5.toInt(); style = Paint.Style.FILL
    }
    private val paintHandleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 38f; textAlign = Paint.Align.CENTER
    }
    private val paintTextBg = Paint().apply { color = 0xB3000000.toInt() }

    private var mode = 0 // 0=无 1=整体移动 2=左上 3=右上 4=左下 5=右下
    private var lastX = 0f
    private var lastY = 0f
    private val touchR = 84f          // 角点触控半径（大些好按）
    private val minSize = 8 * 44f     // 网格最小边长

    fun setInitial(l: Float, t: Float, r: Float, b: Float) {
        boardRect.set(l, t, r, b)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = boardRect
        // 四周暗化遮罩（突出棋盘区）
        canvas.drawRect(0f, 0f, width.toFloat(), r.top.coerceIn(0f, height.toFloat()), paintDim)
        canvas.drawRect(0f, r.bottom.coerceIn(0f, height.toFloat()), width.toFloat(), height.toFloat(), paintDim)
        canvas.drawRect(0f, r.top, r.left.coerceIn(0f, width.toFloat()), r.bottom, paintDim)
        canvas.drawRect(r.right.coerceIn(0f, width.toFloat()), r.top, width.toFloat(), r.bottom, paintDim)
        // 8×8 网格
        canvas.drawRect(r, paintGridFill)
        val cw = r.width() / 8f
        val ch = r.height() / 8f
        for (i in 0..8) canvas.drawLine(r.left + i * cw, r.top, r.left + i * cw, r.bottom, paintGrid)
        for (i in 0..8) canvas.drawLine(r.left, r.top + i * ch, r.right, r.top + i * ch, paintGrid)
        canvas.drawRect(r, paintGrid)
        // 四角手柄
        for (cx in arrayOf(r.left, r.right)) for (cy in arrayOf(r.top, r.bottom)) {
            canvas.drawCircle(cx, cy, 28f, paintHandleRing)
            canvas.drawCircle(cx, cy, 21f, paintHandle)
        }
        // 提示文字（网格上方，贴边处理防出屏）
        val hint = "拖动移动 · 拉角点缩放 · 网格对齐棋盘64格后点确认"
        val tw = paintText.measureText(hint)
        val ty = (r.top - 58f).coerceIn(90f, height - 40f)
        canvas.drawRect(width / 2f - tw / 2 - 20, ty - 44f, width / 2f + tw / 2 + 20, ty + 16f, paintTextBg)
        canvas.drawText(hint, width / 2f, ty, paintText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                val r = boardRect
                mode = when {
                    dist(e.x, e.y, r.left, r.top) < touchR -> 2
                    dist(e.x, e.y, r.right, r.top) < touchR -> 3
                    dist(e.x, e.y, r.left, r.bottom) < touchR -> 4
                    dist(e.x, e.y, r.right, r.bottom) < touchR -> 5
                    r.contains(e.x, e.y) -> 1
                    else -> 0
                }
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                val r = boardRect
                when (mode) {
                    1 -> {
                        var nl = r.left + dx; var nt = r.top + dy
                        if (nl < 0) nl = 0f
                        if (nt < 0) nt = 0f
                        if (nl + r.width() > width) nl = width - r.width()
                        if (nt + r.height() > height) nt = height - r.height()
                        r.offsetTo(nl, nt)
                    }
                    2 -> {
                        r.left = e.x.coerceIn(0f, r.right - minSize)
                        r.top = e.y.coerceIn(0f, r.bottom - minSize)
                    }
                    3 -> {
                        r.right = e.x.coerceIn(r.left + minSize, width.toFloat())
                        r.top = e.y.coerceIn(0f, r.bottom - minSize)
                    }
                    4 -> {
                        r.left = e.x.coerceIn(0f, r.right - minSize)
                        r.bottom = e.y.coerceIn(r.top + minSize, height.toFloat())
                    }
                    5 -> {
                        r.right = e.x.coerceIn(r.left + minSize, width.toFloat())
                        r.bottom = e.y.coerceIn(r.top + minSize, height.toFloat())
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; return true }
        }
        return super.onTouchEvent(e)
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
}
