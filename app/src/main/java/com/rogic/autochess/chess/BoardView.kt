package com.rogic.autochess.chess

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 国际象棋棋盘视图：
 * - 拖拽 / 点击两段式走子
 * - 合法走法提示、上一步高亮、将军警示
 * - 走子滑动动画、坐标标注
 */
class BoardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface MoveListener {
        fun onUserMove(from: Square, to: Square, promotion: Piece?)
        fun onPromotionNeeded(from: Square, to: Square)
        fun onSquareTapped(square: Square) {}
    }

    var listener: MoveListener? = null

    /** 位置数据源 */
    var fen: String = Game.START_FEN
        private set
    var lastMoveUci: String? = null
    var whiteAtBottom: Boolean = true
    var interactive: Boolean = true
    var showCoordinates: Boolean = true

    /** 允许人类走子的颜色（棋子归属方） */
    var movableSide: String = "w"   // "w" / "b" / "none"

    private var selected: Square? = null
    private var legalTargets: List<Square> = emptyList()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)

    // 拖拽状态
    private var dragFrom: Square? = null
    private var dragBitmap: Bitmap? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var dragging = false
    private val touchSlop = 12

    // 动画
    private var animator: ValueAnimator? = null
    private var animFrom: Square? = null
    private var animTo: Square? = null
    private var animPiece: String? = null
    private var animProgress = 1f

    // 主题色
    var lightColor = 0xFFE8D3B4.toInt()
    var darkColor = 0xFF9A6B4F.toInt()

    private var cell = 0f
    private var boardLeft = 0f
    private var boardTop = 0f

    // ─────────────── 数据设置 ───────────────

    fun setPosition(fen: String, lastMoveUci: String?, animateLast: Boolean = false) {
        val changed = fen != this.fen
        this.fen = fen
        this.lastMoveUci = lastMoveUci
        selected = null
        legalTargets = emptyList()
        if (changed && animateLast && lastMoveUci != null && lastMoveUci.length >= 4) {
            startAnimation(lastMoveUci)
        } else {
            animator?.cancel()
            animProgress = 1f
        }
        invalidate()
    }

    private fun startAnimation(uci: String) {
        try {
            val from = squareFromAlgebraic(uci.substring(0, 2)) ?: return
            val to = squareFromAlgebraic(uci.substring(2, 4)) ?: return
            val piece = pieceAt(to) ?: return
            animator?.cancel()
            animFrom = from
            animTo = to
            animPiece = piece
            animProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 130
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } catch (_: Exception) {
        }
    }

    private fun squareFromAlgebraic(s: String): Square? =
        try { Square.valueOf(s.uppercase()) } catch (_: Exception) { null }

    // ─────────────── 几何换算 ───────────────

    private fun fileOfIndex(i: Int) = if (whiteAtBottom) i else 7 - i
    private fun rankOfIndex(r: Int) = if (whiteAtBottom) 7 - r else r

    /** 屏幕格子坐标 -> 棋盘格（Square 枚举是大写如 D2，必须转大写，否则 valueOf 抛异常） */
    private fun squareAtCell(col: Int, row: Int): Square? {
        if (col < 0 || col > 7 || row < 0 || row > 7) return null
        // 复用 squareFromAlgebraic：内部已 uppercase + try-catch，杜绝 "d2" 崩溃
        return squareFromAlgebraic("" + ('a' + fileOfIndex(col)) + (rankOfIndex(row) + 1))
    }

    private fun cellOfSquare(sq: Square): Pair<Int, Int> {
        val file = sq.file.ordinal
        val rank = sq.rank.ordinal
        val col = if (whiteAtBottom) file else 7 - file
        val row = if (whiteAtBottom) 7 - rank else rank
        return col to row
    }

    private fun cellCenterX(col: Int) = boardLeft + (col + 0.5f) * cell
    private fun cellCenterY(row: Int) = boardTop + (row + 0.5f) * cell

    // ─────────────── FEN 解析 ───────────────

    private val pieceGrid = Array(8) { arrayOfNulls<String>(8) } // [rank0..7][file0..7] rank0=1st rank

    private fun parseFen() {
        for (r in 0..7) for (f in 0..7) pieceGrid[r][f] = null
        val boardPart = fen.split(" ")[0]
        var rank = 7
        var file = 0
        for (c in boardPart) {
            when {
                c == '/' -> { rank--; file = 0 }
                c.isDigit() -> file += c.digitToInt()
                else -> {
                    if (rank in 0..7 && file in 0..7) {
                        pieceGrid[rank][file] = if (c.isUpperCase()) "w$c" else "b${c.uppercaseChar()}"
                    }
                    file++
                }
            }
        }
    }

    private fun pieceAt(sq: Square): String? {
        parseOnce()
        return pieceGrid[sq.rank.ordinal][sq.file.ordinal]
    }

    private var fenParsed: String? = null
    private fun parseOnce() {
        if (fenParsed != fen) {
            parseFen()
            fenParsed = fen
        }
    }

    private fun pieceAtCell(col: Int, row: Int): String? {
        parseOnce()
        val f = fileOfIndex(col)
        val r = rankOfIndex(row)
        return pieceGrid[r][f]
    }

    // ─────────────── 绘制 ───────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cell = w / 8f
        boardLeft = (w - cell * 8) / 2f
        boardTop = (h - cell * 8) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        try {
            onDrawInternal(canvas)
        } catch (t: Throwable) {
            // 兜底：绘制崩溃不再向上抛导致闪退
            try {
                t.printStackTrace()
            } catch (_: Throwable) {}
        }
    }

    private fun onDrawInternal(canvas: Canvas) {
        parseOnce()
        val checkSquare = findCheckSquare()

        // 格子
        for (row in 0..7) {
            for (col in 0..7) {
                val x = boardLeft + col * cell
                val y = boardTop + row * cell
                paintFill.color = if ((row + col) % 2 == 0) lightColor else darkColor
                canvas.drawRect(x, y, x + cell, y + cell, paintFill)
            }
        }

        // 上一步高亮
        val lm = lastMoveUci
        if (lm != null && lm.length >= 4) {
            highlightSquare(canvas, lm.substring(0, 2), 0x50F7DC6F)
            highlightSquare(canvas, lm.substring(2, 4), 0x50F7DC6F)
        }

        // 选中格
        selected?.let { highlightSquare(canvas, it.name.lowercase(), 0x505FA8F5) }

        // 合法走法提示
        for (t in legalTargets) {
            val (col, row) = cellOfSquare(t)
            val cx = cellCenterX(col)
            val cy = cellCenterY(row)
            val occupied = pieceAtCell(col, row) != null
            if (occupied) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = cell * 0.07f
                paint.color = 0xB020557A.toInt()
                canvas.drawCircle(cx, cy, cell * 0.44f, paint)
                paint.style = Paint.Style.FILL
            } else {
                paint.color = 0xB020557A.toInt()
                canvas.drawCircle(cx, cy, cell * 0.16f, paint)
            }
        }

        // 将军警示
        if (checkSquare != null) {
            val (col, row) = cellOfSquare(checkSquare)
            val cx = cellCenterX(col)
            val cy = cellCenterY(row)
            paint.shader = RadialGradient(cx, cy, cell * 0.7f,
                0x88D64545.toInt(), 0x00D64545, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, cell * 0.7f, paint)
            paint.shader = null
        }

        // 棋子
        val animatingFrom = if (animProgress < 1f) animFrom else null
        for (row in 0..7) {
            for (col in 0..7) {
                val sq = squareAtCell(col, row)
                if (sq != null && sq == animatingFrom) continue
                val key = pieceAtCell(col, row) ?: continue
                drawPiece(canvas, key, col, row, 1f)
            }
        }

        // 动画中的棋子
        if (animProgress < 1f && animFrom != null && animTo != null && animPiece != null) {
            val (c1, r1) = cellOfSquare(animFrom!!)
            val (c2, r2) = cellOfSquare(animTo!!)
            val cx = lerp(cellCenterX(c1), cellCenterX(c2), animProgress)
            val cy = lerp(cellCenterY(r1), cellCenterY(r2), animProgress)
            drawPieceAt(canvas, animPiece!!, cx, cy, 1.06f)
        }

        // 拖拽中的棋子
        if (dragging && dragBitmap != null && dragFrom != null) {
            val dragKey = pieceKeyAt(dragFrom!!)
            if (dragKey != null) drawPieceAt(canvas, dragKey, dragX, dragY, 1.15f, shadow = true)
        }

        // 坐标
        if (showCoordinates) {
            paintText.textSize = cell * 0.18f
            paintText.color = if ((7 + 0) % 2 == 0) darkColor else lightColor
            for (i in 0..7) {
                // 文件字母（底边）
                val fileChar = ('a' + fileOfIndex(i))
                paintText.color = if (i % 2 == 0) darkColor else lightColor
                paintText.textAlign = Paint.Align.RIGHT
                canvas.drawText(fileChar.toString(), boardLeft + (i + 1) * cell - cell * 0.05f,
                    boardTop + 8 * cell - cell * 0.08f, paintText)
                // 数字（左边）
                val rankNum = (rankOfIndex(i) + 1).toString()
                paintText.color = if (i % 2 == 0) lightColor else darkColor
                paintText.textAlign = Paint.Align.LEFT
                canvas.drawText(rankNum, boardLeft + cell * 0.05f,
                    boardTop + i * cell + cell * 0.28f, paintText)
            }
        }
    }

    private fun findCheckSquare(): Square? {
        return try {
            val parts = fen.split(" ")
            if (parts.size > 1 && (parts[1] == "b" || parts[1] == "w")) {
                // 解析 FEN 找到行棋方国王所在格并判断是否被攻击成本较高，
                // 此处简化：由外部 setCheckSquare 直接设置
                checkSquareHint
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /** 外部直接注入将军格子提示 */
    var checkSquareHint: Square? = null

    private fun highlightSquare(canvas: Canvas, algebraic: String, color: Int) {
        val sq = squareFromAlgebraic(algebraic) ?: return
        val (col, row) = cellOfSquare(sq)
        paintFill.color = color
        canvas.drawRect(boardLeft + col * cell, boardTop + row * cell,
            boardLeft + (col + 1) * cell, boardTop + (row + 1) * cell, paintFill)
    }

    private fun drawPiece(canvas: Canvas, key: String, col: Int, row: Int, scale: Float) {
        val bmp = PieceAssets.get(key) ?: return
        val cx = cellCenterX(col)
        val cy = cellCenterY(row)
        drawPieceAt(canvas, key, cx, cy, scale, bmp = bmp)
    }

    private fun drawPieceAt(canvas: Canvas, key: String, cx: Float, cy: Float, scale: Float,
                            shadow: Boolean = false, bmp: Bitmap? = null) {
        val bitmap = bmp ?: PieceAssets.get(key) ?: return
        val size = cell * 0.98f * scale
        val left = cx - size / 2
        val top = cy - size / 2
        if (shadow) {
            paint.setShadowLayer(cell * 0.1f, 0f, cell * 0.05f, 0x66000000)
        }
        val filter = paint.isFilterBitmap
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, null,
            android.graphics.RectF(left, top, left + size, top + size), paint)
        paint.isFilterBitmap = filter
        if (shadow) paint.clearShadowLayer()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ─────────────── 交互 ───────────────

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val col = ((x - boardLeft) / cell).toInt()
        val row = ((y - boardTop) / cell).toInt()
        if (col < 0 || col > 7 || row < 0 || row > 7) return null
        return col to row
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cellPos = cellAt(event.x, event.y) ?: return false
                val (col, row) = cellPos
                val sq = squareAtCell(col, row) ?: return false
                dragDownX = event.x
                dragDownY = event.y
                dragX = event.x
                dragY = event.y
                val piece = pieceAtCell(col, row)
                if (piece != null && isMovablePiece(piece)) {
                    dragFrom = sq
                    dragging = false
                    // 点自己棋子：选中
                    select(sq)
                    return true
                }
                // 点击目标格（两段式）
                handleTapTarget(sq)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragFrom != null) {
                    dragX = event.x
                    dragY = event.y
                    if (!dragging && (abs(event.x - dragDownX) > touchSlop || abs(event.y - dragDownY) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && dragFrom != null) {
                    val target = cellAt(event.x, event.y)
                    val from = dragFrom
                    dragFrom = null
                    dragging = false
                    invalidate()
                    if (target != null && from != null) {
                        val to = squareAtCell(target.first, target.second)
                        if (to != null && to != from) {
                            tryUserMove(from, to)
                            return true
                        } else if (to == from) {
                            return true // 原地放下，保持选中
                        }
                    }
                    // 无效位置：取消选中
                    deselect()
                    return true
                }
                dragFrom = null
            }
            MotionEvent.ACTION_CANCEL -> {
                dragFrom = null
                dragging = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isMovablePiece(pieceKey: String): Boolean =
        when (movableSide) {
            "w" -> pieceKey.startsWith("w")
            "b" -> pieceKey.startsWith("b")
            else -> false
        }

    private fun pieceKeyAt(sq: Square?): String? {
        sq ?: return null
        return pieceAt(sq)
    }

    private fun select(sq: Square) {
        selected = sq
        listener?.onSquareTapped(sq)
        invalidate()
    }

    private fun deselect() {
        selected = null
        legalTargets = emptyList()
        invalidate()
    }

    /** 设置选中格的合法目标（由 Activity 依据引擎规则提供） */
    fun setLegalTargets(sq: Square, targets: List<Square>) {
        selected = sq
        legalTargets = targets
        invalidate()
    }

    private fun handleTapTarget(sq: Square) {
        val sel = selected
        if (sel != null && sel != sq) {
            tryUserMove(sel, sq)
        } else if (sel == sq) {
            deselect()
        }
    }

    private fun tryUserMove(from: Square, to: Square) {
        val targets = legalTargets
        if (targets.contains(to)) {
            // 需要升变选择？
            listener?.let { l ->
                val piece = pieceAt(from)
                val isPawn = piece != null && piece.endsWith("P")
                val lastRank = if (piece?.startsWith("w") == true) to.rank.ordinal == 7 else to.rank.ordinal == 0
                if (isPawn && lastRank) {
                    l.onPromotionNeeded(from, to)
                    deselect()
                    return
                }
                l.onUserMove(from, to, null)
                deselect()
            }
        } else {
            // 非法目标：若点击自己另一棋子则切换选中
            val piece = pieceAt(to)
            if (piece != null && isMovablePiece(piece)) {
                select(to)
            } else {
                deselect()
            }
        }
    }
}
